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
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

import org.apache.catalina.Session;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.coyote.ActionHook;

import de.javakaffee.web.msm.SessionTrackerValve.SessionBackupService.BackupResult;

/**
 * This valve is used for tracking requests for that the session must be sent
 * to memcached.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
class SessionTrackerValve extends ValveBase {
    
    static final String JSESSIONID = "JSESSIONID";

    static final String RELOCATE = "session.relocate";

    private final Logger _logger = Logger.getLogger( SessionTrackerValve.class.getName() );

    private final Pattern _ignorePattern;
    private final SessionBackupService _sessionBackupService;
    
    public SessionTrackerValve( String ignorePattern, SessionBackupService sessionBackupService ) {
        if ( ignorePattern != null ) {
            _logger.info( "Setting ignorePattern to " + ignorePattern );
            _ignorePattern = Pattern.compile( ignorePattern );
        }
        else {
            _ignorePattern = null;
        }
        _sessionBackupService = sessionBackupService;
    }

    @Override
    public void invoke( final Request request, final Response response ) throws IOException,
            ServletException {

        if ( _ignorePattern != null && _ignorePattern.matcher( request.getRequestURI() ).matches() ) {
            getNext().invoke( request, response );
        }
        else {
            
            /* add the commit intercepting action hook (only) if a session id was requested
             */
            if ( request.getRequestedSessionId() != null && response.getCoyoteResponse().getHook() != null ) {
                // TODO: check if we have already a CommitInterceptingActionHook here, as tomcat
                // might reuse its internal stuff according to Bill Barker:
                // http://www.nabble.com/Re:-How-to-set-header-(directly)-before-response-is-committed-p25217026.html
                final ActionHook origHook = response.getCoyoteResponse().getHook();
                final ActionHook hook = new CommitInterceptingActionHook(response.getCoyoteResponse(), origHook) {
                    
                    @Override
                    void beforeCommit() {
                        //_logger.info( " CommitInterceptingActionHook executing before commit..." );
                        final Session session = request.getSessionInternal( false );
                        if ( session != null ) {
                            final String newSessionId = _sessionBackupService.sessionNeedsRelocate( session );
                            //_logger.info( "CommitInterceptingActionHook before commit got new session id: " + newSessionId );
                            if ( newSessionId != null ) {
                                setSessionIdCookie( response, request, newSessionId );
                            }
                        }
                        response.getCoyoteResponse().setHook( origHook );
                    }
                    
                };
                response.getCoyoteResponse().setHook( hook );
            }
            
            getNext().invoke( request, response );

            /* Do we have a session?
             * 
             * Prior check for requested sessionId or response cookie
             * before invoking getSessionInternal, as getSessionInternal triggers a
             * memcached lookup if the session is not available locally.
             */
             final Session session = request.getRequestedSessionId() != null
                 || getCookie( response, JSESSIONID ) != null ? request.getSessionInternal( false ) : null;
             if ( _logger.isLoggable( Level.FINE ) ) {
                 _logger.fine( "Have a session: " + ( session != null ));
             }
             if ( session != null ) {
                 
                 final BackupResult result = _sessionBackupService.backupSession( session );
                 
                 if ( result == BackupResult.RELOCATED ) {
                     if ( _logger.isLoggable( Level.FINE ) ) {
                         _logger.fine( "Session got relocated, setting a cookie: " + session.getId() );
                     }
                     setSessionIdCookie( response, request, session );
                 }
             }

            if ( _logger.isLoggable( Level.FINE ) ) {
                final Cookie respCookie = getCookie( response, JSESSIONID );
                _logger.fine( "Finished, " + (respCookie != null ? ToStringBuilder.reflectionToString( respCookie ) : null) );
            }
        }

//        if ( _ignorePattern == null || !_ignorePattern.matcher( request.getRequestURI() ).matches() ) {
//        if ( _logger.isLoggable( Level.INFO ) ) {
//            final Cookie cookie = getCookie( request, JSESSIONID );
//            _logger.info( "++++++++++++++ Starting, " + (cookie != null ? cookie.getValue() : null) +
//                    ", session: " + (request.getSession( false ) != null) + ", request: " + request.getRequestURI()  );
//        }
//        }
        
        
    }

    private void setSessionIdCookie( Response response, Request request,
            final Session session ) {
        setSessionIdCookie( response, request, session.getId() );
    }
    
    private void setSessionIdCookie( Response response, Request request, final String sessionId ) {
        //_logger.fine( "Response is committed: " + response.isCommitted() + ", closed: " + response.isClosed() );
        final Cookie newCookie = new Cookie( JSESSIONID, sessionId );
         newCookie.setMaxAge( -1 );
         newCookie.setPath( request.getContextPath() );
         response.addCookieInternal( newCookie );
    }

    private Cookie getCookie( final HttpServletRequest httpRequest, String name ) {
        final Cookie[] cookies = httpRequest.getCookies();
        if ( cookies != null ) {
            for ( Cookie cookie : cookies ) {
                if ( name.equals( cookie.getName() ) ) {
                    return cookie;
                }
            }
        }
        return null;
    }

    private Cookie getCookie( final Response response, String name ) {
        final Cookie[] cookies = response.getCookies();
        if ( cookies != null ) {
            for ( Cookie cookie : cookies ) {
                if ( name.equals( cookie.getName() ) ) {
                    return cookie;
                }
            }
        }
        return null;
    }
    
    public static interface SessionBackupService {
        
        /**
         * Returns the new session id if the provided session has to be relocated.
         * @param session the session to check, never null.
         * @return the new session id, if this session has to be relocated.
         */
        String sessionNeedsRelocate( final Session session );

        BackupResult backupSession( Session session );
        
        static enum BackupResult {
            SUCCESS, FAILURE, RELOCATED
        }
        
    }
    
}
