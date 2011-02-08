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

import net.spy.memcached.CachedData;
import net.spy.memcached.transcoders.Transcoder;
import de.javakaffee.web.msm.Statistics.MinMaxAvgProbe;
import de.javakaffee.web.msm.Statistics.StatsType;

/**
 * A {@link Transcoder} that delegates all calls to a provided delegate.
 * For {@link #encode(Object)} additionally the size of the encoded data
 * is stored in the {@link Statistics#getCachedDataSizeProbe()}.
 *
 * @see CachedData#getData()
 * @see MinMaxAvgProbe#register(long)
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class TranscoderWrapperStatisticsSupport implements Transcoder<Object> {

    private final Statistics _statistics;
    private final Transcoder<Object> _delegate;

    /**
     * Create a new transoder wrapper.
     *
     * @param statistics the statistics instance that must provide a not <code>null</code>
     *  {@link MinMaxAvgProbe} via {@link Statistics#getCachedDataSizeProbe()}.
     * @param delegate the transcoder that gets all calls routed.
     */
    public TranscoderWrapperStatisticsSupport( final Statistics statistics, final Transcoder<Object> delegate ) {
        _statistics = statistics;
        _delegate = delegate;
    }

    /**
     * {@inheritDoc}
     */
    public boolean asyncDecode( final CachedData cachedData ) {
        return _delegate.asyncDecode( cachedData );
    }

    /**
     * {@inheritDoc}
     */
    public Object decode( final CachedData cachedData ) {
        return _delegate.decode( cachedData );
    }

    /**
     * {@inheritDoc}
     */
    public CachedData encode( final Object object ) {
        final CachedData result = _delegate.encode( object );
        _statistics.register( StatsType.CACHED_DATA_SIZE, result.getData().length );
        return result;
    }

    /**
     * {@inheritDoc}
     */
    public int getMaxSize() {
        return _delegate.getMaxSize();
    }

}
