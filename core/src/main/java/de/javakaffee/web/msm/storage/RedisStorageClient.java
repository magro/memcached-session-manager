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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import redis.clients.jedis.BinaryJedis;
import redis.clients.jedis.exceptions.JedisConnectionException;

import de.javakaffee.web.msm.NamedThreadFactory;

/**
 * Storage client backed by a Jedis client instance.
 */
public class RedisStorageClient implements StorageClient {
    protected static final Log _log = LogFactory.getLog(RedisStorageClient.class);

    private final String _host;
    private final int _port;
    private final boolean _ssl;
    private final JedisPool _pool = new JedisPool();
    private final ExecutorService _executor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(), new NamedThreadFactory("msm-redis-client"));

    /**
     * Creates a <code>MemcachedStorageClient</code> instance which connects to the given Redis URL.
     * 
     * @param redisUrl redis URL 
     */
    public RedisStorageClient(String redisUrl) {
        if (redisUrl == null)
            throw new NullPointerException("Param \"redisUrl\" may not be null");
        
        if (_log.isDebugEnabled())
            _log.debug(String.format("Creating RedisStorageClient with URL \"%s\"", redisUrl));

        // Support a Redis URL in the form "redis://hostname:port" or "rediss://" (for SSL connections) like the client "Lettuce" does
        if (!(redisUrl.startsWith("redis://") || redisUrl.startsWith("rediss://")))
            throw new IllegalArgumentException("Redis URL must start with \"redis://\" or \"rediss://\"");
        
        _ssl = redisUrl.startsWith("rediss://");

        String hostNamePort = redisUrl.substring(redisUrl.indexOf('/') + 2);
        int pos = hostNamePort.indexOf(':');
        if (pos != -1) {
            _host = hostNamePort.substring(0, pos);
            try {
                _port = Integer.parseInt(hostNamePort.substring(pos + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Error parsing port number in Redis URL");
            }
        } else {
            _host = hostNamePort;
            _port = 6379;
        }
    }
    
    @Override
    public Future<Boolean> add(final String key, final int exp, final byte[] o) {
        
        if (_log.isDebugEnabled())
            _log.debug(String.format("Adding key to Redis (key=%s, exp=%s, o=%s)", key, exp, o.getClass().getName()));
        
        return _executor.submit(new RedisCommandCallable<Boolean>() {
            private volatile boolean _setCompleted;
            
            @Override protected Boolean execute(BinaryJedis jedis) throws Exception {
                byte[] kb = keyBytes(key);
                if (_setCompleted || jedis.setnx(kb, o) == 1) {
                    _setCompleted = true; // make sure to not call setnx() a second time if connection fails
                    if (exp == 0)
                        return true;
                    else
                        return jedis.expire(kb, convertExp(exp)) == 1;
                } else {
                    return false;
                }
            }
        });
    }

    @Override
    public Future<Boolean> set(final String key, final int exp, final byte[] o) {
        if (_log.isDebugEnabled())
            _log.debug(String.format("Setting key in Redis (key=%s, exp=%s, o=%s)", key, exp, o.getClass().getName()));
        
        return _executor.submit(new RedisCommandCallable<Boolean>() {
            @Override protected Boolean execute(BinaryJedis jedis) throws Exception {
                if (exp == 0)
                    return jedis.set(keyBytes(key), o).equals("OK");
                else
                    return jedis.setex(keyBytes(key), convertExp(exp), o).equals("OK");
            }
        });
    }
    
    @Override
    public byte[] get(final String key) {
        if (_log.isDebugEnabled())
            _log.debug(String.format("Getting key from Redis (key=%s)", key));
        
        Callable<byte[]> callable = new RedisCommandCallable<byte[]>() {
            @Override protected byte[] execute(BinaryJedis jedis) throws Exception {
                return jedis.get(keyBytes(key));
            }
        };
        
        // Execute callable synchronously since we need to wait for the result anyway
        try {
            return callable.call();
        }
        catch (Exception e) {
            if (e instanceof RuntimeException)
                throw (RuntimeException)e;
            else
                throw new RuntimeException("Error getting key from Redis", e);
        }
    }
    
    @Override
    public Future<Boolean> delete(final String key) {
        if (_log.isDebugEnabled())
            _log.debug(String.format("Deleting key in Redis (key=%s)", key));

        return _executor.submit(new RedisCommandCallable<Boolean>() {
            @Override protected Boolean execute(BinaryJedis jedis) throws Exception {
                return jedis.del(keyBytes(key)) == 1;
            }
        });
    }

    @Override
    public void shutdown() {
        _pool.shutdown();
    }
    
    private static int convertExp(int exp) {
        if (exp <= 60*60*24*30) // thirty days
            return exp;
        else
            return Math.max(exp - (int)(System.currentTimeMillis() / 1000), 1);
    }
    
    private static byte[] keyBytes(String key) {
        return key.getBytes(StandardCharsets.UTF_8);
    }
    
    private abstract class RedisCommandCallable<T> implements Callable<T> {
        @Override
        public T call() throws Exception {
            BinaryJedis jedis = null;

            // Borrow an instance from Jedis without checking it for performance reasons and execute the command on it
            try {
                jedis = _pool.borrowInstance(false);
                return execute(jedis);
            } catch (JedisConnectionException e) {
                // Connection error occurred with this Jedis connection, so now make sure to get a known-good one
                // The old connection is not given back to the pool since it is defunct anyway
                if (_log.isDebugEnabled())
                    _log.debug("Connection error occurred, discarding Jedis connection: " + e.getMessage());

                jedis = null;
            } finally {
                if (jedis != null)
                    _pool.returnInstance(jedis);
            }
            
            // Try to execute the command again with a known-good instance
            try {
                jedis = _pool.borrowInstance(true);
                return execute(jedis);
            } finally {
                if (jedis != null)
                    _pool.returnInstance(jedis);
            }
        }
        
        protected abstract T execute(BinaryJedis jedis) throws Exception;
    }

    private class JedisPool {
        private List<BinaryJedis> _instances = new ArrayList<BinaryJedis>();

        public BinaryJedis borrowInstance(boolean knownGood) {
            synchronized (_instances) {
                if (_instances.isEmpty()) {
                    if (_log.isDebugEnabled()) {
                        _log.debug(String.format("Creating new Jedis instance (host=%s, port=%s, ssl=%s)",
                            _host, _port, _ssl));
                    }

                    return createJedisInstance();
                } else {
                    if (knownGood) {
                        // Check all existing connections until we find a good one
                        BinaryJedis jedis;
                        do {
                            jedis = _instances.remove(_instances.size() - 1);
                            try {
                                jedis.ping();
                                
                                if (_log.isDebugEnabled())
                                    _log.debug(String.format("Using known-good connection #%d", _instances.size()));

                                return jedis;
                            } catch (Exception e) {
                                if (_log.isDebugEnabled()) {
                                    _log.debug(String.format("Removing connection #%d since it cannot be pinged", _instances.size()));
                                }
                            }
                        } while (!_instances.isEmpty());

                        // No existing connections are good, so create new one
                        if (_log.isDebugEnabled()) {
                            _log.debug(String.format("Creating new Jedis instance (host=%s, port=%s, ssl=%s) since all existing connections were bad",
                                _host, _port, _ssl));
                        }
                        
                        return createJedisInstance();
                    } else {
                        if (_log.isDebugEnabled())
                            _log.debug(String.format("Using connection #%d", _instances.size() - 1));

                        return _instances.remove(_instances.size() - 1);
                    }
                }
            }
        }
        
        public void returnInstance(BinaryJedis instance) {
            synchronized (_instances) {
                _instances.add(instance);
                
                if (_log.isDebugEnabled())
                    _log.debug(String.format("Returned instance #%d", _instances.size() - 1));
            }
        }
        
        public void shutdown() {
            synchronized (_instances) {
                if (_log.isDebugEnabled())
                    _log.debug(String.format("Closing %d remaining Jedis instance(s)", _instances.size()));
                
                for (BinaryJedis jedis: _instances) {
                    try { jedis.close(); } catch (Exception e) { /* ignore exception */ }
                }
                _instances.clear();
            }
        }
        
        private BinaryJedis createJedisInstance() {
            return new BinaryJedis(_host, _port, _ssl);
        }
    }
}
