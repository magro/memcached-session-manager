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

import static de.javakaffee.web.msm.integration.TestUtils.*;
import static org.testng.Assert.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import de.javakaffee.web.msm.storage.MemcachedStorageClient;
import net.spy.memcached.MemcachedClient;

import org.apache.catalina.Session;
import org.apache.catalina.session.ManagerBase;
import org.apache.http.HttpException;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.thimbleware.jmemcached.CacheElement;
import com.thimbleware.jmemcached.MemCacheDaemon;

import de.javakaffee.web.msm.MemcachedSessionService;
import de.javakaffee.web.msm.integration.TestUtils.Response;
import de.javakaffee.web.msm.integration.TestUtils.SessionAffinityMode;

/**
 * Integration test testing memcached failover.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public abstract class MemcachedFailoverIntegrationTest {

    private static final Log LOG = LogFactory
            .getLog( MemcachedFailoverIntegrationTest.class );

    private MemCacheDaemon<? extends CacheElement> _daemon1;
    private MemCacheDaemon<? extends CacheElement> _daemon2;
    private MemCacheDaemon<? extends CacheElement> _daemon3;

    private TomcatBuilder<?> _tomcat1;

    private int _portTomcat1;

    private DefaultHttpClient _httpClient;

    private String _nodeId1;
    private String _nodeId2;
    private String _nodeId3;

    private InetSocketAddress _address1;

    private InetSocketAddress _address2;

    private InetSocketAddress _address3;

    @BeforeMethod
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
            _tomcat1 = getTestUtils().tomcatBuilder().port(_portTomcat1).sessionTimeout(10).memcachedNodes(memcachedNodes).sticky(true).buildAndStart();
        } catch( final Throwable e ) {
            LOG.error( "could not start tomcat.", e );
            throw e;
        }

        _httpClient = new DefaultHttpClient();
    }

    abstract TestUtils<?> getTestUtils();

    private String toString( final String nodeId, final InetSocketAddress address ) {
        return nodeId + ":" + address.getHostName() + ":" + address.getPort();
    }

    @AfterMethod
    public void tearDown() throws Exception {
        if ( _daemon1.isRunning() ) {
            _daemon1.stop();
        }
        if ( _daemon2.isRunning() ) {
            _daemon2.stop();
        }
        if ( _daemon3.isRunning() ) {
            _daemon3.stop();
        }
        _tomcat1.stop();
        _httpClient.getConnectionManager().shutdown();
    }

    /**
     * Tests, that on a memcached failover sessions are relocated to another node and that
     * the session id reflects this. The session must no longer be available under the old
     * session id.
     */
    @Test( enabled = true, dataProviderClass = TestUtils.class, dataProvider = STICKYNESS_PROVIDER )
    public void testRelocateSession( final SessionAffinityMode sessionAffinity ) throws Throwable {

        _tomcat1.getManager().setSticky( sessionAffinity.isSticky() );

        // we had a situation where no session was created, so let's take some break so that everything's up again
        Thread.sleep( 200 );

        final String sid1 = makeRequest( _httpClient, _portTomcat1, null );
        assertNotNull( sid1, "No session created." );
        final String firstNode = extractNodeId( sid1 );
        assertNotNull( firstNode, "No node id encoded in session id." );

        final FailoverInfo info = getFailoverInfo( firstNode );
        info.activeNode.stop();

        Thread.sleep( 50 );

        final String sid2 = makeRequest( _httpClient, _portTomcat1, sid1 );
        final String secondNode = extractNodeId( sid2 );

        assertNotSame( secondNode, firstNode, "First node again selected" );

        assertEquals(
                sid2,
                sid1.substring( 0, sid1.indexOf( "-" ) + 1 ) + secondNode,
                "Unexpected sessionId, sid1: " + sid1 + ", sid2: " + sid2 );

        // we must get the same session back
        assertEquals( makeRequest( _httpClient, _portTomcat1, sid2 ), sid2, "We should keep the sessionId." );
        assertNotNull( getFailoverInfo( secondNode ).activeNode.getCache().get( key( sid2 ) )[0], "The session should exist in memcached." );

        // some more checks in sticky mode
        if ( sessionAffinity.isSticky() ) {
            final Session session = _tomcat1.getManager().findSession( sid2 );
            assertNotNull( session, "Session not found by new id " + sid2 );
            assertFalse( session.getNoteNames().hasNext(), "Some notes are set: " + toArray( session.getNoteNames() ) );
        }

    }

    /**
     * Tests that multiple memcached nodes can fail and backup/relocation handles this.
     */
    @Test( enabled = true, dataProviderClass = TestUtils.class, dataProvider = STICKYNESS_PROVIDER )
    public void testMultipleMemcachedNodesFailure( final SessionAffinityMode sessionAffinity ) throws Throwable {

        _tomcat1.getManager().setSticky( sessionAffinity.isSticky() );

        // we had a situation where no session was created, so let's take some break so that everything's up again
        Thread.sleep( 200 );

        final String paramKey = "foo";
        final String paramValue = "bar";
        final String sid1 = post( _httpClient, _portTomcat1, null, paramKey, paramValue ).getResponseSessionId();
        assertNotNull( sid1, "No session created." );
        final String firstNode = extractNodeId( sid1 );
        assertNotNull( firstNode, "No node id encoded in session id." );

        /* shutdown active and another memcached node
         */
        final FailoverInfo info = getFailoverInfo( firstNode );
        info.activeNode.stop();
        final Map.Entry<String, MemCacheDaemon<?>> otherNodeWithId = info.previousNode();
        otherNodeWithId.getValue().stop();

        Thread.sleep( 100 );

        final String sid2 = get( _httpClient, _portTomcat1, sid1 ).getResponseSessionId();
        final String secondNode = extractNodeId( sid2 );
        LOG.debug( "Have secondNode " + secondNode );
        final String expectedNode = info.otherNodeExcept( otherNodeWithId.getKey() ).getKey();

        assertEquals( secondNode, expectedNode, "Unexpected nodeId: " + secondNode + "." );

        assertEquals(
                sid2,
                sid1.substring( 0, sid1.indexOf( "-" ) + 1 ) + expectedNode,
                "Unexpected sessionId, sid1: " + sid1 + ", sid2: " + sid2 );

        // we must get the same session back
        final Response response2 = get( _httpClient, _portTomcat1, sid2 );
        assertEquals( response2.getSessionId(), sid2, "We should keep the sessionId." );
        final MemCacheDaemon<?> activeNode = getFailoverInfo( secondNode ).activeNode;
        assertNotNull( activeNode.getCache().get( key( sid2 ) )[0], "The session should exist in memcached." );
        assertEquals( response2.get( paramKey ), paramValue, "The session should still contain the previously stored value." );

        // some more checks in sticky mode
        if ( sessionAffinity.isSticky() ) {
            final Session session = _tomcat1.getManager().findSession( sid2 );
            assertFalse( session.getNoteNames().hasNext(), "Some notes are set: " + toArray( session.getNoteNames() ) );
        }

    }

    /**
     * Tests that after a memcached failure (with only 1 memcached left) and reactivation the backup of the session is
     * stored again in the secondary memcached, so that the primary memcached can die and the session is still available.
     */
    @Test( enabled = true )
    public void testSecondaryBackupForNonStickySessionAfterMemcachedFailover() throws Throwable {

        _tomcat1.getManager().setSticky( false );

        // we had a situation where no session was created, so let's take some break so that everything's up again
        Thread.sleep( 200 );

        final String paramKey = "foo";
        final String paramValue = "bar";
        final String sid1 = post( _httpClient, _portTomcat1, null, paramKey, paramValue ).getResponseSessionId();
        assertNotNull( sid1, "No session created." );
        final String firstNode = extractNodeId( sid1 );
        assertNotNull( firstNode, "No node id encoded in session id." );

        /* shutdown other nodes
         */
        LOG.info( "-------------- stopping other nodes..." );
        final FailoverInfo info = getFailoverInfo( firstNode );
        for( final MemCacheDaemon<?> node : info.otherNodes.values() ) {
            node.stop();
        }
        Thread.sleep( 100 );

        /* make a request with only one memcached
         */
        assertEquals( get( _httpClient, _portTomcat1, sid1 ).getSessionId(), sid1 );
        Thread.sleep( 300 ); // wait for the async processes to complete / be cancelleds

        /* now start the next node that shall get the backup again and make a request
         * that does not modify the session
         */
        LOG.info( "-------------- starting next node..." );
        info.nextNode().getValue().start();
        waitForReconnect( _tomcat1.getManager().getMemcachedSessionService(), info.nextNode().getValue(), 5000 );
        assertEquals( get( _httpClient, _portTomcat1, sid1 ).getSessionId(), sid1 );
        Thread.sleep( 300 ); // wait for the async processes to complete / be cancelleds

        /* now shutdown the active node so that the session is loaded from the secondary node
         */
        LOG.info( "-------------- stopping active node..." );
        info.activeNode.stop();
        Thread.sleep( 100 );

        /* make the request and check that we still have all session data
         */
        final String sid2 = get( _httpClient, _portTomcat1, sid1 ).getSessionId();
        final String secondNode = extractNodeId( sid2 );
        final String expectedNode = info.nextNode().getKey();

        assertEquals( secondNode, expectedNode, "Unexpected nodeId: " + secondNode + "." );

        assertEquals(
                sid2,
                sid1.substring( 0, sid1.indexOf( "-" ) + 1 ) + expectedNode,
                "Unexpected sessionId, sid1: " + sid1 + ", sid2: " + sid2 );

        // we must get the same session back
        final Response response2 = get( _httpClient, _portTomcat1, sid2 );
        assertEquals( response2.getSessionId(), sid2, "We should keep the sessionId." );
        assertNotNull( getFailoverInfo( secondNode ).activeNode.getCache().get( key( sid2 ) )[0], "The session should exist in memcached." );
        assertEquals( response2.get( paramKey ), paramValue, "The session should still contain the previously stored value." );

    }

    private void waitForReconnect( final MemcachedSessionService service, final MemCacheDaemon<?> value, final long timeToWait ) throws InterruptedException {
        MemcachedClient client;
        InetSocketAddress serverAddress;
        try {
            final Method m = MemcachedSessionService.class.getDeclaredMethod("getStorageClient");
            m.setAccessible( true );
            client = ((MemcachedStorageClient) m.invoke( service )).getMemcachedClient();

            final Field field = MemCacheDaemon.class.getDeclaredField( "addr" );
            field.setAccessible( true );
            serverAddress = (InetSocketAddress) field.get( value );
        } catch ( final Exception e ) {
            throw new RuntimeException( e );
        }

        waitForReconnect( client, serverAddress, timeToWait );
    }



    public void waitForReconnect( final MemcachedClient client, final InetSocketAddress serverAddressToCheck, final long timeToWait )
            throws InterruptedException, RuntimeException {
        final long start = System.currentTimeMillis();
        while( System.currentTimeMillis() < start + timeToWait ) {
            for( final SocketAddress address : client.getAvailableServers() ) {
                if ( address.equals( serverAddressToCheck ) ) {
                    return;
                }
            }
            Thread.sleep( 100 );
        }
        throw new RuntimeException( "MemcachedClient did not reconnect after " + timeToWait + " millis." );
    }

    private Set<String> toArray( final Iterator<String> noteNames ) {
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
    @Test( enabled = true )
    public void testAllMemcachedNodesFailure() throws Throwable {

        _tomcat1.getManager().setSticky( true );

        // we had a situation where no session was created, so let's take some break so that everything's up again
        Thread.sleep( 200 );

        final String sid1 = makeRequest( _httpClient, _portTomcat1, null );
        assertNotNull( sid1, "No session created." );

        /* shutdown all memcached nodes
         */
        _daemon1.stop();
        _daemon2.stop();
        _daemon3.stop();

        // wait a little bit
        Thread.sleep( 200 );

        final String sid2 = makeRequest( _httpClient, _portTomcat1, sid1 );

        assertEquals( sid1, sid2, "SessionId changed." );

        assertNotNull( getSessions().get( sid1 ), "Session "+ sid1 +" not existing." );

        final Session session = _tomcat1.getManager().findSession( sid2 );
        assertFalse( session.getNoteNames().hasNext(), "Some notes are set: " + toArray( session.getNoteNames() ) );

    }

    @Test( enabled = true )
    public void testCookieNotSetWhenAllMemcachedsDownIssue40() throws IOException, HttpException, InterruptedException {

        _tomcat1.getManager().setSticky( true );

        // we had a situation where no session was created, so let's take some break so that everything's up again
        Thread.sleep( 200 );

        /* shutdown all memcached nodes
         */
        _daemon1.stop();
        _daemon2.stop();
        _daemon3.stop();

        final Response response1 = get( _httpClient, _portTomcat1, null );
        final String sessionId = response1.getSessionId();
        assertNotNull( sessionId );
        assertNotNull( response1.getResponseSessionId() );

        final String nodeId = extractNodeId( response1.getResponseSessionId() );
        assertNull( nodeId, "NodeId should be null, but is " + nodeId + "." );

        final Response response2 = get( _httpClient, _portTomcat1, sessionId );
        assertEquals( response2.getSessionId(), sessionId, "SessionId changed" );
        assertNull( response2.getResponseSessionId() );

    }

    @Test( enabled = true, dataProviderClass = TestUtils.class, dataProvider = STICKYNESS_PROVIDER )
    public void testCookieNotSetWhenRegularMemcachedDownIssue40( final SessionAffinityMode sessionAffinity ) throws Exception {

        /* reconfigure tomcat with failover node
         */
        final String memcachedNodes = toString( _nodeId1, _address1 ) +
        " " + toString( _nodeId2, _address2 );
        restartTomcat( memcachedNodes, _nodeId1 );
        _tomcat1.getManager().setSticky( sessionAffinity.isSticky() );

        /* shutdown regular memcached node
         */
        _daemon2.stop();

        TestUtils.waitForReconnect(_tomcat1.getService().getStorageClient(), 1, 1000l);

        final Response response1 = get( _httpClient, _portTomcat1, null );
        final String sessionId = response1.getSessionId();
        assertNotNull( sessionId );
        assertNotNull( response1.getResponseSessionId() );

        final String nodeId = extractNodeId( response1.getResponseSessionId() );
        assertEquals( nodeId, _nodeId1 );

        final Response response2 = get( _httpClient, _portTomcat1, sessionId );
        assertEquals( response2.getSessionId(), sessionId, "SessionId changed" );
        assertNull( response2.getResponseSessionId() );

    }

    @Test( enabled = true, dataProviderClass = TestUtils.class, dataProvider = STICKYNESS_PROVIDER )
    public void testReconfigureMemcachedNodesAtRuntimeFeature46( final SessionAffinityMode sessionAffinity ) throws Exception {

        _tomcat1.getManager().setSticky( sessionAffinity.isSticky() );

        // we had a situation where no session was created, so let's take some break so that everything's up again
        Thread.sleep( 200 );

        /* reconfigure tomcat with only two memcached nodes
         */
        final String memcachedNodes1 = toString( _nodeId1, _address1 ) +
        " " + toString( _nodeId2, _address2 );
        restartTomcat( memcachedNodes1, _nodeId2 );

        /* wait until everything's up and running...
         */
        Thread.sleep( 200 );

        final Response response1 = get( _httpClient, _portTomcat1, null );
        final String sessionId1 = response1.getSessionId();
        assertNotNull( sessionId1 );
        assertEquals( extractNodeId( sessionId1 ), _nodeId1 );

        /* reconfigure tomcat with only third memcached nodes and stop
         * the first one
         */
        final String memcachedNodes2 = toString( _nodeId1, _address1 ) +
            " " + toString( _nodeId2, _address2 ) +
            " " + toString( _nodeId3, _address3 );
        _tomcat1.getManager().setMemcachedNodes( memcachedNodes2 );

        _daemon1.stop();

        Thread.sleep( 1000 );

        /* Expect relocation to node3
         */
        final Response response2 = get( _httpClient, _portTomcat1, sessionId1 );
        assertNotSame( response2.getSessionId(), sessionId1 );
        final String sessionId2 = response2.getResponseSessionId();
        assertNotNull( sessionId2 );
        assertEquals( extractNodeId( sessionId2 ), _nodeId3 );

    }

    @Test( enabled = true )
    public void testReconfigureFailoverNodesAtRuntimeFeature46() throws Exception {

        _tomcat1.getManager().setSticky( true );

        /* set failover nodes n2 and n3
         */
        _tomcat1.getManager().setFailoverNodes( _nodeId2 + " " + _nodeId3 );

        /* wait for changes...
         */
        Thread.sleep( 200 );

        final Response response1 = get( _httpClient, _portTomcat1, null );
        final String sessionId1 = response1.getSessionId();
        assertNotNull( sessionId1 );
        assertEquals( extractNodeId( sessionId1 ), _nodeId1 );

        /* set failover nodes n1 and n2
         */
        _tomcat1.getManager().setFailoverNodes( _nodeId1 + " " + _nodeId2 );

        /* wait for changes...
         */
        Thread.sleep( 200 );

        // we need to use another http client, otherwise there's no response cookie.
        final Response response2 = get( new DefaultHttpClient(), _portTomcat1, null );
        final String sessionId2 = response2.getSessionId();
        assertNotNull( sessionId2 );
        assertEquals( extractNodeId( sessionId2 ), _nodeId3 );

    }

    private void restartTomcat( final String memcachedNodes, final String failoverNodes ) throws Exception {
        _tomcat1.stop();
        Thread.sleep( 500 );
        _tomcat1 = getTestUtils().tomcatBuilder().port(_portTomcat1).sessionTimeout(10).memcachedNodes(memcachedNodes).failoverNodes(failoverNodes).buildAndStart();
    }

    private Map<String, Session> getSessions() throws NoSuchFieldException,
            IllegalAccessException {
        final Field field = ManagerBase.class.getDeclaredField( "sessions" );
        field.setAccessible( true );
        @SuppressWarnings("unchecked")
        final Map<String,Session> sessions = (Map<String, Session>)field.get( _tomcat1.getManager() );
        return sessions;
    }

    /* plain stupid
     */
    private FailoverInfo getFailoverInfo( final String nodeId ) {
        if ( _nodeId1.equals( nodeId ) ) {
            return new FailoverInfo( _daemon1, asMap( _nodeId2, _daemon2, _nodeId3, _daemon3 ) );
        } else if ( _nodeId2.equals( nodeId ) ) {
            return new FailoverInfo( _daemon2, asMap( _nodeId3, _daemon3, _nodeId1, _daemon1 ) );
        } else if ( _nodeId3.equals( nodeId ) ) {
            return new FailoverInfo( _daemon3, asMap( _nodeId1, _daemon1, _nodeId2, _daemon2 ) );
        }
        throw new IllegalArgumentException( "Node " + nodeId + " is not a valid node id." );
    }

    private Map<String, MemCacheDaemon<?>> asMap( final String nodeId1, final MemCacheDaemon<?> daemon1,
            final String nodeId2, final MemCacheDaemon<?> daemon2 ) {
        final Map<String, MemCacheDaemon<?>> result = new LinkedHashMap<String, MemCacheDaemon<?>>( 2 );
        result.put( nodeId1, daemon1 );
        result.put( nodeId2, daemon2 );
        return result;
    }

    static class FailoverInfo {
        MemCacheDaemon<?> activeNode;
        Map<String, MemCacheDaemon<?>> otherNodes;
        public FailoverInfo(final MemCacheDaemon<?> first,
                final Map<String, MemCacheDaemon<?>> otherNodes ) {
            this.activeNode = first;
            this.otherNodes = otherNodes;
        }
        public Entry<String, MemCacheDaemon<?>> nextNode() {
            return otherNodes.entrySet().iterator().next();
        }
        public Entry<String, MemCacheDaemon<?>> previousNode() {
            Entry<String, MemCacheDaemon<?>> last = null;
            for ( final Entry<String, MemCacheDaemon<?>> entry : otherNodes.entrySet() ) {
                last = entry;
            }
            return last;
        }
        public Entry<String, MemCacheDaemon<?>> otherNodeExcept( final String key ) {
            for( final Map.Entry<String, MemCacheDaemon<?>> entry : otherNodes.entrySet() ) {
                if ( !entry.getKey().equals( key ) ) {
                    return entry;
                }
            }
            throw new IllegalStateException();
        }
    }

}
