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

/**
 * This exception is thrown when a node is not available.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class NodeFailureException extends RuntimeException {

    private static final long serialVersionUID = 5954872380654336225L;

    private final String _nodeId;

    /**
     * Creates a new instance with the given msg and nodeId.
     *
     * @param msg
     *            the error message
     * @param nodeId
     *            the id of the node that failed
     */
    public NodeFailureException( final String msg, final String nodeId ) {
        super( msg );
        _nodeId = nodeId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        /* we don't need this, saving time... */
        return null;
    }

    /**
     * The id of the node that failed.
     *
     * @return the node id.
     */
    public String getNodeId() {
        return _nodeId;
    }

}
