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

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Arrays;

import junit.framework.Assert;

import net.spy.memcached.MemcachedClient;

import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Embedded;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HeaderElement;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.SimpleHttpConnectionManager;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.jgroups.blocks.MemcachedConnector;
import org.jgroups.blocks.PartitionedHashMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import de.javakaffee.web.msm.MemcachedBackupSessionManager;
import de.javakaffee.web.msm.SessionSerializingTranscoder;
import de.javakaffee.web.msm.SuffixLocatorConnectionFactory;

public class TomcatFailoverIntegrationTest {
    
    private static final Log LOG = LogFactory
            .getLog( TomcatFailoverIntegrationTest.class );
    
    private MemcachedClient _client;
    private PartitionedHashMap<String,byte[]> _map;
    private MemcachedConnector _connector;
    
    private Embedded _tomcat1;
    private Embedded _tomcat2;

    private int _portTomcat1;
    private int _portTomcat2;

    @Before
    public void setUp() throws Throwable {

        _portTomcat1 = 8888;
        _portTomcat2 = 8889;
        
        final int port = 21211;

        _map = new PartitionedHashMap<String,byte[]>( "tcp.xml", getClass().getSimpleName() );
        _connector = new MemcachedConnector( InetAddress.getLocalHost(), port, _map);
        _connector.setThreadPoolCoreThreads(1);
        _connector.setThreadPoolMaxThreads(5);
        _map.start();
        _connector.start();
        
        try {
        final String memcachedNodes = "localhost:" + port;
        _tomcat1 = createCatalina( _portTomcat1, memcachedNodes );
        _tomcat1.start();

        _tomcat2 = createCatalina( _portTomcat2, memcachedNodes );
        _tomcat2.start();
        
        } catch( Throwable e ) {
            LOG.error( "could not start tomcat.", e );
            throw e;
        }
        
        _client = new MemcachedClient(
                new SuffixLocatorConnectionFactory( _tomcat1.getContainer().getManager() ),
                Arrays.asList( new InetSocketAddress( "localhost", port ) ) );
    }

    @After
    public void tearDown() throws Exception {
        _connector.stop();
        _map.stop();
        _tomcat1.stop();
        _tomcat2.stop();
    }
    
//    @Test
//    public void testConnectDaemon() throws IOException, InterruptedException {
//        _client.set( "foo", 3600000, "bar" );
//        Assert.assertEquals( "bar", _client.get( "foo" ) );
//    }
    
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
            
            Thread.sleep( 200 );
            
            Assert.assertNotNull( _client.get( sessionId1 ) );
            
            final String sessionId2 = makeRequest( client, _portTomcat2, sessionId1 );
            
            Assert.assertEquals( sessionId1, sessionId2 );
        } finally {
            connectionManager.shutdown();
        }
        
    }

    private String makeRequest( final HttpClient client, int port, String rsessionId ) throws IOException,
            HttpException {
        System.out.println( port + " >>>>>>>>>>>>>>>>>> Starting >>>>>>>>>>>>>>>>>>>>");
        String responseSessionId;
        final HttpMethod method = new GetMethod("http://localhost:"+ port +"/");
        try {
            if ( rsessionId != null ) {
                method.setRequestHeader( "Cookie", "JSESSIONID=" + rsessionId );
            }
            
            System.out.println( "cookies: " + method.getParams().getCookiePolicy() );
            //method.getParams().setCookiePolicy(CookiePolicy.RFC_2109);
            client.executeMethod( method );
            System.out.println( ">>>>>>>>>>: " + method.getResponseBodyAsString() );
            responseSessionId = getSessionIdFromResponse( method );
            System.out.println( "response cookie: " + responseSessionId );
            
            return responseSessionId;
            
        } finally {
            method.releaseConnection();
            System.out.println( port + " <<<<<<<<<<<<<<<<<<<<<< Finished <<<<<<<<<<<<<<<<<<<<<<<");
        }
    }

    private String getSessionIdFromResponse( final HttpMethod method ) {
        final Header cookie = method.getResponseHeader( "Set-Cookie" );
        if ( cookie != null ) {
            for ( HeaderElement header : cookie.getElements() ) {
                if ( "JSESSIONID".equals( header.getName() ) ) {
                    return header.getValue();
                }
            }
        }
        return null;
    }

    private Embedded createCatalina( final int port, String memcachedNodes ) throws MalformedURLException,
            UnknownHostException, LifecycleException {
        final Embedded catalina = new Embedded();
        final Engine engine = catalina.createEngine();
        /* we must have a unique name for mbeans
         */
        engine.setName( "engine-" + port );
        engine.setDefaultHost( "localhost" );
        
        final URL root = new URL( getClass().getResource( "/" ), "../resources" );
        
        final String docBase = root.getFile() + File.separator + getClass().getPackage().getName().replaceAll( "\\.", File.separator );
        final Host host = catalina.createHost( "localhost", docBase );
        engine.addChild( host );
        final File contextPathFile = new File( docBase + "/" );
        contextPathFile.mkdirs();
        final Context context = catalina.createContext( "/", "webapp" );
        final MemcachedBackupSessionManager sessionManager = new MemcachedBackupSessionManager();
        sessionManager.setMemcachedNodes( memcachedNodes );
        sessionManager.setActiveNodeIndex( 0 );
        context.setManager( sessionManager );
        
        host.addChild( context );
        
        catalina.addEngine( engine );
        
        final Connector connector = catalina.createConnector( InetAddress.getLocalHost(), port, false );
        catalina.addConnector( connector );
        
        return catalina;
    }

}
