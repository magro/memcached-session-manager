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
import java.security.Security;

import javax.annotation.Nonnull;
import javax.security.auth.message.config.AuthConfigFactory;
import javax.servlet.ServletException;

import de.javakaffee.web.msm.MemcachedSessionService;
import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Valve;
import org.apache.catalina.authenticator.AuthenticatorBase;
import org.apache.catalina.authenticator.jaspic.AuthConfigFactoryImpl;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.LoginConfig;
import org.apache.tomcat.util.descriptor.web.SecurityCollection;
import org.apache.tomcat.util.descriptor.web.SecurityConstraint;

import de.javakaffee.web.msm.MemcachedBackupSessionManager;
import de.javakaffee.web.msm.MemcachedSessionService.SessionManager;
import de.javakaffee.web.msm.integration.TestUtils.LoginType;
import edu.umd.cs.findbugs.annotations.SuppressWarnings;

/**
 * Builder for {@link Tomcat} tomcat.
 *
 * @author @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class Tomcat8Builder extends TomcatBuilder<Tomcat> {

    static {
        // set jaspic AuthConfigFactory to prevent NPEs like this:
        // java.lang.NullPointerException
        //     at org.apache.catalina.authenticator.AuthenticatorBase.getJaspicProvider(AuthenticatorBase.java:1140)
        //     at org.apache.catalina.authenticator.AuthenticatorBase.invoke(AuthenticatorBase.java:431)
        //     at org.apache.catalina.core.StandardHostValve.invoke(StandardHostValve.java:140)
        Security.setProperty(AuthConfigFactory.DEFAULT_FACTORY_SECURITY_PROPERTY, AuthConfigFactoryImpl.class.getName());
    }

    @SuppressWarnings( "RV_RETURN_VALUE_IGNORED_BAD_PRACTICE" )
    public Tomcat build() throws MalformedURLException,
            UnknownHostException, LifecycleException {

        final Tomcat tomcat = new Tomcat();

        final URL root = new URL( TestUtils.class.getResource( "/" ), "../test-classes" );
        // use file to get correct separator char, replace %20 introduced by URL for spaces
        final String cleanedRoot = new File( root.getFile().replaceAll("%20", " ") ).toString();

        final String fileSeparator = File.separator.equals( "\\" ) ? "\\\\" : File.separator;
        final String docBase = cleanedRoot + File.separator + TestUtils.class.getPackage().getName().replaceAll( "\\.", fileSeparator );

        final Connector connector = tomcat.getConnector();
        connector.setPort(port);
        connector.setProperty("bindOnInit", "false");

        /* we must have a unique name for mbeans
         */
        tomcat.getEngine().setName("engine-" + port);
        tomcat.getEngine().setJvmRoute(jvmRoute);

        tomcat.addUser(USER_NAME,PASSWORD);
        tomcat.addRole(USER_NAME,ROLE_NAME);

        // brings logging.properties
        tomcat.setBaseDir(docBase);
        final SessionManager sessionManager = createSessionManager();


        Context context;
        try {
            context = tomcat.addWebapp(CONTEXT_PATH, docBase + fileSeparator + "webapp");
        } catch (ServletException e) {
            throw new IllegalStateException(e);
        }

        context.setManager( sessionManager );
        context.setBackgroundProcessorDelay( 1 );
        context.setCookies(cookies);

        if ( loginType != null ) {
            context.addConstraint( createSecurityConstraint( "/*", ROLE_NAME ) );
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
        sessionManager.getMemcachedSessionService().setStorageKeyPrefix(storageKeyPrefix);

        return tomcat;
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

    private Tomcat tomcat;

    @Override
    public Tomcat8Builder buildAndStart() throws Exception {
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
        return (Context) tomcat.getHost().findChild( CONTEXT_PATH );
    }

    @Override
    public SessionManager getManager() {
        return (SessionManager) getContext().getManager();
    }

    @Override
    public MemcachedSessionService getService() {
        return ((SessionManager) getContext().getManager()).getMemcachedSessionService();
    }

    @Override
    public Engine getEngine() {
        return tomcat.getEngine();
    }

    @Override
    public void setChangeSessionIdOnAuth(final boolean changeSessionIdOnAuth) {
        final Engine engine = tomcat.getEngine();
        final Host host = (Host)engine.findChild( DEFAULT_HOST );
        final Container context = host.findChild( CONTEXT_PATH );
        final Valve first = context.getPipeline().getFirst();
        if ( first instanceof AuthenticatorBase ) {
            ((AuthenticatorBase)first).setChangeSessionIdOnAuthentication( changeSessionIdOnAuth );
        }
    }

}
