package org.neo4j.kernel.impl.traversal;

import org.junit.Before;
import org.junit.Test;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.traversal.Evaluation;
import org.neo4j.graphdb.traversal.Evaluator;
import org.neo4j.graphdb.traversal.TraversalDescription;
import org.neo4j.kernel.Traversal;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EvaluatorStateTest extends AbstractTestBase
{

    @Before
    public void clearGraph()
    {
        removeAllNodes( false );
    }

    @Test
    public void shouldIncludeStateFromPreviousSteps()
    {
        createGraph( "a TO b", "b TO c" );
        final Node nodeA = getNodeWithName( "a" );
        final Node nodeB = getNodeWithName( "b" );
        final Node nodeC = getNodeWithName( "c" );
        TraversalDescription desc = Traversal.description().evaluator( new Evaluator()
        {
            @Override
            public Evaluation evaluate( Path path, Map<Object, Object> state )
            {
                if ( path.endNode().equals( nodeB ) )
                {
                    state.put( "key", "value" );
                }
                else if ( path.endNode().equals( nodeC ) )
                {
                    assertEquals( "value", state.get( "key" ) );
                }
                return Evaluation.INCLUDE_AND_CONTINUE;
            }
        } );
        for ( Path path : desc.traverse( nodeA ) )
        {
        }
    }

    @Test
    public void shouldNotRememberStateFromOtherBranches()
    {
        createGraph( "a TO b1", "a TO b2" );
        final Node nodeA = getNodeWithName( "a" );
        final Node nodeB1 = getNodeWithName( "b1" );
        final Node nodeB2 = getNodeWithName( "b2" );
        TraversalDescription desc = Traversal.description().evaluator( new Evaluator()
        {
            @Override
            public Evaluation evaluate( Path path, Map<Object, Object> state )
            {
                if ( path.endNode().equals( nodeB1 ) )
                {
                    state.put( "b1", "value" );
                    assertFalse( state.containsKey( "b2" ) );

                }
                else if ( path.endNode().equals( nodeB2 ) )
                {
                    state.put( "b2", "value" );
                    assertFalse( state.containsKey( "b1" ) );
                }
                return Evaluation.INCLUDE_AND_CONTINUE;
            }
        } );
        for ( Path path : desc.traverse( nodeA ) )
        {
        }
    }

    @Test
    public void shouldLetMultipleEvaluatorsChangeState()
    {
        createGraph( "a TO b" );

        final Node nodeA = getNodeWithName( "a" );
        final Node nodeB = getNodeWithName( "b" );
        TraversalDescription desc = Traversal.description().evaluator( new Evaluator()
        {
            @Override
            public Evaluation evaluate( Path path, Map<Object, Object> state )
            {
                if ( path.endNode().equals( nodeB ) )
                {
                    assertTrue( state.containsKey( "k1" ) );
                    assertTrue( state.containsKey( "k2" ) );
                }
                return Evaluation.INCLUDE_AND_CONTINUE;
            }
        } ).evaluator( new Evaluator()
        {
            @Override
            public Evaluation evaluate( Path path, Map<Object, Object> state )
            {
                if ( path.endNode().equals( nodeA ) )
                {
                    state.put( "k1", "k1" );
                }
                return Evaluation.INCLUDE_AND_CONTINUE;
            }
        } ).evaluator( new Evaluator()
        {
            @Override
            public Evaluation evaluate( Path path, Map<Object, Object> state )
            {
                if ( path.endNode().equals( nodeA ) )
                {
                    state.put( "k2", "k2" );
                }
                return Evaluation.INCLUDE_AND_CONTINUE;
            }
        } );
        for ( Path path : desc.traverse( nodeA ) )
        {
        }
    }
}
