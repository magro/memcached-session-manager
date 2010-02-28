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
import java.util.LinkedHashMap;
import java.util.Map;

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
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;

import com.thimbleware.jmemcached.CacheImpl;
import com.thimbleware.jmemcached.LocalCacheElement;
import com.thimbleware.jmemcached.MemCacheDaemon;
import com.thimbleware.jmemcached.storage.hash.ConcurrentLinkedHashMap;
import com.thimbleware.jmemcached.storage.hash.ConcurrentLinkedHashMap.EvictionPolicy;

import de.javakaffee.web.msm.MemcachedBackupSessionManager;

/**
 * Integration test utils.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class TestUtils {

    public static String makeRequest( final HttpClient client, final int port, final String rsessionId ) throws IOException,
            HttpException {
        // System.out.println( port + " >>>>>>>>>>>>>>>>>> Client Starting >>>>>>>>>>>>>>>>>>>>");
        String responseSessionId;
        final HttpMethod method = new GetMethod("http://localhost:"+ port +"/");
        try {
            if ( rsessionId != null ) {
                method.setRequestHeader( "Cookie", "JSESSIONID=" + rsessionId );
            }

            // System.out.println( "cookies: " + method.getParams().getCookiePolicy() );
            //method.getParams().setCookiePolicy(CookiePolicy.RFC_2109);
            client.executeMethod( method );

            if ( method.getStatusCode() != 200 ) {
                throw new RuntimeException( "GET did not return status 200, but " + method.getStatusLine() );
            }

            // System.out.println( ">>>>>>>>>>: " + method.getResponseBodyAsString() );
            responseSessionId = getSessionIdFromResponse( method );
            // System.out.println( "response cookie: " + responseSessionId );

            return responseSessionId == null ? rsessionId : responseSessionId;

        } finally {
            method.releaseConnection();
            // System.out.println( port + " <<<<<<<<<<<<<<<<<<<<<< Client Finished <<<<<<<<<<<<<<<<<<<<<<<");
        }
    }

    public static Response get( final HttpClient client, final int port, final String rsessionId ) throws IOException,
            HttpException {
        // System.out.println( port + " >>>>>>>>>>>>>>>>>> Client Starting >>>>>>>>>>>>>>>>>>>>");
        String responseSessionId;
        final HttpMethod method = new GetMethod("http://localhost:"+ port +"/");
        try {
            if ( rsessionId != null ) {
                method.setRequestHeader( "Cookie", "JSESSIONID=" + rsessionId );
            }

            // System.out.println( "cookies: " + method.getParams().getCookiePolicy() );
            //method.getParams().setCookiePolicy(CookiePolicy.RFC_2109);
            client.executeMethod( method );

            if ( method.getStatusCode() != 200 ) {
                throw new RuntimeException( "GET did not return status 200, but " + method.getStatusLine() );
            }

            // System.out.println( ">>>>>>>>>>: " + method.getResponseBodyAsString() );
            responseSessionId = getSessionIdFromResponse( method );
            // System.out.println( "response cookie: " + responseSessionId );

            final String bodyAsString = method.getResponseBodyAsString();
            final String[] lines = bodyAsString.split( "\r\n" );
            final Map<String, String> keyValues = new LinkedHashMap<String, String>();
            for ( final String line : lines ) {
                final String[] keyValue = line.split( "=" );
                if ( keyValue.length > 0 ) {
                    keyValues.put( keyValue[0], keyValue.length > 1 ? keyValue[1] : null );
                }
            }

            return new Response( responseSessionId == null ? rsessionId : responseSessionId, keyValues );

        } finally {
            method.releaseConnection();
            // System.out.println( port + " <<<<<<<<<<<<<<<<<<<<<< Client Finished <<<<<<<<<<<<<<<<<<<<<<<");
        }
    }

    public static String post( final HttpClient client, final int port, final String rsessionId, final String paramName, final String paramValue ) throws IOException,
            HttpException {
        // System.out.println( port + " >>>>>>>>>>>>>>>>>> Client Starting >>>>>>>>>>>>>>>>>>>>");
        String responseSessionId;
        final PostMethod method = new PostMethod("http://localhost:"+ port +"/");
        try {
            if ( rsessionId != null ) {
                method.setRequestHeader( "Cookie", "JSESSIONID=" + rsessionId );
            }

            method.addParameter( paramName, paramValue );

            // System.out.println( "cookies: " + method.getParams().getCookiePolicy() );
            //method.getParams().setCookiePolicy(CookiePolicy.RFC_2109);
            client.executeMethod( method );

            if ( method.getStatusCode() != 200 ) {
                throw new RuntimeException( "GET did not return status 200, but " + method.getStatusLine() );
            }

            // System.out.println( ">>>>>>>>>>: " + method.getResponseBodyAsString() );
            responseSessionId = getSessionIdFromResponse( method );
            // System.out.println( "response cookie: " + responseSessionId );

            return responseSessionId == null ? rsessionId : responseSessionId;

        } finally {
            method.releaseConnection();
            // System.out.println( port + " <<<<<<<<<<<<<<<<<<<<<< Client Finished <<<<<<<<<<<<<<<<<<<<<<<");
        }
    }

    public static String getSessionIdFromResponse( final HttpMethod method ) {
        final Header cookie = method.getResponseHeader( "Set-Cookie" );
        if ( cookie != null ) {
            for ( final HeaderElement header : cookie.getElements() ) {
                if ( "JSESSIONID".equals( header.getName() ) ) {
                    return header.getValue();
                }
            }
        }
        return null;
    }

    public static MemCacheDaemon<?> createDaemon( final InetSocketAddress address ) throws IOException {
        final MemCacheDaemon<LocalCacheElement> daemon = new MemCacheDaemon<LocalCacheElement>();
        final ConcurrentLinkedHashMap<String, LocalCacheElement> cacheStorage = ConcurrentLinkedHashMap.create(
                EvictionPolicy.LRU, 100000, 1024*1024 );
        daemon.setCache(new CacheImpl( cacheStorage ));
        daemon.setAddr( address );
        daemon.setVerbose( true );
        return daemon;
    }

    public static Embedded createCatalina( final int port, final String memcachedNodes ) throws MalformedURLException,
            UnknownHostException, LifecycleException {
        return createCatalina( port, 1, memcachedNodes );
    }

    public static Embedded createCatalina( final int port, final String memcachedNodes, final String jvmRoute ) throws MalformedURLException,
            UnknownHostException, LifecycleException {
        return createCatalina( port, 1, memcachedNodes, jvmRoute );
    }

    public static Embedded createCatalina( final int port, final int sessionTimeout, final String memcachedNodes ) throws MalformedURLException,
            UnknownHostException, LifecycleException {
        return createCatalina( port, sessionTimeout, memcachedNodes, null );
    }

    public static Embedded createCatalina( final int port, final int sessionTimeout, final String memcachedNodes, final String jvmRoute ) throws MalformedURLException,
            UnknownHostException, LifecycleException {
        final Embedded catalina = new Embedded();
        final Engine engine = catalina.createEngine();
        /* we must have a unique name for mbeans
         */
        engine.setName( "engine-" + port );
        engine.setDefaultHost( "localhost" );
        engine.setJvmRoute( jvmRoute );

        final URL root = new URL( TestUtils.class.getResource( "/" ), "../resources" );

        final String docBase = root.getFile() + File.separator + TestUtils.class.getPackage().getName().replaceAll( "\\.", File.separator );
        final Host host = catalina.createHost( "localhost", docBase );
        engine.addChild( host );
        new File( docBase ).mkdirs();

        final MemcachedBackupSessionManager sessionManager = new MemcachedBackupSessionManager();
        engine.setManager( sessionManager );

        final Context context = catalina.createContext( "/", "webapp" );
        context.setManager( sessionManager );
        context.setBackgroundProcessorDelay( 1 );
        new File( docBase + File.separator + "webapp" ).mkdirs();

        host.addChild( context );

        /* we must set the maxInactiveInterval after the context,
         * as setContainer(context) uses the session timeout set on the context
         */
        sessionManager.setMemcachedNodes( memcachedNodes );
        sessionManager.setMaxInactiveInterval( sessionTimeout ); // 1 second
        sessionManager.setProcessExpiresFrequency( 1 ); // 1 second (factor for context.setBackgroundProcessorDelay)

        catalina.addEngine( engine );

        final Connector connector = catalina.createConnector( InetAddress.getLocalHost(), port, false );
        catalina.addConnector( connector );

        return catalina;
    }

    public static MemcachedBackupSessionManager getManager( final Embedded tomcat ) {
        return (MemcachedBackupSessionManager) tomcat.getContainer().getManager();
    }

    /**
     * A helper class for a response with a body containing key=value pairs
     * each in one line.
     */
    public static class Response {

        private final String _sessionId;
        private final Map<String, String> _keyValues;
        public Response( final String sessionId, final Map<String, String> keyValues ) {
            _sessionId = sessionId;
            _keyValues = keyValues;
        }
        String getSessionId() {
            return _sessionId;
        }
        Map<String, String> getKeyValues() {
            return _keyValues;
        }
        String get( final String key ) {
            return _keyValues.get( key );
        }

    }

}
