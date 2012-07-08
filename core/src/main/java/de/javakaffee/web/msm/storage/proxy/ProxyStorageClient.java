package de.javakaffee.web.msm.storage.proxy;

import java.util.concurrent.Future;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import de.javakaffee.web.msm.storage.IStorageClient;

public class ProxyStorageClient implements IStorageClient {
	private final Log LOG = LogFactory.getLog(getClass());

	private IStorageClient masterClient;
	private IStorageClient slaveClient;
	private boolean transferToMaster;
	private boolean deleteIfFoundOnSlave;
	private int expirationTime = 3600;

	public ProxyStorageClient(IStorageClient masterClient, IStorageClient slaveClient, boolean transferToMaster,
			boolean deleteIfFoundOnSlave) {

		this.masterClient = masterClient;
		this.slaveClient = slaveClient;
		this.transferToMaster = transferToMaster;
		this.deleteIfFoundOnSlave = deleteIfFoundOnSlave;
	}

	@Override
	public Future<Boolean> delete(String key) {

		Future<Boolean> done = masterClient.delete(key);

		if (deleteIfFoundOnSlave) {
			slaveClient.delete(key);
		}

		return done;
	}

	@Override
	public Future<Boolean> set(String key, int expirationTime, byte[] value) {
		Future<Boolean> future = masterClient.set(key, expirationTime, value);

		return future;
	}

	@Override
	public Future<Boolean> add(String key, int expirationTime, String value) {
		Future<Boolean> future = masterClient.add(key, expirationTime, value);
		return future;
	}

	@Override
	public Future<Boolean> checkExist(String key, int expirationTime) {

		try {
			Boolean found = masterClient.checkExist(key, expirationTime).get();

			if (!found) {
				found = slaveClient.checkExist(key, expirationTime).get();

				if (found && transferToMaster) {
					byte[] value = slaveClient.get(key);
					masterClient.set(key, this.expirationTime, value);

					if (deleteIfFoundOnSlave) {
						slaveClient.delete(key);
					}
				}
			}
		} catch (Exception e) {
			LOG.error("::checkExist key:" + key, e);
		}

		return masterClient.checkExist(key, expirationTime);
	}

	@Override
	public byte[] get(String key) {

		byte[] value = masterClient.get(key);

		if (value == null) {
			value = slaveClient.get(key);

			if (value != null && transferToMaster) {
				masterClient.set(key, expirationTime, value);

				if (deleteIfFoundOnSlave) {
					slaveClient.delete(key);
				}
			}
		}

		return value;
	}

	@Override
	public void shutdown() {

		masterClient.shutdown();
		slaveClient.shutdown();

	}

}
