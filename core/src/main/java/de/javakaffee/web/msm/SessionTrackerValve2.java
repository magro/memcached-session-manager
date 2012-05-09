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
import java.util.concurrent.Future;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;

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
public class SessionTrackerValve2 extends ValveBase {

    static final String INVOKED = "de.javakaffee.msm.contextValve.invoked";
    static final String RELOCATE = "session.relocate";

    protected static final Log _log = LogFactory.getLog( SessionTrackerValve.class );

    private final MemcachedSessionService _sessionBackupService;
    protected final String _sessionCookieName;

    /**
     * Creates a new instance with the given ignore pattern and
     * {@link SessionBackupService}.
     * @param sessionBackupService
     *            the service that actually backups sessions
     * @param ignorePattern
     *            the regular expression for request uris to ignore
     * @param context
     *            the catalina context of this valve
     * @param statistics
     *            used to store statistics
     */
    public SessionTrackerValve2( @Nonnull final String sessionCookieName,
            @Nonnull final MemcachedSessionService sessionBackupService ) {
        _sessionBackupService = sessionBackupService;
        _sessionCookieName = sessionCookieName;
    }

    /**
     * Returns the actually used name for the session cookie.
     * @return the cookie name, never null.
     */
    protected String getSessionCookieName() {
        return _sessionCookieName;
    }

    public boolean wasInvokedWith(final Request currentRequest) {
        return currentRequest != null && currentRequest.getNote(INVOKED) == Boolean.TRUE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void invoke( final Request request, final Response response ) throws IOException, ServletException {

        final Object processRequest = request.getNote(SessionTrackerValve.REQUEST_PROCESS);
        if(processRequest != Boolean.TRUE) {
            request.setNote(INVOKED, Boolean.TRUE);
            try {
                getNext().invoke( request, response );
            } finally {
                request.setNote(SessionTrackerValve.REQUEST_PROCESSED, Boolean.TRUE);
            }
        }
        else {

            if ( _log.isDebugEnabled() ) {
                _log.debug( ">>>>>> Request starting: " + getURIWithQueryString( request ) + " ==================" );
            }

            boolean sessionIdChanged = false;
            try {
                request.setNote(INVOKED, Boolean.TRUE);
                sessionIdChanged = changeRequestedSessionId( request, response );
                getNext().invoke( request, response );
            } finally {
                request.setNote(SessionTrackerValve.REQUEST_PROCESSED, Boolean.TRUE);
                request.setNote(SessionTrackerValve.SESSION_ID_CHANGED, Boolean.valueOf(sessionIdChanged));
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

}
