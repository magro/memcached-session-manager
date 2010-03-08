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

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import net.spy.memcached.MemcachedClient;

import org.apache.catalina.Session;
import org.apache.catalina.startup.Embedded;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.thimbleware.jmemcached.MemCacheDaemon;

import de.javakaffee.web.msm.serializer.xstream.XStreamTranscoderFactory;

/**
 * Integration test testing session manager functionality specific for this
 * serialization strategy.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class XStreamMemcachedSessionManagerIntegrationTest {

    private static final Log LOG = LogFactory.getLog( XStreamMemcachedSessionManagerIntegrationTest.class );

    private MemCacheDaemon<?> _daemon;
    private MemcachedClient _memcached;

    private Embedded _tomcat1;

    private int _portTomcat1;

    private String _memcachedNodeId;

    private DefaultHttpClient _httpClient;

    private int _memcachedPort;

    @BeforeClass
    public void setUp() throws Throwable {

        _portTomcat1 = 18888;

        _memcachedPort = 21211;

        final InetSocketAddress address = new InetSocketAddress( "localhost", _memcachedPort );
        _daemon = createDaemon( address );
        _daemon.start();

        try {
            _memcachedNodeId = "n1";
            final String memcachedNodes = _memcachedNodeId + ":localhost:" + _memcachedPort;
            _tomcat1 = createCatalina( _portTomcat1, memcachedNodes, "app1", XStreamTranscoderFactory.class.getName() );
            _tomcat1.start();
        } catch ( final Throwable e ) {
            LOG.error( "could not start tomcat.", e );
            throw e;
        }

        _memcached =
                new MemcachedClient( new SuffixLocatorConnectionFactory( NodeIdResolver.node(
                        _memcachedNodeId, address ).build(), new SessionIdFormat() ),
                        Arrays.asList( new InetSocketAddress( "localhost", _memcachedPort ) ) );

        // Wait a little bit, so that the memcached client can connect and is ready when test starts
        Thread.sleep( 100 );

        _httpClient = new DefaultHttpClient();
    }

    @AfterClass
    public void tearDown() throws Exception {
        _memcached.shutdown();
        _tomcat1.stop();
        _httpClient.getConnectionManager().shutdown();
        _daemon.stop();
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
        Future<Boolean> future = _memcached.set( session.getId(), session.getMaxInactiveInterval(), session, oldSessionTranscoder );
        Assert.assertTrue( future.get() );
        final Session loadedFromMemcached = manager.loadFromMemcached( session.getId() );
        Assert.assertNotNull( loadedFromMemcached );
        assertDeepEquals( session, loadedFromMemcached );
    }

}
