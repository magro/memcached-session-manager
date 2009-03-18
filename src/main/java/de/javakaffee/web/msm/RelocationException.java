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
 * This exception is thrown when a node is not available but the session
 * can be moved to another node.
 * 
 * @author <a href="mailto:martin.grotzke@freiheit.com">Martin Grotzke</a>
 * @version $Id$
 */
public class RelocationException extends RuntimeException {

    private static final long serialVersionUID = 5954872380654336225L;
    
    private final String _targetNodeId;

    public RelocationException( String msg, String targetNodeId ) {
        super( msg );
        _targetNodeId = targetNodeId;
    }

    /**
     * @return the targetNodeId
     */
    public String getTargetNodeId() {
        return _targetNodeId;
    }

    /* (non-Javadoc)
     * @see java.lang.Throwable#fillInStackTrace()
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        /* we don't need this, saving time... */
        return null;
    }

}
