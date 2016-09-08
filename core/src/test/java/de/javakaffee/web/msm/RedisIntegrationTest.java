/*
 * Copyright 2016 Markus Ellinger
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
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import redis.clients.jedis.BinaryJedis;
import redis.embedded.RedisServer;

import de.javakaffee.web.msm.BackupSessionTask.BackupResult;
import de.javakaffee.web.msm.integration.TestUtils;
import de.javakaffee.web.msm.integration.TomcatBuilder;
import de.javakaffee.web.msm.storage.RedisStorageClient;

/**
 * @author @author <a href="mailto:markus@ellinger.it">Markus Ellinger</a>
 */
public abstract class RedisIntegrationTest {

    private static final Log LOG = LogFactory.getLog(RedisIntegrationTest.class);

    private RedisServer embeddedRedisServer;
    
    private BinaryJedis redisClient;

    private TomcatBuilder<?> _tomcat1;
    private final int _portTomcat1 = 18888;

    private boolean redisProvided;
    private TranscoderService transcoderService;

    abstract TestUtils<?> getTestUtils();

    @BeforeMethod
    public void setUp(final Method testMethod) throws Throwable {

        redisProvided = Boolean.parseBoolean(System.getProperty("redis.provided", "false"));
        final int redisPort = Integer.parseInt(System.getProperty("redis.port", "16379"));

        if (!redisProvided) {
            embeddedRedisServer = new RedisServer(redisPort);
            embeddedRedisServer.start();
        }
        
        try {
            System.setProperty( "org.apache.catalina.startup.EXIT_ON_INIT_FAILURE", "true" );
            _tomcat1 = getTestUtils().tomcatBuilder().port(_portTomcat1).memcachedNodes("redis://localhost:"+ redisPort)
                                     .sticky(true).buildAndStart();
        } catch ( final Throwable e ) {
            LOG.error( "could not start tomcat.", e );
            throw e;
        }

        redisClient = new BinaryJedis("localhost", redisPort);

        transcoderService = new TranscoderService(new JavaSerializationTranscoder(_tomcat1.getManager()));
    }

    @AfterMethod
    public void tearDown() throws Exception {
        if (redisClient != null) {
            redisClient.close();
            redisClient = null;
        }
        
        if (embeddedRedisServer != null) {
            embeddedRedisServer.stop();
            embeddedRedisServer = null;
        }

        _tomcat1.stop();
    }

    @Test
    public void testBackupSessionInRedis()
            throws InterruptedException, ExecutionException, UnsupportedEncodingException, ClassNotFoundException, IOException {
        final MemcachedSessionService service = _tomcat1.getService();
        final MemcachedBackupSession session = createSession( service );
        final String sessionId = "12345";
        session.setId(sessionId);
        session.setAttribute( "foo", "bar" );

        final BackupResult backupResult = service.backupSession( session.getIdInternal(), false, null ).get();
        assertEquals(backupResult.getStatus(), BackupResultStatus.SUCCESS);

        final MemcachedBackupSession loadedSession = transcoderService.deserialize(
                redisClient.get(sessionId.getBytes("UTF-8")), _tomcat1.getManager());
        checkSession(loadedSession, session);
    }

    private void checkSession(final MemcachedBackupSession actual, final MemcachedBackupSession expected) {
        assertNotNull(actual);
        assertEquals(actual.getId(), expected.getId());
        assertEquals(actual.getAttributesInternal(), expected.getAttributesInternal());
    }
}
