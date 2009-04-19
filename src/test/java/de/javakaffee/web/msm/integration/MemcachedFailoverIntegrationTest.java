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
import static org.junit.Assert.*;
import static org.junit.Assert.assertNotSame;

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

    @Before
    public void setUp() throws Throwable {

        _portTomcat1 = 8888;

        final InetSocketAddress address1 = new InetSocketAddress( "localhost", 21211 );
        _daemon1 = createDaemon( address1 );
        _daemon1.start();
        
        final InetSocketAddress address2 = new InetSocketAddress( "localhost", 21212 );
        _daemon2 = createDaemon( address2 );
        _daemon2.start();
        
        try {
            final String memcachedNodes = toString( address1 ) + " " + toString( address2 );
            _tomcat1 = createCatalina( _portTomcat1, 10, memcachedNodes );
            _tomcat1.start();
        } catch( Throwable e ) {
            LOG.error( "could not start tomcat.", e );
            throw e;
        }
        
        _memcached = new MemcachedClient(
                new SuffixLocatorConnectionFactory( _tomcat1.getContainer().getManager() ),
                Arrays.asList( address1, address2 ) );
        
        _connectionManager = new SimpleHttpConnectionManager( true );
        _httpClient = new HttpClient( _connectionManager );
    }

    private String toString( final InetSocketAddress address1 ) {
        return address1.getHostName() + ":" + address1.getPort();
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
        assertTrue( "Session stored on unexpected memcached server", sid1.endsWith( ".0" ) );
        
        /* shutdown memcached node 1
         */
        _daemon1.stop();

        final String sid2 = makeRequest( _httpClient, _portTomcat1, sid1 );
        assertTrue( "Unexpected SessionId", sid2.equals( sid1.substring( 0, sid1.indexOf( "." ) ) + ".1" ) );
        
    }

}
