package de.javakaffee.web.msm;

import java.io.IOException;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpSession;

import org.apache.catalina.Session;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

/**
 * This valve is used for tracking requests for that the session must be sent
 * to memcached.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
class SessionTrackerValve extends ValveBase {
    
    static final String RELOCATE = "session.relocate";

    private final Logger _logger = Logger.getLogger( SessionTrackerValve.class.getName() );

    private final Pattern _ignorePattern;
    private final boolean _relocateSessions;
    
    public SessionTrackerValve( String ignorePattern, boolean relocateSessions ) {
        if ( ignorePattern != null ) {
            _logger.info( "Setting ignorePattern to " + ignorePattern );
            _ignorePattern = Pattern.compile( ignorePattern );
        }
        else {
            _ignorePattern = null;
        }
        _relocateSessions = relocateSessions;
    }

    @Override
    public void invoke( Request request, Response response ) throws IOException,
            ServletException {
        // getContainer().getManager()
        
        final Cookie cookie = getCookie( request, "JSESSIONID" );
        _logger.info( "Starting, " + (cookie != null ? cookie.getValue() : null) +
                ", session: " + (request.getSession( false ) != null) + ", request: " + request.getRequestURI()  );
        
//        final HttpSession session2 = _relocateSessions ? request.getSession( false ) : null;
//        final String sessionId = session2 != null ? session2.getId() : null;
        
        getNext().invoke( request, response );

        
        if ( _ignorePattern != null && !_ignorePattern.matcher( request.getRequestURI() ).matches() ) {
            /* do we have a session?
             */
             final Session session = request.getSessionInternal( false );
             if ( session != null ) {
                 
//                 if ( _relocateSessions && sessionId != null ) {
//                     /* we don't have to compare old and new session ids if we are
//                      * already sending a cookie to the client
//                      */
//                     final Cookie respCookie = getCookie( response, "JSESSIONID" );
//                     if ( respCookie != null ) {
//                         _logger.warning( "Strange: we're told to send a cookie for relocation," +
//                         		" but the response already has a cookie set. I'll do nothing" +
//                         		" (of course send the already existing cookie), but" +
//                         		" you should have a look what's going on here!" );
//                         if ( !sessionId.equals( session.getId() ) ) {
//                             response.addCookie( new Cookie( "JSESSIONID", session.getId() ) );
//                         }
//                     }
//                 }
                 
                 ((MemcachedBackupSessionManager)getContainer().getManager()).storeSession( session );
             }
        }

        final Cookie respCookie = getCookie( response, "JSESSIONID" );
        _logger.info( "Finished, " + (respCookie != null ? respCookie.getValue() : null) );
        
    }

    private Cookie getCookie( final HttpServletRequest httpRequest, String name ) {
        Cookie[] cookies = httpRequest.getCookies();
        for ( Cookie cookie : cookies ) {
            if ( name.equals( cookie.getName() ) ) {
                return cookie;
            }
        }
        return null;
    }

    private Cookie getCookie( final Response response, String name ) {
        for ( Cookie cookie : response.getCookies() ) {
            if ( name.equals( cookie.getName() ) ) {
                return cookie;
            }
        }
        return null;
    }
    
    class SessionInterceptor extends HttpServletRequestWrapper {

        public SessionInterceptor(HttpServletRequest request) {
            super( request );
        }

        /* (non-Javadoc)
         * @see javax.servlet.http.HttpServletRequestWrapper#getSession()
         */
        @Override
        public HttpSession getSession() {
            return super.getSession();
        }

        /* (non-Javadoc)
         * @see javax.servlet.http.HttpServletRequestWrapper#getSession(boolean)
         */
        @Override
        public HttpSession getSession( boolean create ) {
            return super.getSession( create );
        }
    }
    
}
