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

import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import net.spy.memcached.MemcachedClient;

import org.apache.catalina.connector.Request;

import de.javakaffee.web.msm.MemcachedSessionService.LockStatus;

/**
 * This locking strategy locks requests matching a configured uri pattern.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class LockingStrategyUriPattern extends LockingStrategy {

    private final Pattern _uriPattern;

    public LockingStrategyUriPattern( @Nonnull final MemcachedSessionService manager,
            @Nonnull final MemcachedNodesManager memcachedNodesManager,
            @Nonnull final Pattern uriPattern,
            @Nonnull final MemcachedClient memcached,
            @Nonnull final LRUCache<String, Boolean> missingSessionsCache,
            final boolean storeSecondaryBackup,
            @Nonnull final Statistics stats,
            @Nonnull final CurrentRequest currentRequest ) {
        super( manager, memcachedNodesManager, memcached, missingSessionsCache, storeSecondaryBackup, stats, currentRequest );
        if ( uriPattern == null ) {
            throw new IllegalArgumentException( "The uriPattern is null" );
        }
        _uriPattern = uriPattern;
    }

    @Override
    protected LockStatus onBeforeLoadFromMemcached( final String sessionId ) throws InterruptedException,
            ExecutionException {

        final Request request = _currentRequest.get();

        if ( request == null ) {
            throw new RuntimeException( "There's no request set, this indicates that this findSession" +
                    "was triggered by the container which should already be handled in findSession." );
        }

        /* let's see if we should lock the session for this request
         */
        if ( _uriPattern.matcher( RequestTrackingHostValve.getURIWithQueryString( request ) ).matches() ) {
            if ( _log.isDebugEnabled() ) {
                _log.debug( "Lock request for request " + RequestTrackingHostValve.getURIWithQueryString( request ) );
            }
            return lock( sessionId );
        }

        if ( _log.isDebugEnabled() ) {
        	_log.debug( "Not lock request for request " + RequestTrackingHostValve.getURIWithQueryString( request ) );
        }

        _stats.nonStickySessionsReadOnlyRequest();
        return LockStatus.LOCK_NOT_REQUIRED;

    }

}
