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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * The list of node ids.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class NodeIdList extends ArrayList<String> {

    private static final long serialVersionUID = 2585919426234285289L;

    public NodeIdList( @Nonnull final String ... nodeIds ) {
        super( Arrays.asList( nodeIds ) );
    }

    public NodeIdList( @Nonnull final List<String> nodeIds ) {
        super( nodeIds );
    }

    @Nonnull
    public static NodeIdList create( @Nonnull final String ... nodeIds ) {
        return new NodeIdList( nodeIds );
    }

    /**
     * Get the next node id for the given one. For the last node id
     * the first one is returned.
     * If this list contains only a single node, conceptionally there's no next node
     * so that <code>null</code> is returned.
     * @return the next node id or <code>null</code> if there's no next node id.
     * @throws IllegalArgumentException thrown if the given nodeId is not part of this list.
     */
    @CheckForNull
    public String getNextNodeId( @Nonnull final String nodeId ) throws IllegalArgumentException {
        final int idx = indexOf( nodeId );
        if ( idx < 0 ) {
            throw new IllegalArgumentException( "The given node id "+ nodeId +" is not part of this list " + toString() );
        }
        if ( size() == 1 ) {
            return null;
        }
        return ( idx == size() - 1 ) ? get( 0 ) : get( idx + 1 );
    }

}
