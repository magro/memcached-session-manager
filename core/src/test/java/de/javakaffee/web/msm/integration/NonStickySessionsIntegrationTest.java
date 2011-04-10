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

import static de.javakaffee.web.msm.SessionValidityInfo.createValidityInfoKeyName;
import static de.javakaffee.web.msm.integration.TestServlet.*;
import static de.javakaffee.web.msm.integration.TestUtils.*;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.util.Arrays;
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

import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Embedded;
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
import de.javakaffee.web.msm.NodeIdList;
import de.javakaffee.web.msm.NodeIdResolver;
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
public class NonStickySessionsIntegrationTest {

    private static final Log LOG = LogFactory.getLog( NonStickySessionsIntegrationTest.class );

    private MemCacheDaemon<?> _daemon1;
    private MemCacheDaemon<?> _daemon2;
    private MemcachedClient _client;

    private Embedded _tomcat1;
    private Embedded _tomcat2;

    private static final int TC_PORT_1 = 18888;
    private static final int TC_PORT_2 = 18889;

    private static final String NODE_ID_1 = "n1";
    private static final String NODE_ID_2 = "n2";
    private static final int MEMCACHED_PORT_1 = 21211;
    private static final int MEMCACHED_PORT_2 = 21212;
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

        _client =
                new MemcachedClient( new SuffixLocatorConnectionFactory( NodeIdList.create( NODE_ID_1, NODE_ID_2 ), NodeIdResolver.node(
                        NODE_ID_1, address1 ).node( NODE_ID_2, address2 ).build(), new SessionIdFormat(), Statistics.create() ),
                        Arrays.asList( address1, address2 ) );

        final SchemeRegistry schemeRegistry = new SchemeRegistry();
        schemeRegistry.register(
                new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
        _httpClient = new DefaultHttpClient(new ThreadSafeClientConnManager(schemeRegistry));

        _executor = Executors.newCachedThreadPool();
    }

    private Embedded startTomcat( final int port ) throws MalformedURLException, UnknownHostException, LifecycleException {
        final Embedded tomcat = createCatalina( port, 5, MEMCACHED_NODES );
        getManager( tomcat ).setSticky( false );
        tomcat.start();
        return tomcat;
    }

