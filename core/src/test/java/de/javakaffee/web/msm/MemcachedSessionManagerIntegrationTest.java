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

import static de.javakaffee.web.msm.integration.TestUtils.assertDeepEquals;
import static de.javakaffee.web.msm.integration.TestUtils.createCatalina;
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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import net.spy.memcached.MemcachedClient;

import org.apache.catalina.Container;
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

/**
 * Integration test testing basic session manager functionality.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class MemcachedSessionManagerIntegrationTest {

    private static final Log LOG = LogFactory.getLog( MemcachedSessionManagerIntegrationTest.class );

    private MemCacheDaemon<?> _daemon;
    private MemcachedClient _memcached;

    private Embedded _tomcat1;

    private int _portTomcat1;

    private String _memcachedNodeId;

    private DefaultHttpClient _httpClient;

    private int _memcachedPort;

    @BeforeMethod
    public void setUp() throws Throwable {

        _portTomcat1 = 18888;

        _memcachedPort = 21211;

        final InetSocketAddress address = new InetSocketAddress( "localhost", _memcachedPort );
        _daemon = createDaemon( address );
        _daemon.start();

        try {
            _memcachedNodeId = "n1";
            final String memcachedNodes = _memcachedNodeId + ":localhost:" + _memcachedPort;
            _tomcat1 = createCatalina( _portTomcat1, memcachedNodes, "app1" );
            _tomcat1.start();
        } catch ( final Throwable e ) {
            LOG.error( "could not start tomcat.", e );
            throw e;
        }

        _memcached =
                new MemcachedClient( new SuffixLocatorConnectionFactory( NodeIdResolver.node(
                        _memcachedNodeId, address ).build(), new SessionIdFormat(), Statistics.create() ),
                        Arrays.asList( new InetSocketAddress( "localhost", _memcachedPort ) ) );

        // Wait a little bit, so that the memcached client can connect and is ready when test starts
        Thread.sleep( 100 );

        _httpClient = new DefaultHttpClient();
    }

    @AfterMethod
    public void tearDown() throws Exception {
        _memcached.shutdown();
        _tomcat1.stop();
        _httpClient.getConnectionManager().shutdown();
        _daemon.stop();
    }

    @Test
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

    @Test
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
    @Test
    public void testInvalidSessionId() throws IOException, InterruptedException, HttpException {
        final String sessionId1 = makeRequest( _httpClient, _portTomcat1, "12345" );
        assertNotNull( sessionId1, "No session created." );
        assertTrue( sessionId1.indexOf( '-' ) > -1, "Invalid session id format" );
    }

    @Test
    public void testSessionAvailableInMemcached() throws IOException, InterruptedException, HttpException {
        final String sessionId1 = makeRequest( _httpClient, _portTomcat1, null );
        assertNotNull( sessionId1, "No session created." );
        Thread.sleep( 50 );
        assertNotNull( _memcached.get( sessionId1 ), "Session not available in memcached." );
    }

    @Test
    public void testExpiredSessionRemovedFromMemcached() throws IOException, InterruptedException, HttpException {
        final String sessionId1 = makeRequest( _httpClient, _portTomcat1, null );
        assertNotNull( sessionId1, "No session created." );

        /*
         * wait some time, as processExpires runs every second and the
         * maxInactiveTime is set to 1 sec...
         */
        final MemcachedBackupSessionManager manager = getManager( _tomcat1 );
        final Container container = manager.getContainer();
        final long timeout = TimeUnit.SECONDS.toMillis( container.getBackgroundProcessorDelay() + manager.getMaxInactiveInterval() ) + 100;
        Thread.sleep( timeout );

        assertNull( _memcached.get( sessionId1 ), "Expired sesion still existing in memcached" );
    }

    @Test
    public void testInvalidSessionNotFound() throws IOException, InterruptedException, HttpException {
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
     * Tests, that relocated sessions are no longer available under the
     * old/former session id.
     *
     * @throws IOException
     * @throws InterruptedException
     * @throws HttpException
     */
    @Test
    public void testRelocateSession() throws IOException, InterruptedException, HttpException {
        // FIXME implementation does not match docs
        final String sessionId1 = makeRequest( _httpClient, _portTomcat1, null );
        assertNotNull( sessionId1, "No session created." );

        /*
         * wait some time, as processExpires runs every second and the
         * maxInactiveTime is set to 1 sec...
         */
        Thread.sleep( 2100 );

        final String sessionId2 = makeRequest( _httpClient, _portTomcat1, sessionId1 );
        assertNotSame( sessionId1, sessionId2, "Expired session returned" );
    }

    /**
     * Tests, that for a session that was not sent to memcached (because it's attributes
     * were not modified), the expiration is updated so that they don't expire in memcached
     * before they expire in tomcat.
     *
     * @throws Exception if something goes wrong with the http communication with tomcat
     */
    @Test
    public void testExpirationOfSessionsInMemcachedIfBackupWasSkippedSimple() throws Exception {

        final MemcachedBackupSessionManager manager = getManager( _tomcat1 );
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
        makeRequest( _httpClient, _portTomcat1, sessionId1 );

        /* after another 3 seconds check that the session is still alive in memcached,
         * this would have been expired without an updated expiration
         */
        Thread.sleep( TimeUnit.SECONDS.toMillis( delay * 3 ) );
        assertNotNull( _memcached.get( sessionId1 ), "Session expired in memcached." );

        /* after another >1 second (4 seconds since the last request)
         * the session must be expired in memcached
         */
        Thread.sleep( TimeUnit.SECONDS.toMillis( delay ) + 1000 ); // +1000 just to be sure that we're >4 secs
        assertNull( _memcached.get( sessionId1 ), "Session not expired in memcached." );

    }

    /**
     * Tests update of session expiration in memcached (like {@link #testExpirationOfSessionsInMemcachedIfBackupWasSkippedSimple()})
     * but for the scenario where many readonly requests occur: in this case, we cannot just use
     * <em>maxInactiveInterval - secondsSinceLastBackup</em> (in {@link MemcachedBackupSessionManager#updateExpirationInMemcached})
     * to determine if an expiration update is required, but we must use the last expiration time sent to memcached.
     *
     * @throws Exception if something goes wrong with the http communication with tomcat
     */
    @Test
    public void testExpirationOfSessionsInMemcachedIfBackupWasSkippedManyReadonlyRequests() throws Exception {

        final MemcachedBackupSessionManager manager = getManager( _tomcat1 );
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
        makeRequest( _httpClient, _portTomcat1, sessionId1 );

        /* after another 3 seconds make another request without changing the session
         */
        Thread.sleep( TimeUnit.SECONDS.toMillis( delay * 3 ) );
        makeRequest( _httpClient, _portTomcat1, sessionId1 );

        /* after another nearly 4 seconds check that the session is still alive in memcached,
         * this would have been expired without an updated expiration
         */
        Thread.sleep( TimeUnit.SECONDS.toMillis( delay * 4 ) - 500 );
        assertNotNull( _memcached.get( sessionId1 ), "Session expired in memcached." );

        /* after another second (more than 4 seconds since the last request)
         * the session must be expired in memcached
         */
        Thread.sleep( TimeUnit.SECONDS.toMillis( delay ) + 500 );
        assertNull( _memcached.get( sessionId1 ), "Session not expired in memcached." );

    }

    /**
     * Test that a session that has been serialized with the old serialization
     * format (the complete session was serialized by one serialization strategy)
     * can be loaded from memcached.
     * @throws ExecutionException
     * @throws InterruptedException
     */
    @Test
    public void testLoadFromMemcachedOldSessionSerializationFormat() throws InterruptedException, ExecutionException {
        final MemcachedBackupSessionManager manager = getManager( _tomcat1 );
        final Session session = manager.createSession( null );
        final SessionTranscoder oldSessionTranscoder = manager.getTranscoderFactory().createSessionTranscoder( manager );
        final Future<Boolean> future = _memcached.set( session.getId(), session.getMaxInactiveInterval(), session, oldSessionTranscoder );
        assertTrue( future.get() );
        final Session loadedFromMemcached = manager.loadFromMemcached( session.getId() );
        assertNotNull( loadedFromMemcached );
        assertDeepEquals( session, loadedFromMemcached );
    }

}
