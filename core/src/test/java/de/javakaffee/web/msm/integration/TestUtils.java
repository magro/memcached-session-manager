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

import static de.javakaffee.web.msm.Configurations.NODE_AVAILABILITY_CACHE_TTL_KEY;
import static de.javakaffee.web.msm.integration.TestUtils.Predicates.elementAt;
import static de.javakaffee.web.msm.integration.TestUtils.Predicates.notNull;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.logging.LogManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionEvent;

import net.spy.memcached.MemcachedClient;

import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardEngine;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.core.StandardService;
import org.apache.catalina.loader.WebappLoader;
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
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.params.HttpClientParams;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.EntityUtils;
import org.apache.juli.logging.LogFactory;
import org.jboss.netty.buffer.ChannelBuffers;
import org.testng.Assert;
import org.testng.annotations.DataProvider;

import com.thimbleware.jmemcached.CacheElement;
import com.thimbleware.jmemcached.CacheImpl;
import com.thimbleware.jmemcached.Key;
import com.thimbleware.jmemcached.LocalCacheElement;
import com.thimbleware.jmemcached.MemCacheDaemon;
import com.thimbleware.jmemcached.storage.hash.ConcurrentLinkedHashMap;
import com.thimbleware.jmemcached.storage.hash.ConcurrentLinkedHashMap.EvictionPolicy;

import de.javakaffee.web.msm.MemcachedBackupSession;
import de.javakaffee.web.msm.MemcachedSessionService;