    @AfterMethod
    public void tearDown() throws Exception {
        _client.shutdown();
        _daemon1.stop();
        _daemon2.stop();
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
     * Tests that non-sticky sessions are not leading to stale data - that sessions are removed from
     * tomcat when the request is finished.
     */
    @Test( enabled = true )
    public void testNoStaleSessionsWithNonStickySessions() throws IOException, InterruptedException, HttpException {

        getManager( _tomcat1 ).setMaxInactiveInterval( 1 );
        getManager( _tomcat2 ).setMaxInactiveInterval( 1 );

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
        getManager( _tomcat1 ).setLockingMode( lockingMode, uriPattern, true );
        getManager( _tomcat2 ).setLockingMode( lockingMode, uriPattern, true );
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
    @Test
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
    @Test
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

        getManager( _tomcat1 ).setMaxInactiveInterval( 1 );
        getManager( _tomcat1 ).setLockingMode( lockingMode, uriPattern, true );

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

        getManager( _tomcat1 ).setMaxInactiveInterval( 1 );
        getManager( _tomcat1 ).setLockingMode( LockingMode.ALL, null, true );

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

    /**
     * Tests that non-sticky sessions are not leading to stale data - that sessions are removed from
     * tomcat when the request is finished.
     */
    @Test( enabled = true )
    @SuppressWarnings( "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE" )
    public void testNonStickySessionIsStoredInSecondaryMemcachedForBackup() throws IOException, InterruptedException, HttpException {

        getManager( _tomcat1 ).setMaxInactiveInterval( 1 );
        getManager( _tomcat2 ).setMaxInactiveInterval( 1 );

        final String sessionId1 = post( _httpClient, TC_PORT_1, null, "foo", "bar" ).getSessionId();
        assertNotNull( sessionId1 );

        // the memcached client writes async, so it's ok to wait a little bit (especially on windows)
        waitForMemcachedClient( 100 );

        final SessionIdFormat fmt = new SessionIdFormat();

        final String nodeId = fmt.extractMemcachedId( sessionId1 );

        final MemCacheDaemon<?> primary = nodeId.equals( NODE_ID_1 ) ? _daemon1 : _daemon2;
        final MemCacheDaemon<?> secondary = nodeId.equals( NODE_ID_1 ) ? _daemon2 : _daemon1;

        assertNotNull( primary.getCache().get( key( sessionId1 ) )[0] );
        assertNotNull( primary.getCache().get( key( createValidityInfoKeyName( sessionId1 ) ) )[0] );

        // The executor needs some time to finish the backup...
        Thread.sleep( 100 );

        assertNotNull( secondary.getCache().get( key( fmt.createBackupKey( sessionId1 ) ) )[0] );
        assertNotNull( secondary.getCache().get( key( fmt.createBackupKey( createValidityInfoKeyName( sessionId1 ) ) ) )[0] );

    }

    /**
     * Test for issue #79: In non-sticky sessions mode with only a single memcached the backup is done in the primary node.
     */
    @Test( enabled = true )
    public void testNoBackupWhenRunningASingleMemcachedOnly() throws IOException, HttpException, InterruptedException {
        getManager( _tomcat1 ).setMemcachedNodes( NODE_ID_1 + ":localhost:" + MEMCACHED_PORT_1 );

        // let's take some break so that everything's up again
        Thread.sleep( 200 );

        try {
            final String sessionId1 = post( _httpClient, TC_PORT_1, null, "foo", "bar" ).getSessionId();
            assertNotNull( sessionId1 );

            // the memcached client writes async, so it's ok to wait a little bit (especially on windows) (or on cloudbees jenkins)
            waitForMemcachedClient( 200 );

            // 2 for session and validity, if backup would be stored this would be 4 instead
            assertEquals( _daemon1.getCache().getSetCmds(), 2 );

            // just to be sure that node2 was not hit at all
            assertEquals( _daemon2.getCache().getSetCmds(), 0 );
        } finally {
            getManager( _tomcat1 ).setMemcachedNodes( MEMCACHED_NODES );
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
    public void testSessionNotLoadedForReadonlyRequest() throws IOException, HttpException, InterruptedException {
        getManager( _tomcat1 ).setMemcachedNodes( NODE_ID_1 + ":localhost:" + MEMCACHED_PORT_1 );
        try {

            final String sessionId1 = post( _httpClient, TC_PORT_1, null, "foo", "bar" ).getSessionId();
            assertNotNull( sessionId1 );
            Thread.sleep( 200 );

            // 2 for session and validity, if backup would be stored this would be 4 instead
            assertEquals( _daemon1.getCache().getSetCmds(), 2 );
            // no gets at all
            assertEquals( _daemon1.getCache().getGetHits(), 0 );

            // a request without session access should not pull the session from memcached
            // but update the validity info (get + set)
            get( _httpClient, TC_PORT_1, PATH_NO_SESSION_ACCESS, sessionId1 );
            Thread.sleep( 200 );

            assertEquals( _daemon1.getCache().getGetHits(), 1 );
            assertEquals( _daemon1.getCache().getSetCmds(), 3 );

        } finally {
            getManager( _tomcat1 ).setMemcachedNodes( MEMCACHED_NODES );
        }
    }

    @Test( enabled = true )
    public void testBasicAuth() throws Exception {

        _tomcat1.stop();
        _tomcat2.stop();

        _tomcat1 = startTomcatWithAuth( TC_PORT_1, LockingMode.AUTO );
        _tomcat2 = startTomcatWithAuth( TC_PORT_2, LockingMode.AUTO );

        setChangeSessionIdOnAuth( _tomcat1, false );
        setChangeSessionIdOnAuth( _tomcat2, false );

        /* tomcat1: request secured resource, login and check that secured resource is accessable
         */
        final Response tc1Response1 = post( _httpClient, TC_PORT_2, "/", null, asMap( "foo", "bar" ),
                new UsernamePasswordCredentials( TestUtils.USER_NAME, TestUtils.PASSWORD ) );
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

    @SuppressWarnings( "deprecation" )
    private Embedded startTomcatWithAuth( final int port, @Nonnull final LockingMode lockingMode ) throws MalformedURLException, UnknownHostException, LifecycleException {
        final Embedded result = createCatalina( port, MEMCACHED_NODES, null, LoginType.BASIC );
        getManager( result ).setSticky( false );
        getManager( result ).setLockingMode( lockingMode.name() );
        result.start();
        return result;
    }

    @DataProvider
    public Object[][] sessionTrackingModesProvider() {
        return new Object[][] {
                { SessionTrackingMode.COOKIE },
                { SessionTrackingMode.URL }
        };
    }

}
