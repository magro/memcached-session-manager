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
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;

import org.apache.catalina.Session;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.coyote.ActionHook;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import de.javakaffee.web.msm.SessionTrackerValve.SessionBackupService.BackupResultStatus;

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

    /**
     * Creates a new instance with the given ignore pattern and
     * {@link SessionBackupService}.
     *
     * @param ignorePattern
     *            the regular expression for request uris to ignore
     * @param sessionBackupService
     *            the service that actually backups sessions
     */
    public SessionTrackerValve( final String ignorePattern, final SessionBackupService sessionBackupService,
            final Statistics statistics ) {
        if ( ignorePattern != null ) {
            _log.info( "Setting ignorePattern to " + ignorePattern );
            _ignorePattern = Pattern.compile( ignorePattern );
        } else {
            _ignorePattern = null;
        }
        _sessionBackupService = sessionBackupService;
        _statistics = statistics;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void invoke( final Request request, final Response response ) throws IOException, ServletException {

        if ( _ignorePattern != null && _ignorePattern.matcher( request.getRequestURI() ).matches() ) {
            getNext().invoke( request, response );
        } else {

            /*
             * add the commit intercepting action hook (only) if a session id
             * was requested
             */
            if ( request.getRequestedSessionId() != null && response.getCoyoteResponse().getHook() != null ) {
                final ActionHook hook = createCommitHook( request, response );
                response.getCoyoteResponse().setHook( hook );
            }

            getNext().invoke( request, response );

            backupSession( request, response );

            logDebugResponseCookie( response );

        }

    }

    private void backupSession( final Request request, final Response response ) {

        /*
         * Do we have a session?
         *
         * Prior check for requested sessionId or response cookie before
         * invoking getSessionInternal, as getSessionInternal triggers a
         * memcached lookup if the session is not available locally.
         */
        final Session session = request.getRequestedSessionId() != null || getCookie( response, JSESSIONID ) != null
            ? request.getSessionInternal( false )
            : null;
        if ( _log.isDebugEnabled() ) {
            _log.debug( "Have a session: " + ( session != null ) );
        }
        if ( session != null ) {

            _statistics.requestWithSession();

            final BackupResultStatus result = _sessionBackupService.backupSession( session );

            if ( result == BackupResultStatus.RELOCATED ) {
                if ( _log.isDebugEnabled() ) {
                    _log.debug( "Session got relocated, setting a cookie: " + session.getId() );
                }
                setSessionIdCookie( response, request, session );
            }
        }
        else {
            _statistics.requestWithoutSession();
        }

    }

    private void logDebugResponseCookie( final Response response ) {
        if ( _log.isDebugEnabled() ) {
            final Cookie respCookie = getCookie( response, JSESSIONID );
            _log.debug( "Finished, " + ( respCookie != null
                ? toString( respCookie )
                : null ) );
        }
    }

    private String toString( final Cookie cookie ) {
        return new StringBuilder( cookie.getClass().getName() ).append( "[name=" ).append( cookie.getName() ).append( ", value=" ).append(
                cookie.getValue() ).append( ", domain=" ).append( cookie.getDomain() ).append( ", path=" ).append( cookie.getPath() ).append(
                ", maxAge=" ).append( cookie.getMaxAge() ).append( ", secure=" ).append( cookie.getSecure() ).append( ", version=" ).append(
                cookie.getVersion() ).toString();
    }

    private ActionHook createCommitHook( final Request request, final Response response ) {
        // TODO: check if we have already a CommitInterceptingActionHook here, as tomcat
        // might reuse its internal stuff according to Bill Barker:
        // http://www.nabble.com/Re:-How-to-set-header-(directly)-before-response-is-committed-p25217026.html
        final ActionHook origHook = response.getCoyoteResponse().getHook();
        final ActionHook hook = new CommitInterceptingActionHook( response.getCoyoteResponse(), origHook ) {

            @Override
            void beforeCommit() {
                //_logger.info( " CommitInterceptingActionHook executing before commit..." );
                final Session session = request.getSessionInternal( false );
                if ( session != null ) {
                    final String newSessionId = _sessionBackupService.determineSessionIdForBackup( session );
                    //_logger.info( "CommitInterceptingActionHook before commit got new session id: " + newSessionId );
                    if ( newSessionId != null ) {
                        setSessionIdCookie( response, request, newSessionId );
                    }
                }
                response.getCoyoteResponse().setHook( origHook );
            }

        };
        return hook;
    }

    private void setSessionIdCookie( final Response response, final Request request, final Session session ) {
        setSessionIdCookie( response, request, session.getId() );
    }

    private void setSessionIdCookie( final Response response, final Request request, final String sessionId ) {
        //_logger.fine( "Response is committed: " + response.isCommitted() + ", closed: " + response.isClosed() );
        final Cookie newCookie = new Cookie( JSESSIONID, sessionId );
        newCookie.setMaxAge( -1 );
        newCookie.setPath( request.getContextPath() );
        if ( request.isSecure() ) {
            newCookie.setSecure( true );
        }
        response.addCookieInternal( newCookie );
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
         * Returns the new session id if the provided session will be relocated
         * with the next {@link #backupSession(Session)}.
         * This is used to determine during the (directly before) response.commit,
         * if the session will be relocated so that a new session cookie can be
         * added to the response headers.
         *
         * @param session
         *            the session to check, never null.
         * @return the new session id, if this session has to be relocated.
         */
        String determineSessionIdForBackup( final Session session );

        /**
         * Backup the provided session in memcached.
         *
         * @param session
         *            the session to backup
         * @return a {@link BackupResultStatus}
         */
        BackupResultStatus backupSession( Session session );

        /**
         * The enumeration of possible backup results.
         */
        static enum BackupResultStatus {
                /**
                 * The session was successfully stored in the sessions default memcached node.
                 */
                SUCCESS,
                /**
                 * The session could not be stored in any memcached node.
                 */
                FAILURE,
                /**
                 * The session was moved to another memcached node and stored successfully therein,
                 * a new session cookie must be sent to the client.
                 * If the necessary relocation was detected with {@link SessionBackupService#sessionNeedsRelocate(Session)}
                 * before, {@link #SUCCESS} must be returned.
                 */
                RELOCATED,
                /**
                 * The session was not modified and therefore the backup was skipped.
                 */
                SKIPPED
        }

    }

}
