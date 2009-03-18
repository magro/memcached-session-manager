/*
 * $Id: $ (c)
 * Copyright 2009 freiheit.com technologies GmbH
 *
 * Created on Mar 14, 2009
 *
 * This file contains unpublished, proprietary trade secret information of
 * freiheit.com technologies GmbH. Use, transcription, duplication and
 * modification are strictly prohibited without prior written consent of
 * freiheit.com technologies GmbH.
 */
package de.javakaffee.web.msm;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import net.spy.memcached.MemcachedNode;
import net.spy.memcached.NodeLocator;
import net.spy.memcached.ops.Operation;

class SuffixBasedNodeLocator implements NodeLocator {
    
    private final Logger _logger = Logger.getLogger( SuffixBasedNodeLocator.class.getName() );

    private final List<MemcachedNode> _nodes;
    private final Map<String, MemcachedNode> _nodesMap;

    public SuffixBasedNodeLocator( List<MemcachedNode> nodes) {
        _nodes = nodes;
        
        final Map<String,MemcachedNode> map = new HashMap<String, MemcachedNode>( nodes.size(), 1 );
        for ( int i = 0; i < nodes.size(); i++ ) {
            map.put( String.valueOf( i ), nodes.get( i ) );
        }
        _nodesMap = map;
    }

    @Override
    public Collection<MemcachedNode> getAll() {
        return _nodesMap.values();
    }

    @Override
    public MemcachedNode getPrimary( String key ) {
        final MemcachedNode result = _nodesMap.get( getNodeId( key ) );
        // TODO: Can we have an invalid node id - no result here?
        //System.out.println( "-- result: " + result.getSocketAddress() );
        return result;
    }

    private String getNodeId( String key ) {
        return key.substring( key.lastIndexOf( '.' ) + 1 );
    }

    @Override
    public Iterator<MemcachedNode> getSequence( String key ) {
        String targetIdx = getNextNodeId( key );
        throw new RelocationException( "The node " + getNodeId( key ) + " is not available, we could move to node " + targetIdx, targetIdx );
    }

    protected String getNextNodeId( String key ) {
        /* just for simplicity: we know that the node id is the index
         * so we use this knowledge (insteaf of getting the node from the map
         * and the index of the node etc.
         */
        String nodeId = getNodeId( key );
        int idx = Integer.parseInt( nodeId );
        int targetIdx;
        if ( idx < 0 ) {
            _logger.warning( "Got a nodeId < 0, this is not valid" );
            // TODO: introduce some random here
            targetIdx = 0;
        }
        else if ( idx >= _nodes.size() ) {
            _logger.warning( "Got a nodeId > number of nodes, this is not valid" );
            // TODO: introduce some random here
            targetIdx = 0;
        }
        else {
            targetIdx = idx + 1 % _nodes.size();
            if ( targetIdx == idx ) {
                /* we have only a single node - game over
                 */
                throw new UnavailableNodeException( "The node " + nodeId + " is not available and there's no node for relocation left.", nodeId );
            }
        }
        return String.valueOf( targetIdx );
    }

    @Override
    public NodeLocator getReadonlyCopy() {
        final List<MemcachedNode> nodes = new ArrayList<MemcachedNode>();
        for ( MemcachedNode node : _nodes ) {
            nodes.add( new MyMemcachedNodeROImpl( node ) );
        }
        return new SuffixBasedNodeLocator( nodes );
    }
    
    static class MyMemcachedNodeROImpl implements MemcachedNode {

        private final MemcachedNode _root;

        public MyMemcachedNodeROImpl(MemcachedNode node) {
            _root = node;
        }

        @Override
        public String toString() {
            return _root.toString();
        }

        public void addOp(Operation op) {
            throw new UnsupportedOperationException();
        }

        public void connected() {
            throw new UnsupportedOperationException();
        }

        public void copyInputQueue() {
            throw new UnsupportedOperationException();
        }

        public void fillWriteBuffer(boolean optimizeGets) {
            throw new UnsupportedOperationException();
        }

        public void fixupOps() {
            throw new UnsupportedOperationException();
        }

        public int getBytesRemainingToWrite() {
            throw new UnsupportedOperationException();
        }

        public SocketChannel getChannel() {
            throw new UnsupportedOperationException();
        }

        public Operation getCurrentReadOp() {
            throw new UnsupportedOperationException();
        }

        public Operation getCurrentWriteOp() {
            throw new UnsupportedOperationException();
        }

        public ByteBuffer getRbuf() {
            throw new UnsupportedOperationException();
        }

        public int getReconnectCount() {
            throw new UnsupportedOperationException();
        }

        public int getSelectionOps() {
            throw new UnsupportedOperationException();
        }

        public SelectionKey getSk() {
            throw new UnsupportedOperationException();
        }

        public SocketAddress getSocketAddress() {
            return _root.getSocketAddress();
        }

        public ByteBuffer getWbuf() {
            throw new UnsupportedOperationException();
        }

        public boolean hasReadOp() {
            throw new UnsupportedOperationException();
        }

        public boolean hasWriteOp() {
            throw new UnsupportedOperationException();
        }

        public boolean isActive() {
            throw new UnsupportedOperationException();
        }

        public void reconnecting() {
            throw new UnsupportedOperationException();
        }

        public void registerChannel(SocketChannel ch, SelectionKey selectionKey) {
            throw new UnsupportedOperationException();
        }

        public Operation removeCurrentReadOp() {
            throw new UnsupportedOperationException();
        }

        public Operation removeCurrentWriteOp() {
            throw new UnsupportedOperationException();
        }

        public void setChannel(SocketChannel to) {
            throw new UnsupportedOperationException();
        }

        public void setSk(SelectionKey to) {
            throw new UnsupportedOperationException();
        }

        public void setupResend() {
            throw new UnsupportedOperationException();
        }

        public void transitionWriteItem() {
            throw new UnsupportedOperationException();
        }

        public int writeSome() throws IOException {
            throw new UnsupportedOperationException();
        }

        public Collection<Operation> destroyInputQueue() {
            throw new UnsupportedOperationException();
        }
    }
    
}