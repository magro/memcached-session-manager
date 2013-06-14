/*
 * Copyright 2013 Martin Grotzke
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
 */
package de.javakaffee.web.msm.integration;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Hashtable;

import javax.annotation.Nonnull;
import javax.naming.NamingException;

import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Role;
import org.apache.catalina.User;
import org.apache.catalina.UserDatabase;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardServer;
import org.apache.catalina.deploy.LoginConfig;
import org.apache.catalina.deploy.SecurityCollection;
import org.apache.catalina.deploy.SecurityConstraint;
import org.apache.catalina.realm.UserDatabaseRealm;
import org.apache.catalina.startup.Embedded;
import org.apache.catalina.users.MemoryUserDatabase;
import org.apache.naming.NamingContext;

import de.javakaffee.web.msm.JavaSerializationTranscoderFactory;
import de.javakaffee.web.msm.LockingStrategy.LockingMode;
import de.javakaffee.web.msm.MemcachedSessionService;
import de.javakaffee.web.msm.MemcachedSessionService.SessionManager;
import de.javakaffee.web.msm.integration.TestUtils.LoginType;
import edu.umd.cs.findbugs.annotations.SuppressWarnings;

/**
 * Builder for {@link Embedded} tomcat.
 *
 * @author @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public abstract class TomcatBuilder {

    private static final String CONTEXT_PATH = "/";
    private static final String DEFAULT_HOST = "localhost";
    private static final String DEFAULT_TRANSCODER_FACTORY = JavaSerializationTranscoderFactory.class.getName();

    private static final String USER_DATABASE = "UserDatabase";
    protected static final String PASSWORD = "secret";
    protected static final String USER_NAME = "testuser";
    protected static final String ROLE_NAME = "test";

    public static final String STICKYNESS_PROVIDER = "stickynessProvider";
    public static final String BOOLEAN_PROVIDER = "booleanProvider";

    private int port;
    private int sessionTimeout = 1;
    private boolean cookies = true;
    private String jvmRoute = null;
    private LoginType loginType = null;
    private String memcachedNodes;
    private String failoverNodes;
    private boolean enabled = true;
    private boolean sticky = true;
    private LockingMode lockingMode;
    private String memcachedProtocol = MemcachedSessionService.PROTOCOL_TEXT;
    private String username = null;
    private String transcoderFactoryClassName = JavaSerializationTranscoderFactory.class.getName();

    public TomcatBuilder port(final int port) {
        this.port = port;
        return this;
    }

    public TomcatBuilder sessionTimeout(final int sessionTimeout) {
        this.sessionTimeout = sessionTimeout;
        return this;
    }

    public TomcatBuilder cookies(final boolean cookies) {
        this.cookies = cookies;
        return this;
    }

    public TomcatBuilder memcachedNodes(final String memcachedNodes) {
        this.memcachedNodes = memcachedNodes;
        return this;
    }

    public TomcatBuilder failoverNodes(final String failoverNodes) {
        this.failoverNodes = failoverNodes;
        return this;
    }

    public TomcatBuilder enabled(final boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public TomcatBuilder sticky(final boolean sticky) {
        this.sticky = sticky;
        return this;
    }

    public TomcatBuilder lockingMode(final LockingMode lockingMode) {
        this.lockingMode = lockingMode;
        return this;
    }

    public TomcatBuilder memcachedProtocol(final String memcachedProtocol) {
        this.memcachedProtocol = memcachedProtocol;
        return this;
    }

    public TomcatBuilder username(final String memcachedUsername) {
        this.username = memcachedUsername;
        return this;
    }

    public TomcatBuilder jvmRoute(final String jvmRoute) {
        this.jvmRoute = jvmRoute;
        return this;
    }

    public TomcatBuilder loginType(final LoginType loginType) {
        this.loginType = loginType;
        return this;
    }

    public TomcatBuilder transcoderFactoryClassName(final String transcoderFactoryClassName) {
        this.transcoderFactoryClassName = transcoderFactoryClassName;
        return this;
    }

    @SuppressWarnings( "RV_RETURN_VALUE_IGNORED_BAD_PRACTICE" )
    public Embedded build() throws MalformedURLException,
            UnknownHostException, LifecycleException {

        final Embedded catalina = new Embedded();

        final StandardServer server = new StandardServer();
        server.addService( catalina );

        try {
            final NamingContext globalNamingContext = new NamingContext( new Hashtable<String, Object>(), "ctxt" );
            server.setGlobalNamingContext( globalNamingContext );
            globalNamingContext.bind( USER_DATABASE, createUserDatabase() );
        } catch ( final NamingException e ) {
            throw new RuntimeException( e );
        }


        final URL root = new URL( TestUtils.class.getResource( "/" ), "../test-classes" );
        // use file to get correct separator char, replace %20 introduced by URL for spaces
        final String cleanedRoot = new File( root.getFile().replaceAll("%20", " ") ).toString();

        final String fileSeparator = File.separator.equals( "\\" ) ? "\\\\" : File.separator;
        final String docBase = cleanedRoot + File.separator + TestUtils.class.getPackage().getName().replaceAll( "\\.", fileSeparator );

        final Engine engine = catalina.createEngine();

        /* we must have a unique name for mbeans
         */
        engine.setName( "engine-" + port );
        engine.setDefaultHost( DEFAULT_HOST );
        engine.setJvmRoute( jvmRoute );

        catalina.addEngine( engine );
        engine.setService( catalina );

        final UserDatabaseRealm realm = new UserDatabaseRealm();
        realm.setResourceName( USER_DATABASE );
        engine.setRealm( realm );

        final Host host = catalina.createHost( DEFAULT_HOST, docBase );
        engine.addChild( host );
        new File( docBase ).mkdirs();

        final Context context = createContext( catalina, CONTEXT_PATH, "webapp" );
        host.addChild( context );

        final SessionManager sessionManager = createSessionManager();
        context.setManager( sessionManager );
        context.setBackgroundProcessorDelay( 1 );
        context.setCookies(cookies);
        new File( "webapp" + File.separator + "webapp" ).mkdirs();

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
        sessionManager.getMemcachedSessionService().setMemcachedNodes( memcachedNodes );
        sessionManager.getMemcachedSessionService().setFailoverNodes( failoverNodes );
        sessionManager.getMemcachedSessionService().setEnabled(enabled);
        sessionManager.getMemcachedSessionService().setSticky(sticky);
        if(lockingMode != null) {
            sessionManager.getMemcachedSessionService().setLockingMode(lockingMode.name());
        }
        sessionManager.getMemcachedSessionService().setMemcachedProtocol(memcachedProtocol);
        sessionManager.getMemcachedSessionService().setUsername(username);
        sessionManager.setMaxInactiveInterval( sessionTimeout ); // 1 second
        sessionManager.getMemcachedSessionService().setSessionBackupAsync( false );
        sessionManager.getMemcachedSessionService().setSessionBackupTimeout( 100 );
        sessionManager.setProcessExpiresFrequency( 1 ); // 1 second (factor for context.setBackgroundProcessorDelay)
        sessionManager.getMemcachedSessionService().setTranscoderFactoryClass( transcoderFactoryClassName != null ? transcoderFactoryClassName : DEFAULT_TRANSCODER_FACTORY );
        sessionManager.getMemcachedSessionService().setRequestUriIgnorePattern(".*\\.(png|gif|jpg|css|js|ico)$");

        final Connector connector = catalina.createConnector( "localhost", port, false );
        connector.setProperty("bindOnInit", "false");
        catalina.addConnector( connector );

        return catalina;
    }

    protected UserDatabase createUserDatabase() {
        final MemoryUserDatabase userDatabase = new MemoryUserDatabase();
        final Role role = userDatabase.createRole( ROLE_NAME, "the role for unit tests" );
        final User user = userDatabase.createUser( USER_NAME, PASSWORD, "the user for unit tests" );
        user.addRole( role );
        return userDatabase;
    }

    @Nonnull
    protected Context createContext( @Nonnull final Embedded catalina, @Nonnull final String contextPath, @Nonnull final String docBase ) {
        return catalina.createContext( contextPath, docBase );
    }

    /**
     * Must create a {@link SessionManager} for the current tomcat version.
     */
    @Nonnull
    protected abstract SessionManager createSessionManager();

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

}
