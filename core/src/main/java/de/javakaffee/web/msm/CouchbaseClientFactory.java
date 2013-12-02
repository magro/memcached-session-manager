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

import net.spy.memcached.FailureMode;

import com.couchbase.client.CouchbaseClient;
import com.couchbase.client.CouchbaseConnectionFactoryBuilder;

/**
 * Factory to create a {@link CouchbaseClient}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class CouchbaseClientFactory implements MemcachedClientFactory.CouchbaseClientFactory {

    @Override
    public CouchbaseClient createCouchbaseClient(final MemcachedNodesManager memcachedNodesManager,
            final String memcachedProtocol, final String username, String password, final long operationTimeout,
            final long maxReconnectDelay, final Statistics statistics ) {
        try {
            // CouchbaseClient does not accept null for password
            if(password == null)
                password = "";

            // For membase connectivity: http://docs.couchbase.org/membase-sdk-java-api-reference/membase-sdk-java-started.html
            // And: http://code.google.com/p/spymemcached/wiki/Examples#Establishing_a_Membase_Connection
            final CouchbaseConnectionFactoryBuilder factory = newCouchbaseConnectionFactoryBuilder();
            factory.setOpTimeout(operationTimeout);
            factory.setMaxReconnectDelay(maxReconnectDelay);
            factory.setFailureMode(FailureMode.Redistribute);
            return new CouchbaseClient(factory.buildCouchbaseConnection(memcachedNodesManager.getCouchbaseBucketURIs(), username, password));
        } catch (final Exception e) {
            throw new RuntimeException("Could not create memcached client", e);
        }
    }

    protected CouchbaseConnectionFactoryBuilder newCouchbaseConnectionFactoryBuilder() {
        return new CouchbaseConnectionFactoryBuilder();
    }

}