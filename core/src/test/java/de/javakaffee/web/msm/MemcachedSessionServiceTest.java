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

import static de.javakaffee.web.msm.SessionValidityInfo.encode;
import static de.javakaffee.web.msm.integration.TestUtils.STICKYNESS_PROVIDER;
import static de.javakaffee.web.msm.integration.TestUtils.createContext;
import static de.javakaffee.web.msm.integration.TestUtils.createSession;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.Nonnull;

import de.javakaffee.web.msm.storage.MemcachedStorageClient;
import net.spy.memcached.MemcachedClient;
import net.spy.memcached.internal.OperationFuture;
import net.spy.memcached.transcoders.Transcoder;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.core.StandardContext;
import org.mockito.ArgumentCaptor;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import de.javakaffee.web.msm.BackupSessionTask.BackupResult;
import de.javakaffee.web.msm.LockingStrategy.LockingMode;
import de.javakaffee.web.msm.MemcachedSessionService.SessionManager;
import de.javakaffee.web.msm.integration.TestUtils;
import de.javakaffee.web.msm.integration.TestUtils.SessionAffinityMode;


/**
 * Test the {@link MemcachedSessionService}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public abstract class MemcachedSessionServiceTest {

    private MemcachedSessionService _service;
    private MemcachedClient _memcachedMock;
    private ExecutorService _executor;

    @SuppressWarnings("unchecked")
    @BeforeMethod
    public void setup() throws Exception {

        final StandardContext context = createContext();
        context.setBackgroundProcessorDelay( 1 ); // needed for test of updateExpiration

        final SessionManager manager = createSessionManager(context);

        _service = manager.getMemcachedSessionService();
        _service.setMemcachedNodes( "n1:127.0.0.1:11211" );
        _service.setSessionBackupAsync( false );
        _service.setSticky( true );

        _memcachedMock = mock( MemcachedClient.class );

        final OperationFuture<Boolean> setResultMock = mock( OperationFuture.class );
        when( setResultMock.get( ) ).thenReturn( Boolean.TRUE );
        when( setResultMock.get( anyInt(), any( TimeUnit.class ) ) ).thenReturn( Boolean.TRUE );
        when( _memcachedMock.set( any( String.class ), anyInt(), any(), any( Transcoder.class ) ) ).thenReturn( setResultMock );

        final OperationFuture<Boolean> deleteResultMock = mock( OperationFuture.class );
        when( deleteResultMock.get() ).thenReturn( Boolean.TRUE );
        when( _memcachedMock.delete( anyString() ) ).thenReturn( deleteResultMock );


        startInternal( manager, _memcachedMock );

        _executor = Executors.newCachedThreadPool();

    }

    @AfterMethod
    public void afterMethod() {
        _executor.shutdown();
    }

    protected void startInternal( @Nonnull final SessionManager manager, @Nonnull final MemcachedClient memcachedMock ) throws LifecycleException {
        throw new UnsupportedOperationException();
    }

    @Nonnull
    protected abstract SessionManager createSessionManager(Context context);

    @Test
    public void testConfigurationFormatMemcachedNodesFeature44() throws LifecycleException {
        _service.setMemcachedNodes( "n1:127.0.0.1:11211" );
        _service.startInternal(new MemcachedStorageClient(_memcachedMock));
        Assert.assertEquals( _service.getNodeIds(), Arrays.asList( "n1" ) );

        _service.setMemcachedNodes( "n1:127.0.0.1:11211 n2:127.0.0.1:11212" );
        _service.startInternal(new MemcachedStorageClient(_memcachedMock));
        Assert.assertEquals( _service.getNodeIds(), Arrays.asList( "n1", "n2" ) );

        _service.setMemcachedNodes( "n1:127.0.0.1:11211,n2:127.0.0.1:11212" );
        _service.startInternal(new MemcachedStorageClient(_memcachedMock));
        Assert.assertEquals( _service.getNodeIds(), Arrays.asList( "n1", "n2" ) );
    }

    @Test
    public void testConfigurationFormatFailoverNodesFeature44() throws LifecycleException {
        _service.setMemcachedNodes( "n1:127.0.0.1:11211 n2:127.0.0.1:11212" );
        _service.setFailoverNodes( "n1" );
        _service.startInternal(new MemcachedStorageClient(_memcachedMock));
        Assert.assertEquals( _service.getFailoverNodeIds(), Arrays.asList( "n1" ) );

        _service.setMemcachedNodes( "n1:127.0.0.1:11211 n2:127.0.0.1:11212 n3:127.0.0.1:11213" );
        _service.setFailoverNodes( "n1 n2" );
        _service.startInternal(new MemcachedStorageClient(_memcachedMock));
        Assert.assertEquals( _service.getFailoverNodeIds(), Arrays.asList( "n1", "n2" ) );

        _service.setMemcachedNodes( "n1:127.0.0.1:11211 n2:127.0.0.1:11212 n3:127.0.0.1:11213" );
        _service.setFailoverNodes( "n1,n2" );
        _service.startInternal(new MemcachedStorageClient(_memcachedMock));
        Assert.assertEquals( _service.getFailoverNodeIds(), Arrays.asList( "n1", "n2" ) );
    }

    /**
     * Test for issue #105: Make memcached node optional for single-node setup
     * http://code.google.com/p/memcached-session-manager/issues/detail?id=105
     */
    @Test
    public void testConfigurationFormatMemcachedNodesFeature105() throws LifecycleException {
        _service.setMemcachedNodes( "127.0.0.1:11211" );
        _service.startInternal(new MemcachedStorageClient(_memcachedMock));
        assertEquals(_service.getMemcachedNodesManager().getCountNodes(), 1);
        assertEquals(_service.getMemcachedNodesManager().isEncodeNodeIdInSessionId(), false);
        assertEquals(_service.getMemcachedNodesManager().isValidForMemcached("123456"), true);
        _service.shutdown();

        _service.setMemcachedNodes( "n1:127.0.0.1:11211" );
        _service.startInternal(new MemcachedStorageClient(_memcachedMock));
        assertEquals(_service.getMemcachedNodesManager().getCountNodes(), 1);
        assertEquals(_service.getMemcachedNodesManager().isEncodeNodeIdInSessionId(), true);
        assertEquals(_service.getMemcachedNodesManager().isValidForMemcached("123456"), false);
        assertEquals(_service.getMemcachedNodesManager().isValidForMemcached("123456-n1"), true);
    }

    /**
     * Test for issue #105: Make memcached node optional for single-node setup
     * http://code.google.com/p/memcached-session-manager/issues/detail?id=105
     */
    @Test
    public void testBackupSessionFailureWithoutMemcachedNodeIdConfigured105() throws Exception {
        _service.setMemcachedNodes( "127.0.0.1:11211" );
        _service.setSessionBackupAsync(false);
        _service.startInternal(new MemcachedStorageClient(_memcachedMock));

        final MemcachedBackupSession session = createSession( _service );

        session.access();
        session.endAccess();
        session.setAttribute( "foo", "bar" );

        @SuppressWarnings( "unchecked" )
        final OperationFuture<Boolean> futureMock = mock( OperationFuture.class );
        when( futureMock.get( ) ).thenThrow(new ExecutionException(new RuntimeException("Simulated exception.")));
        when( futureMock.get( anyInt(), any( TimeUnit.class ) ) ).thenThrow(new ExecutionException(new RuntimeException("Simulated exception.")));
        when( _memcachedMock.set(  eq( session.getId() ), anyInt(), any(), any( Transcoder.class ) ) ).thenReturn( futureMock );

        final BackupResult backupResult = _service.backupSession( session.getIdInternal(), false, null ).get();
        assertEquals(backupResult.getStatus(), BackupResultStatus.FAILURE);
        verify( _memcachedMock, times( 1 ) ).set( eq( session.getId() ), anyInt(), any(), any( Transcoder.class ) );
    }

    /**
     * Test that sessions are only backuped if they are modified.
     * @throws ExecutionException
     * @throws InterruptedException
     */
    @Test
    public void testOnlySendModifiedSessions() throws InterruptedException, ExecutionException {
        final MemcachedBackupSession session = createSession( _service );

        /* simulate the first request, with session access
         */
        session.access();
        session.endAccess();
        session.setAttribute( "foo", "bar" );
        _service.backupSession( session.getIdInternal(), false, null ).get();
        verify( _memcachedMock, times( 1 ) ).set( eq( session.getId() ), anyInt(), any(), any( Transcoder.class ) );

        // we need some millis between last backup and next access (due to check in BackupSessionService)
        Thread.sleep(5L);

        /* simulate the second request, with session access
         */
        session.access();
        session.endAccess();
        session.setAttribute( "foo", "bar" );
        session.setAttribute( "bar", "baz" );
        _service.backupSession( session.getIdInternal(), false, null ).get();
        verify( _memcachedMock, times( 2 ) ).set( eq( session.getId() ), anyInt(), any(), any( Transcoder.class ) );

        // we need some millis between last backup and next access (due to check in BackupSessionService)
        Thread.sleep(5L);

        /* simulate the third request, without session access
         */
        _service.backupSession( session.getIdInternal(), false, null ).get();
        verify( _memcachedMock, times( 2 ) ).set( eq( session.getId() ), anyInt(), any(), any( Transcoder.class ) );

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
        final ConcurrentMap<String, Object> anyMap = any( ConcurrentMap.class );
        when( transcoderServiceMock.serializeAttributes( any( MemcachedBackupSession.class ), anyMap ) ).thenReturn( new byte[0] );
        _service.setTranscoderService( transcoderServiceMock );

        final MemcachedBackupSession session = createSession( _service );

        session.access();
        session.endAccess();
        session.setAttribute( "foo", "bar" );
        _service.backupSession( session.getIdInternal(), false, null ).get();
        verify( transcoderServiceMock, times( 1 ) ).serializeAttributes( eq( session ), eq( session.getAttributesInternal() ) );

        session.access();
        session.endAccess();
        _service.backupSession( session.getIdInternal(), false, null ).get();
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
        final ConcurrentMap<String, Object> anyMap = any( ConcurrentMap.class );
        when( transcoderServiceMock.serializeAttributes( any( MemcachedBackupSession.class ), anyMap ) ).thenReturn( new byte[0] );
        _service.setTranscoderService( transcoderServiceMock );

        final MemcachedBackupSession session = createSession( _service );

        session.setAttribute( "foo", "bar" );
        _service.backupSession( session.getIdInternal(), false, null ).get();
        verify( transcoderServiceMock, times( 1 ) ).serializeAttributes( eq( session ), eq( session.getAttributesInternal() ) );

        // we need some millis between last backup and next access (due to check in BackupSessionService)
        Thread.sleep(5L);

        session.access();
        session.getAttribute( "foo" );
        _service.backupSession( session.getIdInternal(), false, null ).get();
        verify( transcoderServiceMock, times( 2 ) ).serializeAttributes( eq( session ), eq( session.getAttributesInternal() ) );

        // we need some millis between last backup and next access (due to check in BackupSessionService)
        Thread.sleep(5L);

        _service.backupSession( session.getIdInternal(), false, null ).get();
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

        _service.setStickyInternal( stickyness.isSticky() );
        if ( !stickyness.isSticky() ) {
            _service.setLockingMode( LockingMode.NONE, null, false );
        }

        final MemcachedBackupSession session = createSession( _service );

        session.setAttribute( "foo", "bar" );
        _service.backupSession( session.getIdInternal(), false, "foo" ).get();

        final String oldSessionId = session.getId();
        _service.getManager().changeSessionId( session );

        // on session backup we specify sessionIdChanged as false as we're not aware of this fact
        _service.backupSession( session.getIdInternal(), false, "foo" );

        // remove session with old id and add it with the new id
        verify( _memcachedMock, times( 1 ) ).delete( eq( oldSessionId ) );
        verify( _memcachedMock, times( 1 ) ).set( eq( session.getId() ), anyInt(), any(), any( Transcoder.class ) );

        if ( !stickyness.isSticky() ) {
            Thread.sleep(200l);
            // check validity info
            verify( _memcachedMock, times( 1 ) ).delete( eq( new SessionIdFormat().createValidityInfoKeyName( oldSessionId ) ) );
            verify( _memcachedMock, times( 1 ) ).set( eq( new SessionIdFormat().createValidityInfoKeyName( session.getId() ) ), anyInt(), any(), any( Transcoder.class ) );
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

        _service.setStickyInternal( stickyness.isSticky() );
        if ( !stickyness.isSticky() ) {
            _service.setLockingMode( LockingMode.NONE, null, false );
            _service.setMemcachedNodes( "n1:127.0.0.1:11211 n2:127.0.0.1:11212" ); // for backup support
            _service.startInternal(new MemcachedStorageClient(_memcachedMock)); // we must put in our mock again
        }

        final MemcachedBackupSession session = createSession( _service );
        session.setMaxInactiveInterval( -1 );

        session.access();
        session.endAccess();
        session.setAttribute( "foo", "bar" );
        final String sessionId = session.getId();

        _service.backupSession( sessionId, false, null ).get();

        verify( _memcachedMock, times( 1 ) ).set( eq( sessionId ), eq( 0 ), any(), any( Transcoder.class ) );

        if ( !stickyness.isSticky() ) {
            // check validity info
            final String validityKey = new SessionIdFormat().createValidityInfoKeyName( sessionId );
            verify( _memcachedMock, times( 1 ) ).set( eq( validityKey ), eq( 0 ), any(), any( Transcoder.class ) );

            // As the backup is done asynchronously, we shutdown the executor so that we know the backup
            // task is executed/finished.
            _service.getLockingStrategy().getExecutorService().shutdown();

            // On windows we need to wait a little bit so that the tasks _really_ have finished (not needed on linux)
            Thread.sleep(15);

            final String backupSessionKey = new SessionIdFormat().createBackupKey( sessionId );
            verify( _memcachedMock, times( 1 ) ).set( eq( backupSessionKey ), eq( 0 ), any(), any( Transcoder.class ) );
            final String backupValidityKey = new SessionIdFormat().createBackupKey( validityKey );
            verify( _memcachedMock, times( 1 ) ).set( eq( backupValidityKey ), eq( 0 ), any(), any( Transcoder.class ) );
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

        _service.setStickyInternal( false );
        _service.setLockingMode( LockingMode.NONE, null, false );
        _service.setMemcachedNodes( "n1:127.0.0.1:11211 n2:127.0.0.1:11212" ); // for backup support
        _service.startInternal(new MemcachedStorageClient(_memcachedMock)); // we must put in our mock again

        final String sessionId = "someSessionNotLoaded-n1";

        // stub loading of validity info
        final String validityKey = new SessionIdFormat().createValidityInfoKeyName( sessionId );
        final byte[] validityData = encode( -1, System.currentTimeMillis(), System.currentTimeMillis() );
        when( _memcachedMock.get( eq( validityKey ), any ( Transcoder.class) ) ).thenReturn( validityData );

        // stub session (backup) ping
        @SuppressWarnings( "unchecked" )
        final OperationFuture<Boolean> futureMock = mock( OperationFuture.class );
        when( futureMock.get() ).thenReturn( Boolean.FALSE );
        when( futureMock.get( anyInt(), any( TimeUnit.class ) ) ).thenReturn( Boolean.FALSE );
        when( _memcachedMock.add( any( String.class ), anyInt(), any(), any( Transcoder.class ) ) ).thenReturn( futureMock );

        _service.backupSession( sessionId, false, null ).get();

        // update validity info
        verify( _memcachedMock, times( 1 ) ).set( eq( validityKey ), eq( 0 ), any(), any( Transcoder.class ) );

        // As the backup is done asynchronously, we shutdown the executor so that we know the backup
        // task is executed/finished.
        _service.getLockingStrategy().getExecutorService().shutdown();

        // On windows we need to wait a little bit so that the tasks _really_ have finished (not needed on linux)
        Thread.sleep(15);

        // ping session
        verify( _memcachedMock, times( 1 ) ).add( eq( sessionId ), anyInt(), any(), any( Transcoder.class ) );

        // ping session backup
        final String backupSessionKey = new SessionIdFormat().createBackupKey( sessionId );
        verify( _memcachedMock, times( 1 ) ).add( eq( backupSessionKey ), anyInt(), any(), any( Transcoder.class ) );

        // update validity backup
        final String backupValidityKey = new SessionIdFormat().createBackupKey( validityKey );
        verify( _memcachedMock, times( 1 ) ).set( eq( backupValidityKey ), eq( 0 ), any(), any( Transcoder.class ) );
    }

    /**
     * Tests sessionAttributeFilter attribute: when excluded attributes are accessed/put the session should
     * not be marked as touched.
     */
    @SuppressWarnings( "unchecked" )
    @Test
    public void testOnlyHashAttributesOfAccessedFilteredAttributes() throws InterruptedException, ExecutionException {

        final TranscoderService transcoderServiceMock = mock( TranscoderService.class );
        _service.setTranscoderService( transcoderServiceMock );

        final MemcachedBackupSession session = createSession( _service );
        _service.setSessionAttributeFilter( "^(foo|bar)$" );

        session.setAttribute( "baz", "baz" );

        session.access();
        session.endAccess();

        _service.backupSession( session.getIdInternal(), false, null ).get();

        verify( transcoderServiceMock, never() ).serializeAttributes( (MemcachedBackupSession)any(), (ConcurrentMap)any() );

    }

    /**
     * Tests sessionAttributeFilter attribute: only filtered/allowed attributes must be serialized.
     */
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    @Test
    public void testOnlyFilteredAttributesAreIncludedInSessionBackup() throws InterruptedException, ExecutionException {

        final TranscoderService transcoderServiceMock = mock( TranscoderService.class );
        final ConcurrentMap<String, Object> anyMap = any( ConcurrentMap.class );
        when( transcoderServiceMock.serializeAttributes( any( MemcachedBackupSession.class ), anyMap ) ).thenReturn( new byte[0] );
        _service.setTranscoderService( transcoderServiceMock );

        final MemcachedBackupSession session = createSession( _service );
        _service.setSessionAttributeFilter( "^(foo|bar)$" );

        session.setAttribute( "foo", "foo" );
        session.setAttribute( "bar", "bar" );
        session.setAttribute( "baz", "baz" );

        _service.backupSession( session.getIdInternal(), false, null ).get();

        // capture the supplied argument, alternatively we could have used some Matcher (but there seems to be no MapMatcher).
        final ArgumentCaptor<ConcurrentMap> model = ArgumentCaptor.forClass( ConcurrentMap.class );
        verify( transcoderServiceMock, times( 1 ) ).serializeAttributes( eq( session ), model.capture() );

        // the serialized attributes must only contain allowed ones
        assertTrue( model.getValue().containsKey( "foo" ) );
        assertTrue( model.getValue().containsKey( "bar" ) );
        assertFalse( model.getValue().containsKey( "baz" ) );

    }

    /**
     * Tests sessionAttributeFilter attribute: only filtered/allowed attributes must be serialized in updateExpirationInMemcached.
     */
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    @Test
    public void testOnlyFilteredAttributesAreIncludedDuringUpdateExpiration() throws InterruptedException, ExecutionException {

        final TranscoderService transcoderServiceMock = mock( TranscoderService.class );
        final ConcurrentMap<String, Object> anyMap = any( ConcurrentMap.class );
        when( transcoderServiceMock.serializeAttributes( any( MemcachedBackupSession.class ), anyMap ) ).thenReturn( new byte[0] );
        _service.setTranscoderService( transcoderServiceMock );

        final MemcachedBackupSession session = createSession( _service );
        _service.setSessionAttributeFilter( "^(foo|bar)$" );

        session.setAttribute( "foo", "foo" );
        session.setAttribute( "bar", "bar" );
        session.setAttribute( "baz", "baz" );

        session.access();
        session.endAccess();

        _service.updateExpirationInMemcached();

        // capture the supplied argument, alternatively we could have used some Matcher (but there seems to be no MapMatcher).
        final ArgumentCaptor<ConcurrentMap> model = ArgumentCaptor.forClass( ConcurrentMap.class );
        verify( transcoderServiceMock, times( 1 ) ).serializeAttributes( eq( session ), model.capture() );

        // the serialized attributes must only contain allowed ones
        assertTrue( model.getValue().containsKey( "foo" ) );
        assertTrue( model.getValue().containsKey( "bar" ) );
        assertFalse( model.getValue().containsKey( "baz" ) );

    }

    @Test
    public void testSessionsRefCountHandlingIssue111() throws Exception {
        _service.setSticky(false);
        _service.setLockingMode(LockingMode.ALL.name());

        final TranscoderService transcoderService = new TranscoderService(new JavaSerializationTranscoder());
        _service.setTranscoderService( transcoderService );

        _service.setStorageClient(new MemcachedStorageClient(_memcachedMock));
        _service.startInternal();

        @SuppressWarnings("unchecked")
        final OperationFuture<Boolean> addResultMock = mock(OperationFuture.class);
        when(addResultMock.get()).thenReturn(true);
        when(addResultMock.get(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(_memcachedMock.add(anyString(), anyInt(), any(), any(Transcoder.class))).thenReturn(addResultMock);

        final MemcachedBackupSession session = createSession( _service );
        // the session is now already added to the internal session map
        assertNotNull(session.getId());

        Future<BackupResult> result = _service.backupSession(session.getId(), false, null);
        assertFalse(_service.getManager().getSessionsInternal().containsKey(session.getId()));

        // start another request that loads the session from mc
        final Request requestMock = mock(Request.class);
        when(requestMock.getNote(eq(RequestTrackingContextValve.INVOKED))).thenReturn(Boolean.TRUE);
        _service.getTrackingHostValve().storeRequestThreadLocal(requestMock);

        when(_memcachedMock.get(eq(session.getId()), any(Transcoder.class))).thenReturn(transcoderService.serialize(session));

        final MemcachedBackupSession session2 = _service.findSession(session.getId());
        assertTrue(session2.isLocked());
        assertEquals(session2.getRefCount(), 1);
        session2.setAttribute("foo", "bar");

        final CyclicBarrier barrier = new CyclicBarrier(2);

        // the session is now in the internal session map,
        // now let's run a concurrent request
        final Future<BackupResult> request2 = _executor.submit(new Callable<BackupResult>() {

            @Override
            public BackupResult call() throws Exception {
                final MemcachedBackupSession session3 = _service.findSession(session.getId());
                assertSame(session3, session2);
                assertEquals(session3.getRefCount(), 2);
                // let the other thread proceed (or wait)
                barrier.await();
                // and wait again so that the other thread can do some work
                barrier.await();

                final Future<BackupResult> result = _service.backupSession(session.getId(), false, null);
                _service.getTrackingHostValve().resetRequestThreadLocal();

                assertEquals(result.get().getStatus(), BackupResultStatus.SUCCESS);
                // The session should be released now and no longer stored
                assertFalse(_service.getManager().getSessionsInternal().containsKey(session.getId()));
                // just some double checking on expectations...
                assertEquals(session2.getRefCount(), 0);

                return result.get();
            }

        });

        barrier.await();

        result = _service.backupSession(session.getId(), false, null);
        _service.getTrackingHostValve().resetRequestThreadLocal();
        assertEquals(result.get().getStatus(), BackupResultStatus.SKIPPED);
        // This is the important point!
        assertTrue(_service.getManager().getSessionsInternal().containsKey(session.getId()));
        // just some double checking on expectations...
        assertEquals(session2.getRefCount(), 1);

        // now let the other thread proceed
        barrier.await();

        // and wait for the result, also to get exceptions/assertion errors.
        request2.get();

    }

    @Test
    public void testInvalidNonStickySessionDoesNotCallOnBackupWithoutLoadedSessionIssue137() throws Exception {

        _service.setStickyInternal( false );
        _service.setLockingMode( LockingMode.NONE, null, false );
        _service.startInternal(new MemcachedStorageClient(_memcachedMock)); // we must put in our mock again

        final String sessionId = "nonStickySessionToTimeOut-n1";

        // For findSession needed
        final Request requestMock = mock(Request.class);
        when(requestMock.getNote(eq(RequestTrackingContextValve.INVOKED))).thenReturn(Boolean.TRUE);
        _service.getTrackingHostValve().storeRequestThreadLocal(requestMock);

        final MemcachedBackupSession session = _service.findSession(sessionId);
        assertNull(session);

        _service.backupSession( sessionId, false, null ).get();

        // check that validity info is not loaded - this would trigger the
        // WARNING: Found no validity info for session id ...
        final String validityKey = new SessionIdFormat().createValidityInfoKeyName( sessionId );
        verify( _memcachedMock, times( 0 ) ).get( eq( validityKey ) );
    }

}
