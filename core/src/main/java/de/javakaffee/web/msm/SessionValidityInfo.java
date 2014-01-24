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

import static de.javakaffee.web.msm.TranscoderService.decodeNum;
import static de.javakaffee.web.msm.TranscoderService.encodeNum;

import javax.annotation.Nonnull;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * This class defines the format for session validity information stored in memcached for
 * non-sticky sessions. This information is used when the container (CoyoteAdapter) tries to
 * determine if a provided sessionId is valid when parsing the request.
 * <p>
 * The stored information contains the maxInactiveInterval (might be session specific),
 * lastAccessedTime (used by tomcat7 with STRICT_SERVLET_COMPLIANCE/LAST_ACCESS_AT_START) and
 * thisAccessedTime.
 * </p>
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class SessionValidityInfo {

    @SuppressWarnings( "unused" )
    private static final Log LOG = LogFactory.getLog( SessionValidityInfo.class );

    private final int _maxInactiveInterval;
    private final long _lastAccessedTime;
    private final long _thisAccessedTime;

    public SessionValidityInfo( final int maxInactiveInterval, final long lastAccessedTime, final long thisAccessedTime ) {
        _maxInactiveInterval = maxInactiveInterval;
        _lastAccessedTime = lastAccessedTime;
        _thisAccessedTime = thisAccessedTime;
    }

    /**
     * Encode the given information to a byte[], that can be decoded later via {@link #decode(byte[])}.
     */
    @Nonnull
    public static byte[] encode( final long maxInactiveInterval, final long lastAccessedTime, final long thisAccessedTime ) {
        int idx = 0;
        final byte[] data = new byte[ 4 + 2 * 8 ];
        encodeNum( maxInactiveInterval, data, idx, 4 );
        encodeNum( lastAccessedTime, data, idx += 4, 8 );
        encodeNum( thisAccessedTime, data, idx += 8, 8 );
        return data;
    }

    /**
     * Decode the given byte[] that previously was created via {@link #encode(long, long, long)}.
     */
    @Nonnull
    public static SessionValidityInfo decode( @Nonnull final byte[] data ) {
        int idx = 0;
        final int maxInactiveInterval = (int) decodeNum( data, idx, 4 );
        final long lastAccessedTime = decodeNum( data, idx += 4, 8 );
        final long thisAccessedTime = decodeNum( data, idx += 8, 8 );
        return new SessionValidityInfo( maxInactiveInterval, lastAccessedTime, thisAccessedTime );
    }

    public int getMaxInactiveInterval() {
        return _maxInactiveInterval;
    }

    public long getLastAccessedTime() {
        return _lastAccessedTime;
    }

    public long getThisAccessedTime() {
        return _thisAccessedTime;
    }

    public boolean isValid() {
        final long timeNow = System.currentTimeMillis();
        final int timeIdle = (int) ((timeNow - _thisAccessedTime) / 1000L);
        // if tomcat session inactivity is negative or 0, session
        // should not expire
        return _maxInactiveInterval <= 0 || timeIdle < _maxInactiveInterval;
    }

}