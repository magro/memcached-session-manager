/*
1 * Copyright 2009 Martin Grotzke
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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;

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
 * Integration test testing basic session manager functionality.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class MemcachedSessionManagerIntegrationTest {
    
    private static final Log LOG = LogFactory
            .getLog( MemcachedSessionManagerIntegrationTest.class );

    private MemCacheDaemon _daemon;
    private MemcachedClient _memcached;
    
    private Embedded _tomcat1;

    private int _portTomcat1;

    private String _memcachedNodeId;

    private SimpleHttpConnectionManager _connectionManager;

    private HttpClient _httpClient;

    @Before
    public void setUp() throws Throwable {

        _portTomcat1 = 18888;
        
        final int port = 21211;
        
        final InetSocketAddress address = new InetSocketAddress( "localhost", port );
        _daemon = createDaemon( address );
        _daemon.start(); 
        
        try {
            _memcachedNodeId = "n1";
            final String memcachedNodes = _memcachedNodeId + ":localhost:" + port;
            _tomcat1 = createCatalina( _portTomcat1, memcachedNodes, "app1" );
            _tomcat1.start();
        } catch( Throwable e ) {
            LOG.error( "could not start tomcat.", e );
            throw e;
        }
        
        _memcached = new MemcachedClient(
                new SuffixLocatorConnectionFactory( _tomcat1.getContainer().getManager(),
                        NodeIdResolver.node( _memcachedNodeId, address ).build(),
                        new SessionIdFormat() ),
                Arrays.asList( new InetSocketAddress( "localhost", port ) ) );
        
        _connectionManager = new SimpleHttpConnectionManager( true );
        _httpClient = new HttpClient( _connectionManager );
    }

    @After
    public void tearDown() throws Exception {
        _daemon.stop();
        _tomcat1.stop();
        _connectionManager.shutdown();
    }
    
    @Test
    public void testConfiguredMemcachedNodeId() throws IOException, InterruptedException {
        final String sessionId1 = makeRequest( _httpClient, _portTomcat1, null );
        assertNotNull( "No session created.", sessionId1 );
        /* test that we have the configured memcachedNodeId in the sessionId,
         * the session id looks like "<sid>-<memcachedId>[.<jvmRoute>]"
         */
        final String nodeId = sessionId1.substring( sessionId1.indexOf( '-' ) + 1, sessionId1.indexOf( '.' ) );
        assertEquals( "Invalid memcached node id", _memcachedNodeId, nodeId );
    }
    
    @Test
    public void testSessionIdJvmRouteCompatibility() throws IOException, InterruptedException {
        final String sessionId1 = makeRequest( _httpClient, _portTomcat1, null );
        assertNotNull( "No session created.", sessionId1 );
        assertTrue( "Invalid session format, must be <sid>-<memcachedId>[.<jvmRoute>].", sessionId1.matches( "[^-.]+-[^.]+(\\.[\\w]+)?" ) );
    }
    
    /**
     * Tests, that session ids with an invalid format (not containing the memcached id)
     * do not cause issues. Instead, we want to retrieve a new session id.
     * 
     * @throws IOException 
     * @throws InterruptedException 
     */
    @Test
    public void testInvalidSessionId() throws IOException, InterruptedException {
        final String sessionId1 = makeRequest( _httpClient, _portTomcat1, "12345" );
        assertNotNull( "No session created.", sessionId1 );
        assertTrue( "Invalid session id format", sessionId1.indexOf( '-' ) > -1 );
    }
    
    @Test
    public void testSessionAvailableInMemcached() throws IOException, InterruptedException {
        final String sessionId1 = makeRequest( _httpClient, _portTomcat1, null );
        assertNotNull( "No session created.", sessionId1 );
        Thread.sleep( 50 );
        assertNotNull( "Session not available in memcached.", _memcached.get( sessionId1 ) );
    }
    
    @Test
    public void testExpiredSessionRemovedFromMemcached() throws IOException, InterruptedException {
        final String sessionId1 = makeRequest( _httpClient, _portTomcat1, null );
        assertNotNull( "No session created.", sessionId1 );
        
        /* wait some time, as processExpires runs every second and the maxInactiveTime is set to 1 sec...
         */
        Thread.sleep( 2100 );
        
        assertNull( "Expired sesion still existing in memcached", _memcached.get( sessionId1 ) );
    }
    
    @Test
    public void testInvalidSessionNotFound() throws IOException, InterruptedException {
        final String sessionId1 = makeRequest( _httpClient, _portTomcat1, null );
        assertNotNull( "No session created.", sessionId1 );
        
        /* wait some time, as processExpires runs every second and the maxInactiveTime is set to 1 sec...
         */
        Thread.sleep( 2100 );

        final String sessionId2 = makeRequest( _httpClient, _portTomcat1, sessionId1 );
        assertNotSame( "Expired session returned", sessionId1, sessionId2 );
    }
    
    /**
     * Tests, that relocated sessions are no longer available under the old/former
     * session id.
     * 
     * @throws IOException 
     * @throws InterruptedException 
     */
    @Test
    public void testRelocateSession() throws IOException, InterruptedException {
        final String sessionId1 = makeRequest( _httpClient, _portTomcat1, null );
        assertNotNull( "No session created.", sessionId1 );
        
        /* wait some time, as processExpires runs every second and the maxInactiveTime is set to 1 sec...
         */
        Thread.sleep( 2100 );

        final String sessionId2 = makeRequest( _httpClient, _portTomcat1, sessionId1 );
        assertNotSame( "Expired session returned", sessionId1, sessionId2 );
    }

}
