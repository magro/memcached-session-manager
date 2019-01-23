package de.javakaffee.web.msm;

import net.spy.memcached.BinaryConnectionFactory;
import net.spy.memcached.ConnectionFactory;
import net.spy.memcached.DefaultConnectionFactory;

import java.lang.reflect.*;

/**
 * Created by asodhi on 11/29/2016.
 */
public class MemcachedElasticConnectionFactory implements StorageClientFactory.MemcachedNodeURLBasedConnectionFactory {

    @Override
    public ConnectionFactory createBinaryConnectionFactory(final long operationTimeout,
                                                           final long maxReconnectDelay,
                                                           boolean isClientDynamicMode) {
        try {
            Class clientMode = Class.forName("net.spy.memcached.ClientMode");
            Enum clientModeEnum = getClientMode(clientMode, isClientDynamicMode);
            Class connectionFactoryClass = Class.forName("net.spy.memcached.BinaryConnectionFactory");
            Constructor<BinaryConnectionFactory> connectionFactoryClassConstructor = connectionFactoryClass.getConstructor(clientMode);
            BinaryConnectionFactory binaryConnectionFactory = connectionFactoryClassConstructor.newInstance(clientModeEnum);
            return (ConnectionFactory) Proxy.newProxyInstance(
                    binaryConnectionFactory.getClass().getClassLoader(), new Class<?>[] {ConnectionFactory.class},
                    new ConnectionFactoryInvocationHandler(binaryConnectionFactory,operationTimeout,operationTimeout));
        } catch (final Exception e) {
            throw new RuntimeException("Could not create binary connection factory", e);
        }
    }

    @Override
    public ConnectionFactory createDefaultConnectionFactory(final long operationTimeout,
                                                                   final long maxReconnectDelay,
                                                                   boolean isClientDynamicMode) {
        try {
            Class clientMode = Class.forName("net.spy.memcached.ClientMode");
            Enum clientModeEnum = getClientMode(clientMode, isClientDynamicMode);
            Class connectionFactoryClass = Class.forName("net.spy.memcached.DefaultConnectionFactory");
            Constructor<DefaultConnectionFactory> connectionFactoryClassConstructor = connectionFactoryClass.getConstructor(Object.class);
            DefaultConnectionFactory defaultConnectionFactory = connectionFactoryClassConstructor.newInstance(clientModeEnum);
            return (ConnectionFactory)Proxy.newProxyInstance(
                    defaultConnectionFactory.getClass().getClassLoader(), new Class<?>[] {ConnectionFactory.class},
                    new ConnectionFactoryInvocationHandler(defaultConnectionFactory,operationTimeout,operationTimeout));
        } catch (final Exception e) {
            throw new RuntimeException("Could not create default connection factory", e);
        }
    }

    private Enum getClientMode(Class clientMode, boolean isClientDynamicMode) {
        if (isClientDynamicMode) {
            return Enum.valueOf(clientMode, "Dynamic");
        } else {
            return Enum.valueOf(clientMode, "Static");
        }
    }
    public class ConnectionFactoryInvocationHandler implements InvocationHandler {
        private final Method TO_STRING_METHOD = getMethod("toString",
                (Class<?>[]) null);

        long operationTimeout;
        long maxReconnectDelay;
        DefaultConnectionFactory defaultConnectionFactory;
        public ConnectionFactoryInvocationHandler(DefaultConnectionFactory defaultConnectionFactory,
                                                  final long operationTimeout,
                                                  final long maxReconnectDelay)
        {
            this.defaultConnectionFactory=defaultConnectionFactory;
            this.operationTimeout = operationTimeout;
            this.maxReconnectDelay = maxReconnectDelay;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args)
                throws Throwable {

            Object returnValue;
            try {
                if (method.getName().equalsIgnoreCase("getOperationTimeout"))
                {
                    returnValue = operationTimeout;
                }else if (method.getName().equalsIgnoreCase("getMaxReconnectDelay"))
                {
                    returnValue = maxReconnectDelay;
                }else {
                    returnValue = method.invoke(defaultConnectionFactory, args);
                }
            } catch (InvocationTargetException ex) {
                throw ex.getTargetException();
            }
            if (TO_STRING_METHOD.equals(method)) {
                return "Proxy (" + returnValue + ")";
            }
            return returnValue;
        }

        private Method getMethod(String methodName, Class<?>... paramTypes) {
            try {
                return Object.class.getMethod(methodName, paramTypes);
            } catch (NoSuchMethodException e) {
                e.printStackTrace();
            } catch (SecurityException e) {
                e.printStackTrace();
            }
            return null;
        }
    }
}
