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
    public void invoke( Request request, Response response ) throws IOException,
            ServletException {
        
        if ( _logger.isLoggable( Level.FINE ) ) {
            final Cookie cookie = getCookie( request, JSESSIONID );
            _logger.fine( "Starting, " + (cookie != null ? cookie.getValue() : null) +
                    ", session: " + (request.getSession( false ) != null) + ", request: " + request.getRequestURI()  );
        }
        
        getNext().invoke( request, response );
        
        if ( _ignorePattern == null || !_ignorePattern.matcher( request.getRequestURI() ).matches() ) {
            
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
                     setCookie( response, request, session );
                 }
             }
        }

        if ( _logger.isLoggable( Level.FINE ) ) {
            final Cookie respCookie = getCookie( response, JSESSIONID );
            _logger.fine( "Finished, " + (respCookie != null ? ToStringBuilder.reflectionToString( respCookie ) : null) );
        }
        
    }

    private void setCookie( Response response, Request request,
            final Session session ) {
        final Cookie newCookie = new Cookie( JSESSIONID, session.getId() );
         newCookie.setMaxAge( -1 );
         newCookie.setPath( request.getContextPath() );
         response.addCookie( newCookie );
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

        BackupResult backupSession( Session session );
        
        static enum BackupResult {
            SUCCESS, FAILURE, RELOCATED
        }
        
    }
    
}
