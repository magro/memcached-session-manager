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

import java.beans.PropertyChangeListener;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Loader;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardEngine;
import org.apache.catalina.core.StandardServer;
import org.apache.catalina.startup.ContextConfig;
import org.apache.catalina.startup.Embedded;

import de.javakaffee.web.msm.JavaSerializationTranscoderFactory;
import de.javakaffee.web.msm.MemcachedBackupSessionManager;
import de.javakaffee.web.msm.MemcachedSessionService.SessionManager;
import edu.umd.cs.findbugs.annotations.SuppressWarnings;

/**
 * Class to test embedded tomcat with jbossweb. The main method starts an
 * embedded tomcat that should read the web.xml from
 * <code>de/javakaffee/web/msm/integration/webapp/WEB-INF</code>
 * (located src/main/resources) and waits for requests.
 * 
 * Unfortunately, it seems as if the web.xml is not read as the specified
 * TestServlet is not instantiated.
 * 
 * Thus, all requests are answered with status 400.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class TestEmbeddedJBW3 {
    
    public static void main( final String[] args ) throws MalformedURLException, UnknownHostException, LifecycleException, InterruptedException {

        final Embedded catalina = new TestEmbeddedJBW3().createCatalina( 18888 );
        catalina.start();
        
        Thread.sleep( Long.MAX_VALUE );
        
    }

    protected SessionManager createSessionManager() {
        return new MemcachedBackupSessionManager();
    }
    
    protected Context createContext( final Embedded catalina, final String contextPath, final String docBase ) {
        final Context result = catalina.createContext( CONTEXT_PATH, docBase, new ContextConfig() );
        return result;
    }

    private static final String CONTEXT_PATH = "/";
    private static final String DEFAULT_HOST = "localhost";
    private static final String DEFAULT_TRANSCODER_FACTORY = JavaSerializationTranscoderFactory.class.getName();

    protected static final String PASSWORD = "secret";
    protected static final String USER_NAME = "testuser";
    protected static final String ROLE_NAME = "test";

    public static final String STICKYNESS_PROVIDER = "stickynessProvider";

    @SuppressWarnings( "RV_RETURN_VALUE_IGNORED_BAD_PRACTICE" )
    public Embedded createCatalina( final int port ) throws MalformedURLException,
            UnknownHostException, LifecycleException {

        final Embedded catalina = new Embedded();

        final StandardServer server = new StandardServer();
        server.addService( catalina );


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
        
        ((StandardEngine)engine).setBaseDir( docBase ); // needed for jbossweb
        
        catalina.addEngine( engine );
        engine.setService( catalina );
        catalina.setName( engine.getName() ); // needed for jbossweb / lookup in MapperListener
        
        final Host host = catalina.createHost( DEFAULT_HOST, docBase );
        engine.addChild( host );
        new File( docBase ).mkdirs();

        final Context context = createContext( catalina, CONTEXT_PATH, docBase + File.separator + "webapp" );
        context.setLoader( new StandardLoader() );
        host.addChild( context );

        final SessionManager sessionManager = createSessionManager();
        context.setManager( sessionManager );
        context.setBackgroundProcessorDelay( 1 );

        // manually map servlet for jbw3
        final Wrapper wrapper = context.createWrapper();
        wrapper.setName( "default" );
        wrapper.setServletClass( TestServlet.class.getName() );
        wrapper.setLoadOnStartup(1);
        context.addChild(wrapper);
        context.addServletMapping( "/*" , "default" );

        /* we must set the maxInactiveInterval after the context,
         * as setContainer(context) uses the session timeout set on the context
         */
        sessionManager.getMemcachedSessionService().setEnabled( false );
        sessionManager.getMemcachedSessionService().setMemcachedNodes( "n1:localhost:21211" );
        sessionManager.setMaxInactiveInterval( 60 ); // 60 seconds
        sessionManager.getMemcachedSessionService().setSessionBackupAsync( false );
        sessionManager.getMemcachedSessionService().setSessionBackupTimeout( 100 );
        sessionManager.setProcessExpiresFrequency( 1 ); // 1 second (factor for context.setBackgroundProcessorDelay)
        sessionManager.getMemcachedSessionService().setTranscoderFactoryClass( DEFAULT_TRANSCODER_FACTORY );

        final Connector connector = catalina.createConnector( "localhost", port, false );
        connector.setProperty("bindOnInit", "false");
        catalina.addConnector( connector );

        return catalina;
    }

    private static final class StandardLoader implements Loader {
        private Container _container;
        private boolean _delegate;

        @Override
        public ClassLoader getClassLoader() {
            return Thread.currentThread().getContextClassLoader();
        }

        @Override
        public void addPropertyChangeListener( final PropertyChangeListener arg0 ) {}

        @Override
        public void addRepository( final String arg0 ) {
            ;
        }

        @Override
        public void backgroundProcess() {}

        @Override
        public Container getContainer() {
            return _container;
        }

        @Override
        public String getInfo() {
            return "Dummy Loader";
        }

        @Override
        public void removePropertyChangeListener( final PropertyChangeListener arg0 ) {}

        @Override
        public void setContainer( final Container arg0 ) {
            _container = arg0;
        }
        
    }

}
