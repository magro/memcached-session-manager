/*
 * Copyright 2009 Martin Grotzke
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
 *
 */
package de.javakaffee.web.msm;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves an {@link InetSocketAddress} to a memcached node id.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public abstract class NodeIdResolver {

    /**
     * Resolve an {@link InetSocketAddress} to a memcached node id.
     *
     * @param address
     *            the address of the memcached node
     * @return the memcached node id
     */
    public abstract String getNodeId( InetSocketAddress address );

    /**
     * Start to build a {@link NodeIdResolver} with the help of the builder. You
     * can use it like this:
     *
     * <pre>
     * NodeIdResolver resolver = NodeIdResolver.node( nodeId1, address1 ).node( nodeId2, address2 ).build()
     * </pre>
     *
     * @param id
     *            the id of the memcached node
     * @param address
     *            the address of the memcached node
     * @return a {@link Builder} that finally creates the {@link NodeIdResolver}
     *         via {@link Builder#build()}.
     */
    public static Builder node( final String id, final InetSocketAddress address ) {
        return new Builder().node( id, address );
    }

    /**
     * Allows to build a {@link NodeIdResolver}.
     */
    public static class Builder {

        private final Map<InetSocketAddress, String> _address2Ids = new HashMap<InetSocketAddress, String>();

        /**
         * Add a new node with the give id and address.
         *
         * @param id
         *            the node id
         * @param address
         *            the {@link InetSocketAddress} of the node
         * @return this builder
         */
        public Builder node( final String id, final InetSocketAddress address ) {
            final String previous = _address2Ids.put( address, id );
            if ( previous != null ) {
                throw new IllegalArgumentException( "There's already an address bound to id " + previous );
            }
            return this;
        }

        /**
         * Creates the {@link NodeIdResolver}.
         *
         * @return a new instance/implementation of {@link NodeIdResolver}
         */
        public NodeIdResolver build() {
            return new MapBasedResolver( _address2Ids );
        }

    }

    /**
     * A {@link NodeIdResolver} that is based on a node-address to node-id
     * {@link Map}.
     */
    public static class MapBasedResolver extends NodeIdResolver {

        private final Map<InetSocketAddress, String> _address2Ids;

        /**
         * Creates a new {@link MapBasedResolver} with the given addresses.
         *
         * @param address2Ids
         *            the mapping of addresses to node ids.
         */
        public MapBasedResolver( final Map<InetSocketAddress, String> address2Ids ) {
            _address2Ids = address2Ids;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String getNodeId( final InetSocketAddress inetAddress ) {
            return _address2Ids.get( inetAddress );
        }

    }

}