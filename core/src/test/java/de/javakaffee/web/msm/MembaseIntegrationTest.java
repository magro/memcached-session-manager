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
import static de.javakaffee.web.msm.integration.TestUtils.getService;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import net.spy.memcached.MemcachedClient;
import net.spy.memcached.vbucket.ConfigurationException;

import org.apache.catalina.startup.Embedded;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.couchbase.mock.CouchbaseMock;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import de.javakaffee.web.msm.BackupSessionTask.BackupResult;
import de.javakaffee.web.msm.SessionTrackerValve.SessionBackupService.BackupResultStatus;
import de.javakaffee.web.msm.integration.TestUtils;

/**
 * @author @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public abstract class MembaseIntegrationTest {

    private static final Log LOG = LogFactory.getLog(MembaseIntegrationTest.class);

    private final List<Pair<CouchbaseMock, Thread>> cluster = new ArrayList<Pair<CouchbaseMock,Thread>>(2);
    private MemcachedClient mc;

    private Embedded _tomcat1;
    private final int _portTomcat1 = 18888;

    private boolean membaseProvided;
    private TranscoderService transcoderService;

    abstract TestUtils getTestUtils();

    @BeforeMethod
    public void setUp(final Method testMethod) throws Throwable {
    	
        membaseProvided = Boolean.parseBoolean(System.getProperty("membase.provided", "false"));
        final int membasePort = Integer.parseInt(System.getProperty("membase.port", "18091"));

        if(!membaseProvided) {
            cluster.add(setupMembase(membasePort));
        }

        try {
            System.setProperty( "org.apache.catalina.startup.EXIT_ON_INIT_FAILURE", "true" );
            _tomcat1 = getTestUtils().createCatalina(_portTomcat1, "http://localhost:"+ membasePort +"/pools");
            getManager( _tomcat1 ).setSticky( true );
            getService(_tomcat1).setMemcachedProtocol("binary");
            getManager(_tomcat1).setUsername("default");
            _tomcat1.start();
        } catch ( final Throwable e ) {
            LOG.error( "could not start tomcat.", e );
            throw e;
        }

        setupMembaseClient();

        transcoderService = new TranscoderService(new JavaSerializationTranscoder(getManager(_tomcat1)));
    }

    @AfterMethod
    public void tearDown() throws Exception {
        mc.shutdown();
        mc = null;
        
        if(!membaseProvided) {
            tearDownMembase();
        }

        _tomcat1.stop();
    }
    
    @Test
    public void testBackupSessionInMembase() throws InterruptedException, ExecutionException {
        final MemcachedSessionService service = getService(_tomcat1);
        final MemcachedBackupSession session = createSession( service );
        final String sessionId = "12345";
        session.setId(sessionId);
        session.setAttribute( "foo", "bar" );

        final BackupResult backupResult = service.backupSession( session.getIdInternal(), false, null ).get();
        assertEquals(backupResult.getStatus(), BackupResultStatus.SUCCESS);
        
        final MemcachedBackupSession loadedSession = transcoderService.deserialize((byte[])mc.get(sessionId), getManager(_tomcat1));
        checkSession(loadedSession, session);
    }
    
    @Test(enabled = false) // spurious failures
    public void testBackupSessionInMembaseCluster() throws Exception {
        final MemcachedSessionService service = getService(_tomcat1);
        
        cluster.add(setupMembase(getMaxMembasePort() + 1));
        service.setMemcachedNodes(getMemcachedNodesConfig(getURIs()));
        setupMembaseClient();
        
        waitForReconnect(service.getMemcached(), cluster.size(), 1000);
        waitForReconnect(mc, cluster.size(), 1000);
        
        final MemcachedBackupSession session = createSession( service );
        final String sessionId = "12345";
        session.setId(sessionId);
        session.setAttribute( "foo", "bar" );

        final BackupResult backupResult = service.backupSession( session.getIdInternal(), false, null ).get();
        assertEquals(backupResult.getStatus(), BackupResultStatus.SUCCESS);
        
        final MemcachedBackupSession loadedSession = transcoderService.deserialize((byte[])mc.get(sessionId), getManager(_tomcat1));
        checkSession(loadedSession, session);
    }

    private void checkSession(final MemcachedBackupSession actual, final MemcachedBackupSession expected) {
        assertNotNull(actual);
        assertEquals(actual.getId(), expected.getId());
        assertEquals(actual.getAttributesInternal(), expected.getAttributesInternal());
    }

    private void waitForReconnect( final MemcachedClient client, final int expectedServers, final long timeToWait )
            throws InterruptedException, RuntimeException {
        final long start = System.currentTimeMillis();
        while( System.currentTimeMillis() < start + timeToWait ) {
            if(client.getAvailableServers().size() == expectedServers) {
                return;
            }
            Thread.sleep( 20 );
        }
        throw new RuntimeException( "MemcachedClient did not reconnect after " + timeToWait + " millis." );
    }

    private void setupMembaseClient() throws URISyntaxException, IOException, ConfigurationException {
        if(mc != null) {
            LOG.info("Closing existing membase client.");
            mc.shutdown();
        }
        final List<URI> uris = getURIs();
        LOG.info("Creating new membase client with uris " + uris);
        mc = new MemcachedClient(uris, "default", "");
    }

    private List<URI> getURIs() throws URISyntaxException {
        final List<URI> uris = new ArrayList<URI>(cluster.size());
        for (final Pair<CouchbaseMock, Thread> server : cluster) {
            uris.add(new URI("http://localhost:"+ server.getFirst().getHttpPort() +"/pools"));
        }
        return uris;
    }

    private Pair<CouchbaseMock, Thread> setupMembase(final int membasePort) throws IOException {
        final CouchbaseMock membase = new CouchbaseMock("localhost", membasePort, 1, 1);
        membase.setRequiredHttpAuthorization(null);
        final Thread thread = new Thread(membase);
        thread.start();
        return Pair.of(membase, thread);
    }

    private void tearDownMembase() throws InterruptedException {
        for (final Pair<CouchbaseMock, Thread> server : cluster) {
            server.getSecond().interrupt();
            server.getSecond().join(1000);
            server.getFirst().close();
        }
        cluster.clear();
    }

    private String getMemcachedNodesConfig(final List<URI> urIs) {
        final StringBuilder sb = new StringBuilder();
        for (final URI uri : urIs) {
            if(sb.length() > 1) sb.append(",");
            sb.append(uri.toString());
        }
        final String membaseNodes = sb.toString();
        return membaseNodes;
    }

    private int getMaxMembasePort() {
        return cluster.get(cluster.size() - 1).getFirst().getHttpPort();
    }
    
}
