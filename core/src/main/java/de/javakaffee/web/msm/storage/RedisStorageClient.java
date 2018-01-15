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

import de.javakaffee.web.msm.NamedThreadFactory;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import redis.clients.jedis.BinaryJedis;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.lang.String.format;

/**
 * Storage client backed by a Jedis client instance.
 */
public class RedisStorageClient implements StorageClient {
    protected static final Log _log = LogFactory.getLog(RedisStorageClient.class);

    private final URI _uri;
    private final int _timeout;
    private final JedisPool _pool = new JedisPool();
    private final ExecutorService _executor = Executors.newCachedThreadPool(new NamedThreadFactory("msm-redis-client"));

    /**
     * Creates a <code>MemcachedStorageClient</code> instance which connects to the given Redis URL.
     *
     * @param redisUrl redis URL
     * @param operationTimeout the timeout to set for connection and socket timeout on the underlying jedis client.
     */
    public RedisStorageClient(String redisUrl, long operationTimeout) {
        if (redisUrl == null)
            throw new NullPointerException("Param \"redisUrl\" may not be null");
        
        if (_log.isDebugEnabled())
            _log.debug(format("Creating RedisStorageClient with URL \"%s\"", redisUrl));

        try {
            _uri = createURI(redisUrl);
        } catch (URISyntaxException e) {
            throw new RuntimeException("Error parsing redisUrl", e);
        }

        // we just expect no practical problem here...
        _timeout = (int)operationTimeout;
    }

    URI createURI(String redisUrl) throws URISyntaxException {
        URI uri = new URI(redisUrl);
        // set default port 6379 unless specified.
        if (uri.getPort() < 0)
            uri = new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), 6379, uri.getPath(), uri.getQuery(), uri.getFragment());
        return uri;
    }

    @Override
    public Future<Boolean> add(final String key, final int exp, final byte[] o) {
        
        if (_log.isDebugEnabled())
            _log.debug(format("Adding key to Redis (key=%s, exp=%s, o=%s)", key, exp, o.getClass().getName()));
        
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
            _log.debug(format("Setting key in Redis (key=%s, exp=%s, o=%s)", key, exp, o.getClass().getName()));
        
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
            _log.debug(format("Getting key from Redis (key=%s)", key));
        
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
            _log.debug(format("Deleting key in Redis (key=%s)", key));

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
        try {
            return key.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
    
    private abstract class RedisCommandCallable<T> implements Callable<T> {
        @Override
        public T call() throws Exception {
            BinaryJedis jedis = null;

            // Borrow an instance from Jedis without checking it for performance reasons and execute the command on it
            try {
                jedis = _pool.borrowInstance(false);
                return execute(jedis);
            } catch (Exception e) {
                // An error occurred with this Jedis connection, so now make sure to get a known-good one
                // The old connection is not given back to the pool since it is assumed to be defunct anyway
                if (_log.isErrorEnabled())
                    _log.error("Redis connection error occurred, discarding Jedis instance", e);

                if (jedis != null)
                    try { jedis.close(); } catch (Exception e2) { /* ignore */ }

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
        private Queue<BinaryJedis> _queue = new ConcurrentLinkedQueue<BinaryJedis>();

        public BinaryJedis borrowInstance(boolean reinitializePool) {
            if (reinitializePool) {
                if (_log.isInfoEnabled())
                    _log.info("Reinitializing Redis connection pool");
                
                // Shut down pool, effectively closing all connections
                // Note that there is a chance that connections which are currently in use are still returned to the
                // pool after the shutdown because of a race condition, but since we create a new instance below
                // unconditionally we can at least be sure no operation will fail a second time
                shutdown();
                
                if (_log.isDebugEnabled()) {
                    _log.debug(format("Creating new Jedis instance (host=%s, port=%s, ssl=%s) after reinitializing pool",
                            _uri.getHost(), _uri.getPort(), _uri.getScheme().startsWith("rediss")));
                }
                return createJedisInstance();
            }
            
            BinaryJedis res;

            if ((res = _queue.poll()) == null) {
                if (_log.isDebugEnabled()) {
                    _log.debug(format("Creating new Jedis instance (host=%s, port=%s, ssl=%s)",
                            _uri.getHost(), _uri.getPort(), _uri.getScheme().startsWith("rediss")));
                }
                return createJedisInstance();
            }

            // Check all existing connections until we find a good one
            do {
                try {
                    res.ping();

                    if (_log.isTraceEnabled())
                        _log.trace(format("Using known-good connection #%d", _queue.size()));

                    return res;
                } catch (Exception e) {
                    if (_log.isDebugEnabled())
                        _log.debug(format("Removing connection #%d since it cannot be pinged", _queue.size()));

                    try { res.close(); } catch (Exception e2) { /* ignore */ }
                }
            } while ((res = _queue.poll()) != null);

            // No existing connections are good, so create new one
            if (_log.isDebugEnabled()) {
                _log.debug(format("Creating new Jedis instance (host=%s, port=%s, ssl=%s) since all existing connections were bad",
                        _uri.getHost(), _uri.getPort(), _uri.getScheme().startsWith("rediss")));
            }

            return createJedisInstance();
        }
        
        public void returnInstance(BinaryJedis instance) {
            _queue.offer(instance);

            if (_log.isTraceEnabled())
                _log.trace(format("Returned instance #%d", _queue.size()));
        }
        
        public void shutdown() {
            if (_log.isDebugEnabled())
                _log.debug(format("Closing %d Jedis instance(s)", _queue.size()));

            BinaryJedis instance;
            while ((instance = _queue.poll()) != null) {
                try { instance.close(); } catch (Exception e) { /* ignore exception */ }
            }
        }
        
        private BinaryJedis createJedisInstance() {
            BinaryJedis binaryJedis = new BinaryJedis(_uri);
            binaryJedis.getClient().setConnectionTimeout(_timeout);
            binaryJedis.getClient().setSoTimeout(_timeout);
            return binaryJedis;
        }
    }
}
