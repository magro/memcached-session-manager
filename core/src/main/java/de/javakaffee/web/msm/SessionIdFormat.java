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

import java.util.regex.Pattern;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * This class defines the session id format: It creates session ids based on the
 * original session id and the memcached id, and it extracts the session id and
 * memcached id from a compound session id.
 * <p>
 * The session id is of the following format:
 * <code>[^-.]+-[^.]+(\.[^.]+)?</code>
 * </p>
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class SessionIdFormat {

    private static final String BACKUP_PREFIX = "bak:";

    private static final Log LOG = LogFactory.getLog( SessionIdFormat.class );

    /**
     * The pattern for the session id.
     */
    private final Pattern _pattern = Pattern.compile( "[^-.]+-[^.]+(\\.[^.]+)?" );

    private final StorageKeyFormat _storageKeyFormat;

    public SessionIdFormat() {
        this(StorageKeyFormat.EMPTY);
    }

    public SessionIdFormat(final StorageKeyFormat storageKeyFormat) {
        _storageKeyFormat = storageKeyFormat;
    }

    /**
     * Create a session id including the provided memcachedId.
     *
     * @param sessionId
     *            the original session id, it might contain the jvm route
     * @param memcachedId
     *            the memcached id to encode in the session id, may be <code>null</code>.
     * @return the sessionId which now contains the memcachedId if one was provided, otherwise
     *  the sessionId unmodified.
     */
    @Nonnull
    public String createSessionId(@Nonnull final String sessionId, @Nullable final String memcachedId) {
        if ( LOG.isDebugEnabled() ) {
            LOG.debug( "Creating new session id with orig id '" + sessionId + "' and memcached id '" + memcachedId + "'." );
        }
        if ( memcachedId == null ) {
            return sessionId;
        }
        final int idx = sessionId.indexOf( '.' );
        if ( idx < 0 ) {
            return sessionId + "-" + memcachedId;
        } else {
            return sessionId.substring( 0, idx ) + "-" + memcachedId + sessionId.substring( idx );
        }
    }

    /**
     * Change the provided session id (optionally already including a memcachedId) so that it
     * contains the provided newMemcachedId.
     *
     * @param sessionId
     *            the session id that may contain a former memcachedId.
     * @param newMemcachedId
     *            the new memcached id.
     * @return the sessionId which now contains the new memcachedId instead the
     *         former one.
     */
    @Nonnull
    public String createNewSessionId( @Nonnull final String sessionId, @Nonnull final String newMemcachedId) {
        final int idxDot = sessionId.indexOf( '.' );
        if ( idxDot != -1 ) {
            final String plainSessionId = sessionId.substring( 0, idxDot );
            final String jvmRouteWithDot = sessionId.substring( idxDot );
            return appendOrReplaceMemcachedId( plainSessionId, newMemcachedId ) + jvmRouteWithDot;
        }
        else {
            return appendOrReplaceMemcachedId( sessionId, newMemcachedId );
        }
    }

    @Nonnull
    private String appendOrReplaceMemcachedId( @Nonnull final String sessionId, @Nonnull final String newMemcachedId ) {
        final int idxDash = sessionId.indexOf( '-' );
        if ( idxDash < 0 ) {
            return sessionId + "-" + newMemcachedId;
        } else {
            return sessionId.substring( 0, idxDash + 1 ) + newMemcachedId;
        }
    }

    /**
     * Change the provided session id (optionally already including a jvmRoute) so that it
     * contains the provided newJvmRoute.
     *
     * @param sessionId
     *            the session id that may contain a former jvmRoute.
     * @param newJvmRoute
     *            the new jvm route.
     * @return the sessionId which now contains the new jvmRoute instead the
     *         former one.
     */
    @Nonnull
    public String changeJvmRoute( @Nonnull final String sessionId, @Nonnull final String newJvmRoute ) {
        return stripJvmRoute( sessionId ) + "." + newJvmRoute;
    }

    /**
     * Checks if the given session id matches the pattern
     * <code>[^-.]+-[^.]+(\.[\w]+)?</code>.
     *
     * @param sessionId
     *            the session id
     * @return true if matching, otherwise false.
     */
    public boolean isValid( @Nullable final String sessionId ) {
        return sessionId != null && _pattern.matcher( sessionId ).matches();
    }

    /**
     * Extract the memcached id from the given session id.
     *
     * @param sessionId
     *            the session id including the memcached id and eventually the
     *            jvmRoute.
     * @return the memcached id or null if the session id didn't contain any
     *         memcached id.
     */
    @CheckForNull
    public String extractMemcachedId( @Nonnull final String sessionId ) {
        final int idxDash = sessionId.indexOf( '-' );
        if ( idxDash < 0 ) {
            return null;
        }
        final int idxDot = sessionId.indexOf( '.' );
        if ( idxDot < 0 ) {
            return sessionId.substring( idxDash + 1 );
        } else if ( idxDot < idxDash ) /* The dash was part of the jvmRoute */ {
            return null;
        } else {
            return sessionId.substring( idxDash + 1, idxDot );
        }
    }

    /**
     * Extract the jvm route from the given session id if existing.
     *
     * @param sessionId
     *            the session id possibly including the memcached id and eventually the
     *            jvmRoute.
     * @return the jvm route or null if the session id didn't contain any.
     */
    @CheckForNull
    public String extractJvmRoute( @Nonnull final String sessionId ) {
        final int idxDot = sessionId.indexOf( '.' );
        return idxDot < 0 ? null : sessionId.substring( idxDot + 1 );
    }

    /**
     * Remove the jvm route from the given session id if existing.
     *
     * @param sessionId
     *            the session id possibly including the memcached id and eventually the
     *            jvmRoute.
     * @return the session id without the jvm route.
     */
    @Nonnull
    public String stripJvmRoute( @Nonnull final String sessionId ) {
        final int idxDot = sessionId.indexOf( '.' );
        return idxDot < 0 ? sessionId : sessionId.substring( 0, idxDot );
    }

    /**
     * Creates the name/key that can be used for the lock stored in memcached.
     * @param sessionId the session id for that a lock key shall be created.
     * @return a String.
     */
    @Nonnull
    public String createLockName( @Nonnull final String sessionId ) {
        if ( sessionId == null ) {
            throw new IllegalArgumentException( "The sessionId must not be null." );
        }
        return "lock:" + _storageKeyFormat.format(sessionId);
    }

    /**
     * Creates the name/key that can be used for storing the encoded session validity information.
     * @param origKey the session id (or validity info key) for that a key shall be created.
     * @return a String.
     */
    @Nonnull
    public String createValidityInfoKeyName( @Nonnull final String origKey ) {
        if ( origKey == null ) {
            throw new IllegalArgumentException( "The sessionId must not be null." );
        }
        return "validity:" + _storageKeyFormat.format(origKey);
    }

    /**
     * Creates the name/key that is used for the data (session or validity info)
     * that is additionally stored in a secondary memcached node for non-sticky sessions.
     * @param origKey the session id (or validity info key) for that a key shall be created.
     * @return a String.
     */
    @Nonnull
    public String createBackupKey( @Nonnull final String origKey ) {
        if ( origKey == null ) {
            throw new IllegalArgumentException( "The origKey must not be null." );
        }
        return BACKUP_PREFIX + _storageKeyFormat.format(origKey);
    }

    /**
     * Determines, if the given key is a backup key, if it was created via {@link #createBackupKey(String)}.
     */
    public boolean isBackupKey( @Nonnull final String key ) {
        return key.startsWith( BACKUP_PREFIX );
    }

}