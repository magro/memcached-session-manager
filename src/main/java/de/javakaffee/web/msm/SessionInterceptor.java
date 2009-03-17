/*
 * $Id: $ (c)
 * Copyright 2009 freiheit.com technologies GmbH
 *
 * Created on Mar 14, 2009
 *
 * This file contains unpublished, proprietary trade secret information of
 * freiheit.com technologies GmbH. Use, transcription, duplication and
 * modification are strictly prohibited without prior written consent of
 * freiheit.com technologies GmbH.
 */
package de.javakaffee.web.msm;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpSession;

/**
 * TODO: DESCRIBE ME<br>
 * Created on: Mar 14, 2009<br>
 * 
 * @author <a href="mailto:martin.grotzke@freiheit.com">Martin Grotzke</a>
 * @version $Id$
 */
public class SessionInterceptor extends HttpServletRequestWrapper {

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
