package de.javakaffee.web.msm.storage;

import java.io.IOException;

import de.javakaffee.web.msm.MemcachedNodesManager;
import de.javakaffee.web.msm.Statistics;

public interface IStorageFactory {

	public IStorageClient getStorageClient(MemcachedNodesManager memcachedNodesManager, Statistics statistics, long _operationTimeout) throws IOException;

}
