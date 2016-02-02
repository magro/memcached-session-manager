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
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Arrays;

import net.spy.memcached.ConnectionFactory;
import net.spy.memcached.DefaultConnectionFactory;
import net.spy.memcached.MemcachedClient;

import org.apache.http.HttpException;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.thimbleware.jmemcached.MemCacheDaemon;

import de.javakaffee.web.msm.MemcachedBackupSession;
import de.javakaffee.web.msm.MemcachedNodesManager;
import de.javakaffee.web.msm.MemcachedNodesManager.MemcachedClientCallback;
import de.javakaffee.web.msm.MemcachedSessionService.SessionManager;
import de.javakaffee.web.msm.SessionIdFormat;
import de.javakaffee.web.msm.Statistics;
import de.javakaffee.web.msm.SuffixLocatorConnectionFactory;
import de.javakaffee.web.msm.integration.TestUtils.LoginType;
import de.javakaffee.web.msm.integration.TestUtils.RecordingSessionActivationListener;
import de.javakaffee.web.msm.integration.TestUtils.Response;
import de.javakaffee.web.msm.integration.TestUtils.SessionAffinityMode;

/**
 * Integration test testing tomcat failover (tomcats failing).
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public abstract class TomcatFailoverIntegrationTest {

    private static final Log LOG = LogFactory.getLog( TomcatFailoverIntegrationTest.class );

    private static final String GROUP_WITHOUT_NODE_ID = "withoutNodeId";

    private MemCacheDaemon<?> _daemon;
    private MemCacheDaemon<?> _daemon2 = null;
    private MemcachedClient _client;

    private final MemcachedClientCallback _memcachedClientCallback = new MemcachedClientCallback() {
        @Override
        public Object get(final String key) {
            return _client.get(key);
        }
    };

    private TomcatBuilder<?> _tomcat1;
    private TomcatBuilder<?> _tomcat2;

    private static final int TC_PORT_1 = 18888;
    private static final int TC_PORT_2 = 18889;

    private static final String JVM_ROUTE_2 = "tc2";
    private static final String JVM_ROUTE_1 = "tc1";

    private static final String NODE_ID = "n1";
    private static final int MEMCACHED_PORT = 21211;
    private String _memcachedNodes;

    private DefaultHttpClient _httpClient;

    @BeforeMethod
    public void setUp(final Method testMethod) throws Throwable {

        final InetSocketAddress address = new InetSocketAddress( "localhost", MEMCACHED_PORT );
        _daemon = createDaemon( address );
        _daemon.start();

        final String[] testGroups = testMethod.getAnnotation(Test.class).groups();
        final String nodePrefix = testGroups.length == 0 || !GROUP_WITHOUT_NODE_ID.equals(testGroups[0]) ? NODE_ID + ":" : "";
        _memcachedNodes = nodePrefix + "localhost:" + MEMCACHED_PORT;

        try {
            _tomcat1 = startTomcat( TC_PORT_1, JVM_ROUTE_1 );
            _tomcat2 = startTomcat( TC_PORT_2, JVM_ROUTE_2 );

        } catch ( final Throwable e ) {
            LOG.error( "could not start tomcat.", e );
            throw e;
        }

        final MemcachedNodesManager nodesManager = MemcachedNodesManager.createFor(_memcachedNodes, null, null, _memcachedClientCallback);
        final ConnectionFactory cf = nodesManager.isEncodeNodeIdInSessionId()
            ? new SuffixLocatorConnectionFactory( nodesManager, nodesManager.getSessionIdFormat(), Statistics.create(), 1000, 1000 )
            : new DefaultConnectionFactory();
        _client = new MemcachedClient( cf, Arrays.asList( address ) );

        _httpClient = new DefaultHttpClient();
    }

    abstract TestUtils<?> getTestUtils();

    private TomcatBuilder<?> startTomcat( final int port, final String jvmRoute ) throws Exception {
        return startTomcat( port, SessionAffinityMode.STICKY, jvmRoute, null );
    }

    private TomcatBuilder<?> startTomcat( final int port, final SessionAffinityMode sessionAffinityMode,
            final String jvmRoute, final LoginType loginType ) throws Exception {
        return getTestUtils().tomcatBuilder().port(port).memcachedNodes(_memcachedNodes).storageKeyPrefix(null)
                .sticky(sessionAffinityMode.isSticky()).jvmRoute(jvmRoute).loginType(loginType).buildAndStart();
    }

    @AfterMethod
    public void tearDown() throws Exception {
        _client.shutdown();
        _httpClient.getConnectionManager().shutdown();
        _tomcat1.stop();
        _tomcat2.stop();
        _daemon.stop();
        if(_daemon2 != null)
            _daemon2.stop();
    }

    /**
     * Test for issue #38:
     * Notify HttpSessionActivationListeners when loading a session from memcached
     * @throws Exception
     */
    @Test( enabled = true )
    public void testHttpSessionActivationListenersNotifiedOnLoadWithJvmRoute() throws Exception {



        final SessionManager manager1 = _tomcat1.getManager();
        final SessionManager manager2 = _tomcat2.getManager();

        final SessionIdFormat format = new SessionIdFormat();

        final String sessionId1 = get( _httpClient, TC_PORT_1, null ).getSessionId();
        assertEquals( format.extractJvmRoute( sessionId1 ), JVM_ROUTE_1 );

        final MemcachedBackupSession session = (MemcachedBackupSession) manager1.findSession( sessionId1 );
        session.setAttribute( "listener", new RecordingSessionActivationListener() );

        get( _httpClient, TC_PORT_1, sessionId1 );

        final String sessionId2 = get( _httpClient, TC_PORT_2, sessionId1 ).getSessionId();
        assertEquals( format.stripJvmRoute( sessionId2 ), format.stripJvmRoute( sessionId1 ) );
        assertEquals( format.extractJvmRoute( sessionId2 ), JVM_ROUTE_2 );

        final MemcachedBackupSession loaded = (MemcachedBackupSession) manager2.findSession( sessionId2 );
        assertNotNull( loaded );
        final RecordingSessionActivationListener listener = (RecordingSessionActivationListener) loaded.getAttribute( "listener" );
        assertNotNull( listener );

        final String notifiedSessionId = listener.getSessionDidActivate();
        assertEquals( notifiedSessionId, sessionId2 );
    }

    /**
     * Test for issue #38:
     * Notify HttpSessionActivationListeners when loading a session from memcached
     * @throws Exception
     */
    @Test( enabled = true )
    public void testHttpSessionActivationListenersNotifiedOnLoadWithoutJvmRoute() throws Exception {

        _tomcat1.stop();
        _tomcat2.stop();

        _tomcat1 = startTomcat( TC_PORT_1, null );
        _tomcat2 = startTomcat( TC_PORT_2, null );

        final SessionManager manager1 = _tomcat1.getManager();
        final SessionManager manager2 = _tomcat2.getManager();

        final SessionIdFormat format = new SessionIdFormat();

        final String sessionId1 = get( _httpClient, TC_PORT_1, null ).getSessionId();
        assertNull( format.extractJvmRoute( sessionId1 ) );

        final MemcachedBackupSession session = (MemcachedBackupSession) manager1.findSession( sessionId1 );
        session.setAttribute( "listener", new RecordingSessionActivationListener() );

        get( _httpClient, TC_PORT_1, sessionId1 );

        final String sessionId2 = get( _httpClient, TC_PORT_2, sessionId1 ).getSessionId();
        assertEquals( sessionId2, sessionId1 );

        final MemcachedBackupSession loaded = (MemcachedBackupSession) manager2.findSession( sessionId2 );
        assertNotNull( loaded );
        final RecordingSessionActivationListener listener = (RecordingSessionActivationListener) loaded.getAttribute( "listener" );
        assertNotNull( listener );

        final String notifiedSessionId = listener.getSessionDidActivate();
        assertEquals( notifiedSessionId, sessionId2 );
    }

    /**
     * Tests that when two tomcats are running and one tomcat fails the other
     * tomcat can take over the session.
     *
     * @throws IOException
     * @throws InterruptedException
     * @throws HttpException
     */
    @Test( enabled = true )
    public void testTomcatFailover() throws IOException, InterruptedException, HttpException {

        final SessionIdFormat format = new SessionIdFormat();

        final String key = "foo";
        final String value = "bar";
        final String sessionId1 = post( _httpClient, TC_PORT_1, null, key, value ).getSessionId();
        assertEquals( format.extractJvmRoute( sessionId1 ), JVM_ROUTE_1 );

        final Object session = _client.get( sessionId1 );
        assertNotNull( session, "Session not found in memcached: " + sessionId1 );

        final Response response = get( _httpClient, TC_PORT_2, sessionId1 );
        final String sessionId2 = response.getSessionId();
        assertNull( _client.get( sessionId1 ) );
        assertNotNull( _client.get( sessionId2 ) );

        assertEquals( format.stripJvmRoute( sessionId1 ), format.stripJvmRoute( sessionId2 ) );
        assertEquals( format.extractJvmRoute( sessionId2 ), JVM_ROUTE_2 );

        /* check session attributes could be read
         */
        final String actualValue = response.get( key );
        assertEquals( value, actualValue );

        Thread.sleep( 10 );

    }

    /**
     * Related to issue/feature 105 (single memcached node without node id): tomcat failover must work with this configuration.
     */
    @Test( enabled = true, groups = GROUP_WITHOUT_NODE_ID )
    public void testTomcatFailoverWithSingleNodeWithoutConfiguredNodeId() throws IOException, InterruptedException, HttpException {
        // with this group (GROUP_WITHOUT_NODE_ID) the setup method does no set the memcached node id
        // in the memcached nodes configuration. the tomcat failover test does not rely on the node id
        // so that we can just reuse it...
        testTomcatFailover();
    }

    /**
     * Tests that the session that was taken over by another tomcat is not
     * sent again by this tomcat if it was not modified.
     *
     * @throws IOException
     * @throws InterruptedException
     * @throws HttpException
     */
    @Test( enabled = true )
    public void testLoadedSessionOnlySentIfModified() throws IOException, InterruptedException, HttpException {

        /* create a session on tomcat1
         */
        final String key = "foo";
        final String value = "bar";
        final String sessionId1 = post( _httpClient, TC_PORT_1, null, key, value ).getSessionId();
        assertEquals( 1, _daemon.getCache().getSetCmds() );

        final SessionIdFormat format = new SessionIdFormat();

        /* request the session on tomcat2
         */
        final Response response = get( _httpClient, TC_PORT_2, sessionId1 );
        assertEquals( format.stripJvmRoute( sessionId1 ), format.stripJvmRoute( response.getSessionId() ) );
        assertEquals( 2, _daemon.getCache().getSetCmds() );

        /* post key/value already stored in the session again (on tomcat2)
         */
        post( _httpClient, TC_PORT_2, sessionId1, key, value );
        assertEquals( 2, _daemon.getCache().getSetCmds() );

        /* post another key/value pair (on tomcat2)
         */
        post( _httpClient, TC_PORT_2, sessionId1, "bar", "baz" );
        assertEquals( 3, _daemon.getCache().getSetCmds() );

        Thread.sleep( 10 );

    }

    @Test( enabled = true, dataProviderClass = TestUtils.class, dataProvider = STICKYNESS_PROVIDER )
    public void testSerializationOfAuthStuffWithFormAuth( final SessionAffinityMode stickyness ) throws Exception {

        _tomcat1.stop();
        _tomcat2.stop();

        _tomcat1 = startTomcat( TC_PORT_1, stickyness, stickyness.isSticky() ? JVM_ROUTE_1 : null, LoginType.FORM );
        _tomcat2 = startTomcat( TC_PORT_2, stickyness, stickyness.isSticky() ? JVM_ROUTE_2 : null, LoginType.FORM );

        _tomcat1.setChangeSessionIdOnAuth( false );
        _tomcat2.setChangeSessionIdOnAuth( false );

        /* tomcat1: request secured resource, login and check that secured resource is accessable
         */
        final String sessionId = loginWithForm(_httpClient, TC_PORT_1);

        /* tomcat1 failover "simulation":
         * on tomcat2, we now be able to access the secured resource directly
         * with the first request
         */
        final Response tc2Response1 = get( _httpClient, TC_PORT_2, sessionId );
        if ( stickyness.isSticky() ) {
            assertEquals( tc2Response1.getResponseSessionId(), new SessionIdFormat().changeJvmRoute( sessionId, JVM_ROUTE_2 ) );
        }
        else {
            assertEquals( tc2Response1.getSessionId(), sessionId );
        }

    }

    @Test( enabled = true, dataProviderClass = TestUtils.class, dataProvider = STICKYNESS_PROVIDER )
    public void testSerializationOfAuthStuffWithBasicAuth( final SessionAffinityMode stickyness ) throws Exception {

        _tomcat1.stop();
        _tomcat2.stop();

        _tomcat1 = startTomcat( TC_PORT_1, stickyness, stickyness.isSticky() ? JVM_ROUTE_1 : null, LoginType.BASIC );
        _tomcat2 = startTomcat( TC_PORT_2, stickyness, stickyness.isSticky() ? JVM_ROUTE_2 : null, LoginType.BASIC );

        _tomcat1.setChangeSessionIdOnAuth( false );
        _tomcat2.setChangeSessionIdOnAuth( false );

        /* tomcat1: request secured resource, login and check that secured resource is accessable
         */
        final Response tc1Response1 = get( _httpClient, TC_PORT_1, null,
                new UsernamePasswordCredentials( TestUtils.USER_NAME, TestUtils.PASSWORD ) );
        final String sessionId = tc1Response1.getSessionId();

        assertEquals( sessionId, tc1Response1.get( TestServlet.ID ) );

        /* tomcat1 failover "simulation":
         * on tomcat2, we now should be able to access the secured resource directly
         * with the first request
         */
        final Response tc2Response1 = get( _httpClient, TC_PORT_2, sessionId );
        if ( stickyness.isSticky() ) {
            assertEquals( tc2Response1.getResponseSessionId(), new SessionIdFormat().changeJvmRoute( sessionId, JVM_ROUTE_2 ) );
        }
        else {
            assertEquals( tc2Response1.getSessionId(), sessionId );
        }

    }

    @Test( enabled = true )
    public void testSessionOnlyLoadedOnceWithAuth() throws Exception {

        _tomcat1.stop();
        _tomcat2.stop();

        _tomcat1 = startTomcat( TC_PORT_1, SessionAffinityMode.STICKY, JVM_ROUTE_1, LoginType.BASIC );
        _tomcat2 = startTomcat( TC_PORT_2, SessionAffinityMode.STICKY, JVM_ROUTE_2, LoginType.BASIC );

        _tomcat1.setChangeSessionIdOnAuth( false );
        _tomcat2.setChangeSessionIdOnAuth( false );

        /* tomcat1: request secured resource, login and check that secured resource is accessable
         */
        final Response tc1Response1 = get( _httpClient, TC_PORT_1, null,
                new UsernamePasswordCredentials( TestUtils.USER_NAME, TestUtils.PASSWORD ) );
        final String sessionId = tc1Response1.getSessionId();

        assertEquals( sessionId, tc1Response1.get( TestServlet.ID ) );
        assertEquals( _daemon.getCache().getGetHits(), 0 );

        /* on tomcat1 failover and session takeover by tomcat2, msm in tomcat2 should
         * load the session only once.
         */
        final Response tc2Response1 = get( _httpClient, TC_PORT_2, sessionId );
        assertEquals( tc2Response1.getResponseSessionId(), new SessionIdFormat().changeJvmRoute( sessionId, JVM_ROUTE_2 ) );
        assertEquals( _daemon.getCache().getGetHits(), 1 );

    }

    @Test( enabled = true, dataProviderClass = TestUtils.class, dataProvider = STICKYNESS_PROVIDER )
    public void testSessionModificationOnTomcatFailoverNotLostWithAuth( final SessionAffinityMode stickyness ) throws Exception {

        _tomcat1.stop();
        _tomcat2.stop();

        _tomcat1 = startTomcat( TC_PORT_1, stickyness, stickyness.isSticky() ? JVM_ROUTE_1 : null, LoginType.BASIC );
        _tomcat2 = startTomcat( TC_PORT_2, stickyness, stickyness.isSticky() ? JVM_ROUTE_2 : null, LoginType.BASIC );

        _tomcat1.setChangeSessionIdOnAuth( false );
        _tomcat2.setChangeSessionIdOnAuth( false );

        final Response tc1Response1 = get( _httpClient, TC_PORT_1, null, new UsernamePasswordCredentials( TestUtils.USER_NAME, TestUtils.PASSWORD ) );
        final String sessionId = tc1Response1.getSessionId();
        assertEquals( sessionId, tc1Response1.get( TestServlet.ID ) );

        /* on tomcat1 failover and session takeover by tomcat2, the changes made to the
         * session during this request must be available in the following request(s)
         */
        final Response tc2Response1 = post( _httpClient, TC_PORT_2, "/", sessionId, asMap( "foo", "bar" ) );
        if ( stickyness.isSticky() ) {
            assertEquals( tc2Response1.getResponseSessionId(), new SessionIdFormat().changeJvmRoute( sessionId, JVM_ROUTE_2 ) );
        }
        else {
            assertEquals( tc2Response1.getSessionId(), sessionId );
        }

        final Response tc2Response2 = get( _httpClient, TC_PORT_2, tc2Response1.getResponseSessionId() );
        assertEquals( tc2Response2.get( "foo" ), "bar" );

    }

    @Test( enabled = true )
    public void testTomcatFailoverMovesSessionToNonFailoverNode() throws Exception {

        _daemon2 = startMemcached(MEMCACHED_PORT + 1);
        final String memcachedNodes = _memcachedNodes + "," + "n2:localhost:" + (MEMCACHED_PORT + 1);

        _tomcat1.getService().setMemcachedNodes(memcachedNodes);
        _tomcat1.getService().setFailoverNodes("n1");
        _tomcat2.getService().setMemcachedNodes(memcachedNodes);
        _tomcat2.getService().setFailoverNodes("n2");

        final SessionIdFormat format = new SessionIdFormat();

        final String key = "foo";
        final String value = "bar";
        final String sessionId1 = post( _httpClient, TC_PORT_1, null, key, value ).getSessionId();
        assertEquals( format.extractMemcachedId( sessionId1 ), "n2" );
        assertEquals(_daemon.getCache().getCurrentItems(), 0);
        assertEquals(_daemon2.getCache().getCurrentItems(), 1);

        // failover simulation, just request the session from tomcat2
        final Response response = get( _httpClient, TC_PORT_2, sessionId1 );
        final String sessionId2 = response.getSessionId();
        assertEquals( format.extractMemcachedId( sessionId2 ), "n1" );
        assertEquals(_daemon.getCache().getCurrentItems(), 1);

        assertEquals( format.stripJvmRoute( sessionId1 ).replaceAll("n2", "n1"), format.stripJvmRoute( sessionId2 ) );

        /* check session attributes could be read
         */
        final String actualValue = response.get( key );
        assertEquals( value, actualValue );

    }

    private MemCacheDaemon<?> startMemcached(final int memcachedPort) throws IOException {
        final InetSocketAddress address = new InetSocketAddress( "localhost", memcachedPort );
        final MemCacheDaemon<?> daemon2 = createDaemon( address );
        daemon2.start();
        return daemon2;
    }

}
