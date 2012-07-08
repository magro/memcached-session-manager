package de.javakaffee.web.msm.storage.redis;

import java.util.concurrent.Future;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import de.javakaffee.web.msm.storage.IStorageClient;

public class RedisStorage implements IStorageClient {
	private JedisPool redisPool;
	private static final Future<Boolean> TRUE = new DummyFuture(Boolean.TRUE);
	private static final Future<Boolean> FALSE = new DummyFuture(Boolean.FALSE);

	public RedisStorage(JedisPool redisPool) {
		this.redisPool = redisPool;
	}

	@Override
	public Future<Boolean> delete(String name) {

		Jedis resource = null;

		try {
			resource = redisPool.getResource();
			Long del = resource.del(name);
			if (del.intValue() == 1)
				return TRUE;
		} finally {
			if (resource != null)
				redisPool.returnResource(resource);
		}

		return FALSE;
	}

	@Override
	public Future<Boolean> set(String key, int expirationTime, byte[] value) {
		Jedis resource = null;

		try {
			resource = redisPool.getResource();
			String setex = resource.setex(key.getBytes(), expirationTime, value);
			if ("OK".equals(setex) )
				return TRUE;
			
		} finally {
			if (resource != null)
				redisPool.returnResource(resource);
		}

		return FALSE;
	}

	

	@Override
	public Future<Boolean> add(String key, int expirationTime, String value) {
		Jedis resource = null;

		try {
			resource = redisPool.getResource();
			Long setnx = resource.setnx(key, value);
			if (setnx.intValue() == 1) {
				resource.expire(key, expirationTime);
				return TRUE;
			}
		} finally {
			if (resource != null)
				redisPool.returnResource(resource);
		}

		return FALSE;
	}
	
	@Override
	public Future<Boolean> checkExist(String key, int expirationTime) {
		Jedis resource = null;

		// TODO : what to do for expiration  
		try {
			resource = redisPool.getResource();
			Boolean result= resource.exists(key);
			if (result) {
				
				return TRUE;
			}
		} finally {
			if (resource != null)
				redisPool.returnResource(resource);
		}

		return FALSE;
	}

	
	

	@Override
	public Object get(String name) {
		Jedis resource = null;

		Object value = null;

		try {
			resource = redisPool.getResource();
			value = resource.get(name);
		} finally {
			if (resource != null)
				redisPool.returnResource(resource);
		}

		return value;
	}

	@Override
	public void shutdown() {

		redisPool.destroy();

	}


}
