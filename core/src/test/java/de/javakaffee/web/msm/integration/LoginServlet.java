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

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * The login servlet used for integration testing.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class LoginServlet extends HttpServlet {

    private static final long serialVersionUID = 7954803132860358448L;

    public static final String J_USERNAME = "j_username";
    public static final String J_PASSWORD = "j_password";

    /* (non-Javadoc)
     * @see javax.servlet.http.HttpServlet#doGet(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final PrintWriter out = response.getWriter();
        out.print( "<html><head /><body>" +
        "<h1>Login</h1>" +
        "<form method=\"POST\" action=\"j_security_check\">" +
        "<div><label>Username:</label><input type=\"text\" name=\""+ J_USERNAME +"\"/></div>" +
        "<div><label>Password:</label><input type=\"password\" name=\"" + J_PASSWORD + "\" /></div>" +
        "<div><input type=\"submit\" value=\"Login\" /></div>" +
        "</form></body></html>" );

    }

}
