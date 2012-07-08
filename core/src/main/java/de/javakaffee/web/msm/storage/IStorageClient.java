package de.javakaffee.web.msm.storage;

import java.util.concurrent.Future;

public interface IStorageClient {

	public Future<Boolean> delete(String key);

	public Future<Boolean> set(String key, int expirationTime, byte[] value);

	public Future<Boolean> add(String key, int expirationTime, String value);

	public Object get(String key);

	public void shutdown();

	public Future<Boolean> checkExist(String key, int expirationTime);

}
