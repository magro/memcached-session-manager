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

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import net.spy.memcached.MemcachedClient;

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
    protected final SessionIdFormat _sessionIdFormat;

    protected LockingStrategy( @Nonnull final MemcachedClient memcached ) {
        _memcached = memcached;
        _sessionIdFormat = new SessionIdFormat();
    }

    /**
     * Creates the appropriate {@link LockingStrategy} for the given {@link LockingMode}.
     * @param lockingMode
     * @return
     */
    @CheckForNull
    public static LockingStrategy create( @Nonnull final LockingMode lockingMode,
            @Nonnull final MemcachedClient memcached,
            @Nonnull final MemcachedBackupSessionManager manager ) {
        switch( lockingMode ) {
            case ALL: return new LockingStrategyAll( memcached );
            case APP: return new LockingStrategyApp( memcached, manager );
            case AUTO: return new LockingStrategyAuto( memcached );
            default: return null;
        }
    }

    protected LockStatus lock( final String sessionId ) {
        return lock( sessionId, LOCK_TIMEOUT, TimeUnit.MILLISECONDS );
    }

    protected LockStatus lock( final String sessionId, final long timeout, final TimeUnit timeUnit ) {
        _log.info( "Locking session " + sessionId );
        try {
            acquireLock( sessionId, LOCK_RETRY_INTERVAL, LOCK_MAX_RETRY_INTERVAL, timeUnit.toMillis( timeout ), System.currentTimeMillis() );
            _log.info( "Locked session " + sessionId );
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

    protected abstract void detectSessionReadOnlyRequestPattern( @Nonnull Future<BackupResultStatus> result, @Nonnull String requestId );

    @CheckForNull
    protected abstract LockStatus lockBeforeLoadingFromMemcached( @Nonnull String sessionId ) throws InterruptedException, ExecutionException;

    /**
     * Must return true if a dummy session object shall be used for session validation check by the container
     * and if this request comes from the container.
     * @return
     */
    protected abstract boolean isContainerSessionLookup();

    protected void onRequestFinished() {
        // nothing to do per default
    }

    protected void onRequestStart( @Nonnull final Request request ) {
        // nothing to do per default
    }

}
