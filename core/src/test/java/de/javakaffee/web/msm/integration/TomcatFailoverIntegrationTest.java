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
import static de.javakaffee.web.msm.integration.TestUtils.setChangeSessionIdOnAuth;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import junit.framework.Assert;
import net.spy.memcached.MemcachedClient;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Embedded;
import org.apache.http.HttpException;
import org.apache.http.auth.UsernamePasswordCredentials;
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
     * Tests that when two tomcats are running and one tomcat fails the other
     * tomcat can take over the session.
     *
     * @throws IOException
     * @throws InterruptedException
     * @throws HttpException
     */
    @Test( enabled = true )
    public void testTomcatFailover() throws IOException, InterruptedException, HttpException {

        final String key = "foo";
        final String value = "bar";
        final String sessionId1 = post( _httpClient, TC_PORT_1, null, key, value ).getSessionId();

        final Object session = _client.get( sessionId1 );
        Assert.assertNotNull( "Session not found in memcached: " + sessionId1, session );

        final Response response = get( _httpClient, TC_PORT_2, sessionId1 );
        final String sessionId2 = response.getSessionId();

        Assert.assertEquals( sessionId1, sessionId2 );

        /* check session attributes could be read
         */
        final String actualValue = response.get( key );
        assertEquals( value, actualValue );

        Thread.sleep( 10 );

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
        Assert.assertEquals( 1, _daemon.getCache().getSetCmds() );

        /* request the session on tomcat2
         */
        final Response response = get( _httpClient, TC_PORT_2, sessionId1 );
        Assert.assertEquals( sessionId1, response.getSessionId() );
        Assert.assertEquals( 1, _daemon.getCache().getSetCmds() );

        /* post key/value already stored in the session again (on tomcat2)
         */
        post( _httpClient, TC_PORT_2, sessionId1, key, value );
        Assert.assertEquals( 1, _daemon.getCache().getSetCmds() );

        /* post another key/value pair (on tomcat2)
         */
        post( _httpClient, TC_PORT_2, sessionId1, "bar", "baz" );
        Assert.assertEquals( 2, _daemon.getCache().getSetCmds() );

        Thread.sleep( 10 );

    }

    @Test( enabled = true )
    public void testSerializationOfAuthStuffWithFormAuth() throws Exception {

        _tomcat1.stop();
        _tomcat2.stop();

        _tomcat1 = startTomcat( TC_PORT_1, LoginType.FORM );
        _tomcat2 = startTomcat( TC_PORT_2, LoginType.FORM );

        setChangeSessionIdOnAuth( _tomcat1, false );
        setChangeSessionIdOnAuth( _tomcat2, false );

        /* tomcat1: request secured resource, login and check that secured resource is accessable
         */
        final Response tc1Response1 = get( _httpClient, TC_PORT_1, null );
        final String sessionId = tc1Response1.getSessionId();

        assertFalse( sessionId.equals( tc1Response1.get( TestServlet.ID ) ) );

        final Map<String, String> params = new HashMap<String, String>();
        params.put( LoginServlet.J_USERNAME, TestUtils.USER_NAME );
        params.put( LoginServlet.J_PASSWORD, TestUtils.PASSWORD );
        final Response tc1Response2 = post( _httpClient, TC_PORT_1, "j_security_check", sessionId, params );

        assertTrue( sessionId.equals( tc1Response2.get( TestServlet.ID ) ) );

        /* tomcat1 failover "simulation":
         * on tomcat2, we now be able to access the secured resource directly
         * with the first request
         */
        final Response tc2Response1 = get( _httpClient, TC_PORT_2, sessionId );
        assertEquals( sessionId, tc2Response1.get( TestServlet.ID ) );

    }

    @Test( enabled = true )
    public void testSerializationOfAuthStuffWithBasicAuth() throws Exception {

        _tomcat1.stop();
        _tomcat2.stop();

        _tomcat1 = startTomcat( TC_PORT_1, LoginType.BASIC );
        _tomcat2 = startTomcat( TC_PORT_2, LoginType.BASIC );

        setChangeSessionIdOnAuth( _tomcat1, false );
        setChangeSessionIdOnAuth( _tomcat2, false );

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
        assertEquals( sessionId, tc2Response1.get( TestServlet.ID ) );

    }

}
