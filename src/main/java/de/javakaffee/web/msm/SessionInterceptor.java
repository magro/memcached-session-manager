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
