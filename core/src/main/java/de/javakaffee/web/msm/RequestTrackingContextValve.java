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

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * This valve is used for tracking that a request was actually processed
 * (after e.g. authentication was passed) and detects if sessionId must
 * be changed (due to tomcat/memcached failover).
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class RequestTrackingContextValve extends ValveBase {

    static final String INVOKED = "de.javakaffee.msm.contextValve.invoked";
    static final String RELOCATE = "session.relocate";

    protected static final Log _log = LogFactory.getLog( RequestTrackingHostValve.class );

    private final MemcachedSessionService _sessionBackupService;
    protected final String _sessionCookieName;

    /**
     * Creates a new instance with the given ignore pattern and
     * {@link MemcachedSessionService}.
     * @param sessionBackupService
     *            the service that actually backups sessions
     * @param ignorePattern
     *            the regular expression for request uris to ignore
     * @param context
     *            the catalina context of this valve
     * @param statistics
     *            used to store statistics
     */
    public RequestTrackingContextValve( @Nonnull final String sessionCookieName,
            @Nonnull final MemcachedSessionService sessionBackupService ) {
        _sessionBackupService = sessionBackupService;
        _sessionCookieName = sessionCookieName;
    }

    /**
     * Returns the actually used name for the session cookie.
     * @return the cookie name, never null.
     */
    protected String getSessionCookieName() {
        return _sessionCookieName;
    }

    public boolean wasInvokedWith(final Request currentRequest) {
        return currentRequest != null && currentRequest.getNote(INVOKED) == Boolean.TRUE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void invoke( final Request request, final Response response ) throws IOException, ServletException {

        final Object processRequest = request.getNote(RequestTrackingHostValve.REQUEST_PROCESS);
        if(processRequest != Boolean.TRUE) {
            request.setNote(INVOKED, Boolean.TRUE);
            try {
                getNext().invoke( request, response );
            } finally {
                request.setNote(RequestTrackingHostValve.REQUEST_PROCESSED, Boolean.TRUE);
            }
        }
        else {

            boolean sessionIdChanged = false;
            try {
                request.setNote(INVOKED, Boolean.TRUE);
                sessionIdChanged = changeRequestedSessionId( request, response );
                getNext().invoke( request, response );
            } finally {
                request.setNote(RequestTrackingHostValve.REQUEST_PROCESSED, Boolean.TRUE);
                request.setNote(RequestTrackingHostValve.SESSION_ID_CHANGED, Boolean.valueOf(sessionIdChanged));
            }

        }

    }

    /**
     * If there's a session for a requested session id that is taken over (tomcat failover) or
     * that will be relocated (memcached failover), the new session id will be set (via {@link Request#changeSessionId(String)}).
     *
     * @param request the request
     * @param response the response
     *
     * @return <code>true</code> if the id of a valid session was changed.
     *
     * @see Request#changeSessionId(String)
     */
    private boolean changeRequestedSessionId( final Request request, final Response response ) {
        /*
         * Check for session relocation only if a session id was requested
         */
        if ( request.getRequestedSessionId() != null ) {

        	String newSessionId = _sessionBackupService.changeSessionIdOnTomcatFailover( request.getRequestedSessionId() );
        	if ( newSessionId == null ) {
                newSessionId = _sessionBackupService.changeSessionIdOnMemcachedFailover( request.getRequestedSessionId() );
            }

            if ( newSessionId != null ) {
                request.changeSessionId( newSessionId );
                return true;
            }

        }
        return false;
    }

}
