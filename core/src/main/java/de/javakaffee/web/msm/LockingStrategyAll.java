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

import javax.annotation.Nonnull;

import de.javakaffee.web.msm.MemcachedSessionService.LockStatus;
import de.javakaffee.web.msm.storage.StorageClient;

/**
 * This locking strategy locks each request accessing the session.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class LockingStrategyAll extends LockingStrategy {

    public LockingStrategyAll( @Nonnull final MemcachedSessionService manager,
            @Nonnull final MemcachedNodesManager memcachedNodesManager,
            @Nonnull final StorageClient memcached,
            @Nonnull final LRUCache<String, Boolean> missingSessionsCache,
            final boolean storeSecondaryBackup,
            @Nonnull final Statistics stats,
            @Nonnull final CurrentRequest currentRequest ) {
        super( manager, memcachedNodesManager, memcached, missingSessionsCache, storeSecondaryBackup, stats, currentRequest );
    }

    @Override
    protected LockStatus onBeforeLoadFromMemcached( @Nonnull final String sessionId ) throws InterruptedException, ExecutionException {
        return lock( sessionId );
    }

}
