package de.javakaffee.web.msm.storage.memcached;

import java.io.IOException;

import net.spy.memcached.BinaryConnectionFactory;
import net.spy.memcached.ConnectionFactory;
import net.spy.memcached.ConnectionFactoryBuilder;
import net.spy.memcached.DefaultConnectionFactory;
import net.spy.memcached.MemcachedClient;
import net.spy.memcached.auth.AuthDescriptor;
import net.spy.memcached.auth.PlainCallbackHandler;
import net.spy.memcached.vbucket.ConfigurationException;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import de.javakaffee.web.msm.MemcachedNodesManager;
import de.javakaffee.web.msm.Statistics;
import de.javakaffee.web.msm.storage.IStorageClient;
import de.javakaffee.web.msm.storage.IStorageFactory;

public class MemcachedConnectionFactory implements IStorageFactory {
	private static final String PROTOCOL_TEXT = "text";
	private static final String PROTOCOL_BINARY = "binary";

	private final Log _log = LogFactory.getLog(getClass());

	private String memcachedProtocol = PROTOCOL_TEXT;

	private String username;
	private String password;
	private long _operationTimeout = 1000;

	private ConnectionType connectionType = null;
	private MemcachedNodesManager memcachedNodesManager;
	private Statistics statistics;

	@Override
	public IStorageClient getStorageClient(MemcachedNodesManager memcachedNodesManager, Statistics statistics,
			long operationTimeout) throws ConfigurationException, IOException {

		connectionType = ConnectionType.valueOf(memcachedNodesManager.isMembaseBucketConfig(), username, password);

		this.memcachedNodesManager = memcachedNodesManager;
		this.statistics = statistics;
		this._operationTimeout = operationTimeout;

		ConnectionFactory connectionFactory = null;

		if (PROTOCOL_BINARY.equals(memcachedProtocol)) {
			if (connectionType.isSASL()) {
				final AuthDescriptor authDescriptor = new AuthDescriptor(new String[] { "PLAIN" },
						new PlainCallbackHandler(username, password));

				connectionFactory = memcachedNodesManager.isEncodeNodeIdInSessionId() ? new SuffixLocatorBinaryConnectionFactory(
						memcachedNodesManager, memcachedNodesManager.getSessionIdFormat(), statistics,
						_operationTimeout, authDescriptor) : new ConnectionFactoryBuilder()
						.setProtocol(ConnectionFactoryBuilder.Protocol.BINARY).setAuthDescriptor(authDescriptor)
						.setOpTimeout(_operationTimeout).build();

			} else if (connectionType.isMembaseBucketConfig()) {
				connectionFactory = new ConnectionFactoryBuilder()
						.setProtocol(ConnectionFactoryBuilder.Protocol.BINARY).setOpTimeout(_operationTimeout).build();

			} else {
				connectionFactory = memcachedNodesManager.isEncodeNodeIdInSessionId() ? new SuffixLocatorBinaryConnectionFactory(
						memcachedNodesManager, memcachedNodesManager.getSessionIdFormat(), statistics,
						_operationTimeout) : new BinaryConnectionFactory();
			}
		} else {
			connectionFactory = memcachedNodesManager.isEncodeNodeIdInSessionId() ? new SuffixLocatorConnectionFactory(
					memcachedNodesManager, memcachedNodesManager.getSessionIdFormat(), statistics, _operationTimeout)
					: new DefaultConnectionFactory();

		}

		MemcachedClient client = null;

		if (connectionType.isMembaseBucketConfig()) {
			// For membase connectivity:
			// http://docs.couchbase.org/membase-sdk-java-api-reference/membase-sdk-java-started.html
			// And:
			// http://code.google.com/p/spymemcached/wiki/Examples#Establishing_a_Membase_Connection
			client = new MemcachedClient(memcachedNodesManager.getMembaseBucketURIs(), username, password);
		}
		client = new MemcachedClient(connectionFactory, memcachedNodesManager.getAllMemcachedAddresses());

		return new MemcachedStorage(client);
	}

	/**
	 * Specifies the memcached protocol to use, either "text" (default) or
	 * "binary".
	 * 
	 * @param memcachedProtocol
	 *            one of "text" or "binary".
	 */
	public void setMemcachedProtocol(final String memcachedProtocol) {
		if (!PROTOCOL_TEXT.equals(memcachedProtocol) && !PROTOCOL_BINARY.equals(memcachedProtocol)) {
			_log.warn("Illegal memcachedProtocol " + memcachedProtocol + ", using default (" + memcachedProtocol
					+ ").");
			return;
		}
		this.memcachedProtocol = memcachedProtocol;
	}

	public void setUsername(final String username) {
		this.username = username;
	}

	/**
	 * username required for SASL Connection types
	 * 
	 * @return
	 */
	public String getUsername() {
		return username;
	}

	public void setPassword(final String password) {
		this.password = password;
	}

	/**
	 * password required for SASL Connection types
	 * 
	 * @return
	 */
	public String getPassword() {
		return password;
	}

	public long getOperationTimeout() {
		return _operationTimeout;
	}

	public void setOperationTimeout(final long operationTimeout) {
		_operationTimeout = operationTimeout;
	}
}
