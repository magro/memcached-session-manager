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

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * Stores readonly requests and a blacklist (requests that modified the session).
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class ReadOnlyRequestsCache {

    private static final Comparator<AtomicLong> ATOMLONG_COMP = new Comparator<AtomicLong>() {

        @Override
        public int compare( final AtomicLong o1, final AtomicLong o2 ) {
            final long val1 = o1.longValue();
            final long val2 = o2.longValue();
            return val1 < val2 ? -1 : ( val1 == val2 ? 0 : 1);
        }

    };

    private final Log _log = LogFactory.getLog( getClass() );

    private final LRUCache<String, AtomicLong> _readOnlyRequests;
    private final LRUCache<String, AtomicLong> _blacklist;

    public ReadOnlyRequestsCache() {
        final long sixHours = TimeUnit.HOURS.toMillis( 6 );
        _readOnlyRequests = new LRUCache<String, AtomicLong>( 1000, sixHours );
        _blacklist = new LRUCache<String, AtomicLong>( 50000, sixHours );
    }

    /**
     * Registers the given requestURI as a readonly request, as long as it has not been tracked
     * before as a modifying request (via {@link #modifyingRequest(String)}).
     * <p>
     * There's a limit on the number and the time readonly requests are beeing stored (simply a LRU cache),
     * so that the most frequently accessed readonly requests are stored.
     * </p>
     * @param requestId the request uri to track.
     * @return <code>true</code> if the requestURI was stored as readonly, <code>false</code> if it was on the blacklist.
     * @see #modifyingRequest(String)
     */
    public boolean readOnlyRequest( final String requestId ) {
        if ( !_blacklist.containsKey( requestId ) ) {
            if ( _log.isDebugEnabled() ) {
                _log.debug( "Registering readonly request: " + requestId );
            }
            incrementOrPut( _readOnlyRequests, requestId );
            return true;
        }
        return false;
    }

    /**
     * Registers the given requestURI as a modifying request, which can be seen as a blacklist for
     * readonly requests. There's a limit on number and time for modifying requests beeing stored.
     * @param requestId the request uri to track.
     */
    public void modifyingRequest( final String requestId ) {
        if ( _log.isDebugEnabled() ) {
            _log.debug( "Registering modifying request: " + requestId );
        }
        incrementOrPut( _blacklist, requestId );
        _readOnlyRequests.remove( requestId );
    }

    /**
     * Determines, if the given requestURI is a readOnly request and not blacklisted as a modifying request.
     * @param requestId the request uri to check
     * @return <code>true</code> if the given request uri can be regarded as read only.
     */
    public boolean isReadOnlyRequest( final String requestId ) {
        if ( _log.isDebugEnabled() ) {
            _log.debug( "Asked for readonly request: " + requestId + " ("+ _readOnlyRequests.containsKey( requestId ) +")" );
        }
        // TODO: add some threshold
        return _readOnlyRequests.containsKey( requestId );
    }

    /**
     * The readonly requests, ordered by last accessed time, from least-recently accessed to most-recently.
     * @return a list of readonly requests.
     */
    public List<String> getReadOnlyRequests() {
        return _readOnlyRequests.getKeys();
    }

    /**
     * The readonly requests, ordered by the frequency they got registered, from least-frequently to most-frequently.
     * @return a list of readonly requests.
     */
    public List<String> getReadOnlyRequestsByFrequency() {
        return _readOnlyRequests.getKeysSortedByValue( ATOMLONG_COMP );
    }

    private void incrementOrPut( final LRUCache<String, AtomicLong> cache, final String requestURI ) {
        final AtomicLong count = cache.get( requestURI );
        if ( count != null ) {
            count.incrementAndGet();
        }
        else {
            cache.put( requestURI, new AtomicLong( 1 ) );
        }
    }

}
