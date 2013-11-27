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

import java.lang.reflect.Constructor;

/**
 * Factory to create the {@link MemcachedClient}, either directly the spymemcached {@link MemcachedClient}
 * or the {@link com.couchbase.client.CouchbaseClient}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class MemcachedClientFactory {

    public static final String PROTOCOL_BINARY = "binary";

    protected MemcachedClient createMemcachedClient(final MemcachedNodesManager memcachedNodesManager,
            final String memcachedProtocol, final String username, final String password, final long operationTimeout,
            final Statistics statistics ) {
        try {
            final ConnectionType connectionType = ConnectionType.valueOf(memcachedNodesManager.isCouchbaseBucketConfig(), username, password);
            if (connectionType.isCouchbaseBucketConfig()) {
                return createCouchbaseClient(memcachedNodesManager, memcachedProtocol, username, password, operationTimeout,
                        statistics);
            }
            final ConnectionFactory connectionFactory = createConnectionFactory(memcachedNodesManager, connectionType, memcachedProtocol,
                    username, password, operationTimeout, statistics);
            return new MemcachedClient(connectionFactory, memcachedNodesManager.getAllMemcachedAddresses());
        } catch (final Exception e) {
            throw new RuntimeException("Could not create memcached client", e);
        }
    }

    protected MemcachedClient createCouchbaseClient(final MemcachedNodesManager memcachedNodesManager,
            final String memcachedProtocol, final String username, final String password, final long operationTimeout,
            final Statistics statistics) {
        // return new CouchbaseClientFactory().createCouchbaseClient(memcachedNodesManager, memcachedProtocol, username, password, operationTimeout, statistics);
        // Call CouchBaseClientFactory with reflect api, then net.spy client have no lib dependency!

        try {
            Class fooClass = Class.forName("de.javakaffee.web.msm.CouchbaseClientFactory");
            Constructor fooCtrs = fooClass.getConstructor(null);
            Object factory = fooCtrs.newInstance(null);
            java.lang.reflect.Method method = fooClass.getDeclaredMethod("createCouchbaseClient",
                MemcachedNodesManager.class,
                String.class,
                String.class,
                String.class,
                long.class,
                Statistics.class);
            method.setAccessible(true);
            return (MemcachedClient) method.invoke( factory,
                    memcachedNodesManager,
                    memcachedProtocol,
                    username,
                    password,
                    operationTimeout,
                    statistics);
        } catch (Exception ex) { ex.printStackTrace(); throw new RuntimeException("Could not create couchbase memcached client", ex); }

    }

    protected ConnectionFactory createConnectionFactory(final MemcachedNodesManager memcachedNodesManager,
            final ConnectionType connectionType, final String memcachedProtocol, final String username, final String password, final long operationTimeout, final Statistics statistics ) {
        if (PROTOCOL_BINARY.equals( memcachedProtocol )) {
            if (connectionType.isSASL()) {
                final AuthDescriptor authDescriptor = new AuthDescriptor(new String[]{"PLAIN"}, new PlainCallbackHandler(username, password));
                return memcachedNodesManager.isEncodeNodeIdInSessionId()
                        ? new SuffixLocatorBinaryConnectionFactory( memcachedNodesManager,
                                memcachedNodesManager.getSessionIdFormat(), statistics, operationTimeout,
                                authDescriptor)
                        : new ConnectionFactoryBuilder().setProtocol(ConnectionFactoryBuilder.Protocol.BINARY)
                                .setAuthDescriptor(authDescriptor)
                                .setOpTimeout(operationTimeout).build();
            }
            else {
                return memcachedNodesManager.isEncodeNodeIdInSessionId() ? new SuffixLocatorBinaryConnectionFactory( memcachedNodesManager,
                        memcachedNodesManager.getSessionIdFormat(),
                        statistics, operationTimeout ) : new BinaryConnectionFactory();
            }
        }
        return memcachedNodesManager.isEncodeNodeIdInSessionId()
                ? new SuffixLocatorConnectionFactory( memcachedNodesManager, memcachedNodesManager.getSessionIdFormat(), statistics, operationTimeout )
                : new DefaultConnectionFactory();
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
