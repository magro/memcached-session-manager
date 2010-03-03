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
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Currency;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.naming.NamingException;

import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Role;
import org.apache.catalina.User;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardServer;
import org.apache.catalina.deploy.LoginConfig;
import org.apache.catalina.deploy.SecurityCollection;
import org.apache.catalina.deploy.SecurityConstraint;
import org.apache.catalina.realm.UserDatabaseRealm;
import org.apache.catalina.startup.Embedded;
import org.apache.catalina.users.MemoryUserDatabase;
import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HeaderElement;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.naming.NamingContext;
import org.junit.Assert;

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

    private static final String USER_DATABASE = "UserDatabase";
    protected static final String PASSWORD = "secret";
    protected static final String USER_NAME = "testuser";
    protected static final String ROLE_NAME = "test";

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

    public static Response get( final HttpClient client, final int port, final String rsessionId )
        throws IOException, HttpException {
        return get( client, port, null, rsessionId );
    }

    public static Response get( final HttpClient client, final int port, final String rsessionId,
            final Credentials credentials )
        throws IOException, HttpException {
        return get( client, port, null, rsessionId, credentials );
    }

    public static Response get( final HttpClient client, final int port, final String path, final String rsessionId ) throws IOException,
            HttpException {
        return get( client, port, path, rsessionId, null );
    }

    public static Response get( final HttpClient client, final int port, final String path, final String rsessionId,
            final Credentials credentials ) throws IOException,
            HttpException {
        // System.out.println( port + " >>>>>>>>>>>>>>>>>> Client Starting >>>>>>>>>>>>>>>>>>>>");
        final String baseUri = "http://localhost:"+ port +"/";
        final String url = baseUri + ( path != null ? path : "" );
        final HttpMethod method = new GetMethod( url );
        try {
            if ( rsessionId != null ) {
                method.setRequestHeader( "Cookie", "JSESSIONID=" + rsessionId );
            }

            if ( credentials != null ) {
                client.getState().setCredentials( AuthScope.ANY, credentials );
                method.setDoAuthentication( true );
            }

            // System.out.println( "cookies: " + method.getParams().getCookiePolicy() );
            //method.getParams().setCookiePolicy(CookiePolicy.RFC_2109);
            client.executeMethod( method );

            if ( method.getStatusCode() != 200 ) {
                throw new RuntimeException( "GET did not return status 200, but " + method.getStatusLine() );
            }

            return readResponse( rsessionId, method );

        } finally {
            method.releaseConnection();
            // System.out.println( port + " <<<<<<<<<<<<<<<<<<<<<< Client Finished <<<<<<<<<<<<<<<<<<<<<<<");
        }
    }

    private static Response readResponse( final String rsessionId, final HttpMethod method ) throws IOException {
        final String responseSessionId = getSessionIdFromResponse( method );
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

        final Response response = new Response( responseSessionId == null ? rsessionId : responseSessionId, keyValues );
        return response;
    }

    public static Response post( final HttpClient client,
            final int port,
            final String rsessionId,
            final String paramName,
            final String paramValue ) throws IOException, HttpException {
        final Map<String, String> params = new HashMap<String, String>();
        params.put( paramName, paramValue );
        return post( client, port, null, rsessionId, params );
    }

    public static Response post( final HttpClient client,
            final int port,
            final String path,
            final String rsessionId,
            final Map<String, String> params ) throws IOException, HttpException {
        // System.out.println( port + " >>>>>>>>>>>>>>>>>> Client Starting >>>>>>>>>>>>>>>>>>>>");
        final String baseUri = "http://localhost:"+ port +"/";
        final String url = baseUri + ( path != null ? path : "" );
        final PostMethod method = new PostMethod( url );
        try {
            if ( rsessionId != null ) {
                method.setRequestHeader( "Cookie", "JSESSIONID=" + rsessionId );
            }

            for( final Map.Entry<String, String> param : params.entrySet() ) {
                method.addParameter( param.getKey(), param.getValue() );
            }

            // System.out.println( "cookies: " + method.getParams().getCookiePolicy() );
            //method.getParams().setCookiePolicy(CookiePolicy.RFC_2109);
            client.executeMethod( method );

            if ( method.getStatusCode() == 302 ) {
                final String location = method.getResponseHeader( "Location" ).getValue();
                if ( !location.startsWith( baseUri ) ) {
                    throw new RuntimeException( "There's s.th. wrong, the location header should start with the base URI " + baseUri +
                            ". The location header: " + location );
                }
                final String redirectPath = location.substring( baseUri.length(), location.length() );
                return get( client, port, redirectPath, rsessionId );
            }

            if ( method.getStatusCode() != 200 ) {
                throw new RuntimeException( "GET did not return status 200, but " + method.getStatusLine() );
            }

            return readResponse( rsessionId, method );

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
        daemon.setCache( new CacheImpl( cacheStorage ) );
        daemon.setAddr( address );
        daemon.setVerbose( false );
        return daemon;
    }

    public static Embedded createCatalina( final int port, final String memcachedNodes ) throws MalformedURLException,
            UnknownHostException, LifecycleException {
        return createCatalina( port, 1, memcachedNodes );
    }

    public static Embedded createCatalina( final int port, final String memcachedNodes, final LoginType loginType ) throws MalformedURLException,
            UnknownHostException, LifecycleException {
        return createCatalina( port, 1, memcachedNodes, null, loginType );
    }

    public static Embedded createCatalina( final int port, final String memcachedNodes, final String jvmRoute ) throws MalformedURLException,
            UnknownHostException, LifecycleException {
        return createCatalina( port, 1, memcachedNodes, jvmRoute, null );
    }

    public static Embedded createCatalina( final int port, final String memcachedNodes, final String jvmRoute, final LoginType loginType ) throws MalformedURLException,
            UnknownHostException, LifecycleException {
        return createCatalina( port, 1, memcachedNodes, jvmRoute, loginType );
    }

    public static Embedded createCatalina( final int port, final int sessionTimeout, final String memcachedNodes ) throws MalformedURLException,
            UnknownHostException, LifecycleException {
        return createCatalina( port, sessionTimeout, memcachedNodes, null, null );
    }

    public static Embedded createCatalina( final int port, final int sessionTimeout, final String memcachedNodes, final String jvmRoute,
            final LoginType loginType ) throws MalformedURLException,
            UnknownHostException, LifecycleException {
        final Embedded catalina = new Embedded();

        final StandardServer server = new StandardServer();
        catalina.setServer( server );

        try {
            final NamingContext globalNamingContext = new NamingContext( new Hashtable<Object, Object>(), "ctxt" );
            server.setGlobalNamingContext( globalNamingContext );
            globalNamingContext.bind( USER_DATABASE, createUserDatabase() );
        } catch ( final NamingException e ) {
            throw new RuntimeException( e );
        }

        final Engine engine = catalina.createEngine();
        /* we must have a unique name for mbeans
         */
        engine.setName( "engine-" + port );
        engine.setDefaultHost( "localhost" );
        engine.setJvmRoute( jvmRoute );

        final UserDatabaseRealm realm = new UserDatabaseRealm();
        realm.setResourceName( USER_DATABASE );
        engine.setRealm( realm );

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

        if ( loginType != null ) {
            context.addConstraint( createSecurityConstraint( "/*", ROLE_NAME ) );
            // context.addConstraint( createSecurityConstraint( "/j_security_check", null ) );
            context.addSecurityRole( ROLE_NAME );
            final LoginConfig loginConfig = loginType == LoginType.FORM
                ? new LoginConfig( "FORM", null, "/login", "/error" )
                : new LoginConfig( "BASIC", null, null, null );
                context.setLoginConfig( loginConfig );
        }

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

    private static SecurityConstraint createSecurityConstraint( final String pattern, final String role ) {
        final SecurityConstraint constraint = new SecurityConstraint();
        final SecurityCollection securityCollection = new SecurityCollection();
        securityCollection.addPattern( pattern );
        constraint.addCollection( securityCollection );
        if ( role != null ) {
            constraint.addAuthRole( role );
        }
        return constraint;
    }

    public static enum LoginType {
        BASIC, FORM
    }

    private static MemoryUserDatabase createUserDatabase() {
        final MemoryUserDatabase userDatabase = new MemoryUserDatabase();
        final Role role = userDatabase.createRole( ROLE_NAME, "the role for unit tests" );
        final User user = userDatabase.createUser( USER_NAME, PASSWORD, "the user for unit tests" );
        user.addRole( role );
        return userDatabase;
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

    public static void assertDeepEquals( final Object one, final Object another ) {
        assertDeepEquals( one, another, new IdentityHashMap<Object, Object>() );
    }

    public static void assertDeepEquals( final Object one, final Object another, final Map<Object, Object> alreadyChecked ) {
        if ( one == another ) {
            return;
        }
        if ( one == null && another != null || one != null && another == null ) {
            Assert.fail( "One of both is null: " + one + ", " + another );
        }
        if ( alreadyChecked.containsKey( one ) ) {
            return;
        }
        alreadyChecked.put( one, another );

        Assert.assertEquals( one.getClass(), another.getClass() );
        if ( one.getClass().isPrimitive() || one instanceof String || one instanceof Character || one instanceof Boolean ) {
            Assert.assertEquals( one, another );
            return;
        }

        if ( Map.class.isAssignableFrom( one.getClass() ) ) {
            final Map<?, ?> m1 = (Map<?, ?>) one;
            final Map<?, ?> m2 = (Map<?, ?>) another;
            Assert.assertEquals( m1.size(), m2.size() );
            for ( final Map.Entry<?, ?> entry : m1.entrySet() ) {
                assertDeepEquals( entry.getValue(), m2.get( entry.getKey() ) );
            }
            return;
        }

        if ( Number.class.isAssignableFrom( one.getClass() ) ) {
            Assert.assertEquals( ( (Number) one ).longValue(), ( (Number) another ).longValue() );
            return;
        }

        if ( one instanceof Currency ) {
            // Check that the transient field defaultFractionDigits is initialized correctly (that was issue #34)
            final Currency currency1 = ( Currency) one;
            final Currency currency2 = ( Currency) another;
            Assert.assertEquals( currency1.getCurrencyCode(), currency2.getCurrencyCode() );
            Assert.assertEquals( currency1.getDefaultFractionDigits(), currency2.getDefaultFractionDigits() );
        }

        Class<? extends Object> clazz = one.getClass();
        while ( clazz != null ) {
            assertEqualDeclaredFields( clazz, one, another, alreadyChecked );
            clazz = clazz.getSuperclass();
        }

    }

    public static void assertEqualDeclaredFields( final Class<? extends Object> clazz, final Object one, final Object another,
            final Map<Object, Object> alreadyChecked ) {
        for ( final Field field : clazz.getDeclaredFields() ) {
            field.setAccessible( true );
            if ( !Modifier.isTransient( field.getModifiers() ) ) {
                try {
                    assertDeepEquals( field.get( one ), field.get( another ), alreadyChecked );
                } catch ( final IllegalArgumentException e ) {
                    throw new RuntimeException( e );
                } catch ( final IllegalAccessException e ) {
                    throw new RuntimeException( e );
                }
            }
        }
    }

}
