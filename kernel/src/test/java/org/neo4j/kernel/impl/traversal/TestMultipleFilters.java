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

import org.junit.BeforeClass;
import org.junit.Test;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.traversal.Evaluation;
import org.neo4j.graphdb.traversal.Evaluator;
import org.neo4j.graphdb.traversal.TraversalDescription;
import org.neo4j.helpers.Predicate;
import org.neo4j.helpers.collection.IteratorUtil;
import org.neo4j.kernel.Traversal;

import java.util.Map;

public class TestMultipleFilters extends AbstractTestBase
{
    @BeforeClass
    public static void setupGraph()
    {
        //
        //                     (a)--------
        //                     /          \
        //                    v            v
        //                  (b)-->(k)<----(c)-->(f)
        //                  / \
        //                 v   v
        //                (d)  (e)
        createGraph( "a TO b", "b TO d", "b TO e", "b TO k", "a TO c", "c TO f", "c TO k" );
    }
    
    private static class MustBeConnectedToNodeFilter implements Predicate<Path>, Evaluator
    {
        private final Node node;

        MustBeConnectedToNodeFilter( Node node )
        {
            this.node = node;
        }
        
        public boolean accept( Path item )
        {
            for ( Relationship rel : item.endNode().getRelationships( Direction.OUTGOING ) )
            {
                if ( rel.getEndNode().equals( node ) )
                {
                    return true;
                }
            }
            return false;
        }

        public Evaluation evaluate( Path path, Map<Object, Object> state )
        {
            return accept( path ) ? Evaluation.INCLUDE_AND_CONTINUE : Evaluation.EXCLUDE_AND_CONTINUE;
        }
    }

    @Test
    public void testNarrowingFilters()
    {
        Evaluator mustBeConnectedToK = new MustBeConnectedToNodeFilter( getNodeWithName( "k" ) );
        Evaluator mustNotHaveMoreThanTwoOutRels = new Evaluator()
        {
            public Evaluation evaluate( Path path, Map<Object, Object> state )
            {
                return Evaluation.ofIncludes( IteratorUtil.count( path.endNode().getRelationships( Direction.OUTGOING ) ) <= 2 );
            }
        };
        
        TraversalDescription description = Traversal.description().evaluator( mustBeConnectedToK );
        expectNodes( description.traverse( referenceNode() ), "b", "c" );
        expectNodes( description.evaluator( mustNotHaveMoreThanTwoOutRels ).traverse( referenceNode() ), "c" );
    }
    
    @Test
    public void testBroadeningFilters()
    {
        MustBeConnectedToNodeFilter mustBeConnectedToC = new MustBeConnectedToNodeFilter( getNodeWithName( "c" ) );
        MustBeConnectedToNodeFilter mustBeConnectedToE = new MustBeConnectedToNodeFilter( getNodeWithName( "e" ) );
        
        // Nodes connected (OUTGOING) to c (which "a" is)
        expectNodes( Traversal.description().evaluator( mustBeConnectedToC ).traverse( referenceNode() ), "a" );
        // Nodes connected (OUTGOING) to c AND e (which none is)
        expectNodes( Traversal.description().evaluator( mustBeConnectedToC ).evaluator( mustBeConnectedToE ).traverse( referenceNode() ) );
        // Nodes connected (OUTGOING) to c OR e (which "a" and "b" is)
        expectNodes( Traversal.description().filter( Traversal.returnAcceptedByAny( mustBeConnectedToC, mustBeConnectedToE ) ).traverse( referenceNode() ), "a", "b" );
    }
}
