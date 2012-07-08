package de.javakaffee.web.msm.storage.memcached;

import java.util.concurrent.Future;

import net.spy.memcached.MemcachedClient;
import de.javakaffee.web.msm.storage.IStorageClient;

public class MemcachedStorage implements IStorageClient {
	private MemcachedClient client = null;

	public MemcachedStorage(MemcachedClient client) {
		this.client = client;
	}

	@Override
	public Future<Boolean> delete(String key) {
		return client.delete(key);
	}

	@Override
	public Future<Boolean> set(String key, int expirationTime, byte[] data) {
		return client.set(key, expirationTime, data);
	}

	@Override
	public Future<Boolean> checkExist(String key, int expirationTime) {
		return client.add(key, expirationTime, 1);
	}

	@Override
	public Future<Boolean> add(String key, int expirationTime, String value) {
		return client.add(key, expirationTime, value);
	}

	@Override
	public byte[] get(String key) {
		return (byte[]) client.get(key);
	}

	@Override
	public void shutdown() {
		client.shutdown();
	}

}
