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
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import de.javakaffee.web.msm.storage.MemcachedStorageClient;
import de.javakaffee.web.msm.storage.StorageClient;
import net.spy.memcached.MemcachedClient;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.couchbase.mock.CouchbaseMock;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.couchbase.client.CouchbaseClient;

import de.javakaffee.web.msm.BackupSessionTask.BackupResult;
import de.javakaffee.web.msm.integration.TestUtils;
import de.javakaffee.web.msm.integration.TomcatBuilder;
import de.javakaffee.web.msm.storage.MemcachedStorageClient.ByteArrayTranscoder;

/**
 * @author @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public abstract class CouchbaseIntegrationTest {

    private static final Log LOG = LogFactory.getLog(CouchbaseIntegrationTest.class);

    private final List<Pair<CouchbaseMock, Thread>> cluster = new ArrayList<Pair<CouchbaseMock,Thread>>(2);
    private MemcachedClient mc;

    private TomcatBuilder<?> _tomcat1;
    private final int _portTomcat1 = 18888;

    private boolean couchbaseProvided;
    private TranscoderService transcoderService;

    abstract TestUtils<?> getTestUtils();

    @BeforeMethod
    public void setUp(final Method testMethod) throws Throwable {

        couchbaseProvided = Boolean.parseBoolean(System.getProperty("couchbase.provided", "false"));
        final int couchbasePort = Integer.parseInt(System.getProperty("couchbase.port", "18091"));

        if(!couchbaseProvided) {
            cluster.add(setupCouchbase(couchbasePort));
        }

        try {
            System.setProperty( "org.apache.catalina.startup.EXIT_ON_INIT_FAILURE", "true" );
            _tomcat1 = getTestUtils().tomcatBuilder().port(_portTomcat1).memcachedNodes("http://localhost:"+ couchbasePort +"/pools")
                    .sticky(true).memcachedProtocol("binary").username("default").buildAndStart();
        } catch ( final Throwable e ) {
            LOG.error( "could not start tomcat.", e );
            throw e;
        }

        setupCouchbaseClient();

        transcoderService = new TranscoderService(new JavaSerializationTranscoder(_tomcat1.getManager()));
    }

    @AfterMethod
    public void tearDown() throws Exception {
        mc.shutdown();
        mc = null;

        if(!couchbaseProvided) {
            tearDownCouchbase();
        }

        _tomcat1.stop();
    }

    @Test
    public void testBackupSessionInCouchbase() throws InterruptedException, ExecutionException {
        final MemcachedSessionService service = _tomcat1.getService();
        final MemcachedBackupSession session = createSession( service );
        final String sessionId = "12345";
        session.setId(sessionId);
        session.setAttribute( "foo", "bar" );

        final BackupResult backupResult = service.backupSession( session.getIdInternal(), false, null ).get();
        assertEquals(backupResult.getStatus(), BackupResultStatus.SUCCESS);

        final MemcachedBackupSession loadedSession = transcoderService.deserialize(mc.get(sessionId, ByteArrayTranscoder.INSTANCE), _tomcat1.getManager());
        checkSession(loadedSession, session);
    }

    @Test(enabled = false) // spurious failures
    public void testBackupSessionInCouchbaseCluster() throws Exception {
        final MemcachedSessionService service = _tomcat1.getService();

        cluster.add(setupCouchbase(getMaxCouchbasePort() + 1));
        service.setMemcachedNodes(getMemcachedNodesConfig(getURIs()));
        setupCouchbaseClient();

        waitForReconnect(service.getStorageClient(), cluster.size(), 1000);
        waitForReconnect(mc, cluster.size(), 1000);

        final MemcachedBackupSession session = createSession( service );
        final String sessionId = "12345";
        session.setId(sessionId);
        session.setAttribute( "foo", "bar" );

        final BackupResult backupResult = service.backupSession( session.getIdInternal(), false, null ).get();
        assertEquals(backupResult.getStatus(), BackupResultStatus.SUCCESS);

        final MemcachedBackupSession loadedSession = transcoderService.deserialize(mc.get(sessionId, ByteArrayTranscoder.INSTANCE), _tomcat1.getManager());
        checkSession(loadedSession, session);
    }

    private void checkSession(final MemcachedBackupSession actual, final MemcachedBackupSession expected) {
        assertNotNull(actual);
        assertEquals(actual.getId(), expected.getId());
        assertEquals(actual.getAttributesInternal(), expected.getAttributesInternal());
    }

    private void waitForReconnect(final StorageClient client, final int expectedServers, final long timeToWait )
            throws InterruptedException, RuntimeException {
        waitForReconnect(((MemcachedStorageClient)client).getMemcachedClient(), expectedServers, timeToWait);
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

    private void setupCouchbaseClient() throws URISyntaxException, IOException {
        if(mc != null) {
            LOG.info("Closing existing couchbase client.");
            mc.shutdown();
        }
        final List<URI> uris = getURIs();
        LOG.info("Creating new couchbase client with uris " + uris);
        mc = new CouchbaseClient(uris, "default", "");
    }

    private List<URI> getURIs() throws URISyntaxException {
        final List<URI> uris = new ArrayList<URI>(cluster.size());
        for (final Pair<CouchbaseMock, Thread> server : cluster) {
            uris.add(new URI("http://localhost:"+ server.getFirst().getHttpPort() +"/pools"));
        }
        return uris;
    }

    private Pair<CouchbaseMock, Thread> setupCouchbase(final int couchbasePort) throws IOException {
        final CouchbaseMock couchbase = new CouchbaseMock("localhost", couchbasePort, 1, 1);
        couchbase.setRequiredHttpAuthorization(null);
        final Thread thread = new Thread(couchbase);
        thread.start();
        return Pair.of(couchbase, thread);
    }

    private void tearDownCouchbase() throws InterruptedException {
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
        final String couchbaseNodes = sb.toString();
        return couchbaseNodes;
    }

    private int getMaxCouchbasePort() {
        return cluster.get(cluster.size() - 1).getFirst().getHttpPort();
    }

}
