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

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import net.spy.memcached.MemcachedClient;

import org.apache.catalina.Session;
import org.apache.catalina.connector.Request;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

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
        APP
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
    private final LRUCache<String, SessionValidityInfo> _sessionValidityInfos;

    protected LockingStrategy( @Nonnull final MemcachedClient memcached,
            @Nonnull final LRUCache<String, Boolean> missingSessionsCache ) {
        _memcached = memcached;
        _missingSessionsCache = missingSessionsCache;
        _sessionIdFormat = new SessionIdFormat();
        _requestsThreadLocal = new InheritableThreadLocal<Request>();
        _sessionValidityInfos = new LRUCache<String, SessionValidityInfo>( 100, 500 );
    }

    /**
     * Creates the appropriate {@link LockingStrategy} for the given {@link LockingMode}.
     */
    @CheckForNull
    public static LockingStrategy create( @Nonnull final LockingMode lockingMode,
            @Nonnull final MemcachedClient memcached,
            @Nonnull final MemcachedBackupSessionManager manager,
            @Nonnull final LRUCache<String, Boolean> missingSessionsCache ) {
        switch( lockingMode ) {
            case ALL: return new LockingStrategyAll( memcached, missingSessionsCache );
            case APP: return new LockingStrategyApp( memcached, manager, missingSessionsCache );
            case AUTO: return new LockingStrategyAuto( memcached, missingSessionsCache );
            default: return null;
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
    protected void onAfterBackupSession( @Nonnull final MemcachedBackupSession session, @Nonnull final Future<BackupResultStatus> backupResult, @Nonnull final String requestId ) {
        _sessionValidityInfos.remove( session.getIdInternal() );

        final byte[] data = SessionValidityInfo.encode( session.getMaxInactiveInterval(), session.getLastAccessedTimeInternal(), session.getThisAccessedTimeInternal() );
        _memcached.set( SessionValidityInfo.createAccessedTimesKeyName( session.getIdInternal() ), session.getMaxInactiveInterval(), data );
        if ( _log.isDebugEnabled() ) {
            _log.debug( "Stored session validity info for session " + session.getIdInternal() );
        }
    }

    @CheckForNull
    protected abstract LockStatus lockBeforeLoadingFromMemcached( @Nonnull String sessionId ) throws InterruptedException, ExecutionException;

    /**
     * Must return true if a dummy session object shall be used for session validation check by the container
     * and if this request comes from the container.
     * @return
     */
    protected final boolean isContainerSessionLookup() {
        return _requestsThreadLocal.get() == null;
    }

    /**
     * Checks if there's a session stored in memcached for the given key.
     */
    @CheckForNull
    Session getSessionForContainerIsValidCheck( @Nonnull final String id ) throws IOException {
        final SessionValidityInfo info = loadSessionValidityInfo( id );
        if ( info == null ) {
            if ( _log.isDebugEnabled() ) {
                _log.debug( "No session validity info found for session " + id );
            }
            _missingSessionsCache.put( id, Boolean.TRUE );
            return null;
        }
        if ( _log.isDebugEnabled() ) {
            _log.debug( "Loaded session validity info for session " + id );
        }
        _sessionValidityInfos.put( id, info );
        return new EmptyValidSession( info.getMaxInactiveInterval(), info.getLastAccessedTime(), info.getThisAccessedTime() );
    }

    @CheckForNull
    private SessionValidityInfo loadSessionValidityInfo( @Nonnull final String id ) {
        final byte[] validityInfo = (byte[]) _memcached.get( SessionValidityInfo.createAccessedTimesKeyName( id ) );
        return validityInfo != null ? SessionValidityInfo.decode( validityInfo ) : null;
    }

    /**
     * Invoked after a non-sticky session is loaded from memcached, can be used to update some session
     * fields based on separately stored information (e.g. session validity info).
     */
    public void onLoadedFromMemcached( @Nonnull final MemcachedBackupSession session ) {
        SessionValidityInfo info = _sessionValidityInfos.get( session.getIdInternal() );
        if ( info == null ) {
            _log.warn( "No session validity info for session id "+ session.getIdInternal() +" found in cache, loading from memcached now." );
            info = loadSessionValidityInfo( session.getIdInternal() );
        }
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

}
