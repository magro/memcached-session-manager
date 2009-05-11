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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;

import net.spy.memcached.MemcachedClient;

import org.apache.catalina.startup.Embedded;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
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
 * Integration test testing memcached failover.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class MemcachedFailoverIntegrationTest {
    
    private static final Log LOG = LogFactory
            .getLog( MemcachedFailoverIntegrationTest.class );

    private MemCacheDaemon _daemon1;
    private MemCacheDaemon _daemon2;
    private MemcachedClient _memcached;
    
    private Embedded _tomcat1;

    private int _portTomcat1;

    private SimpleHttpConnectionManager _connectionManager;

    private HttpClient _httpClient;

    private String _nodeId1;

    private String _nodeId2;

    @Before
    public void setUp() throws Throwable {

        _portTomcat1 = 8888;

        final InetSocketAddress address1 = new InetSocketAddress( "localhost", 21211 );
        _daemon1 = createDaemon( address1 );
        _daemon1.start();
        
        final InetSocketAddress address2 = new InetSocketAddress( "localhost", 21212 );
        _daemon2 = createDaemon( address2 );
        _daemon2.start();
        
        _nodeId1 = "n1";
        _nodeId2 = "n2";
        try {
            final String memcachedNodes = toString( _nodeId1, address1 ) + " " + toString( _nodeId2, address2 );
            _tomcat1 = createCatalina( _portTomcat1, 10, memcachedNodes );
            _tomcat1.start();
        } catch( Throwable e ) {
            LOG.error( "could not start tomcat.", e );
            throw e;
        }
        
        _memcached = new MemcachedClient(
                new SuffixLocatorConnectionFactory( _tomcat1.getContainer().getManager(),
                        NodeIdResolver.node( _nodeId1, address1 ).node( _nodeId2, address2 ).build(),
                        new SessionIdFormat() ),
                Arrays.asList( address1, address2 ) );
        
        _connectionManager = new SimpleHttpConnectionManager( true );
        _httpClient = new HttpClient( _connectionManager );
    }

    private String toString( final String nodeId, final InetSocketAddress address ) {
        return nodeId + ":" + address.getHostName() + ":" + address.getPort();
    }

    @After
    public void tearDown() throws Exception {
        if ( _daemon1.isRunning() ) _daemon1.stop();
        if ( _daemon2.isRunning() ) _daemon2.stop();
        _tomcat1.stop();
        _connectionManager.shutdown();
    }
    
    /**
     * Tests, that relocated sessions are no longer available under the old/former
     * session id.
     * @throws IOException 
     * @throws HttpException 
     * 
     * @throws IOException 
     * @throws InterruptedException 
     */
    @Test
    public void testRelocateSession() throws HttpException, IOException {
        final String sid1 = makeRequest( _httpClient, _portTomcat1, null );
        assertNotNull( "No session created.", sid1 );
        final String firstNode = sid1.substring( sid1.lastIndexOf( '-' ) + 1 );
        assertNotNull( "No node id encoded in session id.", sid1 );
        
        /* shutdown appropriate memcached node
         */
        final boolean node1 = firstNode.equals( _nodeId1 );
        if ( node1 ) {
            _daemon1.stop();
        }
        else {
            _daemon2.stop();
        }

        final String sid2 = makeRequest( _httpClient, _portTomcat1, sid1 );
        final String secondNode = sid2.substring( sid2.lastIndexOf( '-' ) + 1 );
        final String expectedNode = node1 ? _nodeId2 : _nodeId1;

        assertEquals( "Unexpected nodeId.", expectedNode, secondNode );
        
        assertEquals( "Unexpected sessionId, sid1: " + sid1 + ", sid2: " + sid2,
                sid1.substring( 0, sid1.indexOf( "-" ) + 1 ) + expectedNode,
                sid2 );
        
    }

}
