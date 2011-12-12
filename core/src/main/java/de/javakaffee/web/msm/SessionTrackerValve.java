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

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;

import org.apache.catalina.Context;
import org.apache.catalina.Session;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import de.javakaffee.web.msm.BackupSessionTask.BackupResult;

/**
 * This valve is used for tracking requests for that the session must be sent to
 * memcached.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
abstract class SessionTrackerValve extends ValveBase {

    static final String RELOCATE = "session.relocate";

    protected static final Log _log = LogFactory.getLog( SessionTrackerValve.class );

    private final Pattern _ignorePattern;
    private final SessionBackupService _sessionBackupService;
    private final Statistics _statistics;
    private final AtomicBoolean _enabled;
    protected final String _sessionCookieName;
    private @CheckForNull LockingStrategy _lockingStrategy;    
    private Map<String, String> _internalSessionLocks;

    /**
     * Creates a new instance with the given ignore pattern and
     * {@link SessionBackupService}.
     *
     * @param ignorePattern
     *            the regular expression for request uris to ignore
     * @param context
     *            the catalina context of this valve
     * @param sessionBackupService
     *            the service that actually backups sessions
     * @param statistics
     *            used to store statistics
     * @param enabled
     *            specifies if memcached-session-manager is enabled or not.
     *            If <code>false</code>, each request is just processed without doing anything further.
     */
    public SessionTrackerValve( @Nullable final String ignorePattern, @Nonnull final Context context,
            @Nonnull final SessionBackupService sessionBackupService,
            @Nonnull final Statistics statistics,
            @Nonnull final AtomicBoolean enabled ) {
        if ( ignorePattern != null ) {
            _log.info( "Setting ignorePattern to " + ignorePattern );
            _ignorePattern = Pattern.compile( ignorePattern );
        } else {
            _ignorePattern = null;
        }
        _sessionBackupService = sessionBackupService;
        _statistics = statistics;
        _enabled = enabled;
        _sessionCookieName = getSessionCookieName( context );
        _internalSessionLocks = Collections.synchronizedMap(new HashMap<String, String>());
    }

    /**
     * Must return the configured session cookie name.
     * @param context the context that might provide the session cookie name configuration.
     * @return the session cookie name.
     */
    @Nonnull
    protected abstract String getSessionCookieName( final Context context );

    /**
     * Returns the actually used name for the session cookie.
     * @return the cookie name, never null.
     */
    protected String getSessionCookieName() {
        return _sessionCookieName;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void invoke( final Request request, final Response response ) throws IOException, ServletException {

        if ( !_enabled.get() || _ignorePattern != null && _ignorePattern.matcher( request.getRequestURI() ).matches() ) {
            getNext().invoke( request, response );
        } else {

            if ( _log.isDebugEnabled() ) {
                _log.debug( ">>>>>> Request starting: " + getURIWithQueryString( request ) + " ==================" );
            }
            
            final String sessionId = request.getRequestedSessionId();
            
            if (_lockingStrategy instanceof LockingStrategyAll) {     
            	// for LockingStrategyAll, lock internal session to prevent concurrent access.
            	final long start = System.currentTimeMillis();  
            	long retryInterval = 10;
            	while (_internalSessionLocks.containsKey(sessionId) ) {            		
            		try {
            			_lockingStrategy.checkTimeoutAndWait(sessionId, retryInterval, start);
            			retryInterval = retryInterval * 2;
            		} catch (Exception e) {
            			_log.warn( "Exception waiting for lock to release: " + sessionId, e);
            			break;
            		}
            	}            	
            }
            	
            boolean sessionIdChanged = false;
            try {
            	_internalSessionLocks.put(sessionId, "LOCKED");
                storeRequestThreadLocal( request );
                sessionIdChanged = changeRequestedSessionId( request, response );
                getNext().invoke( request, response );
            } finally {
            	backupSession( request, response, sessionIdChanged );            	
                resetRequestThreadLocal();
                _internalSessionLocks.remove(sessionId);
            }

            if ( _log.isDebugEnabled() ) {
                logDebugRequestSessionCookie( request );
                logDebugResponseCookie( response );
                _log.debug( "<<<<<< Request finished: " + getURIWithQueryString( request ) + " ==================" );
            }
            
            

        }

    }

    protected void logDebugRequestSessionCookie( final Request request ) {
        final Cookie[] cookies = request.getCookies();
        if ( cookies == null ) {
            return;
        }
        for( final javax.servlet.http.Cookie cookie : cookies ) {
            if ( cookie.getName().equals( _sessionCookieName ) ) {
                _log.debug( "Have request session cookie: domain=" + cookie.getDomain() + ", maxAge=" + cookie.getMaxAge() +
                        ", path=" + cookie.getPath() + ", value=" + cookie.getValue() +
                        ", version=" + cookie.getVersion() + ", secure=" + cookie.getSecure() );
            }
        }
    }

    @Nonnull
    protected static String getURIWithQueryString( @Nonnull final Request request ) {
        final String uri = request.getRequestURI();
        final String qs = isPostMethod(request) ? null : request.getQueryString();
        return qs != null ? uri + "?" + qs : uri;
    }

	protected static boolean isPostMethod(final Request request) {
		final String method = request.getMethod();
		if ( method == null && _log.isDebugEnabled() ) {
			_log.debug("No method set for request " + request.getRequestURI() +
					(request.getQueryString() != null ? "?" + request.getQueryString() : ""));
		}
		return method != null ? method.toLowerCase().equals( "post" ) : false;
	}

    private void resetRequestThreadLocal() {
        if ( _lockingStrategy != null ) {
            _lockingStrategy.onRequestFinished();
        }
    }

    private void storeRequestThreadLocal( @Nonnull final Request request ) {
        if ( _lockingStrategy != null ) {
            _lockingStrategy.onRequestStart( request );
        }
    }

    /**
     * If there's a session for a requested session id that is taken over (tomcat failover) or
     * that will be relocated (memcached failover), the new session id will be set (via {@link Request#changeSessionId(String)}).
     *
     * @param request the request
     * @param response the response
     *
     * @return <code>true</code> if the id of a valid session was changed.
     *
     * @see Request#changeSessionId(String)
     */
    private boolean changeRequestedSessionId( final Request request, final Response response ) {
        /*
         * Check for session relocation only if a session id was requested
         */
        if ( request.getRequestedSessionId() != null ) {

        	String newSessionId = _sessionBackupService.changeSessionIdOnTomcatFailover( request.getRequestedSessionId() );
        	if ( newSessionId == null ) {
                newSessionId = _sessionBackupService.changeSessionIdOnMemcachedFailover( request.getRequestedSessionId() );
            }

            if ( newSessionId != null ) {
                request.changeSessionId( newSessionId );
                return true;
            }

        }
        return false;
    }

    private void backupSession( final Request request, final Response response, final boolean sessionIdChanged ) {

        /*
         * Do we have a session?
         */
        String sessionId = getSessionIdFromResponseSessionCookie( response );
        if ( sessionId == null ) {
            sessionId = request.getRequestedSessionId();
        }
        if ( sessionId != null ) {
            _statistics.requestWithSession();
            _sessionBackupService.backupSession( sessionId, sessionIdChanged, getURIWithQueryString( request ) );
        }
        else {
            _statistics.requestWithoutSession();
        }

    }

    private String getSessionIdFromResponseSessionCookie( final Response response ) {
        final String header = response.getHeader( "Set-Cookie" );
        if ( header != null && header.contains( _sessionCookieName ) ) {
            final String sessionIdPrefix = _sessionCookieName + "=";
            final int idxNameStart = header.indexOf( sessionIdPrefix );
            final int idxValueStart = idxNameStart + sessionIdPrefix.length();
            int idxValueEnd = header.indexOf( ';', idxNameStart );
            if ( idxValueEnd == -1 ) {
                idxValueEnd = header.indexOf( ' ', idxValueStart );
            }
            if ( idxValueEnd == -1 ) {
                idxValueEnd = header.length();
            }
            return header.substring( idxValueStart, idxValueEnd );
        }
        return null;
    }

    private void logDebugResponseCookie( final Response response ) {
        final String header = response.getHeader("Set-Cookie");
        if ( header != null && header.contains( _sessionCookieName ) ) {
            _log.debug( "Request finished, with Set-Cookie header: " + header );
        }
    }

    /**
     * The service that stores session backups in memcached.
     */
    public static interface SessionBackupService {

        /**
         * Check if the given session id does not belong to this tomcat (according to the
         * local jvmRoute and the jvmRoute in the session id). If the session contains a
         * different jvmRoute load if from memcached. If the session was found in memcached and
         * if it's valid it must be associated with this tomcat and therefore the session id has to
         * be changed. The new session id must be returned if it was changed.
         * <p>
         * This is only useful for sticky sessions, in non-sticky operation mode <code>null</code> should
         * always be returned.
         * </p>
         *
         * @param requestedSessionId
         *            the sessionId that was requested.
         *
         * @return the new session id if the session is taken over and the id was changed.
         *          Otherwise <code>null</code>.
         *
         * @see Request#getRequestedSessionId()
         */
        String changeSessionIdOnTomcatFailover( final String requestedSessionId );

        /**
         * Check if the valid session associated with the provided
         * requested session Id will be relocated with the next {@link #backupSession(Session, boolean)}
         * and change the session id to the new one (containing the new memcached node). The
         * new session id must be returned if the session will be relocated and the id was changed.
         *
         * @param requestedSessionId
         *            the sessionId that was requested.
         *
         * @return the new session id if the session will be relocated and the id was changed.
         *          Otherwise <code>null</code>.
         *
         * @see Request#getRequestedSessionId()
         */
        String changeSessionIdOnMemcachedFailover( final String requestedSessionId );

        /**
         * Backup the session for the provided session id in memcached if the session was modified or
         * if the session needs to be relocated. In non-sticky session-mode the session should not be
         * loaded from memcached for just storing it again but only metadata should be updated.
         *
         * @param sessionId
         *            the if of the session to backup
         * @param sessionIdChanged
         *            specifies, if the session id was changed due to a memcached failover or tomcat failover.
         * @param requestId
         *            the uri of the request for that the session backup shall be performed.
         *
         * @return a {@link Future} providing the {@link BackupResultStatus}.
         */
        Future<BackupResult> backupSession( @Nonnull String sessionId, boolean sessionIdChanged, String requestId );

        /**
         * The enumeration of possible backup results.
         */
        static enum BackupResultStatus {
                /**
                 * The session was successfully stored in the sessions default memcached node.
                 * This status is also used, if a session was relocated to another memcached node.
                 */
                SUCCESS,
                /**
                 * The session could not be stored in any memcached node.
                 */
                FAILURE,
                /**
                 * The session was not modified and therefore the backup was skipped.
                 */
                SKIPPED
        }

    }

    public void setLockingStrategy( @Nullable final LockingStrategy lockingStrategy ) {
        _lockingStrategy = lockingStrategy;
    }

}
