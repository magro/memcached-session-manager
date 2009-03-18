/*
 * $Id: $ (c)
 * Copyright 2009 freiheit.com technologies GmbH
 *
 * Created on Mar 18, 2009
 *
 * This file contains unpublished, proprietary trade secret information of
 * freiheit.com technologies GmbH. Use, transcription, duplication and
 * modification are strictly prohibited without prior written consent of
 * freiheit.com technologies GmbH.
 */
package de.javakaffee.web.msm;

/**
 * This exception is thrown when a node is not available and no failover
 * node is left.
 * 
 * @author <a href="mailto:martin.grotzke@freiheit.com">Martin Grotzke</a>
 * @version $Id$
 */
public class UnavailableNodeException extends RuntimeException {
    
    private static final long serialVersionUID = 1L;
    
    private final String _nodeId;

    public UnavailableNodeException(String msg, String nodeId) {
        super( msg );
        _nodeId = nodeId;
    }

    /**
     * @return the nodeId
     */
    public String getNodeId() {
        return _nodeId;
    }

}
