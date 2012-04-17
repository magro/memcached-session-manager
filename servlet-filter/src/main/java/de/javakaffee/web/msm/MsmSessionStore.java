/**
 * 
 */
package de.javakaffee.web.msm;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import net.spy.memcached.MemcachedClient;
import net.spy.memcached.internal.OperationFuture;

/**
 * Some msm session store, this is just a simple memcached backed implementation.
 * 
 * @author Martin Grotzke
 */
public class MsmSessionStore {

    private final MemcachedClient memcached;

    public MsmSessionStore(final MemcachedClient memcached) {
        this.memcached = memcached;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> loadSessionAttributes(final String requestedSessionId) {
        if (requestedSessionId == null) {
            return null;
        }
        return (Map<String, Object>) memcached.get(requestedSessionId);
    }

    public OperationFuture<Boolean> saveSessionAttributes(final String sessionId,
            final Map<String, Object> sessionAttributes) {
        // TODO: this should depend on the session timeout actually
        final int expirationInSeconds = (int) TimeUnit.MINUTES.toSeconds(60);
        return memcached.set(sessionId, expirationInSeconds, sessionAttributes);
    }

}
