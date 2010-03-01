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
package de.javakaffee.web.msm.integration;

import static de.javakaffee.web.msm.integration.TestUtils.createCatalina;
import static de.javakaffee.web.msm.integration.TestUtils.createDaemon;
import static de.javakaffee.web.msm.integration.TestUtils.get;
import static de.javakaffee.web.msm.integration.TestUtils.post;
import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;

import junit.framework.Assert;
import net.spy.memcached.MemcachedClient;

import org.apache.catalina.startup.Embedded;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.SimpleHttpConnectionManager;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.thimbleware.jmemcached.MemCacheDaemon;

import de.javakaffee.web.msm.NodeIdResolver;
import de.javakaffee.web.msm.SessionIdFormat;
import de.javakaffee.web.msm.SuffixLocatorConnectionFactory;
import de.javakaffee.web.msm.integration.TestUtils.Response;

/**
 * Integration test testing tomcat failover (tomcats failing).
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class TomcatFailoverIntegrationTest {

    private static final Log LOG = LogFactory.getLog( TomcatFailoverIntegrationTest.class );

    private MemCacheDaemon<?> _daemon;
    private MemcachedClient _client;

    private Embedded _tomcat1;
    private Embedded _tomcat2;

    private int _portTomcat1;
    private int _portTomcat2;

    private String _nodeId;

    @Before
    public void setUp() throws Throwable {

        _portTomcat1 = 18888;
        _portTomcat2 = 18889;

        final int port = 21211;

        final InetSocketAddress address = new InetSocketAddress( "localhost", port );
        _daemon = createDaemon( address );
        _daemon.start();

        _nodeId = "n1";
        try {
            final String memcachedNodes = _nodeId + ":localhost:" + port;
            _tomcat1 = createCatalina( _portTomcat1, 2, memcachedNodes );
            _tomcat1.start();

            _tomcat2 = createCatalina( _portTomcat2, memcachedNodes );
            _tomcat2.start();
        } catch ( final Throwable e ) {
            LOG.error( "could not start tomcat.", e );
            throw e;
        }

        _client =
                new MemcachedClient( new SuffixLocatorConnectionFactory( NodeIdResolver.node(
                        _nodeId, address ).build(), new SessionIdFormat() ),
                        Arrays.asList( address ) );
    }

    @After
    public void tearDown() throws Exception {
        _client.shutdown();
        _daemon.stop();
        _tomcat1.stop();
        _tomcat2.stop();
    }

    /**
     * Tests that when two tomcats are running and one tomcat fails the other
     * tomcat can take over the session.
     *
     * @throws IOException
     * @throws InterruptedException
     */
    @Test
    public void testTomcatFailover() throws IOException, InterruptedException {
        final SimpleHttpConnectionManager connectionManager = new SimpleHttpConnectionManager( true );
        try {
            final HttpClient client = new HttpClient( connectionManager );

            final String key = "foo";
            final String value = "bar";
            final String sessionId1 = post( client, _portTomcat1, null, key, value );

            final Object session = _client.get( sessionId1 );
            Assert.assertNotNull( "Session not found in memcached: " + sessionId1, session );

            final Response response = get( client, _portTomcat2, sessionId1 );
            final String sessionId2 = response.getSessionId();

            Assert.assertEquals( sessionId1, sessionId2 );

            /* check session attributes could be read
             */
            final String actualValue = response.get( key );
            assertEquals( value, actualValue );

            Thread.sleep( 10 );

        } finally {
            connectionManager.shutdown();
        }

    }

    /**
     * Tests that the session that was taken over by another tomcat is not
     * sent again by this tomcat if it was not modified.
     *
     * @throws IOException
     * @throws InterruptedException
     */
    @Test
    public void testLoadedSessionOnlySentIfModified() throws IOException, InterruptedException {
        final SimpleHttpConnectionManager connectionManager = new SimpleHttpConnectionManager( true );
        try {
            final HttpClient client = new HttpClient( connectionManager );

            /* create a session on tomcat1
             */
            final String key = "foo";
            final String value = "bar";
            final String sessionId1 = post( client, _portTomcat1, null, key, value );
            Assert.assertEquals( 1, _daemon.getCache().getSetCmds() );

            /* request the session on tomcat2
             */
            final Response response = get( client, _portTomcat2, sessionId1 );
            Assert.assertEquals( sessionId1, response.getSessionId() );
            Assert.assertEquals( 1, _daemon.getCache().getSetCmds() );

            /* post key/value already stored in the session again (on tomcat2)
             */
            post( client, _portTomcat2, sessionId1, key, value );
            Assert.assertEquals( 1, _daemon.getCache().getSetCmds() );

            /* post another key/value pair (on tomcat2)
             */
            post( client, _portTomcat2, sessionId1, "bar", "baz" );
            Assert.assertEquals( 2, _daemon.getCache().getSetCmds() );

            Thread.sleep( 10 );

        } finally {
            connectionManager.shutdown();
        }

    }

}
