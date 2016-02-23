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

import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;
import static org.testng.Assert.assertEquals;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;

import org.apache.catalina.Context;
import org.apache.catalina.Host;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.tomcat.util.http.ServerCookie;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import de.javakaffee.web.msm.MemcachedSessionService.SessionManager;

/**
 * Test the {@link RequestTrackingHostValve}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public abstract class RequestTrackingHostValveTest {

    protected MemcachedSessionService _service;
    private RequestTrackingHostValve _sessionTrackerValve;
    private Valve _nextValve;
    private Request _request;
    private Response _response;

    @BeforeMethod
    public void setUp() throws Exception {
        _service = mock( MemcachedSessionService.class );
        _request = mock( Request.class );
        _response = mock( Response.class );

        final Context _contextContainer = mock(Context.class);
        final Host _hostContainer = mock(Host.class);
        final SessionManager _manager = mock(SessionManager.class);

        when(_service.getManager()).thenReturn(_manager);
        when(_manager.getContext()).thenReturn(_contextContainer);
        when(_contextContainer.getParent()).thenReturn(_hostContainer);
        when(_contextContainer.getPath()).thenReturn("/");

        _sessionTrackerValve = createSessionTrackerValve();
        _nextValve = mock( Valve.class );
        _sessionTrackerValve.setNext( _nextValve );
        _sessionTrackerValve.setContainer(_hostContainer);

        when(_request.getRequestURI()).thenReturn( "/someRequest");
        when(_request.getMethod()).thenReturn("GET");
        when(_request.getQueryString()).thenReturn(null);
        when(_request.getContext()).thenReturn(_contextContainer);

        when(_request.getNote(eq(RequestTrackingHostValve.REQUEST_PROCESSED))).thenReturn(Boolean.TRUE);
        when(_request.getNote(eq(RequestTrackingHostValve.SESSION_ID_CHANGED))).thenReturn(Boolean.FALSE);
    }

    @Nonnull
    protected RequestTrackingHostValve createSessionTrackerValve() {
        return new RequestTrackingHostValve(".*\\.(png|gif|jpg|css|js|ico)$", "somesessionid", _service, Statistics.create(),
                new AtomicBoolean( true ), new CurrentRequest()) {
            @Override
            protected String[] getSetCookieHeaders(final Response response) {
                return RequestTrackingHostValveTest.this.getSetCookieHeaders(response);
            }
        };
    }

    protected abstract String[] getSetCookieHeaders(final Response response);

    @AfterMethod
    public void tearDown() throws Exception {
        reset( _service,
                _nextValve,
                _request,
                _response );
    }

    @Test
    public final void testGetSessionCookieName() throws IOException, ServletException {
        final RequestTrackingHostValve cut = new RequestTrackingHostValve(null, "foo", _service, Statistics.create(),
                new AtomicBoolean( true ), new CurrentRequest()) {
            @Override
            protected String[] getSetCookieHeaders(final Response response) {
                final Collection<String> result = response.getHeaders("Set-Cookie");
                return result.toArray(new String[result.size()]);
            }
        };
        assertEquals(cut.getSessionCookieName(), "foo");
    }

    @Test
    public final void testProcessRequestNotePresent() throws IOException, ServletException {
        _sessionTrackerValve.invoke( _request, _response );

        verify( _service, never() ).backupSession( anyString(), anyBoolean(), anyString() );
        verify(_request).setNote(eq(RequestTrackingHostValve.REQUEST_PROCESS), eq(Boolean.TRUE));
    }

    @Test
    public final void testBackupSessionNotInvokedWhenNoSessionIdPresent() throws IOException, ServletException {
        when( _request.getRequestedSessionId() ).thenReturn( null );
        when( _response.getHeader( eq( "Set-Cookie" ) ) ).thenReturn( null );

        _sessionTrackerValve.invoke( _request, _response );

        verify( _service, never() ).backupSession( anyString(), anyBoolean(), anyString() );
    }

    @Test
    public final void testBackupSessionInvokedWhenResponseCookiePresent() throws IOException, ServletException {
        when( _request.getRequestedSessionId() ).thenReturn( null );
        final Cookie cookie = new Cookie( _sessionTrackerValve.getSessionCookieName(), "foo" );
        setupGetResponseSetCookieHeadersExpectations(_response, new String[]{generateCookieString( cookie )});
        _sessionTrackerValve.invoke( _request, _response );

        verify( _service ).backupSession( eq( "foo" ), eq( false), anyString() );

    }

    @Test
    public final void testChangeSessionIdForRelocatedSession() throws IOException, ServletException {

        final String sessionId = "bar";
        final String newSessionId = "newId";

        when(_request.getNote(eq(RequestTrackingHostValve.SESSION_ID_CHANGED))).thenReturn(Boolean.TRUE);
        when( _request.getRequestedSessionId() ).thenReturn( sessionId );

        final Cookie cookie = new Cookie( _sessionTrackerValve.getSessionCookieName(), newSessionId );
        setupGetResponseSetCookieHeadersExpectations(_response, new String[]{generateCookieString( cookie )});

        _sessionTrackerValve.invoke( _request, _response );

        verify( _service ).backupSession( eq( newSessionId ), eq( true ), anyString() );

    }

    @Test
    public final void testRequestFinishedShouldBeInvokedForIgnoredResources() throws IOException, ServletException {
        when( _request.getRequestedSessionId() ).thenReturn( "foo" );
        when(_request.getRequestURI()).thenReturn("/pixel.gif");

        _sessionTrackerValve.invoke( _request, _response );

        verify( _service ).requestFinished( eq( "foo" ), anyString() );
    }

    protected abstract void setupGetResponseSetCookieHeadersExpectations(Response response, String[] result);

    @Nonnull
    protected String generateCookieString(final Cookie cookie) {
        final StringBuffer sb = new StringBuffer();
        ServerCookie.appendCookieValue
                (sb, cookie.getVersion(), cookie.getName(), cookie.getValue(),
                        cookie.getPath(), cookie.getDomain(), cookie.getComment(),
                        cookie.getMaxAge(), cookie.getSecure(), true);
        final String setSessionCookieHeader = sb.toString();
        return setSessionCookieHeader;
    }

}
