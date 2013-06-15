/*
 * Copyright 2009 Martin Grotzke
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an &quot;AS IS&quot; BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package de.javakaffee.web.msm;

import static de.javakaffee.web.msm.Statistics.StatsType.EFFECTIVE_BACKUP;
import static de.javakaffee.web.msm.Statistics.StatsType.RELEASE_LOCK;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.Nonnull;

import net.spy.memcached.MemcachedClient;

import org.apache.catalina.Session;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import de.javakaffee.web.msm.BackupSessionTask.BackupResult;

/**
 * This service is responsible for storing sessions memcached. This includes
 * serialization (which is delegated to the {@link TranscoderService}) and
 * the communication with memcached (using a provided {@link MemcachedClient}).
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class BackupSessionService {

    private static final Log _log = LogFactory.getLog( BackupSessionService.class );

    private final TranscoderService _transcoderService;
    private final boolean _sessionBackupAsync;
    private final int _sessionBackupTimeout;
    private final MemcachedClient _memcached;
    private final MemcachedNodesManager _memcachedNodesManager;
    private final Statistics _statistics;

    private final ExecutorService _executorService;


    /**
     * @param sessionBackupAsync
     * @param sessionBackupTimeout
     * @param backupThreadCount TODO
     * @param memcached
     * @param memcachedNodesManager
     * @param failoverNodeIds
     */
    public BackupSessionService( final TranscoderService transcoderService,
            final boolean sessionBackupAsync,
            final int sessionBackupTimeout,
            final int backupThreadCount,
            final MemcachedClient memcached,
            final MemcachedNodesManager memcachedNodesManager,
            final Statistics statistics ) {
        _transcoderService = transcoderService;
        _sessionBackupAsync = sessionBackupAsync;
        _sessionBackupTimeout = sessionBackupTimeout;
        _memcached = memcached;
        _memcachedNodesManager = memcachedNodesManager;
        _statistics = statistics;

        _executorService = sessionBackupAsync
            ? Executors.newFixedThreadPool( backupThreadCount, new NamedThreadFactory("msm-storage") )
            : new SynchronousExecutorService();

    }

    /**
     * Shutdown this service, this stops the possibly existing threads used for session backup.
     */
    public void shutdown() {
        _executorService.shutdown();
    }

    /**
     * Update the expiration for the session associated with this {@link BackupSessionService}
     * in memcached, so that the session will expire in
     * <em>session.maxInactiveInterval - timeIdle</em>
     * seconds in memcached (whereas timeIdle is calculated as
     * <em>System.currentTimeMillis - session.thisAccessedTime</em>).
     * <p>
     * <strong>IMPLEMENTATION NOTE</strong>: right now this performs a new backup of the session
     * in memcached. Once the touch command is available in memcached
     * (see <a href="http://code.google.com/p/memcached/issues/detail?id=110">issue #110</a> in memcached),
     * we can consider to use this.
     * </p>
     *
     * @param session the session for that the expiration shall be updated in memcached.
     *
     * @see Session#getMaxInactiveInterval()
     * @see MemcachedBackupSession#getThisAccessedTimeInternal()
     */
    public void updateExpiration( final MemcachedBackupSession session ) throws InterruptedException {
        if ( _log.isDebugEnabled() ) {
            _log.debug( "Updating expiration time for session " + session.getId() );
        }

        if ( !_memcachedNodesManager.getSessionIdFormat().isValid( session.getId() ) ) {
            return;
        }

        session.setExpirationUpdateRunning( true );
        session.setLastBackupTime( System.currentTimeMillis() );
        try {
            final Map<String, Object> attributes = session.getAttributesFiltered();
            final byte[] attributesData = _transcoderService.serializeAttributes( session, attributes );
            final byte[] data = _transcoderService.serialize( session, attributesData );
            createBackupSessionTask( session, true ).doBackupSession( session, data, attributesData );
        } finally {
            session.setExpirationUpdateRunning( false );
        }
    }

    /**
     * Store the provided session in memcached if the session was modified
     * or if the session needs to be relocated.
     * <p>
     * The session backup is done asynchronously according to the provided
     * <em>sessionBackupAsynch</em> flag (in the constructor).
     * </p>
     * <p>
     * Before a new {@link BackupSessionTask} is created for session backup the following
     * checks are done:
     * <ul>
     * <li>check if the session id contains a memcached id, otherwise abort</li>
     * <li>check if the session was accessed during this request</li>
     * <li>check if session attributes were accessed during this request</li>
     * </ul>
     * </p>
     *
     * @param session
     *            the session to save
     * @param force
     *            specifies, if session backup shall be forced, e.g. because the
     *            session id was changed due to a memcached failover or tomcat failover.
     * @return a {@link Future} providing the result of the backup task.
     *
     * @see MemcachedSessionService#setSessionBackupAsync(boolean)
     * @see BackupSessionTask#call()
     */
    public Future<BackupResult> backupSession( final MemcachedBackupSession session, final boolean force ) {
        if ( _log.isDebugEnabled() ) {
            _log.debug( "Starting for session id " + session.getId() );
        }

        final long start = System.currentTimeMillis();
        try {

            if ( !_memcachedNodesManager.getSessionIdFormat().isValid( session.getId() ) ) {
                if ( _log.isDebugEnabled() ) {
                    _log.debug( "Skipping backup for session id " + session.getId() + " as the session id is not usable for memcached." );
                }
                _statistics.requestWithBackupFailure();
                return new SimpleFuture<BackupResult>( BackupResult.FAILURE );
            }

            /* Check if the session was accessed at all since the last backup/check.
             * If this is not the case, we even don't have to check if attributes
             * have changed (and can skip serialization and hash calucation)
             */
            if ( !session.wasAccessedSinceLastBackupCheck()
                    && !force ) {
                _log.debug( "Session was not accessed since last backup/check, therefore we can skip this" );
                _statistics.requestWithoutSessionAccess();
                releaseLock( session );
                return new SimpleFuture<BackupResult>( BackupResult.SKIPPED );
            }

            if ( !session.attributesAccessedSinceLastBackup()
                    && !force
                    && !session.authenticationChanged()
                    && !session.isNewInternal() ) {
                _log.debug( "Session attributes were not accessed since last backup/check, therefore we can skip this" );
                _statistics.requestWithoutAttributesAccess();
                releaseLock( session );
                return new SimpleFuture<BackupResult>( BackupResult.SKIPPED );
            }

            final BackupSessionTask task = createBackupSessionTask( session, force );
            final Future<BackupResult> result = _executorService.submit( task );

            if ( !_sessionBackupAsync ) {
                try {
                    result.get( _sessionBackupTimeout, TimeUnit.MILLISECONDS );
                } catch ( final Exception e ) {
                    if ( _log.isInfoEnabled() ) {
                        _log.info( "Could not store session " + session.getId() + " in memcached.", e );
                    }
                }
            }

            return result;

        } finally {
            _statistics.registerSince( EFFECTIVE_BACKUP, start );
        }

    }

    private BackupSessionTask createBackupSessionTask( final MemcachedBackupSession session, final boolean force ) {
        return new BackupSessionTask( session,
                force,
                _transcoderService,
                _sessionBackupAsync,
                _sessionBackupTimeout,
                _memcached,
                _memcachedNodesManager,
                _statistics );
    }

    private void releaseLock( @Nonnull final MemcachedBackupSession session ) {
        if ( session.isLocked()  ) {
            try {
                if ( _log.isDebugEnabled() ) {
                    _log.debug( "Releasing lock for session " + session.getIdInternal() );
                }
                final long start = System.currentTimeMillis();
                _memcached.delete( _memcachedNodesManager.getSessionIdFormat().createLockName( session.getIdInternal() ) ).get();
                _statistics.registerSince( RELEASE_LOCK, start );
                session.releaseLock();
            } catch( final Exception e ) {
                _log.warn( "Caught exception when trying to release lock for session " + session.getIdInternal(), e );
            }
        }
    }

    /**
     * An implementation of {@link ExecutorService} that executes submitted {@link Callable}s
     * and {@link Runnable}s in the caller thread.
     * <p>
     * Implementation note: It does not extend {@link AbstractExecutorService} for performance
     * reasons, as the {@link AbstractExecutorService} internals and the used {@link Future}
     * implementations provide an overhead due to concurrency handling.
     * </p>
     */
    static class SynchronousExecutorService implements ExecutorService {

        private boolean _shutdown;

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean awaitTermination( final long timeout, final TimeUnit unit ) throws InterruptedException {
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public <T> List<Future<T>> invokeAll( final Collection<? extends Callable<T>> tasks ) throws InterruptedException {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public <T> List<Future<T>> invokeAll( final Collection<? extends Callable<T>> tasks, final long timeout, final TimeUnit unit )
            throws InterruptedException {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public <T> T invokeAny( final Collection<? extends Callable<T>> tasks ) throws InterruptedException, ExecutionException {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public <T> T invokeAny( final Collection<? extends Callable<T>> tasks, final long timeout, final TimeUnit unit ) throws InterruptedException,
            ExecutionException, TimeoutException {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean isShutdown() {
            return _shutdown;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean isTerminated() {
            return _shutdown;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void shutdown() {
            _shutdown = true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public List<Runnable> shutdownNow() {
            shutdown();
            return null;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public <T> Future<T> submit( final Callable<T> task ) {
            try {
                return new SimpleFuture<T>( task.call() );
            } catch ( final Exception e ) {
                return new SimpleFuture<T>( new ExecutionException( e ) );
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Future<?> submit( final Runnable task ) {
            try {
                task.run();
                return new SimpleFuture<Object>( null );
            } catch ( final Exception e ) {
                return new SimpleFuture<Object>( new ExecutionException( e ) );
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public <T> Future<T> submit( final Runnable task, final T result ) {
            try {
                task.run();
                return new SimpleFuture<T>( result );
            } catch ( final Exception e ) {
                return new SimpleFuture<T>( new ExecutionException( e ) );
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void execute( final Runnable command ) {
            command.run();
        }

    }

    /**
     * A future implementations that wraps an already existing result
     * or a caught exception.
     *
     * @param <T> the result type
     */
    static class SimpleFuture<T> implements Future<T> {

        private final T _result;
        private final ExecutionException _e;

        /**
         * @param result
         */
        public SimpleFuture( final T result ) {
            _result = result;
            _e = null;
        }

        /**
         * @param e
         */
        public SimpleFuture( final ExecutionException e ) {
            _result = null;
            _e = e;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean cancel( final boolean mayInterruptIfRunning ) {
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public T get() throws InterruptedException, ExecutionException {
            if ( _e != null ) {
                throw _e;
            }
            return _result;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public T get( final long timeout, final TimeUnit unit ) throws InterruptedException, ExecutionException, TimeoutException {
            if ( _e != null ) {
                throw _e;
            }
            return _result;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean isCancelled() {
            return false;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean isDone() {
            return true;
        }

    }

}
