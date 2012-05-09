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
package de.javakaffee.web.msm.integration;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Enumeration;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.catalina.servlets.DefaultServlet;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * The servlet used for integration testing.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class TestServlet extends HttpServlet {

    /**
     * The key of the id in the response body.
     */
    public static final String ID = "id";
    public static final String PATH_WAIT = "/sleep";
    public static final String PARAM_WAIT = "sleep";
    public static final String PARAM_MILLIS = "millies";
    public static final String PARAM_REMOVE = "remove";
    public static final String PATH_GET_REQUESTED_SESSION_INFO = "/requestedSessionInfo";
    public static final String KEY_REQUESTED_SESSION_ID = "requestedSessionId";
    public static final String KEY_IS_REQUESTED_SESSION_ID_VALID = "isRequestedSessionIdValid";
    public static final String PATH_NO_SESSION_ACCESS = "/noSessionAccess";
    public static final String PATH_INVALIDATE = "/invalidate";

    private static final long serialVersionUID = 7954803132860358448L;

    private static final Log LOG = LogFactory.getLog( TestServlet.class );
    private DefaultServlet defaultServlet;

    @Override
    public void init(final ServletConfig config) throws ServletException {
        super.init(config);
        defaultServlet = new DefaultServlet();
        defaultServlet.init(config);
    }

    /* (non-Javadoc)
     * @see javax.servlet.http.HttpServlet#doGet(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String pathInfo = request.getPathInfo();
        LOG.info( " + starting "+ pathInfo +"..." );

        if ("/pixel.gif".equals(pathInfo)) {
            defaultServlet.service(request, response);
            return;
        }
        else if ( PATH_GET_REQUESTED_SESSION_INFO.equals( pathInfo ) ) {
            LOG.info( "getRequestedSessionId: " + request.getRequestedSessionId() );
            LOG.info( "isRequestedSessionIdValid: " + request.isRequestedSessionIdValid() );
            final PrintWriter out = response.getWriter();
            out.println( KEY_REQUESTED_SESSION_ID + "=" + request.getRequestedSessionId() );
            out.println( KEY_IS_REQUESTED_SESSION_ID_VALID + "=" + request.isRequestedSessionIdValid() );
        }
        else if ( PATH_NO_SESSION_ACCESS.equals( pathInfo ) ) {
            LOG.info( "skipping session access" );
            response.getWriter().println( "Skipped session access" );
        }
        else if ( PATH_INVALIDATE.equals( pathInfo ) ) {
            final HttpSession session = request.getSession(false);
            LOG.info( "Invalidating session " + session.getId() );
            session.invalidate();
            response.getWriter().println( "Invalidated session " + session.getId() );
        }
        else {

            final HttpSession session = request.getSession();

            waitIfRequested( request );

            final String removeKey = request.getParameter(PARAM_REMOVE);
            if (removeKey != null && !"".equals(removeKey)) {
                final String[] keys = removeKey.split(",");
                LOG.info("Removing " + (keys.length > 1 ? "keys " : "key ") + Arrays.asList(keys));
                for (final String key : keys) {
                    session.removeAttribute(key);
                }
            }

            final PrintWriter out = response.getWriter();
            out.println( ID + "=" + session.getId() );

            // final HttpSession session = request.getSession( false );
            final Enumeration<?> attributeNames = session.getAttributeNames();
            while ( attributeNames.hasMoreElements() ) {
                final String name = attributeNames.nextElement().toString();
                final Object value = session.getAttribute( name );
                out.println( name + "=" + value );
            }
        }

        LOG.info( " - finished." );

    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response ) throws ServletException, IOException {

        LOG.info( "invoked" );

        final HttpSession session = request.getSession();

        waitIfRequested( request );

        final PrintWriter out = response.getWriter();

        out.println( "OK: " + session.getId() );

        @SuppressWarnings( "unchecked" )
        final Enumeration<String> names = request.getParameterNames();
        while ( names.hasMoreElements() ) {
            final String name = names.nextElement();
            final String value = request.getParameter( name );
            session.setAttribute( name, value );
        }

    }

    private void waitIfRequested( final HttpServletRequest request ) throws ServletException {
        final String pathInfo = request.getPathInfo();
        if ( PATH_WAIT.equals( pathInfo ) || request.getParameter( PARAM_WAIT ) != null ) {
            try {
                Thread.sleep( Long.parseLong( request.getParameter( PARAM_MILLIS ) ) );
            } catch ( final Exception e ) {
                throw new ServletException( "Could not sleep.", e );
            }
        }
    }

}
