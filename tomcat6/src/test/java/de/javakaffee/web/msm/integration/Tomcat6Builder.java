package de.javakaffee.web.msm.integration;

import de.javakaffee.web.msm.MemcachedSessionService;
import org.apache.catalina.*;
import org.apache.catalina.authenticator.AuthenticatorBase;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardEngine;
import org.apache.catalina.core.StandardServer;
import org.apache.catalina.deploy.LoginConfig;
import org.apache.catalina.deploy.SecurityCollection;
import org.apache.catalina.deploy.SecurityConstraint;
import org.apache.catalina.realm.UserDatabaseRealm;
import org.apache.catalina.startup.Embedded;
import org.apache.naming.NamingContext;

import javax.annotation.Nonnull;
import javax.naming.NamingException;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Hashtable;

import de.javakaffee.web.msm.MemcachedBackupSessionManager;
import de.javakaffee.web.msm.MemcachedSessionService.SessionManager;

/**
 * @author <a href="mailto:cleclerc@cloudbees.com">Cyrille Le Clerc</a>
 */
public class Tomcat6Builder extends TomcatBuilder<Embedded> {
    private Embedded tomcat;

    @Override
    public Tomcat6Builder buildAndStart() throws Exception {
        tomcat = build();
        tomcat.start();
        return this;
    }

    @Override
    public void stop() throws Exception {
        tomcat.stop();
    }

    @Override
    public Context getContext() {
        return (Context) tomcat.getContainer().findChild( DEFAULT_HOST ).findChild( CONTEXT_PATH );
    }

    @Override
    public MemcachedSessionService.SessionManager getManager() {
        return (MemcachedSessionService.SessionManager) getContext().getManager();
    }

    @Override
    public MemcachedSessionService getService() {
        return ((MemcachedSessionService.SessionManager) getContext().getManager()).getMemcachedSessionService();
    }

    @Override
    public Engine getEngine() {
        return (Engine) tomcat.getContainer();
    }

    @Override
    public void setChangeSessionIdOnAuth(final boolean changeSessionIdOnAuth) {
        final Engine engine = (StandardEngine)tomcat.getContainer();
        final Host host = (Host)engine.findChild( DEFAULT_HOST );
        final Container context = host.findChild( CONTEXT_PATH );
        final Valve first = context.getPipeline().getFirst();
        if ( first instanceof AuthenticatorBase) {
            ((AuthenticatorBase)first).setChangeSessionIdOnAuthentication( changeSessionIdOnAuth );
        }
    }

    @edu.umd.cs.findbugs.annotations.SuppressWarnings( "RV_RETURN_VALUE_IGNORED_BAD_PRACTICE" )
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

        final MemcachedSessionService.SessionManager sessionManager = createSessionManager();
        context.setManager( sessionManager );
        context.setBackgroundProcessorDelay( 1 );
        context.setCookies(cookies);
        new File( "webapp" + File.separator + "webapp" ).mkdirs();

        if ( loginType != null ) {
            context.addConstraint( createSecurityConstraint( "/*", ROLE_NAME ) );
            // context.addConstraint( createSecurityConstraint( "/j_security_check", null ) );
            context.addSecurityRole( ROLE_NAME );
            final LoginConfig loginConfig = loginType == TestUtils.LoginType.FORM
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

    @Nonnull
    protected Context createContext( @Nonnull final Embedded catalina, @Nonnull final String contextPath, @Nonnull final String docBase ) {
        return catalina.createContext( contextPath, docBase );
    }

    /**
     * Must create a {@link SessionManager} for the current tomcat version.
     */
    @Nonnull
    protected SessionManager createSessionManager() {
        return new MemcachedBackupSessionManager();
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

}