/**
 * Integration test utils.
 *
 * @param <T> The type of {@link TomcatBuilder}, returned by {@link #tomcatBuilder()}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public abstract class TestUtils<T extends TomcatBuilder<?>> {

    private static final String CONTEXT_PATH = "/";
    private static final String DEFAULT_HOST = "localhost";

    protected static final String PASSWORD = "secret";
    protected static final String USER_NAME = "testuser";
    protected static final String ROLE_NAME = "test";

    public static final String STICKYNESS_PROVIDER = "stickynessProvider";
    public static final String BOOLEAN_PROVIDER = "booleanProvider";

    static {
        initLogConfig(TestUtils.class);
        System.setProperty(NODE_AVAILABILITY_CACHE_TTL_KEY, "50");
    }

    public static void initLogConfig(@SuppressWarnings("rawtypes") final Class<? extends TestUtils> clazz) {
        final URL loggingProperties = clazz.getResource("/logging.properties");
        try {
            System.setProperty("java.util.logging.config.file", new File(loggingProperties.toURI()).getAbsolutePath());
        } catch (final Exception e) {
            // we don't have a plain file (e.g. the case for msm-kryo-serializer etc), so we can skip reading the config
            return;
        }
        try {
            LogManager.getLogManager().readConfiguration();
        } catch (final Exception e) {
            LogFactory.getLog( TestUtils.class ).error("Could not init logging configuration.", e);
        }
    }

    /**
     * Login using form based auth and return the session id.
     */
    public static String loginWithForm(final DefaultHttpClient client, final int tcPort) throws IOException, HttpException {
        final Response tc1Response1 = get( client, tcPort, null );
        final String sessionId = tc1Response1.getSessionId();
        assertNotNull( sessionId );
        assertTrue(tc1Response1.getContent().contains("j_security_check"), "/j_security_check not found, app is not properly initialized");

        final Map<String, String> params = new HashMap<String, String>();
        params.put( LoginServlet.J_USERNAME, TestUtils.USER_NAME );
        params.put( LoginServlet.J_PASSWORD, TestUtils.PASSWORD );
        final Response tc1Response2 = post( client, tcPort, "/j_security_check", sessionId, params );
        assertEquals(tc1Response2.getSessionId(), sessionId);
        new RuntimeException("err").printStackTrace();
        assertEquals( tc1Response2.get( TestServlet.ID ), sessionId );

        return tc1Response2.getSessionId();
    }

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
        EntityUtils.consume(response.getEntity());

        return responseSessionId == null ? rsessionId : responseSessionId;
    }

    public static Response get( final DefaultHttpClient client, final int port, final String rsessionId )
        throws IOException, HttpException {
        return get( client, port, null, rsessionId );
    }

    public static Response get( final DefaultHttpClient client, final int port, final String rsessionId,
            final Credentials credentials )
        throws IOException, HttpException {
        return get( client, port, null, rsessionId, null, null, credentials );
    }

    public static Response get( final DefaultHttpClient client, final int port, final String path, final String rsessionId ) throws IOException,
            HttpException {
        return get( client, port, path, rsessionId, null, null, null );
    }

    public static Response get( final DefaultHttpClient client, final int port, final String path, final String rsessionId,
            final Map<String, String> params ) throws IOException,
            HttpException {
        return get( client, port, path, rsessionId, null, params, null );
    }

    public static Response get( final DefaultHttpClient client, final int port, final String path, final String rsessionId,
            final SessionTrackingMode sessionTrackingMode,
            final Map<String, String> params,
            final Credentials credentials ) throws IOException,
            HttpException {
        // System.out.println( port + " >>>>>>>>>>>>>>>>>> Client Starting >>>>>>>>>>>>>>>>>>>>");
        String url = getUrl( port, path );
        if ( params != null && !params.isEmpty() ) {
            url += toQueryString( params );
        }
        if ( rsessionId != null && sessionTrackingMode == SessionTrackingMode.URL ) {
            url += ";jsessionid=" + rsessionId;
        }
        final HttpGet method = new HttpGet( url );
        if ( rsessionId != null && sessionTrackingMode == SessionTrackingMode.COOKIE ) {
            method.setHeader( "Cookie", "JSESSIONID=" + rsessionId );
        }

        final HttpResponse response = credentials == null
            ? client.execute( method )
            : executeRequestWithAuth( client, method, credentials );

        if ( response.getStatusLine().getStatusCode() != 200 ) {
            throw new RuntimeException( "GET "+ path +" did not return status 200, but " + response.getStatusLine() );
        }

        return readResponse( rsessionId, response );
    }

    private static String getUrl( final int port, String path ) throws IllegalArgumentException {
        // we assume the context_path is "/"
        if ( path != null && !path.startsWith( "/" ) ) {
            // but we can also fix this
            path = CONTEXT_PATH + path;
        }
        return "http://"+ DEFAULT_HOST +":"+ port + ( path != null ? path : CONTEXT_PATH );
    }

    /**
     * @param params
     * @return
     */
    private static String toQueryString( final Map<String, String> params ) {
        final StringBuilder sb = new StringBuilder();
        sb.append( "?" );
        for ( final Iterator<Entry<String, String>> iterator = params.entrySet().iterator(); iterator.hasNext(); ) {
            final Entry<String, String> entry = iterator.next();
            sb.append( entry.getKey() ).append( "=" ).append( entry.getValue() );
            if ( iterator.hasNext() ) {
                sb.append( "&" );
            }
        }
        final String qs = sb.toString();
        return qs;
    }

    private static HttpResponse executeRequestWithAuth( final DefaultHttpClient client, final HttpUriRequest method,
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

        final StringBuilder sb = new StringBuilder();
        final Map<String, String> keyValues = new LinkedHashMap<String, String>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader( new InputStreamReader( response.getEntity().getContent() ) );
            String line = null;
            while ( ( line = reader.readLine() ) != null ) {
                sb.append(line);
                final String[] keyValue = line.split( "=" );
                if ( keyValue.length > 0 ) {
                    keyValues.put( keyValue[0], keyValue.length > 1 ? keyValue[1] : null );
                }
            }
        } finally {
            if(reader != null) {
                reader.close();
            }
        }

        return new Response( response, responseSessionId == null ? rsessionId : responseSessionId, responseSessionId, sb.toString(), keyValues );
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
        return post( client, port, path, rsessionId, params, null, true );
    }

    public static Response post( final DefaultHttpClient client,
            final int port,
            final String path,
            final String rsessionId,
            final Map<String, String> params,
            @Nullable final Credentials credentials,
            final boolean followRedirects ) throws IOException, HttpException {
        // System.out.println( port + " >>>>>>>>>>>>>>>>>> Client Starting >>>>>>>>>>>>>>>>>>>>");
        final String baseUri = "http://"+ DEFAULT_HOST +":"+ port;
        final String url = getUrl( port, path );
        final HttpPost method = new HttpPost( url );
        if ( rsessionId != null ) {
            method.setHeader( "Cookie", "JSESSIONID=" + rsessionId );
        }

        method.setEntity( createFormEntity( params ) );

        // For 303 httpclient automatically redirects, so let's prevent this if requested.
        if (!followRedirects) {
            HttpClientParams.setRedirecting(method.getParams(), false);
        }

        // System.out.println( "cookies: " + method.getParams().getCookiePolicy() );
        //method.getParams().setCookiePolicy(CookiePolicy.RFC_2109);
        final HttpResponse response = credentials == null
            ? client.execute( method )
            : executeRequestWithAuth( client, method, credentials );

        final int statusCode = response.getStatusLine().getStatusCode();
        if ( followRedirects && isRedirect(statusCode) ) {
            return redirect( response, client, port, rsessionId, baseUri );
        }

        if ( statusCode != 200 && !(!followRedirects && isRedirect(statusCode)) ) {
            throw new RuntimeException( "POST"+(path != null ? " " + path : "")+" did not return status 200, but " + response.getStatusLine() +
                    "\n" + toString(response.getEntity().getContent()) );
        }

        return readResponse( rsessionId, response );
    }

    public static boolean isRedirect(final int statusCode) {
        return statusCode == 302 || statusCode == 303;
    }

    public static String toString(final InputStream in) {
        final StringBuilder sb = new StringBuilder();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader( new InputStreamReader( in ) );
            String line = null;
            while ( ( line = reader.readLine() ) != null ) {
                sb.append(line);
            }
        } catch (final IOException e) {
            throw new RuntimeException(e);
        } finally {
            if(reader != null) {
                try { reader.close(); } catch (final IOException e) {/* ignore */}
            }
        }
        return sb.toString();
    }

    private static Response redirect( final HttpResponse response, final DefaultHttpClient client, final int port,
            final String rsessionId, final String baseUri ) throws IOException, HttpException {
        /* consume content so that the connection can be released
         */
        EntityUtils.consume(response.getEntity());

        /* redirect
         */
        final String location = response.getFirstHeader( "Location" ).getValue();
        final String redirectPath = location.startsWith( baseUri ) ? location.substring( baseUri.length(), location.length() ) : location;
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

    public static StandardContext createContext() {
        final StandardEngine engine = new StandardEngine();
        engine.setService( new StandardService() );

        final StandardContext context = new StandardContext();
        context.setPath( "/" );
        context.setSessionCookiePath( "/" );

        final WebappLoader webappLoader = new WebappLoader() {
            @Override
            public ClassLoader getClassLoader() {
                return Thread.currentThread().getContextClassLoader();
            }
        };
        context.setLoader( webappLoader );

        final StandardHost host = new StandardHost();
        engine.addChild( host );
        host.addChild( context );

        return context;
    }

    public static MemCacheDaemon<? extends CacheElement> createDaemon( final InetSocketAddress address ) throws IOException {
        final MemCacheDaemon<LocalCacheElement> daemon = new MemCacheDaemon<LocalCacheElement>();
        final ConcurrentLinkedHashMap<Key, LocalCacheElement> cacheStorage = ConcurrentLinkedHashMap.create(
                EvictionPolicy.LRU, 100000, 1024*1024 );
        daemon.setCache( new CacheImpl( cacheStorage ) );
        daemon.setAddr( address );
        daemon.setVerbose( true );
        return daemon;
    }

    /**
     * Must create a {@link TomcatBuilder} for the current tomcat version.
     */
    public abstract T tomcatBuilder();

    public static enum LoginType {
        BASIC, FORM
    }

    /**
     * A helper class for a response with a body containing key=value pairs
     * each in one line.
     */
    public static class Response {

        private final String _sessionId;
        private final String _responseSessionId;
        private final String _content;
        private final Map<String, String> _keyValues;
        private final HttpResponse _response;
        public Response( final HttpResponse response, final String sessionId, final String responseSessionId, final String content, final Map<String, String> keyValues ) {
            _response = response;
            _sessionId = sessionId;
            _responseSessionId = responseSessionId;
            _content = content;
            _keyValues = keyValues;
        }
        public int getStatusCode() {
            return _response.getStatusLine().getStatusCode();
        }
        public String getHeader(final String name) {
           final Header header = _response.getFirstHeader(name);
           return header == null ? null : header.getValue();
        }
        public String getSessionId() {
            return _sessionId;
        }
        public String getResponseSessionId() {
            return _responseSessionId;
        }
        public String getContent() {
            return _content;
        }
        public Map<String, String> getKeyValues() {
            return _keyValues;
        }
        public String get( final String key ) {
            return _keyValues.get( key );
        }

    }

    /**
     * Extracts the memcached node id from the provided session id.
     * @param sessionId the session id, that may contain the node id, e.g. as <code>${origsessionid}-${nodeid}</code>
     * @return the extracted node id or <code>null</code>, if no node information was found.
     */
    public static String extractNodeId( final String sessionId ) {
        final int idx = sessionId.lastIndexOf( '-' );
        return idx > -1 ? sessionId.substring( idx + 1 ) : null;
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

        if ( Set.class.isAssignableFrom( one.getClass() ) ) {
            final Set<?> m1 = (Set<?>) one;
            final Set<?> m2 = (Set<?>) another;
            Assert.assertEquals( m1.size(), m2.size() );
            final Iterator<?> iter1 = m1.iterator();
            final Iterator<?> iter2 = m2.iterator();
            while( iter1.hasNext() ) {
                assertDeepEquals( iter1.next(), iter2.next() );
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

    /**
     * A simple serializable {@link HttpSessionActivationListener} that provides the
     * session id that was passed during {@link #sessionDidActivate(HttpSessionEvent)}
     * via {@link #getSessionDidActivate()}.
     */
    public static final class RecordingSessionActivationListener implements HttpSessionActivationListener, Serializable {

        private static final long serialVersionUID = 1L;

        private transient String _sessionDidActivate;

        @Override
        public void sessionWillPassivate( final HttpSessionEvent se ) {
        }

        @Override
        public void sessionDidActivate( final HttpSessionEvent se ) {
            _sessionDidActivate = se.getSession().getId();
        }

        /**
         * Returns the id of the session that was passed in {@link #sessionDidActivate(HttpSessionEvent)}.
         * @return a session id or <code>null</code>.
         */
        public String getSessionDidActivate() {
            return _sessionDidActivate;
        }

    }

    /**
     * Creates a map from the given keys and values (key1, value1, key2, value2, etc.).
     * @param <T> the type of the keys and values.
     * @param keysAndValues the keys and values, must be an even number of arguments.
     * @return a {@link Map} or null if no argument was given.
     */
    public static <T> Map<T,T> asMap( final T ... keysAndValues ) {
        if ( keysAndValues == null ) {
            return null;
        }
        if ( keysAndValues.length % 2 != 0 ) {
            throw new IllegalArgumentException( "You must provide an even number of arguments as key/value pairs." );
        }

        final Map<T,T> result = new HashMap<T,T>();
        for ( int i = 0; i < keysAndValues.length; i++ ) {
            if ( ( i & 1 ) == 1 ) {
                result.put( keysAndValues[i - 1], keysAndValues[i] );
            }
        }

        return result;
    }

    public static enum SessionTrackingMode {
        COOKIE,
        URL
    }

    public static enum SessionAffinityMode {
        STICKY {
            @Override public boolean isSticky() { return true; }
        },
        NON_STICKY {
            @Override public boolean isSticky() { return false; }
        };

        public abstract boolean isSticky();
    }

    @DataProvider
    public static Object[][] stickynessProvider() {
        return new Object[][] {
                { SessionAffinityMode.STICKY },
                { SessionAffinityMode.NON_STICKY }
        };
    }

    @DataProvider
    public static Object[][] booleanProvider() {
        return new Object[][] {
                { true },
                { false }
        };
    }

    @Nonnull
    public static Key key( @Nonnull final String value ) {
        return new Key( ChannelBuffers.wrappedBuffer( value.getBytes() ) );
    }

    @Nonnull
    public static MemcachedBackupSession createSession( @Nonnull final MemcachedSessionService service ) {
        // return (MemcachedBackupSession) service.getManager().createSession( null );
        final MemcachedBackupSession session = service.createEmptySession();
        session.setNew( true );
        session.setValid( true );
        session.setCreationTime( System.currentTimeMillis() );
        session.setMaxInactiveInterval( 23 );
        session.setId( "foo-n1" );
        return session;
    }


    public static void waitForReconnect( final MemcachedClient client, final int expectedNumServers, final long timeToWait )
            throws InterruptedException, RuntimeException {
        final long start = System.currentTimeMillis();
        while( System.currentTimeMillis() < start + timeToWait ) {
            if ( client.getAvailableServers().size() >= expectedNumServers ) {
                return;
            }
            Thread.sleep( 20 );
        }
        throw new RuntimeException( "MemcachedClient did not reconnect after " + timeToWait + " millis." );
    }

    public static <T,V> T assertNotNullElementWaitingWithProxy(final int elementIndex, final long maxTimeToWait, final T objectToProxy) {
        return assertWaitingWithProxy(elementAt(elementIndex, notNull()), maxTimeToWait, objectToProxy);
    }

    @java.lang.SuppressWarnings("unchecked")
    public static <T,V> T assertWaitingWithProxy(final Predicate<V> predicate, final long maxTimeToWait, final T objectToProxy) {
        final Class<?>[] interfaces = objectToProxy.getClass().getInterfaces();
        return (T) Proxy.newProxyInstance( Thread.currentThread().getContextClassLoader(),
                interfaces,
                new InvocationHandler() {
                    @Override
                    public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
                        return assertPredicateWaiting(predicate, maxTimeToWait, objectToProxy, method, args);
                    }
                } );
    }

    private static <V> V assertPredicateWaiting(final Predicate<V> predicate, final long maxTimeToWait, final Object obj, final Method method, final Object[] args) throws Exception {
        final long start = System.currentTimeMillis();
        while( System.currentTimeMillis() < start + maxTimeToWait ) {
            @java.lang.SuppressWarnings("unchecked")
            final V result = (V) method.invoke(obj, args);
            if ( predicate.apply(result) ) {
                return result;
            }
            try {
                Thread.sleep( 10 );
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("Expected not null, actual null.");
    }

    public static interface Predicate<T> {
        /**
         * Applies this predicate to the given object.
         *
         * @param input the input that the predicate should act on
         * @return the value of this predicate when applied to the input {@code t}
         */
        boolean apply(@Nullable T input);
    }

    public static class Predicates {

        private static final Predicate<Object> NOT_NULL = new Predicate<Object>() {
            @Override
            public boolean apply(final Object input) {
                return input != null;
            }
        };
        private static final Predicate<Object> IS_NULL = new Predicate<Object>() {
            @Override
            public boolean apply(final Object input) {
                return input == null;
            }
        };

        /**
         * Returns a predicate that evaluates to {@code true} if the object reference
         * being tested is not null.
         */
        @java.lang.SuppressWarnings("unchecked")
        public static <T> Predicate<T> notNull() {
            return (Predicate<T>) NOT_NULL;
        }

        /**
         * Returns a predicate that evaluates to {@code true} if the object reference
         * being tested is null.
         */
        @java.lang.SuppressWarnings("unchecked")
        public static <T> Predicate<T> isNull() {
            return (Predicate<T>) IS_NULL;
        }

        /**
         * Returns a predicate that evaluates to {@code true} if the object being
         * tested {@code equals()} the given target or both are null.
         */
        public static <T> Predicate<T> equalTo(@Nullable final T target) {
            return (target == null) ? Predicates.<T> isNull()
                    : new Predicate<T>() {
                @Override
                public boolean apply(final T input) {
                    return target.equals(input);
                }
            };
        }

        public static <T> Predicate<T[]> elementAt(final int index, @Nonnull final Predicate<T> elementPredicate) {
            return new Predicate<T[]>() {
                @Override
                public boolean apply(final T[] input) {
                    return input != null && input.length > index && elementPredicate.apply(input[index]);
                }
            };
        }
    }

}
