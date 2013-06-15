/*
 * Copyright 2011 Martin Grotzke
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

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.annotation.Nonnull;

import net.spy.memcached.MemcachedClient;

import org.apache.catalina.connector.Request;

import de.javakaffee.web.msm.BackupSessionService.SimpleFuture;
import de.javakaffee.web.msm.BackupSessionTask.BackupResult;
import de.javakaffee.web.msm.MemcachedSessionService.LockStatus;

/**
 * This locking strategy locks all requests except those that are registed (via autodetection)
 * to access the session only readonly.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class LockingStrategyAuto extends LockingStrategy {

    private final ExecutorService _requestPatternDetectionExecutor;
    private final ReadOnlyRequestsCache _readOnlyRequestCache;

    public LockingStrategyAuto( @Nonnull final MemcachedSessionService manager,
            @Nonnull final MemcachedNodesManager memcachedNodesManager,
            @Nonnull final MemcachedClient memcached,
            @Nonnull final LRUCache<String, Boolean> missingSessionsCache,
            final boolean storeSecondaryBackup,
            @Nonnull final Statistics stats,
            @Nonnull final CurrentRequest currentRequest ) {
        super( manager, memcachedNodesManager, memcached, missingSessionsCache, storeSecondaryBackup, stats, currentRequest );
        _requestPatternDetectionExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory("msm-req-pattern-detector"));
        _readOnlyRequestCache = new ReadOnlyRequestsCache();
    }

    @Override
    public void registerReadonlyRequest(final String requestId) {
        _readOnlyRequestCache.readOnlyRequest( requestId );
    }

    @Override
    protected void onBackupWithoutLoadedSession( @Nonnull final String sessionId, @Nonnull final String requestId,
            @Nonnull final BackupSessionService backupSessionService ) {

        if ( !_sessionIdFormat.isValid( sessionId ) ) {
            return;
        }

        super.onBackupWithoutLoadedSession( sessionId, requestId, backupSessionService );

        _readOnlyRequestCache.readOnlyRequest( requestId );
    }

    @Override
    protected void onAfterBackupSession( final MemcachedBackupSession session, final boolean backupWasForced,
            final Future<BackupResult> result,
            final String requestId,
            final BackupSessionService backupSessionService ) {

        if ( !_sessionIdFormat.isValid( session.getIdInternal() ) ) {
            return;
        }

        super.onAfterBackupSession( session, backupWasForced, result, requestId, backupSessionService );

        final Callable<Void> task = new Callable<Void>() {

            @Override
            public Void call() {
                try {
                    if ( result.get().getStatus() == BackupResultStatus.SKIPPED ) {
                        _readOnlyRequestCache.readOnlyRequest( requestId );
                    } else {
                        _readOnlyRequestCache.modifyingRequest( requestId );
                    }
                } catch ( final Exception e ) {
                    _readOnlyRequestCache.modifyingRequest( requestId );
                }
                return null;
            }

        };
        /* A simple future does not need to go through the executor, but we can process the result right now.
         */
        if ( result instanceof SimpleFuture ) {
            try {
                task.call();
            } catch ( final Exception e ) { /* caught in the callable */ }
        }
        else {
            _requestPatternDetectionExecutor.submit( task );
        }
    }

    @Override
    protected LockStatus onBeforeLoadFromMemcached( final String sessionId ) throws InterruptedException,
            ExecutionException {

        final Request request = _currentRequest.get();

        if ( request == null ) {
            throw new RuntimeException( "There's no request set, this indicates that this findSession" +
                    "was triggered by the container which should already be handled in findSession." );
        }

        /* lets see if we can skip the locking as we consider this beeing a readonly request
         */
        if ( _readOnlyRequestCache.isReadOnlyRequest( RequestTrackingHostValve.getURIWithQueryString( request ) ) ) {
            if ( _log.isDebugEnabled() ) {
                _log.debug( "Not getting lock for readonly request " + RequestTrackingHostValve.getURIWithQueryString( request ) );
            }
            _stats.nonStickySessionsReadOnlyRequest();
            return LockStatus.LOCK_NOT_REQUIRED;
        }

        return lock( sessionId );

    }

}
