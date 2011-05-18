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

import static de.javakaffee.web.msm.integration.TestUtils.createContext;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;
import static org.testng.Assert.assertEquals;

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;

import org.apache.catalina.Context;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.core.StandardContext;
import org.apache.tomcat.util.http.ServerCookie;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import de.javakaffee.web.msm.SessionTrackerValve.SessionBackupService;

/**
 * Test the {@link SessionTrackerValve}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public abstract class SessionTrackerValveTest {

    protected SessionBackupService _service;
    private SessionTrackerValve _sessionTrackerValve;
    private Valve _nextValve;
    private Request _request;
    private Response _response;

    @BeforeMethod
    public void setUp() throws Exception {
        _service = mock( SessionBackupService.class );
        _sessionTrackerValve = createSessionTrackerValve( createContext() );
        _nextValve = mock( Valve.class );
        _sessionTrackerValve.setNext( _nextValve );
        _request = mock( Request.class );
        _response = mock( Response.class );
    }

    @Nonnull
    protected abstract SessionTrackerValve createSessionTrackerValve( @Nonnull final Context context );

    @Nonnull
    protected abstract String getGlobalSessionCookieName( @Nonnull final Context context );

    @AfterMethod
    public void tearDown() throws Exception {
        reset( _service,
                _nextValve,
                _request,
                _response );
    }

    @Test
    public final void testSessionCookieName() throws IOException, ServletException {
        final StandardContext context = createContext();
        context.setSessionCookieName( "foo" );
        SessionTrackerValve cut = createSessionTrackerValve( context );
        assertEquals( "foo", cut.getSessionCookieName() );

        context.setSessionCookieName( null );
        cut = createSessionTrackerValve( context );
        assertEquals( getGlobalSessionCookieName( context ), cut.getSessionCookieName() );
    }

    @Test
    public final void testBackupSessionNotInvokedWhenNoSessionIdPresent() throws IOException, ServletException {
        when( _request.getRequestedSessionId() ).thenReturn( null );
        when( _response.getHeader( eq( "Set-Cookie" ) ) ).thenReturn( null );

        _sessionTrackerValve.invoke( _request, _response );

        verify( _service, never() ).backupSession( anyString(), anyBoolean(), anyString() );
    }

    public final void testGetSessionInternalNotInvokedWhenNoSessionIdPresent() throws IOException, ServletException {
        when( _request.getRequestedSessionId() ).thenReturn( null );
        when( _response.getHeader( eq( "Set-Cookie" ) ) ).thenReturn( null );
        _sessionTrackerValve.invoke( _request, _response );

        verify( _request, never() ).getSessionInternal();
    }

    @Test
    public final void testBackupSessionInvokedWhenResponseCookiePresent() throws IOException, ServletException {
        when( _request.getRequestedSessionId() ).thenReturn( null );
        final Cookie cookie = new Cookie( _sessionTrackerValve.getSessionCookieName(), "foo" );
        when( _response.getHeader( eq( "Set-Cookie" ) ) ).thenReturn( generateCookieString( cookie ) );
        when( _request.getRequestURI() ).thenReturn( "/someRequest" );
        when( _request.getMethod() ).thenReturn( "GET" );
        when( _request.getQueryString() ).thenReturn( null );
        _sessionTrackerValve.invoke( _request, _response );

        verify( _service ).backupSession( eq( "foo" ), eq( false), anyString() );

    }

    private String generateCookieString(final Cookie cookie) {
        final StringBuffer sb = new StringBuffer();
        ServerCookie.appendCookieValue
        (sb, cookie.getVersion(), cookie.getName(), cookie.getValue(),
             cookie.getPath(), cookie.getDomain(), cookie.getComment(),
             cookie.getMaxAge(), cookie.getSecure(), true );
        final String setSessionCookieHeader = sb.toString();
        return setSessionCookieHeader;
    }

    @Test
    public final void testChangeSessionIdForRelocatedSession() throws IOException, ServletException {

        final String sessionId = "bar";
        final String newSessionId = "newId";

        when( _request.getRequestedSessionId() ).thenReturn( sessionId );

        when( _service.changeSessionIdOnMemcachedFailover( eq( sessionId ) ) ).thenReturn( newSessionId );

        //

        final Cookie cookie = new Cookie( _sessionTrackerValve.getSessionCookieName(), newSessionId );
        when( _response.getHeader( eq( "Set-Cookie" ) ) ).thenReturn( generateCookieString( cookie ) );

        when( _request.getRequestURI() ).thenReturn( "/foo" );
        when( _request.getMethod() ).thenReturn( "GET" );
        when( _request.getQueryString() ).thenReturn( null );

        _sessionTrackerValve.invoke( _request, _response );

        verify( _request ).changeSessionId( eq( newSessionId ) );
        verify( _service ).backupSession( eq( newSessionId ), eq( true ), anyString() );

    }

}
