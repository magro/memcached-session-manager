/*
 * Copyright 2011 Martin Grotzke
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

import static java.lang.Math.min;
import static java.lang.Thread.sleep;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.spy.memcached.MemcachedClient;

import org.apache.catalina.connector.Request;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import de.javakaffee.web.msm.BackupSessionService.SimpleFuture;
import de.javakaffee.web.msm.BackupSessionTask.BackupResult;
import de.javakaffee.web.msm.MemcachedBackupSessionManager.LockStatus;
import de.javakaffee.web.msm.SessionTrackerValve.SessionBackupService.BackupResultStatus;

/**
 * Represents the session locking hooks that must be implemented by the various
 * locking strategies.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public abstract class LockingStrategy {

    public static enum LockingMode {
        /** Sessions are never locked. */
        NONE,
        /** Sessions are locked for each request. */
        ALL,
        /** Readonly requests are tracked and for requests that modify the session the session is locked. */
        AUTO,
        /** The application explicitely manages locks */
        APP,
        /** The session is locked for configured request patterns **/
        URI_PATTERN
    }

    protected static final String LOCK_VALUE = "locked";
    protected static final int LOCK_RETRY_INTERVAL = 10;
    protected static final int LOCK_MAX_RETRY_INTERVAL = 500;
    protected static final int LOCK_TIMEOUT = 2000;

    protected final Log _log = LogFactory.getLog( getClass() );
    protected final MemcachedClient _memcached;
    protected LRUCache<String, Boolean> _missingSessionsCache;
    protected final SessionIdFormat _sessionIdFormat;
    protected final InheritableThreadLocal<Request> _requestsThreadLocal;
    private final ExecutorService _executor;

    protected LockingStrategy( @Nonnull final MemcachedClient memcached,
            @Nonnull final LRUCache<String, Boolean> missingSessionsCache ) {
        _memcached = memcached;
        _missingSessionsCache = missingSessionsCache;
        _sessionIdFormat = new SessionIdFormat();
        _requestsThreadLocal = new InheritableThreadLocal<Request>();
        _executor = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() );
    }

    /**
     * Creates the appropriate {@link LockingStrategy} for the given {@link LockingMode}.
     */
    @CheckForNull
    public static LockingStrategy create( @Nullable final LockingMode lockingMode,
            @Nullable final Pattern uriPattern,
            @Nonnull final MemcachedClient memcached,
            @Nonnull final MemcachedBackupSessionManager manager,
            @Nonnull final LRUCache<String, Boolean> missingSessionsCache ) {
        if ( lockingMode == null ) {
            return null;
        }
        switch( lockingMode ) {
            case ALL: return new LockingStrategyAll( memcached, missingSessionsCache );
            case APP: return new LockingStrategyApp( memcached, manager, missingSessionsCache );
            case AUTO: return new LockingStrategyAuto( memcached, missingSessionsCache );
            case URI_PATTERN: return new LockingStrategyUriPattern( uriPattern, memcached, missingSessionsCache );
            case NONE: return new LockingStrategyNone( memcached, missingSessionsCache );
            default: throw new IllegalArgumentException( "LockingMode not yet supported: " + lockingMode );
        }
    }

    protected LockStatus lock( final String sessionId ) {
        return lock( sessionId, LOCK_TIMEOUT, TimeUnit.MILLISECONDS );
    }

    protected LockStatus lock( final String sessionId, final long timeout, final TimeUnit timeUnit ) {
        if ( _log.isDebugEnabled() ) {
            _log.debug( "Locking session " + sessionId );
        }
        try {
            acquireLock( sessionId, LOCK_RETRY_INTERVAL, LOCK_MAX_RETRY_INTERVAL, timeUnit.toMillis( timeout ), System.currentTimeMillis() );
            if ( _log.isDebugEnabled() ) {
                _log.debug( "Locked session " + sessionId );
            }
            return LockStatus.LOCKED;
        } catch ( final TimeoutException e ) {
            _log.warn( "Reached timeout when trying to aquire lock for session " + sessionId + ". Will use this session without this lock." );
            return LockStatus.COULD_NOT_AQUIRE_LOCK;
        } catch ( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            throw new RuntimeException( "Got interrupted while trying to lock session.", e );
        } catch ( final ExecutionException e ) {
            _log.warn( "An exception occurred when trying to aquire lock for session " + sessionId );
            return LockStatus.COULD_NOT_AQUIRE_LOCK;
        }
    }

    protected void acquireLock( @Nonnull final String sessionId, final long retryInterval, final long maxRetryInterval,
            final long timeout, final long start ) throws InterruptedException, ExecutionException, TimeoutException {
        final Future<Boolean> result = _memcached.add( _sessionIdFormat.createLockName( sessionId ), 5, LOCK_VALUE );
        if ( result.get().booleanValue() ) {
            if ( _log.isDebugEnabled() ) {
                _log.debug( "Locked session " + sessionId );
            }
            return;
        }
        else {
            checkTimeoutAndWait( sessionId, retryInterval, maxRetryInterval, timeout, start );
            acquireLock( sessionId, retryInterval * 2, maxRetryInterval, timeout, start );
        }
    }

    protected void checkTimeoutAndWait( @Nonnull final String sessionId, final long retryInterval, final long maxRetryInterval,
            final long timeout, final long start ) throws TimeoutException, InterruptedException {
        if ( System.currentTimeMillis() >= start + timeout  ) {
            throw new TimeoutException( "Reached timeout when trying to aquire lock for session " + sessionId );
        }
        final long timeToWait = min( retryInterval, maxRetryInterval );
        if ( _log.isDebugEnabled() ) {
            _log.debug( "Could not aquire lock for session " + sessionId + ", waiting " + timeToWait + " millis now..." );
        }
        sleep( timeToWait );
    }

    protected void releaseLock( @Nonnull final String sessionId ) {
        try {
            if ( _log.isDebugEnabled() ) {
                _log.debug( "Releasing lock for session " + sessionId );
            }
            _memcached.delete( _sessionIdFormat.createLockName( sessionId ) );
        } catch( final Exception e ) {
            _log.warn( "Caught exception when trying to release lock for session " + sessionId );
        }
    }

    /**
     * Is invoked after the backup of the session is initiated, it's represented by the provided backupResult.
     * The requestId is identifying the request.
     */
    protected void onAfterBackupSession( @Nonnull final MemcachedBackupSession session, final boolean backupWasForced,
            @Nonnull final Future<BackupResult> result,
            @Nonnull final String requestId,
            @Nonnull final BackupSessionService backupSessionService ) {

        if ( !backupWasForced ) {
            pingSessionIfBackupWasSkipped( session, result, backupSessionService );
        }

        final byte[] data = SessionValidityInfo.encode( session.getMaxInactiveInterval(), session.getLastAccessedTimeInternal(), session.getThisAccessedTimeInternal() );
        final String key = SessionValidityInfo.createValidityInfoKeyName( session.getIdInternal() );
        _memcached.set( key, session.getMaxInactiveInterval(), data );
        if ( _log.isDebugEnabled() ) {
            _log.debug( "Stored session validity info for session " + session.getIdInternal() );
        }

        // store backup in secondary memcached
        final String backupKey = _sessionIdFormat.createBackupKey( key );
        _memcached.set( backupKey, session.getMaxInactiveInterval(), data );

    }

    private void pingSessionIfBackupWasSkipped( @Nonnull final MemcachedBackupSession session, @Nonnull final Future<BackupResult> result,
            @Nonnull final BackupSessionService backupSessionService ) {
        final Callable<Void> task = new Callable<Void>() {

            @Override
            public Void call() {
                try {
                    if ( result.get().getStatus() == BackupResultStatus.SKIPPED ) {
                        pingSession( session, backupSessionService );
                    }
                } catch ( final Exception e ) {
                    _log.warn( "An exception occurred during backup.", e );
                }
                return null;
            }

        };
        /* A simple future does not need to go through the executor, but we can process the result right now.
         */
        if ( result instanceof SimpleFuture ) {
            try {
                task.call();
            } catch ( final Exception e ) { /* caught in the callable */ }
        }
        else {
            _executor.submit( task );
        }
    }

    /**
     * Is used to determine if this thread / the current request already hit the application
     * or if this method invocation comes from the container.
     */
    protected final boolean isContainerSessionLookup() {
        return _requestsThreadLocal.get() == null;
    }

    @CheckForNull
    protected SessionValidityInfo loadSessionValidityInfo( @Nonnull final String id ) {
        final byte[] validityInfo = (byte[]) _memcached.get( SessionValidityInfo.createValidityInfoKeyName( id ) );
        return validityInfo != null ? SessionValidityInfo.decode( validityInfo ) : null;
    }

    @CheckForNull
    protected SessionValidityInfo loadBackupSessionValidityInfo( @Nonnull final String id ) {
        final String key = SessionValidityInfo.createValidityInfoKeyName( id );
        final String backupKey = _sessionIdFormat.createBackupKey( key );
        final byte[] validityInfo = (byte[]) _memcached.get( backupKey );
        return validityInfo != null ? SessionValidityInfo.decode( validityInfo ) : null;
    }

    /**
     * Invoked before the session for this sessionId is loaded from memcached.
     */
    @CheckForNull
    protected abstract LockStatus onBeforeLoadFromMemcached( @Nonnull String sessionId ) throws InterruptedException, ExecutionException;

    /**
     * Invoked after a non-sticky session is loaded from memcached, can be used to update some session
     * fields based on separately stored information (e.g. session validity info).
     * @param lockStatus the {@link LockStatus} that was returned from {@link #onBeforeLoadFromMemcached(String)}.
     */
    protected void onAfterLoadFromMemcached( @Nonnull final MemcachedBackupSession session, @Nullable final LockStatus lockStatus ) {
        session.setLockStatus( lockStatus );

        final SessionValidityInfo info = loadSessionValidityInfo( session.getIdInternal() );
        if ( info != null ) {
            session.setLastAccessedTimeInternal( info.getLastAccessedTime() );
            session.setThisAccessedTimeInternal( info.getThisAccessedTime() );
        }
        else {
            _log.warn( "No validity info available for session " + session.getIdInternal() );
        }
    }

    protected final void onRequestStart( final Request request ) {
        _requestsThreadLocal.set( request );
    }

    protected final void onRequestFinished() {
        _requestsThreadLocal.set( null );
    }

    private void pingSession( @Nonnull final MemcachedBackupSession session, @Nonnull final BackupSessionService backupSessionService ) throws InterruptedException {
        final Future<Boolean> touchResult = _memcached.add( session.getIdInternal(), session.getMaxInactiveInterval(), 1 );
        try {
            _log.debug( "Got ping result " + touchResult.get() );
            if ( touchResult.get() ) {
                _log.warn( "The session " + session.getIdInternal() + " should be touched in memcached, but it seemed to be" +
                        " not existing anymore. Will store in memcached again." );
                updateSession( session, backupSessionService );
            }
        } catch ( final ExecutionException e ) {
            _log.warn( "An exception occurred when trying to ping session " + session.getIdInternal(), e );
        }
    }

    private void updateSession( @Nonnull final MemcachedBackupSession session, @Nonnull final BackupSessionService backupSessionService )
            throws InterruptedException {
        final Future<BackupResult> result = backupSessionService.backupSession( session, true );
        try {
            if ( result.get().getStatus() != BackupResultStatus.SUCCESS ) {
                _log.warn( "Update for session (after unsuccessful ping) did not return SUCCESS, but " + result.get() );
            }
        } catch ( final ExecutionException e ) {
            _log.warn( "An exception occurred when trying to update session " + session.getIdInternal(), e );
        }
    }

}
