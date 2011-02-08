/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.traversal;

import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.RelationshipExpander;
import org.neo4j.graphdb.traversal.TraversalBranch;
import org.neo4j.kernel.impl.traversal.TraverserImpl.TraverserIterator;

import java.util.HashMap;

class StartNodeTraversalBranch extends TraversalBranchImpl
{
    StartNodeTraversalBranch( TraverserIterator traverser, Node source )
    {
        super( traverser, null, 0, source, null, new HashMap<Object, Object>() );
        evaluatePosition();
    }

    @Override
    public TraversalBranch next()
    {
        if ( !hasExpandedRelationships() )
        {
            if ( traverser.okToProceedFirst( this ) )
            {
                expandRelationships( false );
                return this;
            }
            else
            {
                return null;
            }
        }
        return super.next();
    }
}
