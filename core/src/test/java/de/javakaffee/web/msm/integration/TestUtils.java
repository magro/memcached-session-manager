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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpException;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HTTP;
import org.apache.naming.NamingContext;
import org.testng.Assert;

import com.thimbleware.jmemcached.CacheImpl;
import com.thimbleware.jmemcached.LocalCacheElement;
import com.thimbleware.jmemcached.MemCacheDaemon;
import com.thimbleware.jmemcached.storage.hash.ConcurrentLinkedHashMap;
import com.thimbleware.jmemcached.storage.hash.ConcurrentLinkedHashMap.EvictionPolicy;

import de.javakaffee.web.msm.JavaSerializationTranscoderFactory;
import de.javakaffee.web.msm.MemcachedBackupSessionManager;

/**
 * Integration test utils.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class TestUtils {

    private static final String CONTEXT_PATH = "/";
    private static final String DEFAULT_HOST = "localhost";
    private static final String DEFAULT_TRANSCODER_FACTORY = JavaSerializationTranscoderFactory.class.getName();

    private static final String USER_DATABASE = "UserDatabase";
    protected static final String PASSWORD = "secret";
    protected static final String USER_NAME = "testuser";
    protected static final String ROLE_NAME = "test";

    public static String makeRequest( final HttpClient client, final int port, final String rsessionId ) throws IOException,
            HttpException {
        // System.out.println( port + " >>>>>>>>>>>>>>>>>> Client Starting >>>>>>>>>>>>>>>>>>>>");
        String responseSessionId;
        final HttpGet method = new HttpGet("http://"+ DEFAULT_HOST +":"+ port + CONTEXT_PATH);
        if ( rsessionId != null ) {
            method.setHeader( "Cookie", "JSESSIONID=" + rsessionId );
        }

        // System.out.println( "cookies: " + method.getParams().getCookiePolicy() );
        //method.getParams().setCookiePolicy(CookiePolicy.RFC_2109);
        final HttpResponse response = client.execute( method );

        if ( response.getStatusLine().getStatusCode() != 200 ) {
            throw new RuntimeException( "GET did not return status 200, but " + response.getStatusLine() );
        }

        // System.out.println( ">>>>>>>>>>: " + method.getResponseBodyAsString() );
        responseSessionId = getSessionIdFromResponse( response );
        // System.out.println( "response cookie: " + responseSessionId );

        // We must consume the content so that the connection will be released
        response.getEntity().consumeContent();

        return responseSessionId == null ? rsessionId : responseSessionId;
    }

    public static Response get( final DefaultHttpClient client, final int port, final String rsessionId )
        throws IOException, HttpException {
        return get( client, port, null, rsessionId );
    }

    public static Response get( final DefaultHttpClient client, final int port, final String rsessionId,
            final Credentials credentials )
        throws IOException, HttpException {
        return get( client, port, null, rsessionId, credentials );
    }

    public static Response get( final DefaultHttpClient client, final int port, final String path, final String rsessionId ) throws IOException,
            HttpException {
        return get( client, port, path, rsessionId, null );
    }

    public static Response get( final DefaultHttpClient client, final int port, final String path, final String rsessionId,
            final Credentials credentials ) throws IOException,
            HttpException {
        // System.out.println( port + " >>>>>>>>>>>>>>>>>> Client Starting >>>>>>>>>>>>>>>>>>>>");
        final String baseUri = "http://"+ DEFAULT_HOST +":"+ port + CONTEXT_PATH;
        final String url = baseUri + ( path != null ? path : "" );
        final HttpGet method = new HttpGet( url );
        if ( rsessionId != null ) {
            method.setHeader( "Cookie", "JSESSIONID=" + rsessionId );
        }

        final HttpResponse response = credentials == null
            ? client.execute( method )
            : executeRequestWithAuth( client, method, credentials );

        if ( response.getStatusLine().getStatusCode() != 200 ) {
            throw new RuntimeException( "GET did not return status 200, but " + response.getStatusLine() );
        }

        return readResponse( rsessionId, response );
    }

    private static HttpResponse executeRequestWithAuth( final DefaultHttpClient client, final HttpGet method,
            final Credentials credentials ) throws IOException, ClientProtocolException {
        client.getCredentialsProvider().setCredentials( AuthScope.ANY, credentials );

        final BasicHttpContext localcontext = new BasicHttpContext();

        // Generate BASIC scheme object and stick it to the local
        // execution context
        final BasicScheme basicAuth = new BasicScheme();
        localcontext.setAttribute( "preemptive-auth", basicAuth );

        // System.out.println( "cookies: " + method.getParams().getCookiePolicy() );
        //method.getParams().setCookiePolicy(CookiePolicy.RFC_2109);
        return client.execute( method, localcontext );
    }

    private static Response readResponse( final String rsessionId, final HttpResponse response ) throws IOException {
        final String responseSessionId = getSessionIdFromResponse( response );
        // System.out.println( "response cookie: " + responseSessionId );

        final Map<String, String> keyValues = new LinkedHashMap<String, String>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader( new InputStreamReader( response.getEntity().getContent() ) );
            String line = null;
            while ( ( line = reader.readLine() ) != null ) {
                final String[] keyValue = line.split( "=" );
                if ( keyValue.length > 0 ) {
                    keyValues.put( keyValue[0], keyValue.length > 1 ? keyValue[1] : null );
                }
            }
        } finally {
            reader.close();
        }

        return new Response( responseSessionId == null ? rsessionId : responseSessionId, keyValues );
    }

    public static Response post( final DefaultHttpClient client,
            final int port,
            final String rsessionId,
            final String paramName,
            final String paramValue ) throws IOException, HttpException {
        final Map<String, String> params = new HashMap<String, String>();
        params.put( paramName, paramValue );
        return post( client, port, null, rsessionId, params );
    }

    public static Response post( final DefaultHttpClient client,
            final int port,
            final String path,
            final String rsessionId,
            final Map<String, String> params ) throws IOException, HttpException {
        // System.out.println( port + " >>>>>>>>>>>>>>>>>> Client Starting >>>>>>>>>>>>>>>>>>>>");
        final String baseUri = "http://"+ DEFAULT_HOST +":"+ port + CONTEXT_PATH;
        final String url = baseUri + ( path != null ? path : "" );
        final HttpPost method = new HttpPost( url );
        if ( rsessionId != null ) {
            method.setHeader( "Cookie", "JSESSIONID=" + rsessionId );
        }

        method.setEntity( createFormEntity( params ) );

        // System.out.println( "cookies: " + method.getParams().getCookiePolicy() );
        //method.getParams().setCookiePolicy(CookiePolicy.RFC_2109);
        final HttpResponse response = client.execute( method );

        final int statusCode = response.getStatusLine().getStatusCode();
        if ( statusCode == 302 ) {
            return redirect( response, client, port, rsessionId, baseUri );
        }

        if ( statusCode != 200 ) {
            throw new RuntimeException( "GET did not return status 200, but " + response.getStatusLine() );
        }

        return readResponse( rsessionId, response );
    }

    private static Response redirect( final HttpResponse response, final DefaultHttpClient client, final int port,
            final String rsessionId, final String baseUri ) throws IOException, HttpException {
        final String location = response.getFirstHeader( "Location" ).getValue();
        if ( !location.startsWith( baseUri ) ) {
            throw new RuntimeException( "There's s.th. wrong, the location header should start with the base URI " + baseUri +
                    ". The location header: " + location );
        }
        /* consume content so that the connection can be released
         */
        response.getEntity().consumeContent();

        /* redirect
         */
        final String redirectPath = location.substring( baseUri.length(), location.length() );
        return get( client, port, redirectPath, rsessionId );
    }

    private static UrlEncodedFormEntity createFormEntity( final Map<String, String> params ) throws UnsupportedEncodingException {
        final List<NameValuePair> parameters = new ArrayList <NameValuePair>();
        for( final Map.Entry<String, String> param : params.entrySet() ) {
            parameters.add( new BasicNameValuePair( param.getKey(), param.getValue() ) );
        }
        final UrlEncodedFormEntity entity = new UrlEncodedFormEntity( parameters, HTTP.UTF_8 );
        return entity;
    }

    public static String getSessionIdFromResponse( final HttpResponse response ) {
        final Header cookie = response.getFirstHeader( "Set-Cookie" );
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
        return createCatalina( port, 1, memcachedNodes, null, loginType, DEFAULT_TRANSCODER_FACTORY );
    }

    public static Embedded createCatalina( final int port, final String memcachedNodes, final String jvmRoute ) throws MalformedURLException,
            UnknownHostException, LifecycleException {
        return createCatalina( port, 1, memcachedNodes, jvmRoute, null, DEFAULT_TRANSCODER_FACTORY );
    }

    public static Embedded createCatalina( final int port, final String memcachedNodes, final String jvmRoute,
            final String transcoderFactoryClassName ) throws MalformedURLException,
            UnknownHostException, LifecycleException {
        return createCatalina( port, 1, memcachedNodes, jvmRoute, null, transcoderFactoryClassName );
    }

    public static Embedded createCatalina( final int port, final String memcachedNodes, final String jvmRoute, final LoginType loginType ) throws MalformedURLException,
            UnknownHostException, LifecycleException {
        return createCatalina( port, 1, memcachedNodes, jvmRoute, loginType, DEFAULT_TRANSCODER_FACTORY );
    }

    public static Embedded createCatalina( final int port, final int sessionTimeout, final String memcachedNodes ) throws MalformedURLException,
            UnknownHostException, LifecycleException {
        return createCatalina( port, sessionTimeout, memcachedNodes, null, null, DEFAULT_TRANSCODER_FACTORY );
    }

    public static Embedded createCatalina( final int port, final int sessionTimeout, final String memcachedNodes, final String jvmRoute,
            final LoginType loginType,
            final String transcoderFactoryClassName ) throws MalformedURLException,
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
        catalina.addEngine( engine );

        /* we must have a unique name for mbeans
         */
        engine.setName( "engine-" + port );
        engine.setDefaultHost( DEFAULT_HOST );
        engine.setJvmRoute( jvmRoute );

        final UserDatabaseRealm realm = new UserDatabaseRealm();
        realm.setResourceName( USER_DATABASE );
        engine.setRealm( realm );

        final URL root = new URL( TestUtils.class.getResource( "/" ), "../resources" );

        final String docBase = root.getFile() + File.separator + TestUtils.class.getPackage().getName().replaceAll( "\\.", File.separator );
        final Host host = catalina.createHost( DEFAULT_HOST, docBase );
        engine.addChild( host );
        new File( docBase ).mkdirs();

        final Context context = catalina.createContext( CONTEXT_PATH, "webapp" );
        host.addChild( context );

        final MemcachedBackupSessionManager sessionManager = new MemcachedBackupSessionManager();
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

        /* we must set the maxInactiveInterval after the context,
         * as setContainer(context) uses the session timeout set on the context
         */
        sessionManager.setMemcachedNodes( memcachedNodes );
        sessionManager.setMaxInactiveInterval( sessionTimeout ); // 1 second
        sessionManager.setProcessExpiresFrequency( 1 ); // 1 second (factor for context.setBackgroundProcessorDelay)
        sessionManager.setTranscoderFactoryClass( transcoderFactoryClassName );

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
        return (MemcachedBackupSessionManager) tomcat.getContainer().findChild( DEFAULT_HOST ).findChild( CONTEXT_PATH ).getManager();
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
