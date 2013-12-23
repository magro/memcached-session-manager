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


import static de.javakaffee.web.msm.Statistics.StatsType.ATTRIBUTES_SERIALIZATION;
import static de.javakaffee.web.msm.Statistics.StatsType.CACHED_DATA_SIZE;
import static de.javakaffee.web.msm.Statistics.StatsType.LOAD_FROM_MEMCACHED;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import net.spy.memcached.MemcachedClient;

import org.apache.catalina.Session;

import de.javakaffee.web.msm.BackupSessionService.SimpleFuture;
import de.javakaffee.web.msm.BackupSessionTask.BackupResult;
import de.javakaffee.web.msm.MemcachedNodesManager.MemcachedClientCallback;

/**
 * This {@link MemcachedSessionService} can be used for debugging session
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
public class DummyMemcachedSessionService<T extends MemcachedSessionService.SessionManager> extends MemcachedSessionService {

    private final Map<String,byte[]> _sessionData = new ConcurrentHashMap<String, byte[]>();
    private final ExecutorService _executorService = Executors.newSingleThreadExecutor(new NamedThreadFactory("dummy-msm"));

    public DummyMemcachedSessionService( final T manager ) {
        super( manager );
    }

    @Override
    protected MemcachedClient createMemcachedClient( final MemcachedNodesManager memcachedNodesManager,
            final Statistics statistics ) {
        return null;
    }

    @Override
    protected MemcachedClientCallback createMemcachedClientCallback() {
    	return new MemcachedClientCallback() {
			@Override
			public Object get(final String key) {
				return null;
			}
		};
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
     * @return the {@link BackupResultStatus}
     */
    public Future<BackupResult> backupSession( final Session session, final boolean sessionIdChanged, final String requestURI ) {
        _log.info( "Serializing session data for session " + session.getIdInternal() );
        final long startSerialization = System.currentTimeMillis();
        final byte[] data = _transcoderService.serializeAttributes( (MemcachedBackupSession) session, ((MemcachedBackupSession) session).getAttributesFiltered() );
        _log.info( String.format( "Serializing %1$,.3f kb session data for session %2$s took %3$d ms.",
                (double)data.length / 1000, session.getIdInternal(), System.currentTimeMillis() - startSerialization ) );
        _sessionData.put( session.getIdInternal(), data );
        _statistics.registerSince( ATTRIBUTES_SERIALIZATION, startSerialization );
        _statistics.register( CACHED_DATA_SIZE, data.length );
        return new SimpleFuture<BackupResult>( new BackupResult( BackupResultStatus.SUCCESS ) );
    }

    @Override
    public MemcachedBackupSession findSession( final String id ) throws IOException {
        final MemcachedBackupSession result = super.findSession( id );
        if ( result != null ) {
            final byte[] data = _sessionData.remove( id );
            if ( data != null ) {
                _executorService.submit( new SessionDeserialization( id, data ) );
            }
        }
        return result;
    }

    @Override
    protected MemcachedBackupSession loadFromMemcachedWithCheck( final String sessionId ) {
        return null;
    }

    @Override
    protected void updateExpirationInMemcached() {
    }

    private final class SessionDeserialization implements Callable<Void> {

        private final String _id;
        private final byte[] _data;

        private SessionDeserialization( final String id, final byte[] data ) {
            _id = id;
            _data = data;
        }

        @Override
        public Void call() throws Exception {
            _log.info( String.format( "Deserializing %1$,.3f kb session data for session %2$s (asynchronously).", (double)_data.length / 1000, _id ) );
            final long startDeserialization = System.currentTimeMillis();
            try {
                _transcoderService.deserializeAttributes( _data );
            } catch( final Exception e ) {
                _log.warn( "Could not deserialize session data.", e );
            }
            _log.info( String.format( "Deserializing %1$,.3f kb session data for session %2$s took %3$d ms.",
                    (double)_data.length / 1000, _id, System.currentTimeMillis() - startDeserialization ) );
            _statistics.registerSince( LOAD_FROM_MEMCACHED, startDeserialization );
            return null;
        }
    }

}
