/*
 * Copyright 2010 Martin Grotzke
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import net.spy.memcached.MemcachedClient;

import org.apache.catalina.Session;

import de.javakaffee.web.msm.BackupSessionService.SimpleFuture;
import de.javakaffee.web.msm.NodeAvailabilityCache.CacheLoader;

/**
 * This {@link MemcachedBackupSessionManager} can be used for debugging session
 * <em>deserialization</em> - to see if serialized session data actually can be
 * deserialized. Session data is serialized at the end of the request as normal (stored
 * in a simple map), and deserialized when a following request is asking for the session.
 * The deserialization is done like this (instead of directly at the end of the request
 * when it is serialized) to perform deserialization at the same point in the lifecycle
 * as it would happen in the real failover case (there might be difference in respect
 * to initialized ThreadLocals or other stuff).
 * <p>
 * The memcached configuration (<code>memcachedNodes</code>, <code>failoverNode</code>) is
 * not used to create a memcached client, so serialized session data will <strong>not</strong>
 * be sent to memcached - and therefore no running memcacheds are required. Though, the
 * <code>memcachedNodes</code> attribute is still required (use some dummy values).
 * </p>
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class DummyMemcachedBackupSessionManager extends MemcachedBackupSessionManager {

    private final Map<String,byte[]> _sessionData = new ConcurrentHashMap<String, byte[]>();

    @Override
    protected MemcachedClient createMemcachedClient( final List<InetSocketAddress> addresses, final Map<InetSocketAddress, String> address2Ids,
            final Statistics statistics ) {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected NodeAvailabilityCache<String> createNodeAvailabilityCache( final int size, final long ttlInMillis, final MemcachedClient memcachedClient ) {
        return new NodeAvailabilityCache<String>( size, ttlInMillis, new CacheLoader<String>() {
            @Override
            public boolean isNodeAvailable( final String key ) {
                return true;
            }
        } );
    }

    @Override
    protected void deleteFromMemcached(final String sessionId) {
        // no memcached access
    }

    /**
     * Store the provided session in memcached if the session was modified
     * or if the session needs to be relocated.
     *
     * @param session
     *            the session to save
     * @param sessionRelocationRequired
     *            specifies, if the session id was changed due to a memcached failover or tomcat failover.
     * @return the {@link SessionTrackerValve.SessionBackupService.BackupResultStatus}
     */
    public Future<BackupResultStatus> backupSession( final Session session, final boolean sessionIdChanged ) {
        _log.info( "Serializing session data for session " + session.getIdInternal() );
        final long startSerialization = System.currentTimeMillis();
        final byte[] data = _transcoderService.serializeAttributes( (MemcachedBackupSession) session, ((MemcachedBackupSession) session).getAttributesInternal() );
        _log.info( String.format( "Serializing %1$,.3f kb session data for session %2$s took %3$d ms.",
                (double)data.length / 1000, session.getIdInternal(), System.currentTimeMillis() - startSerialization ) );
        _sessionData.put( session.getIdInternal(), data );
        _statistics.getAttributesSerializationProbe().registerSince( startSerialization );
        _statistics.getCachedDataSizeProbe().register( data.length );
        return new SimpleFuture<BackupResultStatus>( BackupResultStatus.SUCCESS );
    }

    @Override
    public Session findSession( final String id ) throws IOException {
        final Session result = super.findSession( id );
        if ( result != null ) {
            final byte[] data = _sessionData.remove( id );
            if ( data != null ) {
                _log.info( "Deserializing session data for session " + id );
                final long startDeserialization = System.currentTimeMillis();
                try {
                    _transcoderService.deserializeAttributes( data );
                } catch( final Exception e ) {
                    _log.warn( "Could not deserialize session data.", e );
                }
                _statistics.getLoadFromMemcachedProbe().registerSince( startDeserialization );
            }
        }
        return result;
    }

    protected MemcachedBackupSession loadFromMemcached( final String sessionId ) {
        return null;
    }

    @Override
    protected void updateExpirationInMemcached() {
    }

}
