/*
 * Copyright 2013 Martin Grotzke
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
 */
package de.javakaffee.web.msm;

import net.spy.memcached.BinaryConnectionFactory;
import net.spy.memcached.ConnectionFactory;
import net.spy.memcached.ConnectionFactoryBuilder;
import net.spy.memcached.DefaultConnectionFactory;
import net.spy.memcached.MemcachedClient;
import net.spy.memcached.auth.AuthDescriptor;
import net.spy.memcached.auth.PlainCallbackHandler;

import de.javakaffee.web.msm.storage.MemcachedStorageClient;
import de.javakaffee.web.msm.storage.RedisStorageClient;
import de.javakaffee.web.msm.storage.StorageClient;

/**
 * Factory to create the {@link MemcachedClient}, either directly the spymemcached {@link MemcachedClient}
 * or the {@link com.couchbase.client.CouchbaseClient}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class StorageClientFactory {

    public static final String PROTOCOL_BINARY = "binary";

    static interface CouchbaseClientFactory {
        MemcachedClient createCouchbaseClient(MemcachedNodesManager memcachedNodesManager,
                String memcachedProtocol, String username, String password, long operationTimeout,
                long maxReconnectDelay, Statistics statistics );
    }

    static interface MemcachedNodeURLBasedConnectionFactory {
        ConnectionFactory createBinaryConnectionFactory(long operationTimeout,
                                                        long maxReconnectDelay, boolean isClientDynamicMode);

        ConnectionFactory createDefaultConnectionFactory(long operationTimeout,
                                                         long maxReconnectDelay, boolean isClientDynamicMode);
    }


    protected StorageClient createStorageClient(final MemcachedNodesManager memcachedNodesManager,
                                                final String memcachedProtocol, final String username, final String password, final long operationTimeout,
                                                final long maxReconnectDelay, final Statistics statistics ) {
        try {
            if (memcachedNodesManager.isRedisConfig()) {
                return new RedisStorageClient(memcachedNodesManager.getMemcachedNodes(), operationTimeout);
            }
            final ConnectionType connectionType = ConnectionType.valueOf(memcachedNodesManager.isCouchbaseBucketConfig(), username, password);
            if (connectionType.isCouchbaseBucketConfig()) {
                return new MemcachedStorageClient(MemcachedHelper.createCouchbaseClient(memcachedNodesManager, memcachedProtocol, username, password,
                        operationTimeout, maxReconnectDelay, statistics));
            }
            final ConnectionFactory connectionFactory = MemcachedHelper.createConnectionFactory(memcachedNodesManager, connectionType, memcachedProtocol,
                    username, password, operationTimeout, maxReconnectDelay, statistics);
            return new MemcachedStorageClient(new MemcachedClient(connectionFactory, memcachedNodesManager.getAllMemcachedAddresses()));
        } catch (final Exception e) {
            throw new RuntimeException("Could not create memcached client", e);
        }
    }

    // keep memcached stuff in it's own class, so that classes from spymemcached are not loaded necessarily and don't
    // cause CNFE if spymemcached is not in the classpath.
    static class MemcachedHelper {

        static MemcachedClient createCouchbaseClient(final MemcachedNodesManager memcachedNodesManager,
                                                        final String memcachedProtocol, final String username, final String password, final long operationTimeout,
                                                        final long maxReconnectDelay, final Statistics statistics) {
            try {
                final CouchbaseClientFactory factory = Class.forName("de.javakaffee.web.msm.CouchbaseClientFactory").asSubclass(CouchbaseClientFactory.class).newInstance();
                return factory.createCouchbaseClient(memcachedNodesManager, memcachedProtocol, username, password, operationTimeout, maxReconnectDelay, statistics);
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }

        protected static MemcachedNodeURLBasedConnectionFactory createMemcachedNodeURLBasedConnectionFactory() {
            try {
                Class.forName("net.spy.memcached.ClientMode");
                return  Class.forName("de.javakaffee.web.msm.MemcachedElasticConnectionFactory").asSubclass(MemcachedNodeURLBasedConnectionFactory.class).newInstance();
            } catch (final ClassNotFoundException e) {
                try {
                    return Class.forName("de.javakaffee.web.msm.MemcachedConnectionFactory").asSubclass(MemcachedNodeURLBasedConnectionFactory.class).newInstance();
                } catch (final Exception ex)
                {
                    throw new RuntimeException(ex);
                }
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }

        static ConnectionFactory createConnectionFactory(final MemcachedNodesManager memcachedNodesManager,
                                                            final ConnectionType connectionType, final String memcachedProtocol, final String username, final String password, final long operationTimeout,
                                                            final long maxReconnectDelay, final Statistics statistics ) {
            boolean isClientDynamicMode = false;
            if (memcachedNodesManager.getMemcachedNodes().contains(".cfg."))
            {
                isClientDynamicMode=true;
            }
            if (PROTOCOL_BINARY.equals( memcachedProtocol )) {
                if (connectionType.isSASL()) {
                    final AuthDescriptor authDescriptor = new AuthDescriptor(new String[]{"PLAIN"}, new PlainCallbackHandler(username, password));
                    return memcachedNodesManager.isEncodeNodeIdInSessionId()
                            ? new SuffixLocatorBinaryConnectionFactory( memcachedNodesManager,
                            memcachedNodesManager.getSessionIdFormat(), statistics, operationTimeout, maxReconnectDelay,
                            authDescriptor)
                            : new ConnectionFactoryBuilder().setProtocol(ConnectionFactoryBuilder.Protocol.BINARY)
                            .setAuthDescriptor(authDescriptor)
                            .setOpTimeout(operationTimeout)
                            .setMaxReconnectDelay(maxReconnectDelay)
                            .build();
                }
                else {
                    return memcachedNodesManager.isEncodeNodeIdInSessionId() ? new SuffixLocatorBinaryConnectionFactory( memcachedNodesManager,
                            memcachedNodesManager.getSessionIdFormat(),
                            statistics, operationTimeout, maxReconnectDelay ) :
                            createMemcachedNodeURLBasedConnectionFactory().createBinaryConnectionFactory(operationTimeout, maxReconnectDelay,isClientDynamicMode);
                }
            }
            return memcachedNodesManager.isEncodeNodeIdInSessionId()
                    ? new SuffixLocatorConnectionFactory( memcachedNodesManager, memcachedNodesManager.getSessionIdFormat(), statistics, operationTimeout, maxReconnectDelay )
                    : createMemcachedNodeURLBasedConnectionFactory().createDefaultConnectionFactory(operationTimeout, maxReconnectDelay,isClientDynamicMode);

        }

    }

    static class ConnectionType {

        private final boolean couchbaseBucketConfig;
        private final String username;
        private final String password;
        public ConnectionType(final boolean couchbaseBucketConfig, final String username, final String password) {
            this.couchbaseBucketConfig = couchbaseBucketConfig;
            this.username = username;
            this.password = password;
        }
        public static ConnectionType valueOf(final boolean couchbaseBucketConfig, final String username, final String password) {
            return new ConnectionType(couchbaseBucketConfig, username, password);
        }
        boolean isCouchbaseBucketConfig() {
            return couchbaseBucketConfig;
        }
        boolean isSASL() {
            return !couchbaseBucketConfig && !isBlank(username) && !isBlank(password);
        }
        boolean isDefault() {
            return !isCouchbaseBucketConfig() && !isSASL();
        }

        boolean isBlank(final String value) {
            return value == null || value.trim().length() == 0;
        }
    }

}
