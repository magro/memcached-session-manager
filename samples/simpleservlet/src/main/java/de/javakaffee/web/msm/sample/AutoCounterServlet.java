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


/**
 * Increments an integer and saves it to the session.
 */
public class AutoCounterServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final String COUNTER = "_autocounter";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        String to = req.getParameter("to");
        if(to == null) {
            resp.setStatus(400);
            resp.getOutputStream().println("The parameter 'to' is missing, please specify a number > 0.");
        } else {
            int t = Integer.parseInt(to);
            String maybeLast = req.getParameter("last");
            int last = maybeLast != null ? Integer.parseInt(maybeLast) : -1;

            HttpSession session = req.getSession();
            Integer counter = (Integer) session.getAttribute(COUNTER);
            int iTmp = 0;
            // if "last" was not submitted, the user is starting a new run, so let's ignore session data
            if ( maybeLast != null && counter != null ) {
                iTmp = counter + 1;
            }

            if(iTmp != (last + 1)) {
                resp.setStatus(500);
                resp.getOutputStream().println("KO: The last saved value was "+ last +" but "+ counter +" was retrieved from the session - the last write seems to be lost.");
            } else if(iTmp == t) {
                resp.setStatus(500);
                resp.getOutputStream().println("OK: All counts from 0 to " + counter + " were saved and retrieved as expected - no data was lost.");
            } else {
                session.setAttribute(COUNTER, iTmp);
                String location = req.getRequestURI() + "?last=" + iTmp + "&to=" + to;
                String content = "<html><head>" +
                        "<meta http-equiv=\"refresh\" content=\"0;URL='"+ location +"'\" />" +
                        // try to prevent favicon requests that might cause the round robin lb and route "real" requests mainly to a single node
                        "<link rel=\"icon\" type=\"image/png\" href=\"data:image/png;base64,iVBORw0KGgo=\">" +
                        "</head>" +
                        "<body>"+ iTmp +"</body></html>";
                resp.getOutputStream().println(content);
            }

        }

        resp.flushBuffer();

    }

}
