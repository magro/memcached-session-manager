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
 * Hello world!
 * 
 */
public class SessionTrackerValve extends ValveBase {
    
    private final Logger _logger = Logger.getLogger( SessionTrackerValve.class.getName() );

    private Pattern _ignorePattern;
    
    public void setIgnorePattern( String ignorePattern ) {
        _logger.info( "Setting ignorePattern to " + ignorePattern );
        _ignorePattern = Pattern.compile( ignorePattern );
    }

    @Override
    public void invoke( Request request, Response response ) throws IOException,
            ServletException {
        // getContainer().getManager()
        
        _logger.info( "Starting, " + getCookie( request, "JSESSIONID" ) +
                ", session: " + (request.getSession( false ) != null) + ", request: " + request.getRequestURI()  );
        
        getNext().invoke( request, response );

        
        if ( _ignorePattern.matcher( request.getRequestURI() ).matches() ) {
            /* do we have a session?
             */
             final Session session = request.getSessionInternal( false );
             if ( session != null ) {
                 ((MemcachedBackupSessionManager)getContainer().getManager()).storeSession( session );
             }
        }
        
        _logger.info( "Finished, " + getCookie( response, "JSESSIONID" ) );
        
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
