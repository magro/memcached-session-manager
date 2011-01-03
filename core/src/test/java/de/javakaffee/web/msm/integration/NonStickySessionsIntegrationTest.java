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

import static de.javakaffee.web.msm.integration.TestServlet.PARAM_MILLIS;
import static de.javakaffee.web.msm.integration.TestServlet.PATH_POST_WAIT;
import static de.javakaffee.web.msm.integration.TestUtils.*;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import net.spy.memcached.MemcachedClient;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Embedded;
import org.apache.http.HttpException;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.thimbleware.jmemcached.MemCacheDaemon;

import de.javakaffee.web.msm.NodeIdResolver;
import de.javakaffee.web.msm.SessionIdFormat;
import de.javakaffee.web.msm.Statistics;
import de.javakaffee.web.msm.SuffixLocatorConnectionFactory;
import de.javakaffee.web.msm.integration.TestUtils.Response;

/**
 * Integration test testing non-sticky sessions.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class NonStickySessionsIntegrationTest {

    private static final Log LOG = LogFactory.getLog( NonStickySessionsIntegrationTest.class );

    private MemCacheDaemon<?> _daemon;
    private MemcachedClient _client;

    private Embedded _tomcat1;
    private Embedded _tomcat2;

    private static final int TC_PORT_1 = 18888;
    private static final int TC_PORT_2 = 18889;

    private static final String NODE_ID = "n1";
    private static final int MEMCACHED_PORT = 21211;
    private static final String MEMCACHED_NODES = NODE_ID + ":localhost:" + MEMCACHED_PORT;

    private DefaultHttpClient _httpClient;
    private ExecutorService _executor;

    @BeforeMethod
    public void setUp() throws Throwable {

        final InetSocketAddress address = new InetSocketAddress( "localhost", MEMCACHED_PORT );
        _daemon = createDaemon( address );
        _daemon.start();

        try {
            _tomcat1 = startTomcat( TC_PORT_1 );
            _tomcat2 = startTomcat( TC_PORT_2 );
        } catch ( final Throwable e ) {
            LOG.error( "could not start tomcat.", e );
            throw e;
        }

        _client =
                new MemcachedClient( new SuffixLocatorConnectionFactory( NodeIdResolver.node(
                        NODE_ID, address ).build(), new SessionIdFormat(), Statistics.create() ),
                        Arrays.asList( address ) );

        final SchemeRegistry schemeRegistry = new SchemeRegistry();
        schemeRegistry.register(
                new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
        _httpClient = new DefaultHttpClient(new ThreadSafeClientConnManager(schemeRegistry));

        _executor = Executors.newCachedThreadPool();
    }

    private Embedded startTomcat( final int port ) throws MalformedURLException, UnknownHostException, LifecycleException {
        final Embedded tomcat = createCatalina( port, 5, MEMCACHED_NODES );
        tomcat.start();
        return tomcat;
    }

    @AfterMethod
    public void tearDown() throws Exception {
        _client.shutdown();
        _daemon.stop();
        _tomcat1.stop();
        _tomcat2.stop();
        _httpClient.getConnectionManager().shutdown();
        _executor.shutdownNow();
    }

    /**
     * Tests that non-sticky sessions are not leading to stale data - that sessions are removed from
     * tomcat when the request is finished.
     */
    @Test( enabled = true )
    public void testNoStaleSessionsWithNonStickySessions() throws IOException, InterruptedException, HttpException {

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

    /**
     * Tests that non-sticky sessions are not leading to stale data - that sessions are removed from
     * tomcat when the request is finished.
     */
    @Test( enabled = true )
    public void testParallelRequestsDontCauseDataLoss() throws IOException, InterruptedException, HttpException, ExecutionException {

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
                return post( _httpClient, TC_PORT_1, PATH_POST_WAIT, sessionId, asMap( PARAM_MILLIS, "500",
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

}
