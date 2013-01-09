package de.javakaffee.web.msm.storage.proxy;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;

import org.apache.commons.beanutils.BeanUtils;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import de.javakaffee.web.msm.MemcachedNodesManager;
import de.javakaffee.web.msm.Statistics;
import de.javakaffee.web.msm.storage.IStorageClient;
import de.javakaffee.web.msm.storage.IStorageFactory;

public class ProxyStorageFactory implements IStorageFactory {
	private final Log LOG = LogFactory.getLog(getClass());
	private String masterClass;
	private String slaveClass;

	private IStorageFactory master;
	private IStorageFactory slave;

	private boolean transferToMaster = true;
	private boolean deleteIfFoundOnSlave = false;

	private HashMap<String, String> properties = new HashMap<String, String>();

	@Override
	public IStorageClient getStorageClient(MemcachedNodesManager memcachedNodesManager, Statistics statistics,
			long _operationTimeout) throws IOException {

		try {
			master = instantiateFactory(masterClass, "master.");
			slave = instantiateFactory(slaveClass, "slave.");

			IStorageClient masterClient = master.getStorageClient(memcachedNodesManager, statistics, _operationTimeout);
			IStorageClient slaveClient = slave.getStorageClient(memcachedNodesManager, statistics, _operationTimeout);

			return new ProxyStorageClient(masterClient, slaveClient, transferToMaster, deleteIfFoundOnSlave);

		} catch (Exception e) {
			LOG.error("::getStorageClient masterClass:" + masterClass + " slaveClass:" + slaveClass, e);
			throw new IOException(e);
		}
	}

	private IStorageFactory instantiateFactory(String className, String prefix) throws Exception {

		IStorageFactory factory = Class.forName(className).asSubclass(IStorageFactory.class).newInstance();

		int len = prefix.length();

		Iterator<String> iter = properties.keySet().iterator();
		while (iter.hasNext()) {
			String key = iter.next();
			if (!key.startsWith(prefix))
				continue;

			String value = properties.get(key);
			key = key.substring(len);

			BeanUtils.setProperty(factory, key, value);
		}

		return factory;
	}

	public void setMasterClass(String masterClass) {
		this.masterClass = masterClass;
	}

	public void setSlaveClass(String slaveClass) {
		this.slaveClass = slaveClass;
	}

	public boolean setProperty(String name, String value) {
		properties.put(name, value);
		return true;
	}

}
