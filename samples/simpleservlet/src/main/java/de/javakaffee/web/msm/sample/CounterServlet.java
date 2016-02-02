/*
 * Copyright 2016 Martin Grotzke
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

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.logging.Logger;


/**
 * Increments an integer and saves it to the session.
 */
public class CounterServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final String COUNTER = "_counter";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {


        HttpSession session = req.getSession();
        Integer counter = (Integer) session.getAttribute(COUNTER);
        int iTmp = 0;
        if ( counter != null ) {
            iTmp = counter + 1;
        }
        String sTmp = Integer.toString(iTmp);
        resp.getOutputStream().println(sTmp);
        session.setAttribute(COUNTER, iTmp);

        resp.flushBuffer();
    }

}
