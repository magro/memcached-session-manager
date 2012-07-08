package de.javakaffee.web.msm.storage.redis;

import java.io.IOException;

import org.apache.commons.pool.impl.GenericObjectPool.Config;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Protocol;
import de.javakaffee.web.msm.MemcachedNodesManager;
import de.javakaffee.web.msm.Statistics;
import de.javakaffee.web.msm.storage.IStorageClient;
import de.javakaffee.web.msm.storage.IStorageFactory;

public class RedisConnectionFactory implements IStorageFactory {
	private JedisPool redisPool;
	private Config poolConfig;
	private String host;
	private int port = Protocol.DEFAULT_PORT;
	private int timeout = Protocol.DEFAULT_TIMEOUT;
	private String password;
	private int database = Protocol.DEFAULT_DATABASE;

	public RedisConnectionFactory() {
		poolConfig = new Config();
		redisPool = new JedisPool(poolConfig, host, port, timeout, password, database);
	}

	@Override
	public IStorageClient getStorageClient(MemcachedNodesManager memcachedNodesManager, Statistics statistics,
			long _operationTimeout) throws IOException {

		return new RedisStorage(redisPool);
	}

	public String getHost() {
		return host;
	}

	public void setHost(String host) {
		this.host = host;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public int getTimeout() {
		return timeout;
	}

	public void setTimeout(int timeout) {
		this.timeout = timeout;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public int getDatabase() {
		return database;
	}

	public void setDatabase(int database) {
		this.database = database;
	}

}
