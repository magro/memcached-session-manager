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

import java.net.SocketAddress;
import java.nio.channels.SocketChannel;
import java.util.List;

import net.spy.memcached.BinaryConnectionFactory;
import net.spy.memcached.DefaultConnectionFactory;
import net.spy.memcached.FailureMode;
import net.spy.memcached.MemcachedNode;
import net.spy.memcached.NodeLocator;
import net.spy.memcached.OperationFactory;
import net.spy.memcached.protocol.binary.BinaryMemcachedNodeImpl;
import net.spy.memcached.protocol.binary.BinaryOperationFactory;
import net.spy.memcached.transcoders.SerializingTranscoder;
import net.spy.memcached.transcoders.Transcoder;


/**
 * This {@link net.spy.memcached.ConnectionFactory} uses the
 * {@link SuffixBasedNodeLocator} as {@link NodeLocator} and overwrites
 * methods as {@link BinaryConnectionFactory} does it as well.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public final class SuffixLocatorBinaryConnectionFactory extends DefaultConnectionFactory {

	private final MemcachedNodesManager _memcachedNodesManager;
	private final SessionIdFormat _sessionIdFormat;
    private final Statistics _statistics;

    /**
     * Creates a new instance.
     * @param memcachedNodesManager
     *            the memcached nodes manager holding list of nodeIds
     * @param sessionIdFormat
     *            the {@link SessionIdFormat}
     */
    public SuffixLocatorBinaryConnectionFactory( final MemcachedNodesManager memcachedNodesManager, final SessionIdFormat sessionIdFormat,
            final Statistics statistics ) {
        _memcachedNodesManager = memcachedNodesManager;
        _sessionIdFormat = sessionIdFormat;
        _statistics = statistics;
    }

    /**
     * We don't want to try another memcached node and we also don't want to wait
     * until the failed node becomes available again.
     */
    @Override
    public FailureMode getFailureMode() {
        return FailureMode.Cancel;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NodeLocator createLocator( final List<MemcachedNode> nodes ) {
        return new SuffixBasedNodeLocator( nodes, _memcachedNodesManager, _sessionIdFormat );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Transcoder<Object> getDefaultTranscoder() {
        final SerializingTranscoder transcoder = new SerializingTranscoder();
        transcoder.setCompressionThreshold( SerializingTranscoder.DEFAULT_COMPRESSION_THRESHOLD );
        return new TranscoderWrapperStatisticsSupport( _statistics, transcoder );
    }

    @Override
    public MemcachedNode createMemcachedNode(final SocketAddress sa,
            final SocketChannel c, final int bufSize) {
        final boolean doAuth = false;
        return new BinaryMemcachedNodeImpl(sa, c, bufSize,
            createReadOperationQueue(),
            createWriteOperationQueue(),
            createOperationQueue(),
            getOpQueueMaxBlockTime(),
            doAuth);
    }

    @Override
    public OperationFactory getOperationFactory() {
        return new BinaryOperationFactory();
    }

}