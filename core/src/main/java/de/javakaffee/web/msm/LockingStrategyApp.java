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

import java.lang.reflect.Constructor;
import java.util.concurrent.ExecutionException;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import net.spy.memcached.MemcachedClient;
import de.javakaffee.web.msm.MemcachedBackupSessionManager.LockStatus;

/**
 * Represents the session locking hooks that must be implemented by the various
 * locking strategies.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class LockingStrategyApp extends LockingStrategy {

    private static final String SESSION_LOCK_CLASSNAME = "de.javakaffee.web.msm.SessionLock";

    public LockingStrategyApp( @Nonnull final MemcachedClient memcached,
            @Nonnull final MemcachedBackupSessionManager manager,
            @Nonnull final LRUCache<String, Boolean> missingSessionsCache ) {
        super( memcached, missingSessionsCache );
        initSessionLock( manager );
    }

    private void initSessionLock( @Nonnull final MemcachedBackupSessionManager manager ) throws RuntimeException {
        final ClassLoader classLoader = manager.getContainer().getLoader().getClassLoader();
        try {
            _log.info( "Loading "+ SESSION_LOCK_CLASSNAME +" using classloader " + classLoader );
            final Class<?> clazz = Class.forName( SESSION_LOCK_CLASSNAME, false, classLoader );
            final Constructor<?> constructor = clazz.getDeclaredConstructor( LockingStrategy.class );
            constructor.newInstance( this );
        } catch ( final Exception e ) {
            _log.info( "Could not load "+ SESSION_LOCK_CLASSNAME +" with classloader "+ classLoader, e );
            throw new RuntimeException( e );
        }
    }

    @Override
    @CheckForNull
    protected LockStatus lockBeforeLoadingFromMemcached( final String sessionId ) throws InterruptedException,
            ExecutionException {
        // Nothing to do
        return null;
    }

}
