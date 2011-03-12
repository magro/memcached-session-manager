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

import static de.javakaffee.web.msm.SessionValidityInfo.createValidityInfoKeyName;
import static de.javakaffee.web.msm.SessionValidityInfo.encode;
import static de.javakaffee.web.msm.integration.TestUtils.STICKYNESS_PROVIDER;
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
import java.util.concurrent.TimeoutException;

import net.spy.memcached.MemcachedClient;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.loader.WebappLoader;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import de.javakaffee.web.msm.LockingStrategy.LockingMode;
import de.javakaffee.web.msm.integration.TestUtils;
import de.javakaffee.web.msm.integration.TestUtils.SessionAffinityMode;


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
        _manager.setSessionBackupAsync( false );
        _manager.setSticky( true );

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

        _manager.startInternal( _memcachedMock );

    }

    @Test
    public void testConfigurationFormatMemcachedNodesFeature44() throws LifecycleException {
        _manager.setMemcachedNodes( "n1:127.0.0.1:11211" );
        _manager.startInternal(_memcachedMock);
        Assert.assertEquals( _manager.getNodeIds(), Arrays.asList( "n1" ) );

        _manager.setMemcachedNodes( "n1:127.0.0.1:11211 n2:127.0.0.1:11212" );
        _manager.startInternal(_memcachedMock);
        Assert.assertEquals( _manager.getNodeIds(), Arrays.asList( "n1", "n2" ) );

        _manager.setMemcachedNodes( "n1:127.0.0.1:11211,n2:127.0.0.1:11212" );
        _manager.startInternal(_memcachedMock);
        Assert.assertEquals( _manager.getNodeIds(), Arrays.asList( "n1", "n2" ) );
    }

    @Test
    public void testConfigurationFormatFailoverNodesFeature44() throws LifecycleException {
        _manager.setMemcachedNodes( "n1:127.0.0.1:11211 n2:127.0.0.1:11212" );
        _manager.setFailoverNodes( "n1" );
        _manager.startInternal(_memcachedMock);
        Assert.assertEquals( _manager.getFailoverNodeIds(), Arrays.asList( "n1" ) );

        _manager.setMemcachedNodes( "n1:127.0.0.1:11211 n2:127.0.0.1:11212 n3:127.0.0.1:11213" );
        _manager.setFailoverNodes( "n1 n2" );
        _manager.startInternal(_memcachedMock);
        Assert.assertEquals( _manager.getFailoverNodeIds(), Arrays.asList( "n1", "n2" ) );

        _manager.setMemcachedNodes( "n1:127.0.0.1:11211 n2:127.0.0.1:11212 n3:127.0.0.1:11213" );
        _manager.setFailoverNodes( "n1,n2" );
        _manager.startInternal(_memcachedMock);
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
        _manager.backupSession( session.getIdInternal(), false, null ).get();
        verify( _memcachedMock, times( 1 ) ).set( eq( session.getId() ), anyInt(), any() );

        // we need at least 1 milli between last backup and next access (due to check in BackupSessionService)
        Thread.sleep(1L);

        /* simulate the second request, with session access
         */
        session.access();
        session.endAccess();
        session.setAttribute( "foo", "bar" );
        session.setAttribute( "bar", "baz" );
        _manager.backupSession( session.getIdInternal(), false, null ).get();
        verify( _memcachedMock, times( 2 ) ).set( eq( session.getId() ), anyInt(), any() );

        // we need at least 1 milli between last backup and next access (due to check in BackupSessionService)
        Thread.sleep(1L);

        /* simulate the third request, without session access
         */
        _manager.backupSession( session.getIdInternal(), false, null ).get();
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
        _manager.backupSession( session.getIdInternal(), false, null ).get();
        verify( transcoderServiceMock, times( 1 ) ).serializeAttributes( eq( session ), eq( session.getAttributesInternal() ) );

        session.access();
        session.endAccess();
        _manager.backupSession( session.getIdInternal(), false, null ).get();
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
        _manager.backupSession( session.getIdInternal(), false, null ).get();
        verify( transcoderServiceMock, times( 1 ) ).serializeAttributes( eq( session ), eq( session.getAttributesInternal() ) );

        // we need at least 1 milli between last backup and next access (due to check in BackupSessionService)
        Thread.sleep(1L);

        session.access();
        session.getAttribute( "foo" );
        _manager.backupSession( session.getIdInternal(), false, null ).get();
        verify( transcoderServiceMock, times( 2 ) ).serializeAttributes( eq( session ), eq( session.getAttributesInternal() ) );

        // we need at least 1 milli between last backup and next access (due to check in BackupSessionService)
        Thread.sleep(1L);

        _manager.backupSession( session.getIdInternal(), false, null ).get();
        verify( transcoderServiceMock, times( 2 ) ).serializeAttributes( eq( session ), eq( session.getAttributesInternal() ) );

    }

    /**
     * Test for issue #68: External change of sessionId must be handled correctly.
     *
     * When the webapp is configured with BASIC auth the sessionId is changed on login since 6.0.21
     * (AuthenticatorBase.register invokes manager.changeSessionId(session)).
     * This change of the sessionId was not recognized by msm so that it might have happened that the
     * session is removed from memcached under the old id but not sent to memcached (if the case the session
     * was not accessed during this request at all, which is very unprobable but who knows).
     */
    @Test( dataProviderClass = TestUtils.class, dataProvider = STICKYNESS_PROVIDER )
    public void testChangeSessionId( final SessionAffinityMode stickyness ) throws InterruptedException, ExecutionException, TimeoutException {

        _manager.setStickyInternal( stickyness.isSticky() );
        if ( !stickyness.isSticky() ) {
            _manager.setLockingMode( LockingMode.NONE, null, false );
        }

        final MemcachedBackupSession session = (MemcachedBackupSession) _manager.createSession( null );

        session.setAttribute( "foo", "bar" );
        _manager.backupSession( session.getIdInternal(), false, "foo" ).get();

        final String oldSessionId = session.getId();
        _manager.changeSessionId( session );

        // on session backup we specify sessionIdChanged as false as we're not aware of this fact
        _manager.backupSession( session.getIdInternal(), false, "foo" );

        // remove session with old id and add it with the new id
        verify( _memcachedMock, times( 1 ) ).delete( eq( oldSessionId ) );
        verify( _memcachedMock, times( 1 ) ).set( eq( session.getId() ), anyInt(), any() );

        if ( !stickyness.isSticky() ) {
            // check validity info
            verify( _memcachedMock, times( 1 ) ).delete( eq( createValidityInfoKeyName( oldSessionId ) ) );
            verify( _memcachedMock, times( 1 ) ).set( eq( createValidityInfoKeyName( session.getId() ) ), anyInt(), any() );
        }

    }

    /**
     * Test that sessions with a timeout of 0 or less are stored in memcached with unlimited
     * expiration time (0) also (see http://code.sixapart.com/svn/memcached/trunk/server/doc/protocol.txt).
     * For non-sticky sessions that must hold true for all related items stored in memcached (validation,
     * backup etc.)
     *
     * This is the test for issue #88 "Support session-timeout of 0 or less (no session expiration)"
     * http://code.google.com/p/memcached-session-manager/issues/detail?id=88
     */
    @Test( dataProviderClass = TestUtils.class, dataProvider = STICKYNESS_PROVIDER )
    public void testSessionTimeoutUnlimitedWithSessionLoaded( final SessionAffinityMode stickyness ) throws InterruptedException, ExecutionException, LifecycleException {

        _manager.setStickyInternal( stickyness.isSticky() );
        if ( !stickyness.isSticky() ) {
            _manager.setLockingMode( LockingMode.NONE, null, false );
            _manager.setMemcachedNodes( "n1:127.0.0.1:11211 n2:127.0.0.1:11212" ); // for backup support
            _manager.startInternal(_memcachedMock); // we must put in our mock again
        }

        final MemcachedBackupSession session = (MemcachedBackupSession) _manager.createSession( null );
        session.setMaxInactiveInterval( -1 );

        session.access();
        session.endAccess();
        session.setAttribute( "foo", "bar" );
        final String sessionId = session.getId();

        _manager.backupSession( sessionId, false, null ).get();

        verify( _memcachedMock, times( 1 ) ).set( eq( sessionId ), eq( 0 ), any() );

        if ( !stickyness.isSticky() ) {
            // check validity info
            final String validityKey = createValidityInfoKeyName( sessionId );
            verify( _memcachedMock, times( 1 ) ).set( eq( validityKey ), eq( 0 ), any() );

            // As the backup is done asynchronously, we shutdown the executor so that we know the backup
            // task is executed/finished.
            _manager.getLockingStrategy().getExecutorService().shutdown();

            final String backupSessionKey = new SessionIdFormat().createBackupKey( sessionId );
            verify( _memcachedMock, times( 1 ) ).set( eq( backupSessionKey ), eq( 0 ), any() );
            final String backupValidityKey = new SessionIdFormat().createBackupKey( validityKey );
            verify( _memcachedMock, times( 1 ) ).set( eq( backupValidityKey ), eq( 0 ), any() );
        }
    }

    /**
     * Test that non-sticky sessions with a timeout of 0 or less that have not been loaded by a request
     * the validity info is stored in memcached with unlimited
     * expiration time (0) also (see http://code.sixapart.com/svn/memcached/trunk/server/doc/protocol.txt).
     * For non-sticky sessions that must hold true for all related items stored in memcached (validation,
     * backup etc.)
     *
     * This is the test for issue #88 "Support session-timeout of 0 or less (no session expiration)"
     * http://code.google.com/p/memcached-session-manager/issues/detail?id=88
     */
    @Test
    public void testSessionTimeoutUnlimitedWithNonStickySessionNotLoaded() throws InterruptedException, ExecutionException, LifecycleException, TimeoutException {

        _manager.setStickyInternal( false );
        _manager.setLockingMode( LockingMode.NONE, null, false );
        _manager.setMemcachedNodes( "n1:127.0.0.1:11211 n2:127.0.0.1:11212" ); // for backup support
        _manager.startInternal(_memcachedMock); // we must put in our mock again

        final String sessionId = "someSessionNotLoaded-n1";

        // stub loading of validity info
        final String validityKey = createValidityInfoKeyName( sessionId );
        final byte[] validityData = encode( -1, System.currentTimeMillis(), System.currentTimeMillis() );
        when( _memcachedMock.get( eq( validityKey ) ) ).thenReturn( validityData );

        // stub session (backup) ping
        @SuppressWarnings( "unchecked" )
        final Future<Boolean> futureMock = mock( Future.class );
        when( futureMock.get() ).thenReturn( Boolean.FALSE );
        when( futureMock.get( anyInt(), any( TimeUnit.class ) ) ).thenReturn( Boolean.FALSE );
        when( _memcachedMock.add(  any( String.class ), anyInt(), any() ) ).thenReturn( futureMock );

        _manager.backupSession( sessionId, false, null ).get();

        // update validity info
        verify( _memcachedMock, times( 1 ) ).set( eq( validityKey ), eq( 0 ), any() );

        // As the backup is done asynchronously, we shutdown the executor so that we know the backup
        // task is executed/finished.
        _manager.getLockingStrategy().getExecutorService().shutdown();

        // ping session
        verify( _memcachedMock, times( 1 ) ).add( eq( sessionId ), anyInt(), any() );

        // ping session backup
        final String backupSessionKey = new SessionIdFormat().createBackupKey( sessionId );
        verify( _memcachedMock, times( 1 ) ).add( eq( backupSessionKey ), anyInt(), any() );

        // update validity backup
        final String backupValidityKey = new SessionIdFormat().createBackupKey( validityKey );
        verify( _memcachedMock, times( 1 ) ).set( eq( backupValidityKey ), eq( 0 ), any() );
    }

}
