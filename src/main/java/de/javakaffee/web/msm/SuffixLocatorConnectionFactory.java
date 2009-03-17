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

import java.util.List;

import net.spy.memcached.DefaultConnectionFactory;
import net.spy.memcached.MemcachedNode;
import net.spy.memcached.NodeLocator;
import net.spy.memcached.transcoders.Transcoder;

import org.apache.catalina.Manager;

public final class SuffixLocatorConnectionFactory extends
        DefaultConnectionFactory {
    
    private final Manager _manager;
    
    public SuffixLocatorConnectionFactory( Manager manager ) {
        _manager = manager;
    }

    /* (non-Javadoc)
     * @see net.spy.memcached.DefaultConnectionFactory#createLocator(java.util.List)
     */
    @Override
    public NodeLocator createLocator(
            List<MemcachedNode> nodes ) {
        return new SuffixBasedNodeLocator( nodes );
    }
    
    @Override
    public Transcoder<Object> getDefaultTranscoder() {
        return new SessionSerializingTranscoder( _manager );
    }
    
}