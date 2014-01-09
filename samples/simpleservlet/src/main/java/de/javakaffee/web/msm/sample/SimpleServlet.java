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
package de.javakaffee.web.msm.sample;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Enumeration;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple servlet for testing.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class SimpleServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger( SimpleServlet.class );

    /*
     * (non-Javadoc)
     * 
     * @see javax.servlet.http.HttpServlet#doGet(javax.servlet.http.HttpServletRequest,
     * javax.servlet.http.HttpServletResponse)
     */
    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        if ( "/favicon.ico".equals( request.getRequestURI() ) ) {
            super.doGet( request, response );
            return;
        }

        final String pathInfo = request.getPathInfo();
        LOG.info( " + starting at path " + pathInfo );

        if ( pathInfo.startsWith( "/readonly" ) ) {
            response.getWriter().println( "readonly" );
            return;
        }

        if ( pathInfo.startsWith( "/put" ) ) {
            final HttpSession session = request.getSession();
            final Cache cache = getOrCreateCache( session );
            final Enumeration<String> names = request.getParameterNames();
            while ( names.hasMoreElements() ) {
                final String name = names.nextElement();
                final String value = request.getParameter( name );
                cache.put( name, value );
            }
        }
        else if ( pathInfo.startsWith( "/list" ) ) {
            final HttpSession session = request.getSession(false);
            if(session == null)
            	return;
            
            final PrintWriter out = response.getWriter();
            final Cache cache = getOrCreateCache( session );
            for ( final Map.Entry<String, Object> entry : cache.entrySet() ) {
                out.println( entry.getKey() + "=" + entry.getValue() );
            }
        }
        else if ( pathInfo.startsWith( "/swapSessionId" ) ) {
            final HttpSession session = request.getSession();
            final Cache cache = getOrCreateCache( session );

            final PrintWriter out = response.getWriter();
            out.println( "old sessionId: " + session.getId() );
            session.invalidate();

            final HttpSession newSession = request.getSession();
            newSession.setAttribute( "cache", cache );
            out.println( "new sessionId: " + newSession.getId() );
        }

    }

    private Cache getOrCreateCache( final HttpSession session ) {
        Cache cache = (Cache) session.getAttribute( "cache" );
        if ( cache == null ) {
            cache = new CacheImpl();
            session.setAttribute( "cache", cache );
        }
        return cache;
    }

}
