package de.javakaffee.web.msm.storage;

import static java.lang.String.format;

import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import de.javakaffee.web.msm.NamedThreadFactory;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisConnectionException;

public class RedisClusterStorageClient implements StorageClient {
    protected static final Log _log = LogFactory.getLog(RedisClusterStorageClient.class);

	private JedisCluster jedisCluster;
    private final ExecutorService _executor = Executors.newCachedThreadPool(new NamedThreadFactory("msm-redis-client"));

    /**
     * Creates a <code>MemcachedStorageClient</code> instance which connects to the given Redis URL.
     *
     * @param redisUrl redis URL
     * @param operationTimeout the timeout to set for connection and socket timeout on the underlying jedis client.
     */
    public RedisClusterStorageClient(String nodes, long operationTimeout) {
        if (nodes == null)
            throw new NullPointerException("Param \"nodes\" may not be null");
        
        if (_log.isDebugEnabled())
            _log.debug(format("Creating RedisClusterStorageClient with nodes \"%s\"", nodes));

        try {
			Set<HostAndPort> jedisClusterNodes = new HashSet<HostAndPort>();
			for (String server : nodes.split(",")) {
				URI uri = URI.create(server);
				jedisClusterNodes.add(new HostAndPort(uri.getHost(), uri.getPort()));
			}

			GenericObjectPoolConfig poolConfig = new JedisPoolConfig();
			poolConfig.setMaxTotal(200);
			poolConfig.setMaxIdle(20);
			poolConfig.setMinIdle(5);
			poolConfig.setMaxWaitMillis(operationTimeout);

			jedisCluster = new JedisCluster(jedisClusterNodes, poolConfig);
        } catch (Exception e) {
            throw new RuntimeException("Error parsing nodes", e);
        }
    }

    @Override
    public Future<Boolean> add(final String key, final int exp, final byte[] o) {
        
        if (_log.isDebugEnabled())
            _log.debug(format("Adding key to Redis (key=%s, exp=%s, o=%s)", key, exp, o.getClass().getName()));
        
        return _executor.submit(new RedisCommandCallable<Boolean>() {
            private volatile boolean _setCompleted;
            
            @Override protected Boolean execute() throws Exception {
                byte[] kb = keyBytes(key);
                if (_setCompleted || jedisCluster.setnx(kb, o) == 1) {
                    _setCompleted = true; // make sure to not call setnx() a second time if connection fails
                    if (exp == 0)
                        return true;
                    else
                        return jedisCluster.expire(kb, convertExp(exp)) == 1;
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
            @Override protected Boolean execute() throws Exception {
                if (exp == 0)
                    return jedisCluster.set(keyBytes(key), o).equals("OK");
                else
                    return jedisCluster.setex(keyBytes(key), convertExp(exp), o).equals("OK");
            }
        });
    }
    
    @Override
    public byte[] get(final String key) {
        if (_log.isDebugEnabled())
            _log.debug(format("Getting key from Redis (key=%s)", key));
        
        Callable<byte[]> callable = new RedisCommandCallable<byte[]>() {
            @Override protected byte[] execute() throws Exception {
                return jedisCluster.get(keyBytes(key));
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
            @Override protected Boolean execute() throws Exception {
                return jedisCluster.del(keyBytes(key)) == 1;
            }
        });
    }

    @Override
    public void shutdown() {
    	try {
			jedisCluster.close();
		} catch (IOException exception) {
			// TODO Auto-generated catch block
			exception.printStackTrace();
		}
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
            try {
                return execute();
            } catch (JedisConnectionException e) {
            	e.printStackTrace();
                if (_log.isDebugEnabled())
                    _log.debug("Error occurred: " + e.getMessage());
            }
            return null;
        }
        
        protected abstract T execute() throws Exception;
    }
}
