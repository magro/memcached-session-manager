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
class SessionTrackerValve extends ValveBase {

    static final String JSESSIONID = "JSESSIONID";

    static final String RELOCATE = "session.relocate";

    private final Log _log = LogFactory.getLog( MemcachedBackupSessionManager.class );

    private final Pattern _ignorePattern;
    private final SessionBackupService _sessionBackupService;
    private final Statistics _statistics;
    private final AddCookieInteralStrategy _addCookieInteralStrategy;
    private final AtomicBoolean _enabled;
    private @CheckForNull LockingStrategy _lockingStrategy;

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
        _addCookieInteralStrategy = AddCookieInteralStrategy.createFor( context );
        _enabled = enabled;
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

            boolean sessionIdChanged = false;
            try {
                storeRequestThreadLocal( request );
                sessionIdChanged = changeRequestedSessionId( request, response );
                getNext().invoke( request, response );
            } finally {
                backupSession( request, response, sessionIdChanged );
                resetRequestThreadLocal();
            }

            if ( _log.isDebugEnabled() ) {
                final Cookie respCookie = getCookie( response, JSESSIONID );
                if ( respCookie != null ) {
                    _log.debug( "Sent response cookie: " + toString( respCookie ) );
                }
                _log.debug( "<<<<<< Request finished: " + getURIWithQueryString( request ) + " ==================" );
            }

        }

    }

    @Nonnull
    protected static String getURIWithQueryString( @Nonnull final Request request ) {
        final String uri = request.getRequestURI();
        final String qs = request.getMethod().toLowerCase().equals( "post" ) ? null : request.getQueryString();
        return qs != null ? uri + "?" + qs : uri;
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
     * that will be relocated (memcached failover), the new session id will be set as requested
     * session id on the request and a new session id cookie will be set (if the session id was
     * requested via a cookie and if the context is configured to use cookies for session ids).
     *
     * @param request the request
     * @param response the response
     *
     * @return <code>true</code> if the id of a valid session was changed.
     *
     * @see Request#setRequestedSessionId(String)
     * @see Request#isRequestedSessionIdFromCookie()
     * @see Context#getCookies()
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
                request.setRequestedSessionId( newSessionId );
                if ( request.isRequestedSessionIdFromCookie() ) {
                    setSessionIdCookie( response, request, newSessionId );
                }
                return true;
            }

        }
        return false;
    }

    private void backupSession( final Request request, final Response response, final boolean sessionIdChanged ) {

        /*
         * Do we have a session?
         *
         * Prior check for requested sessionId or response cookie before
         * invoking getSessionInternal, as getSessionInternal triggers a
         * memcached lookup if the session is not available locally.
         */
        // TODO: in non-sticky mode we should not load the session to just store it afterwards...
        final Session session = request.getRequestedSessionId() != null || getCookie( response, JSESSIONID ) != null
            ? request.getSessionInternal( false )
            : null;
        if ( session != null ) {
            _statistics.requestWithSession();
            _sessionBackupService.backupSession( session, sessionIdChanged, getURIWithQueryString( request ) );
        }
        else {
            _statistics.requestWithoutSession();
        }

    }

    private String toString( final Cookie cookie ) {
        return new StringBuilder( cookie.getClass().getName() ).append( "[name=" ).append( cookie.getName() ).append( ", value=" ).append(
                cookie.getValue() ).append( ", domain=" ).append( cookie.getDomain() ).append( ", path=" ).append( cookie.getPath() ).append(
                ", maxAge=" ).append( cookie.getMaxAge() ).append( ", secure=" ).append( cookie.getSecure() ).append( ", version=" ).append(
                cookie.getVersion() ).toString();
    }

    private void setSessionIdCookie( final Response response, final Request request, final String sessionId ) {
        //_logger.fine( "Response is committed: " + response.isCommitted() + ", closed: " + response.isClosed() );
        final Context context = request.getContext();
        if ( context.getCookies() ) {
            final Cookie newCookie = new Cookie( JSESSIONID, sessionId );
            newCookie.setMaxAge( -1 );
            newCookie.setPath( getContextPath( request ) );
            if ( request.isSecure() ) {
                newCookie.setSecure( true );
            }
            _addCookieInteralStrategy.addCookieInternal( newCookie, response );
        }
    }

    private String getContextPath( final Request request ) {
        final String contextPath = request.getContext().getEncodedPath();
        return contextPath != null && contextPath.length() > 0 ? contextPath : "/";
    }

    private Cookie getCookie( final Response response, final String name ) {
        final Cookie[] cookies = response.getCookies();
        if ( cookies != null ) {
            for ( final Cookie cookie : cookies ) {
                if ( name.equals( cookie.getName() ) ) {
                    return cookie;
                }
            }
        }
        return null;
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
         * Backup the provided session in memcached if the session was modified or
         * if the session needs to be relocated.
         *
         * @param session
         *            the session to backup
         * @param sessionIdChanged
         *            specifies, if the session id was changed due to a memcached failover or tomcat failover.
         * @param requestId
         *            the uri of the request for that the session backup shall be performed.
         *
         * @return a {@link Future} providing the {@link BackupResultStatus}.
         */
        Future<BackupResult> backupSession( Session session, boolean sessionIdChanged, String requestId );

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
