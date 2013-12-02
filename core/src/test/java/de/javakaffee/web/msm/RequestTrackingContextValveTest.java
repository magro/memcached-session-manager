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

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;

import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Test the {@link RequestTrackingContextValve}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class RequestTrackingContextValveTest {

    protected MemcachedSessionService _service;
    private RequestTrackingContextValve _sessionTrackerValve;
    private Valve _nextValve;
    private Request _request;
    private Response _response;

    @BeforeMethod
    public void setUp() throws Exception {
        _service = mock( MemcachedSessionService.class );
        _sessionTrackerValve = createSessionTrackerValve();
        _nextValve = mock( Valve.class );
        _sessionTrackerValve.setNext( _nextValve );
        _request = mock( Request.class );
        _response = mock( Response.class );

        when(_request.getNote(eq(AbstractRequestTrackingHostValve.REQUEST_PROCESS))).thenReturn(Boolean.TRUE);
    }

    @Nonnull
    protected RequestTrackingContextValve createSessionTrackerValve() {
        return new RequestTrackingContextValve("foo", _service);
    }

    @AfterMethod
    public void tearDown() throws Exception {
        reset( _service,
                _nextValve,
                _request,
                _response );
    }

    @Test
    public final void testGetSessionCookieName() throws IOException, ServletException {
        final RequestTrackingContextValve cut = new RequestTrackingContextValve("foo", _service);
        assertEquals(cut.getSessionCookieName(), "foo");
    }

    @Test
    public final void testRequestIsMarkedAsProcessed() throws IOException, ServletException {
        _sessionTrackerValve.invoke( _request, _response );
        verify(_request).setNote(eq(AbstractRequestTrackingHostValve.REQUEST_PROCESSED), eq(Boolean.TRUE));
    }

    @Test
    public final void testChangeSessionIdForRelocatedSession() throws IOException, ServletException {

        final String sessionId = "bar";
        final String newSessionId = "newId";

        when( _request.getRequestedSessionId() ).thenReturn( sessionId );
        when( _service.changeSessionIdOnMemcachedFailover( eq( sessionId ) ) ).thenReturn( newSessionId );

        _sessionTrackerValve.invoke( _request, _response );

        verify( _request ).changeSessionId( eq( newSessionId ) );
        verify(_request).setNote(eq(AbstractRequestTrackingHostValve.SESSION_ID_CHANGED), eq(Boolean.TRUE));

    }

}
