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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.apache.catalina.Session;
import org.apache.catalina.session.ManagerBase;
import org.apache.catalina.startup.Embedded;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.SimpleHttpConnectionManager;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.thimbleware.jmemcached.MemCacheDaemon;

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
    private MemCacheDaemon _daemon3;
    
    private Embedded _tomcat1;

    private int _portTomcat1;

    private SimpleHttpConnectionManager _connectionManager;

    private HttpClient _httpClient;

    private String _nodeId1;
    private String _nodeId2;
    private String _nodeId3;

    private InetSocketAddress _address1;

    private InetSocketAddress _address2;

    private InetSocketAddress _address3;

    @Before
    public void setUp() throws Throwable {

        _portTomcat1 = 18888;

        _address1 = new InetSocketAddress( "localhost", 21211 );
        _daemon1 = createDaemon( _address1 );
        _daemon1.start();
        
        _address2 = new InetSocketAddress( "localhost", 21212 );
        _daemon2 = createDaemon( _address2 );
        _daemon2.start();
        
        _address3 = new InetSocketAddress( "localhost", 21213 );
        _daemon3 = createDaemon( _address3 );
        _daemon3.start();
        
        _nodeId1 = "n1";
        _nodeId2 = "n2";
        _nodeId3 = "n3";

        try {
            final String memcachedNodes = toString( _nodeId1, _address1 ) +
                " " + toString( _nodeId2, _address2 ) +
                " " + toString( _nodeId3, _address3 );
            _tomcat1 = createCatalina( _portTomcat1, 10, memcachedNodes );
            _tomcat1.start();
        } catch( Throwable e ) {
            LOG.error( "could not start tomcat.", e );
            throw e;
        }
        
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
        if ( _daemon3.isRunning() ) _daemon3.stop();
        _tomcat1.stop();
        _connectionManager.shutdown();
    }
    
    /**
     * Tests, that on a memcached failover sessions are relocated to another node and that
     * the session id reflects this.
     * 
     * This test asumes/knows, that the "next" node is selected in the case of a node failure.
     * @throws IOException 
     * @throws InterruptedException 
     * @throws InterruptedException 
     * @throws Throwable 
     */
    @SuppressWarnings("unchecked")
    @Test
    public void testRelocateSession() throws Throwable {
        
        final String sid1 = makeRequest( _httpClient, _portTomcat1, null );
        assertNotNull( "No session created.", sid1 );
        final String firstNode = sid1.substring( sid1.lastIndexOf( '-' ) + 1 );
        assertNotNull( "No node id encoded in session id.", firstNode );
        
        Thread.sleep( 50 );

        final FailoverInfo info = getFailoverInfo( firstNode );
        info.activeNode.stop();
        
        Thread.sleep( 50 );

        final String sid2 = makeRequest( _httpClient, _portTomcat1, sid1 );
        final String secondNode = sid2.substring( sid2.lastIndexOf( '-' ) + 1 );
        final String expectedNode = info.nextNodeId;

        assertEquals( "Unexpected nodeId.", expectedNode, secondNode );
        
        assertEquals( "Unexpected sessionId, sid1: " + sid1 + ", sid2: " + sid2,
                sid1.substring( 0, sid1.indexOf( "-" ) + 1 ) + expectedNode,
                sid2 );

        Session session = _tomcat1.getContainer().getManager().findSession( sid2 );
        assertFalse( "Some notes are set: " + toArray( session.getNoteNames() ), session.getNoteNames().hasNext() );
        
    }
    
    /**
     * Tests that multiple memcached nodes can fail and backup/relocation handles this.
     * 
     * This test asumes/knows, that the "next" node is selected in the case of a node failure.
     * 
     * @throws InterruptedException 
     * @throws Throwable 
     */
    @SuppressWarnings("unchecked")
    @Test
    public void testMultipleMemcachedNodesFailure() throws Throwable {
        
        final String sid1 = makeRequest( _httpClient, _portTomcat1, null );
        assertNotNull( "No session created.", sid1 );
        final String firstNode = sid1.substring( sid1.lastIndexOf( '-' ) + 1 );
        assertNotNull( "No node id encoded in session id.", firstNode );
        
        Thread.sleep( 50 );
        
        /* shutdown appropriate memcached node
         */
        final FailoverInfo info = getFailoverInfo( firstNode );
        info.activeNode.stop();
        info.nextNode.stop();
        
        Thread.sleep( 50 );

        final String sid2 = makeRequest( _httpClient, _portTomcat1, sid1 );
        final String secondNode = sid2.substring( sid2.lastIndexOf( '-' ) + 1 );
        final String expectedNode = info.failoverNodeId;

        assertEquals( "Unexpected nodeId.", expectedNode, secondNode );
        
        assertEquals( "Unexpected sessionId, sid1: " + sid1 + ", sid2: " + sid2,
                sid1.substring( 0, sid1.indexOf( "-" ) + 1 ) + expectedNode,
                sid2 );

        Session session = _tomcat1.getContainer().getManager().findSession( sid2 );
        assertFalse( "Some notes are set: " + toArray( session.getNoteNames() ), session.getNoteNames().hasNext() );
        
    }
    
    private Set<String> toArray( Iterator<String> noteNames ) {
        final Set<String> result = new HashSet<String>();
        while ( noteNames.hasNext() ) {
            result.add( noteNames.next() );
        }
        return result;
    }

    /**
     * Tests that the previous session id is kept when all memcached nodes fail.
     * 
     * @throws Throwable 
     */
    @SuppressWarnings("unchecked")
    @Test
    public void testAllMemcachedNodesFailure() throws Throwable {
        
        final String sid1 = makeRequest( _httpClient, _portTomcat1, null );
        assertNotNull( "No session created.", sid1 );
        
        /* shutdown all memcached nodes
         */
        _daemon1.stop();
        _daemon2.stop();
        _daemon3.stop();
        
        final String sid2 = makeRequest( _httpClient, _portTomcat1, sid1 );

        assertEquals( "SessionId changed.", sid1, sid2 );
        
        assertNotNull( "Session not existing.", getSessions().get( sid2 ) );

        Session session = _tomcat1.getContainer().getManager().findSession( sid2 );
        assertFalse( "Some notes are set: " + toArray( session.getNoteNames() ), session.getNoteNames().hasNext() );
        
    }

    private Map<String, Session> getSessions() throws NoSuchFieldException,
            IllegalAccessException {
        final Field field = ManagerBase.class.getDeclaredField( "sessions" );
        field.setAccessible( true );
        @SuppressWarnings("unchecked")
        final Map<String,Session> sessions = (Map<String, Session>)field.get( _tomcat1.getContainer().getManager() );
        return sessions;
    }
    
    /* plain stupid
     */
    private FailoverInfo getFailoverInfo( String nodeId ) {
        if ( _nodeId1.equals( nodeId ) )
            return new FailoverInfo( _daemon1, _daemon2, _nodeId2, _nodeId3 );
        else if ( _nodeId2.equals( nodeId ) )
            return new FailoverInfo( _daemon2, _daemon3, _nodeId3, _nodeId1 );
        else if ( _nodeId3.equals( nodeId ) )
            return new FailoverInfo( _daemon3, _daemon1, _nodeId1, _nodeId2 );
        throw new IllegalArgumentException( "Node " + nodeId + " is not a valid node id." );
    }

    static class FailoverInfo {
        MemCacheDaemon activeNode;
        MemCacheDaemon nextNode;
        String nextNodeId;
        String failoverNodeId;
        public FailoverInfo(MemCacheDaemon first,
                MemCacheDaemon second,
                String nextNodeId,
                String failoverNodeId) {
            this.activeNode = first;
            this.nextNode = second;
            this.nextNodeId = nextNodeId;
            this.failoverNodeId = failoverNodeId;
        }
    }

}
