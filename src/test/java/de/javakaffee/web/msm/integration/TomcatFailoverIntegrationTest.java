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
import static de.javakaffee.web.msm.integration.TestUtils.makeRequest;

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

/**
 * Integration test testing tomcat failover (tomcats failing).
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class TomcatFailoverIntegrationTest {
    
    private static final Log LOG = LogFactory
            .getLog( TomcatFailoverIntegrationTest.class );

    private MemCacheDaemon _daemon;
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
        } catch( Throwable e ) {
            LOG.error( "could not start tomcat.", e );
            throw e;
        }
        
        _client = new MemcachedClient(
                new SuffixLocatorConnectionFactory( _tomcat1.getContainer().getManager(),
                        NodeIdResolver.node( _nodeId, address ).build(),
                        new SessionIdFormat() ),
                Arrays.asList( address ) );
    }

    @After
    public void tearDown() throws Exception {
        _daemon.stop();
        _tomcat1.stop();
        _tomcat2.stop();
    }
    
    @Test
    public void testConnectDaemon() throws IOException, InterruptedException {
        final Object value = "bar";
        _client.set( "foo-" + _nodeId, 3600, value );
        Assert.assertEquals( value, _client.get( "foo-" + _nodeId ) );
    }
    
    /**
     * Tests that when two tomcats are running and one tomcat fails the other tomcat can
     * take over the session.
     * @throws IOException
     * @throws InterruptedException
     */
    @Test
    public void testTomcatFailover() throws IOException, InterruptedException {
        final SimpleHttpConnectionManager connectionManager = new SimpleHttpConnectionManager( true );
        try {
            final HttpClient client = new HttpClient( connectionManager );
    
            final String sessionId1 = makeRequest( client, _portTomcat1, null );
            
            Thread.sleep( 10 );
            
            final Object session = _client.get( sessionId1 );
            Assert.assertNotNull( session );
            
            final String sessionId2 = makeRequest( client, _portTomcat2, sessionId1 );
            
            Assert.assertEquals( sessionId1, sessionId2 );
            
            Thread.sleep( 10 );
            
        } finally {
            connectionManager.shutdown();
        }
        
    }


}
