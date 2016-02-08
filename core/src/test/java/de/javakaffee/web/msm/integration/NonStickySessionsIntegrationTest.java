/*
 * Copyright 2011 Martin Grotzke
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
package de.javakaffee.web.msm.integration;

import static de.javakaffee.web.msm.integration.TestServlet.*;
import static de.javakaffee.web.msm.integration.TestUtils.*;
import static de.javakaffee.web.msm.integration.TestUtils.Predicates.equalTo;
import static org.testng.Assert.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.spy.memcached.MemcachedClient;

import org.apache.http.HttpException;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.thimbleware.jmemcached.MemCacheDaemon;

import de.javakaffee.web.msm.LockingStrategy.LockingMode;
import de.javakaffee.web.msm.MemcachedNodesManager;
import de.javakaffee.web.msm.MemcachedNodesManager.MemcachedClientCallback;
import de.javakaffee.web.msm.MemcachedSessionService.SessionManager;
import de.javakaffee.web.msm.NodeIdList;
import de.javakaffee.web.msm.SessionIdFormat;
import de.javakaffee.web.msm.Statistics;
import de.javakaffee.web.msm.SuffixLocatorConnectionFactory;
import de.javakaffee.web.msm.integration.TestUtils.LoginType;
import de.javakaffee.web.msm.integration.TestUtils.Response;
import de.javakaffee.web.msm.integration.TestUtils.SessionTrackingMode;
import edu.umd.cs.findbugs.annotations.SuppressWarnings;
/**
 * Integration test testing non-sticky sessions.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public abstract class NonStickySessionsIntegrationTest {

    private static final Log LOG = LogFactory.getLog( NonStickySessionsIntegrationTest.class );

    private MemCacheDaemon<?> _daemon1;
    private MemCacheDaemon<?> _daemon2;
    private MemCacheDaemon<?> _daemon3;
    private MemcachedClient _client;

    private final MemcachedClientCallback _memcachedClientCallback = new MemcachedClientCallback() {
        @Override
        public Object get(final String key) {
            return _client.get(key);
        }
    };

    private TomcatBuilder<?> _tomcat1;
    private TomcatBuilder<?> _tomcat2;

    private static final int TC_PORT_1 = 18888;
    private static final int TC_PORT_2 = 18889;

    private static final String NODE_ID_1 = "n1";
    private static final String NODE_ID_2 = "n2";
    private static final String NODE_ID_3 = "n3";
    private static final int MEMCACHED_PORT_1 = 21211;
    private static final int MEMCACHED_PORT_2 = 21212;
    private static final int MEMCACHED_PORT_3 = 21213;
    private static final String MEMCACHED_NODES = NODE_ID_1 + ":localhost:" + MEMCACHED_PORT_1 + "," +
                                                  NODE_ID_2 + ":localhost:" + MEMCACHED_PORT_2;

    private DefaultHttpClient _httpClient;
    private ExecutorService _executor;

    @BeforeMethod
    public void setUp() throws Throwable {

        final InetSocketAddress address1 = new InetSocketAddress( "localhost", MEMCACHED_PORT_1 );
        _daemon1 = createDaemon( address1 );
        _daemon1.start();

        final InetSocketAddress address2 = new InetSocketAddress( "localhost", MEMCACHED_PORT_2 );
        _daemon2 = createDaemon( address2 );
        _daemon2.start();

        try {
            _tomcat1 = startTomcat( TC_PORT_1 );
            _tomcat2 = startTomcat( TC_PORT_2 );
        } catch ( final Throwable e ) {
            LOG.error( "could not start tomcat.", e );
            throw e;
        }

        final MemcachedNodesManager nodesManager = MemcachedNodesManager.createFor(MEMCACHED_NODES, null, null, _memcachedClientCallback);
        _client =
                new MemcachedClient( new SuffixLocatorConnectionFactory( nodesManager, nodesManager.getSessionIdFormat(), Statistics.create(), 1000, 1000 ),
                        Arrays.asList( address1, address2 ) );

        final SchemeRegistry schemeRegistry = new SchemeRegistry();
        schemeRegistry.register(
                new Scheme("http", 80, PlainSocketFactory.getSocketFactory()));
        _httpClient = new DefaultHttpClient(new ThreadSafeClientConnManager(schemeRegistry));

        _executor = Executors.newCachedThreadPool();
    }

    abstract TestUtils<?> getTestUtils();

    private TomcatBuilder<?> startTomcat( final int port ) throws Exception {
        return startTomcat(port, MEMCACHED_NODES, null);
    }

    private TomcatBuilder<?> startTomcat( final int port, final String memcachedNodes, final LockingMode lockingMode ) throws Exception {
        return getTestUtils().tomcatBuilder().port(port).sessionTimeout(5).memcachedNodes(memcachedNodes)
                .sticky(false).lockingMode(lockingMode).storageKeyPrefix(null).buildAndStart();
    }

    @AfterMethod
    public void tearDown() throws Exception {
        _client.shutdown();
        _daemon1.stop();
        _daemon2.stop();
        if(_daemon3 != null && _daemon3.isRunning()) {
            _daemon3.stop();
        }
        _tomcat1.stop();
        _tomcat2.stop();
        _httpClient.getConnectionManager().shutdown();
        _executor.shutdownNow();
    }

    @DataProvider
    public Object[][] lockingModes() {
        return new Object[][] {
                { LockingMode.ALL, null },
                { LockingMode.AUTO, null },
                { LockingMode.URI_PATTERN, Pattern.compile( ".*" ) },
                { LockingMode.NONE, null }
        };
    }

    @DataProvider
    public Object[][] lockingModesWithSessionLocking() {
        return new Object[][] {
                { LockingMode.ALL, null },
                { LockingMode.AUTO, null },
                { LockingMode.URI_PATTERN, Pattern.compile( ".*" ) }
        };
    }

    /**
     * Test for issue http://code.google.com/p/memcached-session-manager/issues/detail?id=120
     */
    @Test(enabled = true, dataProvider = "lockingModesWithSessionLocking")
    @edu.umd.cs.findbugs.annotations.SuppressWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    public void testLoadBackupSessionShouldWorkWithInfiniteSessionTimeoutIssue120(@Nonnull final LockingMode lockingMode,
            @Nullable final Pattern uriPattern) throws IOException, InterruptedException, HttpException,
            ExecutionException {

        _tomcat1.getManager().setMaxInactiveInterval(-1);
        setLockingMode(lockingMode, uriPattern);

        final String sessionId = post(_httpClient, TC_PORT_1, null, "k1", "v1").getSessionId();
        assertNotNull(sessionId);

        Thread.sleep(200);

        // we want to get the session from the primary node
        Response response = get(_httpClient, TC_PORT_1, sessionId);
        assertEquals(response.getSessionId(), sessionId);
        assertEquals(response.get("k1"), "v1");

        // now we shut down the primary node so that the session is loaded from the backup node
        final SessionIdFormat fmt = new SessionIdFormat();
        final String nodeId = fmt.extractMemcachedId( sessionId );
        final MemCacheDaemon<?> primary = NODE_ID_1.equals(nodeId) ? _daemon1 : _daemon2;
        primary.stop();

        Thread.sleep( 200 );

        // the session should be loaded from the backup node
        response = get(_httpClient, TC_PORT_1, sessionId);
        assertEquals(fmt.createNewSessionId(response.getSessionId(), nodeId), sessionId);
        assertEquals(response.get("k1"), "v1");
    }

    /**
     * Test for issue http://code.google.com/p/memcached-session-manager/issues/detail?id=104
     */
    @Test(enabled = true, dataProvider = "lockingModesWithSessionLocking")
    @edu.umd.cs.findbugs.annotations.SuppressWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    public void testLoadBackupSessionShouldWorkWithHighSessionTimeoutIssue104(@Nonnull final LockingMode lockingMode,
            @Nullable final Pattern uriPattern) throws IOException, InterruptedException, HttpException,
            ExecutionException {

        /* from memcached protocol:
         *  the actual value sent may either be Unix time (number of seconds since January 1, 1970, as a 32-bit
         *  value), or a number of seconds starting from current time. In the latter case, this number of seconds
         *  may not exceed 60*60*24*30 (number of seconds in 30 days); if the number sent by a client is larger than
         *  that, the server will consider it to be real Unix time value rather than an offset from current time.
         */
        _tomcat1.getManager().setMaxInactiveInterval(60*60*24*30 / 2 + 1);
        setLockingMode(lockingMode, uriPattern);

        final String sessionId = post(_httpClient, TC_PORT_1, null, "k1", "v1").getSessionId();
        assertNotNull(sessionId);

        Thread.sleep(200);

        // we want to get the session from the primary node
        Response response = get(_httpClient, TC_PORT_1, sessionId);
        assertEquals(response.getSessionId(), sessionId);
        assertEquals(response.get("k1"), "v1");

        // now we shut down the primary node so that the session is loaded from the backup node
        final SessionIdFormat fmt = new SessionIdFormat();
        final String nodeId = fmt.extractMemcachedId( sessionId );
        final MemCacheDaemon<?> primary = NODE_ID_1.equals(nodeId) ? _daemon1 : _daemon2;
        primary.stop();

        Thread.sleep( 200 );

        // the session should be loaded from the backup node
        response = get(_httpClient, TC_PORT_1, sessionId);
        assertEquals(fmt.createNewSessionId(response.getSessionId(), nodeId), sessionId);
        assertEquals(response.get("k1"), "v1");
    }

    /**
     * Tests that parallel request to the same Tomcat instance don't lead to stale data.
     */
    @Test(enabled = true, dataProvider = "lockingModesWithSessionLocking")
    public void testSessionLockingSupportedWithSingleNodeSetup(@Nonnull final LockingMode lockingMode,
            @Nullable final Pattern uriPattern) throws IOException, InterruptedException, HttpException,
            ExecutionException {

        _tomcat1.getManager().setMemcachedNodes("localhost:" + MEMCACHED_PORT_1);
        _tomcat1.getManager().setLockingMode( lockingMode, uriPattern, false );

        final String sessionId = post(_httpClient, TC_PORT_1, null, "k1", "v1").getSessionId();
        assertNotNull(sessionId);

        // just want to see that we can access/load the session
        Response response = get(_httpClient, TC_PORT_1, sessionId);
        assertEquals(response.getSessionId(), sessionId);
        assertEquals(response.get("k1"), "v1");

        // and we want to be able to update the session
        post(_httpClient, TC_PORT_1, sessionId, "k2", "v2");

        response = get(_httpClient, TC_PORT_1, sessionId);
        assertEquals(response.getSessionId(), sessionId);
        assertEquals(response.get("k1"), "v1");
        assertEquals(response.get("k2"), "v2");
    }

    /**
     * Tests that parallel request to the same Tomcat instance don't lead to stale data.
     */
    @Test(enabled = true, dataProvider = "lockingModesWithSessionLocking")
    public void testParallelRequestsToSameTomcatInstanceIssue111(@Nonnull final LockingMode lockingMode,
            @Nullable final Pattern uriPattern) throws IOException, InterruptedException, HttpException,
            ExecutionException {

        setLockingMode(lockingMode, uriPattern);

        final String sessionId = post(_httpClient, TC_PORT_1, null, "k1", "v1").getSessionId();
        assertNotNull(sessionId);

        // this request should lock and update the session.
        final Future<Response> response2 = _executor.submit(new Callable<Response>() {

            @Override
            public Response call() throws Exception {
                return post(_httpClient, TC_PORT_1, PATH_WAIT, sessionId, asMap(PARAM_MILLIS, "500", "k2", "v2"));
            }

        });

        Thread.sleep(200);

        // this request should update the same session instance and reuse the lock.
        post(_httpClient, TC_PORT_1, sessionId, "k3", "v3");

        // this request should wait until the second and third requests have released the
        // session lock.
        final Response finalResponse = get(_httpClient, TC_PORT_2, sessionId);
        assertEquals(finalResponse.getSessionId(), sessionId);
        assertEquals(response2.get().getSessionId(), sessionId);

        // the final response should contain all keys/values
        assertEquals(finalResponse.get("k1"), "v1");
        assertEquals(finalResponse.get("k2"), "v2");
        assertEquals(finalResponse.get("k3"), "v3");
    }

    /**
     * Tests that non-sticky sessions are not leading to stale data - that sessions are removed from
     * tomcat when the request is finished.
     */
    @Test( enabled = true )
    public void testNoStaleSessionsWithNonStickySessions() throws IOException, InterruptedException, HttpException {

        _tomcat1.getManager().setMaxInactiveInterval( 1 );
        _tomcat2.getManager().setMaxInactiveInterval( 1 );

        final String key = "foo";
        final String value1 = "bar";
        final String sessionId1 = post( _httpClient, TC_PORT_1, null, key, value1 ).getSessionId();
        assertNotNull( sessionId1 );

        final Object session = _client.get( sessionId1 );
        assertNotNull( session, "Session not found in memcached: " + sessionId1 );

        /* We modify the stored value with the next request which is served by tc2
         */
        final String value2 = "baz";
        final String sessionId2 = post( _httpClient, TC_PORT_2, sessionId1, key, value2 ).getSessionId();
        assertEquals( sessionId2, sessionId1 );

        /* Check that tc1 reads the updated value
         */
        final Response response = get( _httpClient, TC_PORT_1, sessionId1 );
        assertEquals( response.getSessionId(), sessionId1 );
        assertEquals( response.get( key ), value2 );

    }

    private void setLockingMode( @Nonnull final LockingMode lockingMode, @Nullable final Pattern uriPattern ) {
        _tomcat1.getManager().setLockingMode( lockingMode, uriPattern, true );
        _tomcat2.getManager().setLockingMode( lockingMode, uriPattern, true );
    }

    /**
     * Tests that non-sticky sessions are not leading to stale data - that sessions are removed from
     * tomcat when the request is finished.
     */
    @Test( enabled = true, dataProvider = "lockingModesWithSessionLocking" )
    public void testParallelRequestsDontCauseDataLoss( @Nonnull final LockingMode lockingMode, @Nullable final Pattern uriPattern ) throws IOException, InterruptedException, HttpException, ExecutionException {

        setLockingMode( lockingMode, uriPattern );

        final String key1 = "k1";
        final String value1 = "v1";
        final String sessionId = post( _httpClient, TC_PORT_1, null, key1, value1 ).getSessionId();
        assertNotNull( sessionId );

        final String key2 = "k2";
        final String value2 = "v2";
        LOG.info( "Start request 1" );
        final Future<Response> response1 = _executor.submit( new Callable<Response>() {

            @Override
            public Response call() throws Exception {
                return post( _httpClient, TC_PORT_1, PATH_WAIT, sessionId, asMap( PARAM_MILLIS, "500",
                        key2, value2 ) );
            }

        });

        Thread.sleep( 100 );

        final String key3 = "k3";
        final String value3 = "v3";
        LOG.info( "Start request 2" );
        final Response response2 = post( _httpClient, TC_PORT_2, sessionId, key3, value3 );

        assertEquals( response1.get().getSessionId(), sessionId );
        assertEquals( response2.getSessionId(), sessionId );

        /* The next request should contain all session data
         */
        final Response response3 = get( _httpClient, TC_PORT_1, sessionId );
        assertEquals( response3.getSessionId(), sessionId );

        LOG.info( "Got response for request 2" );
        assertEquals( response3.get( key1 ), value1 );
        assertEquals( response3.get( key2 ), value2 );
        assertEquals( response3.get( key3 ), value3 ); // failed without session locking

    }

    /**
     * Tests that for auto locking mode requests that are found to be readonly don't lock
     * the session
     */
    @Test( enabled = true )
    public void testReadOnlyRequestsDontLockSessionForAutoLocking() throws IOException, InterruptedException, HttpException, ExecutionException {

        setLockingMode( LockingMode.AUTO, null );

        final String key1 = "k1";
        final String value1 = "v1";
        final String sessionId = post( _httpClient, TC_PORT_1, null, key1, value1 ).getSessionId();
        assertNotNull( sessionId );

        // perform a readonly request without waiting, we perform this one later again
        final String path = "/mypath";
        final Map<String, String> params = asMap( "foo", "bar" );
        final Response response0 = get( _httpClient, TC_PORT_1, path, sessionId, params );
        assertEquals( response0.getSessionId(), sessionId );

        // perform a readonly, waiting request that we can perform again later
        final long timeToWaitInMillis = 500;
        final Map<String, String> paramsWait = asMap( PARAM_MILLIS, String.valueOf( timeToWaitInMillis ) );
        final Response response1 = get( _httpClient, TC_PORT_1, PATH_WAIT, sessionId, paramsWait );
        assertEquals( response1.getSessionId(), sessionId );

        // now do it again, now in the background, and in parallel start another readonly request,
        // both should not block each other
        final long start = System.currentTimeMillis();
        final Future<Response> response2 = _executor.submit( new Callable<Response>() {
            @Override
            public Response call() throws Exception {
                return get( _httpClient, TC_PORT_1, PATH_WAIT, sessionId, paramsWait );
            }
        });
        final Future<Response> response3 = _executor.submit( new Callable<Response>() {
            @Override
            public Response call() throws Exception {
                return get( _httpClient, TC_PORT_1, PATH_WAIT, sessionId, paramsWait );
            }
        });
        response2.get();
        response3.get();
        assertTrue ( ( System.currentTimeMillis() - start ) < ( 2 * timeToWaitInMillis ),
                "The time for both requests should be less than 2 * the wait time if they don't block each other." );
        assertEquals( response2.get().getSessionId(), sessionId );
        assertEquals( response3.get().getSessionId(), sessionId );

        // now perform a modifying request and a readonly in parallel which should not be blocked
        final Future<Response> response4 = _executor.submit( new Callable<Response>() {
            @Override
            public Response call() throws Exception {
                return post( _httpClient, TC_PORT_1, PATH_WAIT, sessionId, asMap( PARAM_MILLIS, "500", "foo", "bar" ) );
            }
        });
        Thread.sleep( 50 );
        final Response response5 = get( _httpClient, TC_PORT_1, path, sessionId, params );
        assertEquals( response5.getSessionId(), sessionId );
        assertFalse( response4.isDone(), "The readonly request should return before the long, session locking one" );
        assertEquals( response4.get().getSessionId(), sessionId );

    }

    /**
     * Tests that for uriPattern locking mode requests that don't match the pattern the
     * session is not locked.
     */
    @Test( enabled = true )
    public void testRequestsDontLockSessionForNotMatchingUriPattern() throws IOException, InterruptedException, HttpException, ExecutionException {

        final String pathToLock = "/locksession";
        setLockingMode( LockingMode.URI_PATTERN, Pattern.compile( pathToLock + ".*" ) );

        final String sessionId = get( _httpClient, TC_PORT_1, null ).getSessionId();
        assertNotNull( sessionId );

        // perform a request not matching the uri pattern, and in parallel start another request
        // that should lock the session
        final long timeToWaitInMillis = 500;
        final Map<String, String> paramsWait = asMap( PARAM_WAIT, "true", PARAM_MILLIS, String.valueOf( timeToWaitInMillis ) );
        final long start = System.currentTimeMillis();
        final Future<Response> response2 = _executor.submit( new Callable<Response>() {
            @Override
            public Response call() throws Exception {
                return get( _httpClient, TC_PORT_1, "/pathNotMatchingLockUriPattern", sessionId, paramsWait );
            }
        });
        final Future<Response> response3 = _executor.submit( new Callable<Response>() {
            @Override
            public Response call() throws Exception {
                return get( _httpClient, TC_PORT_1, pathToLock, sessionId, paramsWait );
            }
        });
        response2.get();
        response3.get();
        assertTrue ( ( System.currentTimeMillis() - start ) < ( 2 * timeToWaitInMillis ),
                "The time for both requests should be less than 2 * the wait time if they don't block each other." );
        assertEquals( response2.get().getSessionId(), sessionId );
        assertEquals( response3.get().getSessionId(), sessionId );

        // now perform a locking request and a not locking in parallel which should also not be blocked
        final Future<Response> response4 = _executor.submit( new Callable<Response>() {
            @Override
            public Response call() throws Exception {
                return get( _httpClient, TC_PORT_1, pathToLock, sessionId, paramsWait );
            }
        });
        Thread.sleep( 50 );
        final Response response5 = get( _httpClient, TC_PORT_1, "/pathNotMatchingLockUriPattern", sessionId );
        assertEquals( response5.getSessionId(), sessionId );
        assertFalse( response4.isDone(), "The non locking request should return before the long, session locking one" );
        assertEquals( response4.get().getSessionId(), sessionId );

    }

    /**
     * Tests that non-sticky sessions are not invalidated too early when sessions are accessed readonly.
     * Each (even session readonly request) must update the lastAccessedTime for the session in memcached.
     */
    @Test( enabled = true, dataProvider = "lockingModes" )
    public void testNonStickySessionIsValidEvenWhenAccessedReadonly( @Nonnull final LockingMode lockingMode, @Nullable final Pattern uriPattern ) throws IOException, InterruptedException, HttpException, ExecutionException {

        _tomcat1.getManager().setMaxInactiveInterval( 1 );
        _tomcat1.getManager().setLockingMode( lockingMode, uriPattern, true );

        final String sessionId = get( _httpClient, TC_PORT_1, null ).getSessionId();
        assertNotNull( sessionId );

        assertEquals( get( _httpClient, TC_PORT_1, sessionId ).getSessionId(), sessionId );
        Thread.sleep( 500 );
        assertEquals( get( _httpClient, TC_PORT_1, sessionId ).getSessionId(), sessionId );
        Thread.sleep( 500 );
        assertEquals( get( _httpClient, TC_PORT_1, sessionId ).getSessionId(), sessionId );

    }

    /**
     * Tests that non-sticky sessions are seen as valid (request.isRequestedSessionIdValid) and from
     * the correct source for different session tracking modes (uri/cookie).
     */
    @Test( enabled = true, dataProvider = "sessionTrackingModesProvider" )
    public void testNonStickySessionIsValidForDifferentSessionTrackingModes( @Nonnull final SessionTrackingMode sessionTrackingMode ) throws IOException, InterruptedException, HttpException, ExecutionException {

        _tomcat1.getManager().setMaxInactiveInterval( 1 );
        _tomcat1.getManager().setLockingMode( LockingMode.ALL, null, true );

        final String sessionId = get( _httpClient, TC_PORT_1, null ).getSessionId();
        assertNotNull( sessionId );

        Response response = get( _httpClient, TC_PORT_1, PATH_GET_REQUESTED_SESSION_INFO, sessionId, sessionTrackingMode, null, null );
        assertEquals( response.getSessionId(), sessionId );
        assertEquals( response.get( KEY_REQUESTED_SESSION_ID ), sessionId );
        assertEquals( Boolean.parseBoolean( response.get( KEY_IS_REQUESTED_SESSION_ID_VALID ) ), true );
        Thread.sleep( 100 );

        response = get( _httpClient, TC_PORT_1, PATH_GET_REQUESTED_SESSION_INFO, sessionId, sessionTrackingMode, null, null );
        assertEquals( response.getSessionId(), sessionId );
        assertEquals( response.get( KEY_REQUESTED_SESSION_ID ), sessionId );
        assertEquals( Boolean.parseBoolean( response.get( KEY_IS_REQUESTED_SESSION_ID_VALID ) ), true );

    }

    @Test( enabled = true )
    @SuppressWarnings( "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE" )
    public void testNonStickySessionIsStoredInSecondaryMemcachedForBackup() throws IOException, InterruptedException, HttpException {

        _tomcat1.getManager().setMaxInactiveInterval( 1 );
        _tomcat2.getManager().setMaxInactiveInterval( 1 );

        final String sessionId1 = post( _httpClient, TC_PORT_1, null, "foo", "bar" ).getSessionId();
        assertNotNull( sessionId1 );

        // the memcached client writes async, so it's ok to wait a little bit (especially on windows)
        waitForMemcachedClient( 100 );

        final SessionIdFormat fmt = new SessionIdFormat();

        final String nodeId = fmt.extractMemcachedId( sessionId1 );

        final MemCacheDaemon<?> primary = nodeId.equals( NODE_ID_1 ) ? _daemon1 : _daemon2;
        final MemCacheDaemon<?> secondary = nodeId.equals( NODE_ID_1 ) ? _daemon2 : _daemon1;

        assertNotNull( primary.getCache().get( key( sessionId1 ) )[0], sessionId1 );
        assertNotNull( primary.getCache().get( key( fmt.createValidityInfoKeyName( sessionId1 ) ) )[0], fmt.createValidityInfoKeyName( sessionId1 ) );

        // The executor needs some time to finish the backup...
        Thread.sleep( 500 );

        assertNotNull( secondary.getCache().get( key( fmt.createBackupKey( sessionId1 ) ) )[0] );
        assertNotNull( secondary.getCache().get( key( fmt.createBackupKey( fmt.createValidityInfoKeyName( sessionId1 ) ) ) )[0] );

    }

    /**
     * Test for issue #113: Backup of a session should take place on the next available node when the next logical node is unavailable.
     */
    @Test( enabled = true )
    @SuppressWarnings( "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE" )
    public void testNonStickySessionSecondaryBackupFailover() throws IOException, InterruptedException, HttpException {

        final InetSocketAddress address3 = new InetSocketAddress( "localhost", MEMCACHED_PORT_3 );
        _daemon3 = createDaemon( address3 );
        _daemon3.start();

        final String memcachedNodes = MEMCACHED_NODES + "," + NODE_ID_3 + ":localhost:" + MEMCACHED_PORT_3;

        final SessionManager manager = _tomcat1.getManager();
        manager.setMaxInactiveInterval( 5 );
        manager.setMemcachedNodes(memcachedNodes);
        manager.getMemcachedSessionService().setSessionBackupAsync(false);

        waitForReconnect(manager.getMemcachedSessionService().getMemcached(), 3, 1000);

        final NodeIdList nodeIdList = NodeIdList.create(NODE_ID_1, NODE_ID_2, NODE_ID_3);
        final Map<String, MemCacheDaemon<?>> memcachedsByNodeId = new HashMap<String, MemCacheDaemon<?>>();
        memcachedsByNodeId.put(NODE_ID_1, _daemon1);
        memcachedsByNodeId.put(NODE_ID_2, _daemon2);
        memcachedsByNodeId.put(NODE_ID_3, _daemon3);

        final String sessionId1 = post( _httpClient, TC_PORT_1, null, "key", "v1" ).getSessionId();
        assertNotNull( sessionId1 );

        final SessionIdFormat fmt = new SessionIdFormat();
        final String nodeId = fmt.extractMemcachedId( sessionId1 );
        final MemCacheDaemon<?> first = memcachedsByNodeId.get(nodeId);

        // the memcached client writes async, so it's ok to wait a little bit (especially on windows)
        assertNotNullElementWaitingWithProxy(0, 100, first.getCache()).get( key( sessionId1 ) );
        assertNotNullElementWaitingWithProxy(0, 100, first.getCache()).get( key( fmt.createValidityInfoKeyName( sessionId1 ) ) );

        // The executor needs some time to finish the backup...
        final MemCacheDaemon<?> second = memcachedsByNodeId.get(nodeIdList.getNextNodeId(nodeId));
        assertNotNullElementWaitingWithProxy(0, 4000, second.getCache()).get( key( fmt.createBackupKey( sessionId1 ) ) );
        assertNotNullElementWaitingWithProxy(0, 200, second.getCache()).get( key( fmt.createBackupKey( fmt.createValidityInfoKeyName( sessionId1 ) ) ) );

        // Shutdown the secondary memcached, so that the next backup should got to the next node
        second.stop();

        // Wait for update of nodeAvailabilityNodeCache
        Thread.sleep(100l);

        // Request / Update
        final String sessionId2 = post( _httpClient, TC_PORT_1, sessionId1, "key", "v2" ).getSessionId();
        assertEquals( sessionId2, sessionId1 );

        final MemCacheDaemon<?> third = memcachedsByNodeId.get(nodeIdList.getNextNodeId(nodeIdList.getNextNodeId(nodeId)));
        assertNotNullElementWaitingWithProxy(0, 4000, third.getCache()).get( key( fmt.createBackupKey( sessionId1 ) ) );
        assertNotNullElementWaitingWithProxy(0, 200, third.getCache()).get( key( fmt.createBackupKey( fmt.createValidityInfoKeyName( sessionId1 ) ) ) );

        // Shutdown the first node, so it should be loaded from the 3rd memcached
        first.stop();

        // Wait for update of nodeAvailabilityNodeCache
        Thread.sleep(100l);

        final Response response3 = get(_httpClient, TC_PORT_1, sessionId1);
        final String sessionId3 = response3.getResponseSessionId();
        assertNotNull(sessionId3);
        assertFalse(sessionId3.equals(sessionId1));
        assertEquals(sessionId3, fmt.createNewSessionId(sessionId1, fmt.extractMemcachedId(sessionId3)));

        assertEquals(response3.get("key"), "v2");

    }

    /**
     * Test for issue #113: Backup of a session should take place on the next available node when the next logical node is unavailable.
     */
    @Test( enabled = true )
    @SuppressWarnings( "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE" )
    public void testNonStickySessionSecondaryBackupFailoverForSkippedUpdate() throws IOException, InterruptedException, HttpException {

        final InetSocketAddress address3 = new InetSocketAddress( "localhost", MEMCACHED_PORT_3 );
        _daemon3 = createDaemon( address3 );
        _daemon3.start();

        final String memcachedNodes = MEMCACHED_NODES + "," + NODE_ID_3 + ":localhost:" + MEMCACHED_PORT_3;

        final SessionManager manager = _tomcat1.getManager();
        manager.setMaxInactiveInterval( 5 );
        manager.setMemcachedNodes(memcachedNodes);
        manager.getMemcachedSessionService().setSessionBackupAsync(false);

        waitForReconnect(manager.getMemcachedSessionService().getMemcached(), 3, 1000);

        final NodeIdList nodeIdList = NodeIdList.create(NODE_ID_1, NODE_ID_2, NODE_ID_3);
        final Map<String, MemCacheDaemon<?>> memcachedsByNodeId = new HashMap<String, MemCacheDaemon<?>>();
        memcachedsByNodeId.put(NODE_ID_1, _daemon1);
        memcachedsByNodeId.put(NODE_ID_2, _daemon2);
        memcachedsByNodeId.put(NODE_ID_3, _daemon3);

        final String sessionId1 = post( _httpClient, TC_PORT_1, null, "key", "v1" ).getSessionId();
        assertNotNull( sessionId1 );

        // the memcached client writes async, so it's ok to wait a little bit (especially on windows)
        final SessionIdFormat fmt = new SessionIdFormat();
        final String nodeId = fmt.extractMemcachedId( sessionId1 );
        final MemCacheDaemon<?> first = memcachedsByNodeId.get(nodeId);

        assertNotNullElementWaitingWithProxy(0, 100, first.getCache()).get( key( sessionId1 ) );
        assertNotNullElementWaitingWithProxy(0, 100, first.getCache()).get( key( fmt.createValidityInfoKeyName( sessionId1 ) ) );

        // The executor needs some time to finish the backup...
        final MemCacheDaemon<?> second = memcachedsByNodeId.get(nodeIdList.getNextNodeId(nodeId));
        assertNotNullElementWaitingWithProxy(0, 4000, second.getCache()).get( key( fmt.createBackupKey( sessionId1 ) ) );
        assertNotNullElementWaitingWithProxy(0, 200, second.getCache()).get( key( fmt.createBackupKey( fmt.createValidityInfoKeyName( sessionId1 ) ) ) );

        // Shutdown the secondary memcached, so that the next backup should got to the next node
        second.stop();

        Thread.sleep(100);

        // Request / Update
        final String sessionId2 = get( _httpClient, TC_PORT_1, sessionId1 ).getSessionId();
        assertEquals( sessionId2, sessionId1 );

        final MemCacheDaemon<?> third = memcachedsByNodeId.get(nodeIdList.getNextNodeId(nodeIdList.getNextNodeId(nodeId)));
        assertNotNullElementWaitingWithProxy(0, 4000, third.getCache()).get( key( fmt.createBackupKey( sessionId1 ) ) );
        assertNotNullElementWaitingWithProxy(0, 200, third.getCache()).get( key( fmt.createBackupKey( fmt.createValidityInfoKeyName( sessionId1 ) ) ) );

        // Shutdown the first node, so it should be loaded from the 3rd memcached
        first.stop();
        Thread.sleep(100);

        final Response response3 = get(_httpClient, TC_PORT_1, sessionId1);
        final String sessionId3 = response3.getResponseSessionId();
        assertNotNull(sessionId3);
        assertFalse(sessionId3.equals(sessionId1));
        assertEquals(sessionId3, fmt.createNewSessionId(sessionId1, fmt.extractMemcachedId(sessionId3)));

        assertEquals(response3.get("key"), "v1");

    }

    /**
     * Test for issue #79: In non-sticky sessions mode with only a single memcached the backup is done in the primary node.
     */
    @Test( enabled = true )
    public void testNoBackupWhenRunningASingleMemcachedOnly() throws IOException, HttpException, InterruptedException {
        _tomcat1.getManager().setMemcachedNodes( NODE_ID_1 + ":localhost:" + MEMCACHED_PORT_1 );

        // let's take some break so that everything's up again
        Thread.sleep( 500 );

        try {
            final String sessionId1 = post( _httpClient, TC_PORT_1, null, "foo", "bar" ).getSessionId();
            assertNotNull( sessionId1 );

            // the memcached client writes async, so it's ok to wait a little bit (especially on windows) (or on cloudbees jenkins)
            waitForMemcachedClient( 500 );

            // 2 for session and validity, if backup would be stored this would be 4 instead
            assertEquals( _daemon1.getCache().getSetCmds(), 2 );

            // just to be sure that node2 was not hit at all
            assertEquals( _daemon2.getCache().getSetCmds(), 0 );
        } finally {
            _tomcat1.getManager().setMemcachedNodes( MEMCACHED_NODES );
        }
    }

	private void waitForMemcachedClient( final long millis ) {
		try {
			Thread.sleep( millis );
		} catch (final InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

    @Test( enabled = true )
    public void testSessionNotLoadedForNoSessionAccess() throws IOException, HttpException, InterruptedException {
        _tomcat1.getManager().setMemcachedNodes( NODE_ID_1 + ":localhost:" + MEMCACHED_PORT_1 );
        waitForReconnect(_tomcat1.getService().getMemcached(), 1, 1000);

        final String sessionId1 = post( _httpClient, TC_PORT_1, null, "foo", "bar" ).getSessionId();
        assertNotNull( sessionId1 );

        // 2 for session and validity, if backup would be stored this would be 4 instead
        assertWaitingWithProxy(equalTo(2), 1000, _daemon1.getCache()).getSetCmds();
        // no gets at all
        assertEquals( _daemon1.getCache().getGetHits(), 0 );

        // a request without session access should not pull the session from memcached
        // but update the validity info (get + set)
        get( _httpClient, TC_PORT_1, PATH_NO_SESSION_ACCESS, sessionId1 );

        assertWaitingWithProxy(equalTo(3), 1000, _daemon1.getCache()).getSetCmds();

        // For TC7 the session is looked up by AuthenticatorBase.invoke(AuthenticatorBase.java:430) (TC 7.0.67) which seems
        // to be installed and always check the user principal - therefore we have 2 hits for the session and the validity info.
        // And we want to allow context level valves to access the session (issue #286), therefore we load the session even
        // if our context valve has not been passed (i.e. findSession is not directly triggered from the webapp).
        //
        // For TC{6,8} there's no call from AuthenticatorBase, so there's only 1 hit (validity info)
        assertEquals( _daemon1.getCache().getGetHits(), getExpectedHitsForNoSessionAccess());
    }

    protected int getExpectedHitsForNoSessionAccess() {
        return 1;
    }

    /**
     * Ignored resources (requests matching uriIgnorePattern) should neither load the session
     * from memcached nor should they cause stale session (not released after the request has finished,
     * which was the original issue).
     */
    @Test( enabled = true )
    public void testIgnoredResourcesWithSessionCookieDontCauseSessionStaleness() throws Exception {

        _tomcat1.stop();
        _tomcat2.stop();

        _tomcat1 = startTomcat( TC_PORT_1, NODE_ID_1 + ":localhost:" + MEMCACHED_PORT_1, LockingMode.AUTO );
        _tomcat2 = startTomcat( TC_PORT_2, NODE_ID_1 + ":localhost:" + MEMCACHED_PORT_1, LockingMode.AUTO );

        /* tomcat1: request secured resource and check that secured resource is accessable
         */
        final Response tc1Response1 = post( _httpClient, TC_PORT_1, "/", null, asMap( "foo", "bar" ));
        final String sessionId = tc1Response1.getSessionId();
        assertNotNull(sessionId);

        // 0 gets
        assertEquals( _daemon1.getCache().getGetHits(), 0 );
        // 2 sets for session and validity
        assertEquals( _daemon1.getCache().getSetCmds(), 2 );

        // a request on the static (ignored) resource should not pull the session from memcached
        // and should not update the session in memcached.
        final Response tc1Response2 = get(_httpClient, TC_PORT_1, "/pixel.gif", sessionId);
        assertNull(tc1Response2.getResponseSessionId());

        // gets/sets unchanged
        assertEquals( _daemon1.getCache().getGetHits(), 0 );
        assertEquals( _daemon1.getCache().getSetCmds(), 2 );

        /* another session change on tomcat1, with a hanging session (wrong refcount due to ignored request)
         * this change would not be written to memcached
         */
        final Response tc1Response3 = post( _httpClient, TC_PORT_1, "/", sessionId, asMap( "bar", "baz" ));
        assertEquals(tc1Response3.getSessionId(), sessionId);
        assertNull(tc1Response3.getResponseSessionId());

        /*
         * on tomcat2, we now should be able to get the session with all session attribues
         */
        final Response tc2Response1 = get( _httpClient, TC_PORT_2, sessionId );
        assertEquals(tc2Response1.getSessionId(), sessionId);
        assertEquals( tc2Response1.get( TestServlet.ID ), sessionId );
        assertEquals( tc2Response1.get( "foo" ), "bar" );
        assertEquals( tc2Response1.get( "bar" ), "baz" );

    }

    @Test( enabled = true )
    public void testBasicAuth() throws Exception {

        _tomcat1.stop();
        _tomcat2.stop();

        _tomcat1 = startTomcatWithAuth( TC_PORT_1, LockingMode.AUTO );
        _tomcat2 = startTomcatWithAuth( TC_PORT_2, LockingMode.AUTO );

        _tomcat1.setChangeSessionIdOnAuth( false );
        _tomcat2.setChangeSessionIdOnAuth( false );

        /* tomcat1: request secured resource, login and check that secured resource is accessable
         */
        final Response tc1Response1 = post( _httpClient, TC_PORT_1, "/", null, asMap( "foo", "bar" ),
                new UsernamePasswordCredentials( TestUtils.USER_NAME, TestUtils.PASSWORD ), true );
        final String sessionId = tc1Response1.getSessionId();
        assertNotNull( sessionId );

        /* tomcat1 failover "simulation":
         * on tomcat2, we now should be able to access the secured resource directly
         * with the first request
         */
        final Response tc2Response1 = get( _httpClient, TC_PORT_2, sessionId );
        assertEquals( sessionId, tc2Response1.get( TestServlet.ID ) );
        assertEquals( tc2Response1.get( "foo" ), "bar" );

    }

    /**
     * For form auth ignored resources (requests matching uriIgnorePattern) should load the session
     * from memcached but also clean up / free them after the request has finished.
     *
     */
    @Test( enabled = true )
    public void testIgnoredResourcesWithFormAuthDontCauseSessionStaleness() throws Exception {

        // TODO: see testSessionCreatedForContainerProtectedResourceIsStoredInMemcached

        _tomcat1.stop();
        _tomcat2.stop();

        _tomcat1 = startTomcatWithAuth( TC_PORT_1, NODE_ID_1 + ":localhost:" + MEMCACHED_PORT_1, LockingMode.AUTO, LoginType.FORM );
        _tomcat2 = startTomcatWithAuth( TC_PORT_2, NODE_ID_1 + ":localhost:" + MEMCACHED_PORT_1, LockingMode.AUTO, LoginType.FORM );

        _tomcat1.setChangeSessionIdOnAuth( false );
        _tomcat2.setChangeSessionIdOnAuth( false );

        /* login on tomcat1 (4 sets)
         */
        final String sessionId = loginWithForm(_httpClient, TC_PORT_1);

        /* tomcat1: request secured resource and check that secured resource is accessable
         */
        final Response tc1Response1 = post( _httpClient, TC_PORT_1, "/", sessionId, asMap( "foo", "bar" ));
        assertEquals(tc1Response1.getSessionId(), sessionId);

        // 6 gets for session and validity (4 login + 2 from previous post)
        assertEquals( _daemon1.getCache().getGetHits(), 6 );
        // 8 sets for session and validity
        assertEquals( _daemon1.getCache().getSetCmds(), 8 );

        // a request on the static (ignored) resource should not pull the session from memcached
        // and should not update the session in memcached.
        final Response tc1Response2 = get(_httpClient, TC_PORT_1, "/pixel.gif", sessionId);
        assertNull(tc1Response2.getResponseSessionId());
        assertEquals(tc1Response2.getStatusCode(), 200);

        // load session + validity info for pixel.gif
        assertEquals( _daemon1.getCache().getGetHits(), 8 );
        // ignored resource -> no validity update
        assertEquals( _daemon1.getCache().getSetCmds(), 8 );

        /* another session change on tomcat1, with a hanging session (wrong refcount due to ignored request)
         * this change would not be written to memcached
         */
        final Response tc1Response3 = post( _httpClient, TC_PORT_1, "/", sessionId, asMap( "bar", "baz" ));
        assertEquals(tc1Response3.getSessionId(), sessionId);
        assertNull(tc1Response3.getResponseSessionId());

        /* tomcat1 failover "simulation":
         * on tomcat2, we now should be able to access the secured resource directly
         * with the first request
         */
        final Response tc2Response1 = get( _httpClient, TC_PORT_2, sessionId );
        assertEquals(tc2Response1.getSessionId(), sessionId);
        assertNull(tc2Response1.getResponseSessionId());
        assertEquals( tc2Response1.get( TestServlet.ID ), sessionId );
        assertEquals( tc2Response1.get( "foo" ), "bar" );
        assertEquals( tc2Response1.get( "bar" ), "baz" );

    }

    /**
     * When a session is created for a request that tries to access a container protected
     * resource (container managed auth) this session must also be stored in memcached.
     */
    @Test( enabled = true )
    public void testSessionCreatedForContainerProtectedResourceIsStoredInMemcached() throws Exception {

        _tomcat1.stop();
        _tomcat2.stop();

        _tomcat1 = startTomcatWithAuth( TC_PORT_1, NODE_ID_1 + ":localhost:" + MEMCACHED_PORT_1, LockingMode.AUTO, LoginType.FORM );
        _tomcat2 = startTomcatWithAuth( TC_PORT_2, NODE_ID_1 + ":localhost:" + MEMCACHED_PORT_1, LockingMode.AUTO, LoginType.FORM );

        _tomcat1.setChangeSessionIdOnAuth( false );
        _tomcat2.setChangeSessionIdOnAuth( false );

        LOG.info("START foo1234");
        final Response response1 = get( _httpClient, TC_PORT_1, null );
        LOG.info("END foo1234");
        final String sessionId = response1.getSessionId();
        assertNotNull( sessionId );
        assertTrue(response1.getContent().contains("j_security_check"), "IllegalState: /j_security_check not found, app is not properly initialized");

        // failed sometimes, randomly (timing issue?)?!
        Thread.sleep(200);
        // 2 sets for session and validity
        assertEquals( _daemon1.getCache().getSetCmds(), 2 );

        final Map<String, String> params = new HashMap<String, String>();
        params.put( LoginServlet.J_USERNAME, TestUtils.USER_NAME );
        params.put( LoginServlet.J_PASSWORD, TestUtils.PASSWORD );
        final Response response2 = post( _httpClient, TC_PORT_2, "/j_security_check", sessionId, params, null, false );
        assertNull(response2.getResponseSessionId());
        assertTrue(isRedirect(response2.getStatusCode()), "IllegalState: 'POST /j_security_check' did not issue a redirect,"
                + " but status " + response2.getStatusCode() +". Page content: " + response2.getContent());

        // 2 gets for session and validity
        assertEquals( _daemon1.getCache().getGetHits(), 2 );
        // 2 new sets for session and validity
        assertEquals( _daemon1.getCache().getSetCmds(), 4 );

    }

    /**
     * When a session is created with form based auth the session should be stored
     * appropriately.
     */
    @Test( enabled = true )
    public void testFormAuthDontCauseSessionStaleness() throws Exception {

        _tomcat1.stop();
        _tomcat2.stop();

        _tomcat1 = startTomcatWithAuth( TC_PORT_1, NODE_ID_1 + ":localhost:" + MEMCACHED_PORT_1, LockingMode.AUTO, LoginType.FORM );
        _tomcat2 = startTomcatWithAuth( TC_PORT_2, NODE_ID_1 + ":localhost:" + MEMCACHED_PORT_1, LockingMode.AUTO, LoginType.FORM );

        _tomcat1.setChangeSessionIdOnAuth( false );
        _tomcat2.setChangeSessionIdOnAuth( false );

        waitForReconnect(_tomcat1.getService().getMemcached(), 1, 1000);
        waitForReconnect(_tomcat2.getService().getMemcached(), 1, 1000);

        final Response response1 = get( _httpClient, TC_PORT_1, null );
        final String sessionId = response1.getSessionId();
        assertNotNull( sessionId );
        assertTrue(response1.getContent().contains("j_security_check"), "IllegalState: /j_security_check not found, app is not properly initialized");

        // Wait some time so that the GET is finished
        Thread.sleep(200);

        final Map<String, String> params = new HashMap<String, String>();
        params.put( LoginServlet.J_USERNAME, TestUtils.USER_NAME );
        params.put( LoginServlet.J_PASSWORD, TestUtils.PASSWORD );

        final Response response2 = post( _httpClient, TC_PORT_2, "/j_security_check", sessionId, params, null, true );
        assertNull(response2.getResponseSessionId());
        assertEquals(response2.getStatusCode(), 200, response2.getContent());
        assertEquals(response2.get( TestServlet.ID ), sessionId);

        final Response response3 = post( _httpClient, TC_PORT_2, "/", sessionId, asMap( "foo", "bar" ));
        assertEquals(response3.getSessionId(), sessionId);

        final Response response4 = get(_httpClient, TC_PORT_1, sessionId);
        assertEquals(response4.getSessionId(), sessionId);
        assertEquals(response4.get( TestServlet.ID ), sessionId);
        assertEquals(response4.get( "foo" ), "bar");

    }

    @Test( enabled = true )
    public void testInvalidateSessionShouldReleaseLockIssue144() throws IOException, InterruptedException, HttpException {
        _tomcat1.getManager().setLockingMode(LockingMode.AUTO.name());

        final String sessionId1 = get( _httpClient, TC_PORT_1, null ).getSessionId();
        assertNotNull( sessionId1, "No session created." );

        final Response response = get( _httpClient, TC_PORT_1, PATH_INVALIDATE, sessionId1 );
        assertNull( response.getResponseSessionId() );
        assertNull(_client.get( sessionId1 ), "Invalidated session should be removed from memcached");
        assertNull(_client.get(new SessionIdFormat().createLockName(sessionId1)), "Lock should be released.");
    }

    private TomcatBuilder<?> startTomcatWithAuth( final int port, @Nonnull final LockingMode lockingMode ) throws Exception {
        return startTomcatWithAuth(port, MEMCACHED_NODES, lockingMode, LoginType.BASIC);
    }

    private TomcatBuilder<?> startTomcatWithAuth(final int port, final String memcachedNodes, final LockingMode lockingMode, final LoginType loginType)
            throws Exception {
        return getTestUtils().tomcatBuilder().port(port).sessionTimeout(5).loginType(loginType)
                .memcachedNodes(memcachedNodes).sticky(false).lockingMode(lockingMode).buildAndStart();
    }

    @DataProvider
    public Object[][] sessionTrackingModesProvider() {
        return new Object[][] {
                { SessionTrackingMode.COOKIE },
                { SessionTrackingMode.URL }
        };
    }

}
