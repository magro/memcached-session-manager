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

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;

import org.apache.catalina.Session;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.core.StandardContext;
import org.jmock.Mock;
import org.jmock.cglib.MockObjectTestCase;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import de.javakaffee.web.msm.BackupSessionService.SimpleFuture;
import de.javakaffee.web.msm.SessionTrackerValve.SessionBackupService;
import de.javakaffee.web.msm.SessionTrackerValve.SessionBackupService.BackupResultStatus;

/**
 * Test the {@link SessionTrackerValve}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class SessionTrackerValveTest extends MockObjectTestCase {

    private Mock _sessionBackupServiceControl;
    private SessionBackupService _service;
    private SessionTrackerValve _sessionTrackerValve;
    private Mock _nextValve;
    private Mock _requestControl;
    private Request _request;
    private Response _response;
    private Mock _responseControl;

    @BeforeMethod
    public void setUp() throws Exception {
        _sessionBackupServiceControl = mock( SessionBackupService.class );
        _service = (SessionBackupService) _sessionBackupServiceControl.proxy();
        _sessionTrackerValve = new SessionTrackerValve( null, new StandardContext(), _service, Statistics.create() );
        _nextValve = mock( Valve.class );
        _sessionTrackerValve.setNext( (Valve) _nextValve.proxy() );

        _requestControl = mock( Request.class );
        _request = (Request) _requestControl.proxy();
        _responseControl = mock( Response.class );
        _response = (Response) _responseControl.proxy();
    }

    @AfterMethod
    public void tearDown() throws Exception {
        _sessionBackupServiceControl.reset();
        _nextValve.reset();
        _requestControl.reset();
        _responseControl.reset();
    }

    @Test
    public final void testGetSessionInternalNotInvokedWhenNoSessionIdPresent() throws IOException, ServletException {
        _requestControl.expects( once() ).method( "getRequestedSessionId" ).will( returnValue( null ) );
        _nextValve.expects( once() ).method( "invoke" );
        _requestControl.expects( once() ).method( "getRequestedSessionId" ).will( returnValue( null ) );
        _responseControl.expects( once() ).method( "getCookies" ).will( returnValue( null ) );
        _sessionTrackerValve.invoke( _request, _response );

    }

    @Test
    public final void testGetSessionInternalInvokedWhenResponseCookiePresent() throws IOException, ServletException {

        _requestControl.expects( once() ).method( "getRequestedSessionId" ).will( returnValue( null ) );
        _nextValve.expects( once() ).method( "invoke" );
        _requestControl.expects( once() ).method( "getRequestedSessionId" ).will( returnValue( null ) );
        _responseControl.expects( once() ).method( "getCookies" )
            .will( returnValue( new Cookie[] { new Cookie( SessionTrackerValve.JSESSIONID, "foo" ) } ) );
        _requestControl.expects( once() ).method( "getSessionInternal" ).with( eq( false ) )
            .will( returnValue( null ) );
        _sessionTrackerValve.invoke( _request, _response );

    }

    @Test
    public final void testBackupSessionInvokedWhenSessionExisting() throws IOException, ServletException {

        final Session session = (Session) mock( Session.class ).proxy();

        final String sessionId = "foo";
        _sessionBackupServiceControl.expects( once() ).method( "changeSessionIdIfRelocationRequired" ).with( eq( sessionId)  );
        _requestControl.expects( atLeastOnce() ).method( "getRequestedSessionId" ).will( returnValue( sessionId ) );
        _nextValve.expects( once() ).method( "invoke" );
        _requestControl.expects( once() ).method( "getSessionInternal" ).with( eq( false ) )
            .will( returnValue( session ) );
        _sessionBackupServiceControl.expects( once() ).method( "backupSession" ).with( eq( session ), eq( false ) )
            .will( returnValue( new SimpleFuture<BackupResultStatus>( BackupResultStatus.SUCCESS ) ) );

        _sessionTrackerValve.invoke( _request, _response );

    }

    @Test
    public final void testChangeSessionIdForRelocatedSession() throws IOException, ServletException {

        final Mock sessionControl = mock( Session.class );
        final Session session = (Session) sessionControl.proxy();
        final String sessionId = "bar";
        final String newSessionId = "newId";

        _requestControl.expects( atLeastOnce() ).method( "getRequestedSessionId" ).will( returnValue( sessionId ) );
        _sessionBackupServiceControl.expects( once() ).method( "changeSessionIdIfRelocationRequired" ).with( eq( sessionId)  ).will( returnValue( newSessionId ) );

        _requestControl.expects( once() ).method( "setRequestedSessionId" ).with( eq( newSessionId ) );

        _requestControl.expects( atLeastOnce() ).method( "isRequestedSessionIdFromCookie" ).will( returnValue( true ) );
        _requestControl.expects( atLeastOnce() ).method( "getContext" ).will( returnValue( new StandardContext() ) );
        _requestControl.expects( once() ).method( "isSecure" ).will( returnValue( false ) );
        _responseControl.expects( once() ).method( "addCookieInternal" ).with(
                and( hasProperty( "name", eq( SessionTrackerValve.JSESSIONID ) ),
                     hasProperty( "value", eq( newSessionId ) ) ),
                     eq( false ) ); // default value in StandardContext.useHttpOnly

        _nextValve.expects( once() ).method( "invoke" );
        _requestControl.expects( once() ).method( "getSessionInternal" ).with( eq( false ) )
            .will( returnValue( session ) );
        _sessionBackupServiceControl.expects( once() ).method( "backupSession" ).with( eq( session ), eq( true ) )
            .will( returnValue( new SimpleFuture<BackupResultStatus>( BackupResultStatus.SUCCESS ) ) );

        _sessionTrackerValve.invoke( _request, _response );

    }

}
