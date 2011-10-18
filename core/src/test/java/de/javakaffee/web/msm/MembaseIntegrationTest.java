/*
 * Copyright 2011 Martin Grotzke
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
package de.javakaffee.web.msm;

import static de.javakaffee.web.msm.integration.TestUtils.createSession;
import static de.javakaffee.web.msm.integration.TestUtils.getManager;
import static org.testng.Assert.assertEquals;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

import net.spy.memcached.MemcachedClient;

import org.apache.catalina.startup.Embedded;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.couchbase.mock.CouchbaseMock;
import org.couchbase.mock.CouchbaseMock.BucketType;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import de.javakaffee.web.msm.BackupSessionTask.BackupResult;
import de.javakaffee.web.msm.MemcachedNodesManager.MemcachedClientCallback;
import de.javakaffee.web.msm.SessionTrackerValve.SessionBackupService.BackupResultStatus;
import de.javakaffee.web.msm.integration.TestUtils;

/**
 * @author @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public abstract class MembaseIntegrationTest {

    private static final Log LOG = LogFactory.getLog(MembaseIntegrationTest.class);
    
public static void main(final String[] args) throws IOException, URISyntaxException, InterruptedException, ExecutionException {
    // final CouchbaseMock instance = new CouchbaseMock("localhost", 18091, 1, 1);
    final CouchbaseMock instance = new CouchbaseMock("localhost", 18091, 1, 1099, 1, BucketType.BASE);
    instance.setRequiredHttpAuthorization(null);
    // instance.startServers();
    final Thread thread = new Thread(instance);
    thread.start();
    MemcachedClient mc = null;
    try {
        final URI base = new URI("http://localhost:18091/pools");
        mc = new MemcachedClient(Arrays.asList(base), "default", "");

        final Boolean setResult = mc.set("hello", 1000, "world").get();
        System.out.println("Result from set: " + setResult);
        // final String result = (String) mc.get("hello");
        final String result = (String) mc.get("hello");
        System.out.println("Got hello " + result);
    } finally {
        System.out.println("Stopping services...");
        mc.shutdown();
        // instance.shutdownServers();
        thread.interrupt();
        instance.close();
    }
}
    
    private CouchbaseMock membase;
    private Thread thread;
    private MemcachedClient mc;

    private Embedded _tomcat1;
    private final int _portTomcat1 = 18888;
    
    private final MemcachedClientCallback _memcachedClientCallback = new MemcachedClientCallback() {
        @Override
        public Object get(final String key) {
            return mc.get(key);
        }
    };
    
    abstract TestUtils getTestUtils();

    @BeforeMethod
    public void setUp(final Method testMethod) throws Throwable {
        System.out.println("Starting setup");
        
        membase = new CouchbaseMock("localhost", 18091, 1, 1);
        membase.setRequiredHttpAuthorization(null);
        thread = new Thread(membase);
        thread.start();

        try {
            System.setProperty( "org.apache.catalina.startup.EXIT_ON_INIT_FAILURE", "true" );
            _tomcat1 = getTestUtils().createCatalina(_portTomcat1, "http://localhost:18091/pools");
            getManager( _tomcat1 ).setSticky( true );
            getManager(_tomcat1).setUsername("default");
            _tomcat1.start();
        } catch ( final Throwable e ) {
            LOG.error( "could not start tomcat.", e );
            throw e;
        }

        final URI base = new URI("http://localhost:18091/pools");
        mc = new MemcachedClient(Arrays.asList(base), "default", "");
    }

    @AfterMethod
    public void tearDown() throws Exception {
        mc.shutdown();
        
        thread.interrupt();
        thread.join();
        //instance.shutdownServers();
        membase.close();

        _tomcat1.stop();
    }
    
    @Test
    public void testFirst() throws InterruptedException, ExecutionException {

        final MemcachedSessionService service = getManager(_tomcat1).getMemcachedSessionService();
        final MemcachedBackupSession session = createSession( service );
        session.setId("foo");

        session.access();
        session.endAccess();
        session.setAttribute( "foo", "bar" );

        final BackupResult backupResult = service.backupSession( session.getIdInternal(), false, null ).get();
        assertEquals(backupResult.getStatus(), BackupResultStatus.SUCCESS);
        
    }
    
    @org.junit.Test
    @Test
    public void testConnect() throws IOException, URISyntaxException, InterruptedException, ExecutionException {

        //Thread.sleep(1000000);
        System.out.println("Before set...");
        final Boolean setResult = mc.set("hello", 1000, "world").get();
        // Thread.sleep(500000);
        System.out.println("Result from set: " + setResult);
        // final String result = (String) mc.get("hello");
        final String result = (String) mc.get("hello");
        System.out.println("Got hello " + result);
//        mc.set("hello", 1000, "world");
//        final String result = (String) mc.get("hello");
//        System.out.println("Got hello " + result);
//        assertEquals(result, "world");
        
    }
    
}
