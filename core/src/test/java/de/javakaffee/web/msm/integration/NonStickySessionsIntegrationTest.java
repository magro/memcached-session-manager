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

import static de.javakaffee.web.msm.integration.TestUtils.createCatalina;
import static de.javakaffee.web.msm.integration.TestUtils.createDaemon;
import static de.javakaffee.web.msm.integration.TestUtils.get;
import static de.javakaffee.web.msm.integration.TestUtils.post;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.util.Arrays;

import net.spy.memcached.MemcachedClient;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Embedded;
import org.apache.http.HttpException;
import org.apache.http.impl.client.DefaultHttpClient;
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
import de.javakaffee.web.msm.integration.TestUtils.LoginType;
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

        _httpClient = new DefaultHttpClient();
    }

    private Embedded startTomcat( final int port ) throws MalformedURLException, UnknownHostException, LifecycleException {
        return startTomcat( port, null );
    }

    private Embedded startTomcat( final int port, final LoginType loginType ) throws MalformedURLException, UnknownHostException, LifecycleException {
        final Embedded tomcat = createCatalina( port, MEMCACHED_NODES, loginType );
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
    }

    /**
     * Tests that non-sticky sessions are not leading to stale data - that sessions are removed from
     * tomcat when the request is finished.
     */
    @Test( enabled = true )
    public void testTomcatFailover() throws IOException, InterruptedException, HttpException {

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

}
