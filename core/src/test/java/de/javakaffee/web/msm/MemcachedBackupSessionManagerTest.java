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
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import net.spy.memcached.MemcachedClient;

import org.apache.catalina.loader.WebappLoader;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;


/**
 * Test the {@link MemcachedBackupSessionManager}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class MemcachedBackupSessionManagerTest {

    private MemcachedBackupSessionManager _manager;
    private MemcachedClient _memcachedMock;

    @BeforeMethod( alwaysRun = true )
    public void setup() throws Exception {

        _manager = new MemcachedBackupSessionManager();
        _manager.setMemcachedNodes( "n1:127.0.0.1:11211" );

        _manager.setContainer( createContext() );

        final WebappLoader webappLoader = mock( WebappLoader.class );
        // webappLoaderControl.expects( once() ).method( "setContainer" ).withAnyArguments();
        when( webappLoader.getClassLoader() ).thenReturn( Thread.currentThread().getContextClassLoader() );
        Assert.assertNotNull( webappLoader.getClassLoader(), "Webapp Classloader is null." );

        _manager.getContainer().setLoader( webappLoader );

        _memcachedMock = mock( MemcachedClient.class );

        @SuppressWarnings( "unchecked" )
        final Future<Boolean> futureMock = mock( Future.class );
        when( futureMock.get( anyInt(), any( TimeUnit.class ) ) ).thenReturn( Boolean.TRUE );
        when( _memcachedMock.set(  any( String.class ), anyInt(), any() ) ).thenReturn( futureMock );

        _manager.init( _memcachedMock );

    }

    @Test
    public void testConfigurationFormatMemcachedNodesFeature44() {
        _manager.setMemcachedNodes( "n1:127.0.0.1:11211" );
        _manager.initInternal();
        Assert.assertEquals( _manager.getNodeIds(), Arrays.asList( "n1" ) );

        _manager.setMemcachedNodes( "n1:127.0.0.1:11211 n2:127.0.0.1:11212" );
        _manager.initInternal();
        Assert.assertEquals( _manager.getNodeIds(), Arrays.asList( "n1", "n2" ) );

        _manager.setMemcachedNodes( "n1:127.0.0.1:11211,n2:127.0.0.1:11212" );
        _manager.initInternal();
        Assert.assertEquals( _manager.getNodeIds(), Arrays.asList( "n1", "n2" ) );
    }

    @Test
    public void testConfigurationFormatFailoverNodesFeature44() {
        _manager.setMemcachedNodes( "n1:127.0.0.1:11211 n2:127.0.0.1:11212" );
        _manager.setFailoverNodes( "n1" );
        _manager.initInternal();
        Assert.assertEquals( _manager.getFailoverNodeIds(), Arrays.asList( "n1" ) );

        _manager.setMemcachedNodes( "n1:127.0.0.1:11211 n2:127.0.0.1:11212 n3:127.0.0.1:11213" );
        _manager.setFailoverNodes( "n1 n2" );
        _manager.initInternal();
        Assert.assertEquals( _manager.getFailoverNodeIds(), Arrays.asList( "n1", "n2" ) );

        _manager.setMemcachedNodes( "n1:127.0.0.1:11211 n2:127.0.0.1:11212 n3:127.0.0.1:11213" );
        _manager.setFailoverNodes( "n1,n2" );
        _manager.initInternal();
        Assert.assertEquals( _manager.getFailoverNodeIds(), Arrays.asList( "n1", "n2" ) );
    }

    /**
     * Test that sessions are only backuped if they are modified.
     * @throws ExecutionException
     * @throws InterruptedException
     */
    @Test
    public void testOnlySendModifiedSessions() throws InterruptedException, ExecutionException {
        final MemcachedBackupSession session = (MemcachedBackupSession) _manager.createSession( null );

        /* simulate the first request, with session access
         */
        session.access();
        session.endAccess();
        session.setAttribute( "foo", "bar" );
        _manager.backupSession( session, false ).get();
        verify( _memcachedMock, times( 1 ) ).set( eq( session.getId() ), anyInt(), any() );

        /* simulate the second request, with session access
         */
        session.access();
        session.endAccess();
        session.setAttribute( "foo", "bar" );
        session.setAttribute( "bar", "baz" );
        _manager.backupSession( session, false ).get();
        verify( _memcachedMock, times( 2 ) ).set( eq( session.getId() ), anyInt(), any() );

        /* simulate the third request, without session access
         */
        _manager.backupSession( session, false ).get();
        verify( _memcachedMock, times( 2 ) ).set( eq( session.getId() ), anyInt(), any() );

    }

    /**
     * Test that session attribute serialization and hash calculation is only
     * performed if session attributes were accessed since the last backup.
     * Otherwise this computing time shall be saved for a better world :-)
     * @throws ExecutionException
     * @throws InterruptedException
     */
    @Test
    public void testOnlyHashAttributesOfAccessedAttributes() throws InterruptedException, ExecutionException {

        final TranscoderService transcoderServiceMock = mock( TranscoderService.class );
        @SuppressWarnings( "unchecked" )
        final Map<String, Object> anyMap = any( Map.class );
        when( transcoderServiceMock.serializeAttributes( any( MemcachedBackupSession.class ), anyMap ) ).thenReturn( new byte[0] );
        _manager.setTranscoderService( transcoderServiceMock );

        final MemcachedBackupSession session = (MemcachedBackupSession) _manager.createSession( null );

        session.access();
        session.endAccess();
        session.setAttribute( "foo", "bar" );
        _manager.backupSession( session, false ).get();
        verify( transcoderServiceMock, times( 1 ) ).serializeAttributes( eq( session ), eq( session.getAttributesInternal() ) );

        session.access();
        session.endAccess();
        _manager.backupSession( session, false ).get();
        verify( transcoderServiceMock, times( 1 ) ).serializeAttributes( eq( session ), eq( session.getAttributesInternal() ) );

    }

    /**
     * Test that session attribute serialization and hash calculation is only
     * performed if the session and its attributes were accessed since the last backup/backup check.
     * Otherwise this computing time shall be saved for a better world :-)
     * @throws ExecutionException
     * @throws InterruptedException
     */
    @Test
    public void testOnlyHashAttributesOfAccessedSessionsAndAttributes() throws InterruptedException, ExecutionException {

        final TranscoderService transcoderServiceMock = mock( TranscoderService.class );
        @SuppressWarnings( "unchecked" )
        final Map<String, Object> anyMap = any( Map.class );
        when( transcoderServiceMock.serializeAttributes( any( MemcachedBackupSession.class ), anyMap ) ).thenReturn( new byte[0] );
        _manager.setTranscoderService( transcoderServiceMock );

        final MemcachedBackupSession session = (MemcachedBackupSession) _manager.createSession( null );

        session.setAttribute( "foo", "bar" );
        _manager.backupSession( session, false ).get();
        verify( transcoderServiceMock, times( 1 ) ).serializeAttributes( eq( session ), eq( session.getAttributesInternal() ) );

        session.access();
        session.getAttribute( "foo" );
        _manager.backupSession( session, false ).get();
        verify( transcoderServiceMock, times( 2 ) ).serializeAttributes( eq( session ), eq( session.getAttributesInternal() ) );

        _manager.backupSession( session, false ).get();
        verify( transcoderServiceMock, times( 2 ) ).serializeAttributes( eq( session ), eq( session.getAttributesInternal() ) );

    }

}
