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

import static de.javakaffee.web.msm.integration.TestUtils.STICKYNESS_PROVIDER;
import static de.javakaffee.web.msm.integration.TestUtils.createDaemon;
import static de.javakaffee.web.msm.integration.TestUtils.getManager;
import static de.javakaffee.web.msm.integration.TestUtils.makeRequest;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.Nonnull;

import net.spy.memcached.MemcachedClient;

import org.apache.catalina.Container;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Session;
import org.apache.catalina.startup.Embedded;
import org.apache.http.HttpException;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.thimbleware.jmemcached.MemCacheDaemon;

import de.javakaffee.web.msm.MemcachedNodesManager.MemcachedClientCallback;
import de.javakaffee.web.msm.MemcachedSessionService.SessionManager;
import de.javakaffee.web.msm.integration.TestUtils;
import de.javakaffee.web.msm.integration.TestUtils.SessionAffinityMode;

/**
 * Integration test testing basic session manager functionality.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public abstract class MemcachedSessionManagerIntegrationTest {

    private static final Log LOG = LogFactory.getLog( MemcachedSessionManagerIntegrationTest.class );

    private MemCacheDaemon<?> _daemon;
    private MemcachedClient _memcached;

    private Embedded _tomcat1;

    private int _portTomcat1;

    private String _memcachedNodeId;

    private DefaultHttpClient _httpClient;

    private int _memcachedPort;
    
    private final MemcachedClientCallback _memcachedClientCallback = new MemcachedClientCallback() {
		@Override
		public Object get(final String key) {
			return _memcached.get(key);
		}
	};

    @BeforeMethod
    public void setUp() throws Throwable {

        _portTomcat1 = 18888;

        _memcachedPort = 21211;

        final InetSocketAddress address = new InetSocketAddress( "localhost", _memcachedPort );
        _daemon = createDaemon( address );
        _daemon.start();

        _memcachedNodeId = "n1";
        final String memcachedNodes = _memcachedNodeId + ":localhost:" + _memcachedPort;

        try {
            System.setProperty( "org.apache.catalina.startup.EXIT_ON_INIT_FAILURE", "true" );
            _tomcat1 = getTestUtils().createCatalina( _portTomcat1, memcachedNodes, "app1" );
            getManager( _tomcat1 ).setSticky( true );
            _tomcat1.start();
        } catch ( final Throwable e ) {
            LOG.error( "could not start tomcat.", e );
            throw e;
        }

        _memcached = createMemcachedClient( memcachedNodes, address );

        _httpClient = new DefaultHttpClient();
    }

    private MemcachedClient createMemcachedClient( final String memcachedNodes, final InetSocketAddress address ) throws IOException, InterruptedException {
    	final MemcachedNodesManager nodesManager = MemcachedNodesManager.createFor(memcachedNodes, null, _memcachedClientCallback);
        final MemcachedClient result = new MemcachedClient( new SuffixLocatorConnectionFactory( nodesManager, nodesManager.getSessionIdFormat(), Statistics.create() ),
                Arrays.asList( address ) );

        // Wait a little bit, so that the memcached client can connect and is ready when test starts
        Thread.sleep( 100 );

        return result;
    }

    @AfterMethod
    public void tearDown() throws Exception {
        _memcached.shutdown();
        _tomcat1.stop();
        _httpClient.getConnectionManager().shutdown();
        _daemon.stop();
    }

    @Test( enabled = true )
    public void testConfiguredMemcachedNodeId() throws IOException, InterruptedException, HttpException {
        final String sessionId1 = makeRequest( _httpClient, _portTomcat1, null );
        assertNotNull( sessionId1, "No session created." );
        /*
         * test that we have the configured memcachedNodeId in the sessionId,
         * the session id looks like "<sid>-<memcachedId>[.<jvmRoute>]"
         */
        final String nodeId = sessionId1.substring( sessionId1.indexOf( '-' ) + 1, sessionId1.indexOf( '.' ) );
        assertEquals( _memcachedNodeId, nodeId, "Invalid memcached node id" );
    }

    @Test( enabled = true )
    public void testSessionIdJvmRouteCompatibility() throws IOException, InterruptedException, HttpException {
        final String sessionId1 = makeRequest( _httpClient, _portTomcat1, null );
        assertNotNull( sessionId1, "No session created." );
        assertTrue( sessionId1.matches( "[^-.]+-[^.]+(\\.[\\w]+)?" ),
                "Invalid session format, must be <sid>-<memcachedId>[.<jvmRoute>]." );
    }

    /**
     * Tests, that session ids with an invalid format (not containing the
     * memcached id) do not cause issues. Instead, we want to retrieve a new
     * session id.
     *
     * @throws IOException
     * @throws InterruptedException
     * @throws HttpException
     */
    @Test( enabled = true, dataProviderClass = TestUtils.class, dataProvider = STICKYNESS_PROVIDER )
    public void testInvalidSessionId( final SessionAffinityMode sessionAffinity ) throws IOException, InterruptedException, HttpException {

        getManager( _tomcat1 ).setSticky( sessionAffinity.isSticky() );

        final String sessionId1 = makeRequest( _httpClient, _portTomcat1, "12345" );
        assertNotNull( sessionId1, "No session created." );
        assertTrue( sessionId1.indexOf( '-' ) > -1, "Invalid session id format" );
    }

    @Test( enabled = true, dataProviderClass = TestUtils.class, dataProvider = STICKYNESS_PROVIDER )
    public void testSessionAvailableInMemcached( final SessionAffinityMode sessionAffinity ) throws IOException, InterruptedException, HttpException {

        getManager( _tomcat1 ).setSticky( sessionAffinity.isSticky() );

        final String sessionId1 = makeRequest( _httpClient, _portTomcat1, null );
        assertNotNull( sessionId1, "No session created." );
        Thread.sleep( 50 );
        assertNotNull( _memcached.get( sessionId1 ), "Session not available in memcached." );
    }

    @Test( enabled = true, dataProviderClass = TestUtils.class, dataProvider = STICKYNESS_PROVIDER )
    public void testExpiredSessionRemovedFromMemcached( @Nonnull final SessionAffinityMode sessionAffinity ) throws IOException, InterruptedException, HttpException {

        getManager( _tomcat1 ).setSticky( sessionAffinity.isSticky() );

        final String sessionId1 = makeRequest( _httpClient, _portTomcat1, null );
        assertNotNull( sessionId1, "No session created." );

        waitForSessionExpiration( sessionAffinity.isSticky() );

        assertNull( _memcached.get( sessionId1 ), "Expired session still existing in memcached" );
    }

    @Test( enabled = true, dataProviderClass = TestUtils.class, dataProvider = STICKYNESS_PROVIDER )
    public void testInvalidSessionNotFound( @Nonnull final SessionAffinityMode sessionAffinity ) throws IOException, InterruptedException, HttpException {

        getManager( _tomcat1 ).setSticky( sessionAffinity.isSticky() );

        final String sessionId1 = makeRequest( _httpClient, _portTomcat1, null );
        assertNotNull( sessionId1, "No session created." );

        /*
         * wait some time, as processExpires runs every second and the
         * maxInactiveTime is set to 1 sec...
         */
        Thread.sleep( 2100 );

        final String sessionId2 = makeRequest( _httpClient, _portTomcat1, sessionId1 );
        assertNotSame( sessionId1, sessionId2, "Expired session returned." );
    }

    /**
     * Tests, that for a session that was not sent to memcached (because it's attributes
     * were not modified), the expiration is updated so that they don't expire in memcached
     * before they expire in tomcat.
     *
     * @throws Exception if something goes wrong with the http communication with tomcat
     */
    @Test( enabled = true, dataProviderClass = TestUtils.class, dataProvider = STICKYNESS_PROVIDER )
    public void testExpirationOfSessionsInMemcachedIfBackupWasSkippedSimple( final SessionAffinityMode stickyness ) throws Exception {

        final SessionManager manager = getManager( _tomcat1 );
        manager.setSticky( stickyness.isSticky() );

        // set to 1 sec above (in setup), default is 10 seconds
        final int delay = manager.getContainer().getBackgroundProcessorDelay();
        manager.setMaxInactiveInterval( delay * 4 );

        final String sessionId1 = makeRequest( _httpClient, _portTomcat1, null );
        assertNotNull( sessionId1, "No session created." );
        assertNotNull( _memcached.get( sessionId1 ), "Session not available in memcached." );

        /* after 2 seconds make another request without changing the session, so that
         * it's not sent to memcached
         */
        Thread.sleep( TimeUnit.SECONDS.toMillis( delay * 2 ) );
        assertEquals( makeRequest( _httpClient, _portTomcat1, sessionId1 ), sessionId1, "SessionId should be the same" );

        /* after another 3 seconds check that the session is still alive in memcached,
         * this would have been expired without an updated expiration
         */
        Thread.sleep( TimeUnit.SECONDS.toMillis( delay * 3 ) );
        assertNotNull( _memcached.get( sessionId1 ), "Session should still exist in memcached." );

        /* after another >1 second (4 seconds since the last request)
         * the session must be expired in memcached
         */
        Thread.sleep( TimeUnit.SECONDS.toMillis( delay ) + 500 ); // +1000 just to be sure that we're >4 secs
        assertNotSame( makeRequest( _httpClient, _portTomcat1, sessionId1 ), sessionId1,
                "The sessionId should have changed due to expired sessin" );

    }

    /**
     * Tests update of session expiration in memcached (like {@link #testExpirationOfSessionsInMemcachedIfBackupWasSkippedSimple()})
     * but for the scenario where many readonly requests occur: in this case, we cannot just use
     * <em>maxInactiveInterval - secondsSinceLastBackup</em> (in {@link MemcachedSessionService#updateExpirationInMemcached})
     * to determine if an expiration update is required, but we must use the last expiration time sent to memcached.
     *
     * @throws Exception if something goes wrong with the http communication with tomcat
     */
    @Test( enabled = true, dataProviderClass = TestUtils.class, dataProvider = STICKYNESS_PROVIDER )
    public void testExpirationOfSessionsInMemcachedIfBackupWasSkippedManyReadonlyRequests( final SessionAffinityMode stickyness ) throws Exception {

        final SessionManager manager = getManager( _tomcat1 );
        manager.setSticky( stickyness.isSticky() );

        // set to 1 sec above (in setup), default is 10 seconds
        final int delay = manager.getContainer().getBackgroundProcessorDelay();
        manager.setMaxInactiveInterval( delay * 4 );

        final String sessionId1 = makeRequest( _httpClient, _portTomcat1, null );
        assertNotNull( sessionId1, "No session created." );
        assertNotNull( _memcached.get( sessionId1 ), "Session not available in memcached." );

        /* after 3 seconds make another request without changing the session, so that
         * it's not sent to memcached
         */
        Thread.sleep( TimeUnit.SECONDS.toMillis( delay * 3 ) );
        assertEquals( makeRequest( _httpClient, _portTomcat1, sessionId1 ), sessionId1, "SessionId should be the same" );
        assertNotNull( _memcached.get( sessionId1 ), "Session should still exist in memcached." );

        /* after another 3 seconds make another request without changing the session
         */
        Thread.sleep( TimeUnit.SECONDS.toMillis( delay * 3 ) );
        assertEquals( makeRequest( _httpClient, _portTomcat1, sessionId1 ), sessionId1, "SessionId should be the same" );
        assertNotNull( _memcached.get( sessionId1 ), "Session should still exist in memcached." );

        /* after another nearly 4 seconds (maxInactiveInterval) check that the session is still alive in memcached,
         * this would have been expired without an updated expiration
         */
        Thread.sleep( TimeUnit.SECONDS.toMillis( manager.getMaxInactiveInterval() ) - 500 );
        assertNotNull( _memcached.get( sessionId1 ), "Session should still exist in memcached." );

        /* after another second in sticky mode (more than 4 seconds since the last request), or an two times the
         * maxInactiveInterval in non-sticky mode (we must keep sessions in memcached with double expirationtime)
         * the session must be expired in memcached
         */
        Thread.sleep( TimeUnit.SECONDS.toMillis( delay ) + 500 );
        assertNotSame( makeRequest( _httpClient, _portTomcat1, sessionId1 ), sessionId1,
                "The sessionId should have changed due to expired sessin" );

    }

    /**
     * Test for issue #49:
     * Sessions not associated with a memcached node don't get associated as soon as a memcached is available
     * @throws InterruptedException
     * @throws IOException
     * @throws TimeoutException
     * @throws ExecutionException
     */
    @Test( enabled = true )
    public void testNotAssociatedSessionGetsAssociatedIssue49() throws InterruptedException, IOException, ExecutionException, TimeoutException {
        _daemon.stop();

        final SessionManager manager = getManager( _tomcat1 );
        manager.setMaxInactiveInterval( 5 );
        manager.setSticky( true );
        final SessionIdFormat sessionIdFormat = new SessionIdFormat();

        final Session session = manager.createSession( null );
        assertNull( sessionIdFormat.extractMemcachedId( session.getId() ) );

        _daemon.start();

        // Wait so that the daemon will be available and the client can reconnect (async get didn't do the trick)
        Thread.sleep( 4000 );

        final String newSessionId = manager.getMemcachedSessionService().changeSessionIdOnMemcachedFailover( session.getId() );
        assertNotNull( newSessionId );
        assertEquals( newSessionId, session.getId() );
        assertEquals( sessionIdFormat.extractMemcachedId( newSessionId ), _memcachedNodeId );

    }

    /**
     * Test for issue #60 (Add possibility to disable msm at runtime): disable msm
     */
    @Test( enabled = true )
    public void testDisableMsmAtRuntime() throws InterruptedException, IOException, ExecutionException, TimeoutException, LifecycleException, HttpException {
        final SessionManager manager = getManager( _tomcat1 );
        manager.setSticky( true );
        // disable msm, shutdown our server and our client
        manager.setEnabled( false );
        _memcached.shutdown();
        _daemon.stop();

        checkSessionFunctionalityWithMsmDisabled();
    }

    /**
     * Test for issue #60 (Add possibility to disable msm at runtime): start msm disabled and afterwards enable
     */
    @Test( enabled = true )
    public void testStartMsmDisabled() throws InterruptedException, IOException, ExecutionException, TimeoutException, LifecycleException, HttpException {

        // shutdown our server and our client
        _memcached.shutdown();
        _daemon.stop();

        // start a new tomcat with msm initially disabled
        _tomcat1.stop();
        Thread.sleep( 500 );
        final String memcachedNodes = _memcachedNodeId + ":localhost:" + _memcachedPort;
        _tomcat1 = getTestUtils().createCatalina( _portTomcat1, memcachedNodes, "app1" );
        final SessionManager manager = getManager( _tomcat1 );
        manager.setSticky( true );
        manager.setEnabled( false );
        _tomcat1.start();

        LOG.info( "Waiting, check logs to see if the client causes any 'Connection refused' logging..." );
        Thread.sleep( 1000 );

        // some basic tests for session functionality
        checkSessionFunctionalityWithMsmDisabled();

        // start memcached, client and reenable msm
        _daemon.start();
        _memcached = createMemcachedClient( memcachedNodes, new InetSocketAddress( "localhost", _memcachedPort ) );
        manager.setEnabled( true );
        // Wait a little bit, so that msm's memcached client can connect and is ready when test starts
        Thread.sleep( 100 );

        // memcached based stuff should work again
        final String sessionId1 = makeRequest( _httpClient, _portTomcat1, null );
        assertNotNull( sessionId1, "No session created." );
        assertNotNull( new SessionIdFormat().extractMemcachedId( sessionId1 ), "memcached node id missing with msm switched to enabled" );
        Thread.sleep( 50 );
        assertNotNull( _memcached.get( sessionId1 ), "Session not available in memcached." );

        waitForSessionExpiration( true );

        assertNull( _memcached.get( sessionId1 ), "Expired session still existing in memcached" );

    }
    
    abstract TestUtils getTestUtils();

    private void checkSessionFunctionalityWithMsmDisabled() throws IOException, HttpException, InterruptedException {
        assertTrue( getManager( _tomcat1 ).getMemcachedSessionService().isSticky() );
        final String sessionId1 = makeRequest( _httpClient, _portTomcat1, null );
        assertNotNull( sessionId1, "No session created." );
        assertNull( new SessionIdFormat().extractMemcachedId( sessionId1 ), "Got a memcached node id, even with msm disabled." );
        waitForSessionExpiration( true );
        final String sessionId2 = makeRequest( _httpClient, _portTomcat1, sessionId1 );
        assertNotSame( sessionId2, sessionId1, "SessionId not changed." );
    }

    private void waitForSessionExpiration(final boolean sticky) throws InterruptedException {
        final SessionManager manager = getManager( _tomcat1 );
        assertEquals( manager.getMemcachedSessionService().isSticky(), sticky );
        final Container container = manager.getContainer();
        final long timeout = TimeUnit.SECONDS.toMillis(
                sticky ? container.getBackgroundProcessorDelay() + manager.getMaxInactiveInterval()
                       : 2 * manager.getMaxInactiveInterval() ) + 1000;
        Thread.sleep( timeout );
    }

}
