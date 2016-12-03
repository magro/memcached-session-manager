package de.javakaffee.web.msm;

import net.spy.memcached.BinaryConnectionFactory;
import net.spy.memcached.DefaultConnectionFactory;

/**
 * Created by asodhi on 11/29/2016.
 */
public class MemcachedConnectionFactory implements StorageClientFactory.MemcachedNodeURLBasedConnectionFactory {
    @Override
    public BinaryConnectionFactory createBinaryConnectionFactory(final long operationTimeout,
                                                                 final long maxReconnectDelay,
                                                                 boolean isClientDynamicMode) {
        try {
            return new BinaryConnectionFactory() {
                @Override
                public long getOperationTimeout() {
                    return operationTimeout;
                }

                @Override
                public long getMaxReconnectDelay() {
                    return maxReconnectDelay;
                }
            };
        } catch (final Exception e) {
            throw new RuntimeException("Could not create memcached client", e);
        }
    }

    @Override
    public DefaultConnectionFactory createDefaultConnectionFactory(final long operationTimeout,
                                                                   final long maxReconnectDelay,
                                                                   boolean isClientDynamicMode) {
        try {
            return new DefaultConnectionFactory() {
                @Override
                public long getOperationTimeout() {
                    return operationTimeout;
                }

                @Override
                public long getMaxReconnectDelay() {
                    return maxReconnectDelay;
                }
            };
        } catch (final Exception e) {
            throw new RuntimeException("Could not create memcached client", e);
        }
    }
}
