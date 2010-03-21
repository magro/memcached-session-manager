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

/**
 * This class defines the session id format: It creates session ids based on the
 * original session id and the memcached id, and it extracts the session id and
 * memcached id from a compound session id.
 * <p>
 * The session id is of the following format:
 * <code>[^-.]+-[^.]+(\.[\w]+)?</code>
 * </p>
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class SessionIdFormat {

    /**
     * The pattern for the session id.
     */
    private final Pattern _pattern = Pattern.compile( "[^-.]+-[^.]+(\\.[\\w]+)?" );

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
    public String createSessionId( final String sessionId, final String memcachedId ) {
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
    public String createNewSessionId( final String sessionId, final String newMemcachedId ) {
        final int idxDash = sessionId.indexOf( '-' );
        final int idxDot = sessionId.indexOf( '.' );

        final String sessionIdWithNewMemcachedId;
        if ( idxDash < 0 ) {
            final String plainSessionId = idxDot < 0 ? sessionId : sessionId.substring( 0, idxDot );
            sessionIdWithNewMemcachedId = plainSessionId + "-" + newMemcachedId;
        } else {
            sessionIdWithNewMemcachedId = sessionId.substring( 0, idxDash + 1 ) + newMemcachedId;
        }

        return idxDot < 0 ? sessionIdWithNewMemcachedId : sessionIdWithNewMemcachedId + sessionId.substring( idxDot );
    }

    /**
     * Checks if the given session id matches the pattern
     * <code>[^-.]+-[^.]+(\.[\w]+)?</code>.
     *
     * @param sessionId
     *            the session id
     * @return true if matching, otherwise false.
     */
    public boolean isValid( final String sessionId ) {
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
    public String extractMemcachedId( final String sessionId ) {
        final int idxDash = sessionId.indexOf( '-' );
        if ( idxDash < 0 ) {
            return null;
        }
        final int idxDot = sessionId.indexOf( '.' );
        if ( idxDot < 0 ) {
            return sessionId.substring( idxDash + 1 );
        } else {
            return sessionId.substring( idxDash + 1, idxDot );
        }
    }

}