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
 *
 */
package de.javakaffee.web.msm.storage;

import static org.testng.Assert.*;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import org.testng.annotations.*;
import redis.embedded.RedisServer;

/**
 * Test the {@link RedisStorageClient}.
 *
 * @author <a href="mailto:markus@ellinger.it">Markus Ellinger</a>
 */
public class RedisStorageClientTest {

    private RedisServer embeddedRedisServer;
    private boolean redisProvided;
    private int redisPort;
    
    @BeforeMethod
    public void setUp(final Method testMethod) throws Exception {
        redisProvided = Boolean.parseBoolean(System.getProperty("redis.provided", "false"));
        redisPort = Integer.parseInt(System.getProperty("redis.port", "16379"));

        if (!redisProvided) {
            embeddedRedisServer = new RedisServer(redisPort);
            embeddedRedisServer.start();
        }
    }
    
    @AfterMethod
    public void tearDown() throws Exception {
        if (embeddedRedisServer != null) {
            embeddedRedisServer.stop();
            embeddedRedisServer = null;
        }
    }

    @Test
    public void testFunctions() throws Exception {
        RedisStorageClient client = createClient();
        
        // Add two keys
        assertTrue(client.add("key1", 0, toBytes("foo")).get());
        assertTrue(client.add("key2", 0, toBytes("bar")).get());
        
        // Check that the keys have the given value
        assertEquals("foo", toString(client.get("key1")));
        assertEquals("bar", toString(client.get("key2")));
        
        // Check difference between add() and set()
        assertTrue(client.set("key1", 0, toBytes("baz")).get());
        assertFalse(client.add("key2", 0, toBytes("zoom")).get());
        
        assertEquals("baz", toString(client.get("key1")));
        assertEquals("bar", toString(client.get("key2")));
        
        // Delete key, make sure it is not accessible anymore, but other key should still be there
        assertTrue(client.delete("key1").get());
        assertNull(client.get("key1"));
        assertEquals("bar", toString(client.get("key2")));
        
        client.shutdown();
    }
    
    @Test
    public void testExpirationSeconds() throws Exception {
        RedisStorageClient client = createClient();
        
        // Add a key which expires
        assertTrue(client.add("exp", 2, toBytes("foo")).get());
        
        // Wait some time
        Thread.sleep(1000);
        
        // Key should still be there
        assertEquals("foo", toString(client.get("exp")));
        
        // Wait some more time
        Thread.sleep(2000);
        
        // Now key should be expired
        assertNull(client.get("exp"));
        
        client.shutdown();
    }
    
    @Test
    public void testExpirationTime() throws Exception {
        RedisStorageClient client = createClient();
        
        // Add a key which expires
        assertTrue(client.add("exp", (int)(2 + (System.currentTimeMillis() / 1000)), toBytes("foo")).get());
        assertEquals("foo", toString(client.get("exp")));
        
        // Wait some time
        Thread.sleep(1000);
        
        // Key should still be there
        assertEquals("foo", toString(client.get("exp")));
        
        // Wait some more time
        Thread.sleep(2000);
        
        // Now key should be expired
        assertNull(client.get("exp"));
        
        client.shutdown();
    }
    
    @Test
    public void testAutoReconnect() throws Exception {
        RedisStorageClient client = createClient();

        // Issue a command to create a connection
        assertTrue(client.add("key1", 0, toBytes("foo")).get());
        assertEquals("foo", toString(client.get("key1")));
        
        // Stop and start server to close all connections
        if (!redisProvided) {
            embeddedRedisServer.stop();
            embeddedRedisServer.start();
        }

        // If we now issue commands, the old connection is defunct and will be replaced
        assertTrue(client.add("key1", 0, toBytes("foo")).get());
        assertEquals("foo", toString(client.get("key1")));

        client.shutdown();
    }
    
    private RedisStorageClient createClient() {
       return new RedisStorageClient("redis://localhost:" + redisPort, 1000);
    }
    
    private byte[] toBytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private String toString(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
