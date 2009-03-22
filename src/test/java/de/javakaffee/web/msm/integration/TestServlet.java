/*
 * $Id: $ (c)
 * Copyright 2009 freiheit.com technologies GmbH
 *
 * Created on Mar 22, 2009
 *
 * This file contains unpublished, proprietary trade secret information of
 * freiheit.com technologies GmbH. Use, transcription, duplication and
 * modification are strictly prohibited without prior written consent of
 * freiheit.com technologies GmbH.
 */
package de.javakaffee.web.msm.integration;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * TODO: DESCRIBE ME<br>
 * Created on: Mar 22, 2009<br>
 * 
 * @author <a href="mailto:martin.grotzke@freiheit.com">Martin Grotzke</a>
 * @version $Id$
 */
public class TestServlet extends HttpServlet {
    
    private static final long serialVersionUID = 7954803132860358448L;

    /* (non-Javadoc)
     * @see javax.servlet.http.HttpServlet#doGet(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    protected void doGet( HttpServletRequest request, HttpServletResponse response )
            throws ServletException, IOException {
        
        log( "invoked" );
        
        final PrintWriter out = response.getWriter();
        out.println( "OK: " + request.getSession().getId() );
        
        out.close();
        
    }

}
