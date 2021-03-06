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
package org.neo4j.graphalgo;

import org.neo4j.graphalgo.impl.path.AStar;
import org.neo4j.graphalgo.impl.path.AllPaths;
import org.neo4j.graphalgo.impl.path.AllSimplePaths;
import org.neo4j.graphalgo.impl.path.Dijkstra;
import org.neo4j.graphalgo.impl.path.ShortestPath;
import org.neo4j.graphalgo.impl.util.DoubleEvaluator;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipExpander;

/**
 * Static factory methods for the recommended implementations of common
 * graph algorithms for Neo4j. The algorithms exposed here are implementations
 * which are tested extensively and also scale on bigger graphs.
 * 
 * @author Mattias Persson
 */
public abstract class GraphAlgoFactory
{
    /**
     * Returns an algorithm which can find all available paths between two
     * nodes. These returned paths can contain loops (i.e. a node can occur
     * more than once in any returned path).
     * @see AllPaths
     * @param expander the {@link RelationshipExpander} to use for expanding
     * {@link Relationship}s for each {@link Node}.
     * @param maxDepth the max {@link Path#length()} returned paths are
     * allowed to have.
     * @return an algorithm which finds all paths between two nodes.
     */
    public static PathFinder<Path> allPaths( RelationshipExpander expander, int maxDepth )
    {
        return new AllPaths( maxDepth, expander );
    }

    /**
     * Returns an algorithm which can find all simple paths between two
     * nodes. These returned paths cannot contain loops (i.e. a node cannot
     * occur more than once in any returned path).
     * @see AllSimplePaths
     * @param expander the {@link RelationshipExpander} to use for expanding
     * {@link Relationship}s for each {@link Node}.
     * @param maxDepth the max {@link Path#length()} returned paths are
     * allowed to have.
     * @return an algorithm which finds simple paths between two nodes.
     */
    public static PathFinder<Path> allSimplePaths( RelationshipExpander expander,
            int maxDepth )
    {
        return new AllSimplePaths( maxDepth, expander );
    }

    /**
     * Returns an algorithm which can find all shortest paths (that is paths
     * with as short {@link Path#length()} as possible) between two nodes. These
     * returned paths cannot contain loops (i.e. a node cannot occur more than
     * once in any returned path).
     * 
     * @see ShortestPath
     * @param expander the {@link RelationshipExpander} to use for expanding
     *            {@link Relationship}s for each {@link Node}.
     * @param maxDepth the max {@link Path#length()} returned paths are allowed
     *            to have.
     * @return an algorithm which finds shortest paths between two nodes.
     */
    public static PathFinder<Path> shortestPath( RelationshipExpander expander, int maxDepth )
    {
        return new ShortestPath( maxDepth, expander );
    }

    /**
     * Returns an algorithm which can find simple all paths of a certain length
     * between two nodes. These returned paths cannot contain loops (i.e. a node
     * could not occur more than once in any returned path).
     * 
     * @see ShortestPath
     * @param expander the {@link RelationshipExpander} to use for expanding
     * {@link Relationship}s for each {@link Node}.
     * @param length the {@link Path#length()} returned paths will have, if any
     * paths were found.
     * @return an algorithm which finds paths of a certain length between two nodes.
     */
    public static PathFinder<Path> pathsWithLength( RelationshipExpander expander, int length )
    {
        return new ShortestPath( length, expander, true );
    }
    
    /**
     * Returns an {@link PathFinder} which uses the A* algorithm to find the
     * cheapest path between two nodes. The definition of "cheap" is the lowest
     * possible cost to get from the start node to the end node, where the cost
     * is returned from {@code lengthEvaluator} and {@code estimateEvaluator}.
     * These returned paths cannot contain loops (i.e. a node cannot occur more
     * than once in any returned path).
     * 
     * See http://en.wikipedia.org/wiki/A*_search_algorithm for more
     * information.
     * 
     * @see AStar
     * @param expander the {@link RelationshipExpander} to use for expanding
     * {@link Relationship}s for each {@link Node}.
     * @param lengthEvaluator evaluator that can return the cost represented
     * by each relationship the algorithm traverses.
     * @param estimateEvaluator evaluator that returns an (optimistic)
     * estimation of the cost to get from the current node (in the traversal)
     * to the end node.
     * @return an algorithm which finds the cheapest path between two nodes
     * using the A* algorithm.
     */
    public static PathFinder<WeightedPath> aStar( RelationshipExpander expander,
            CostEvaluator<Double> lengthEvaluator, EstimateEvaluator<Double> estimateEvaluator )
    {
        return new AStar( expander, lengthEvaluator, estimateEvaluator );
    }

    /**
     * Returns an {@link PathFinder} which uses the Dijkstra algorithm to find
     * the cheapest path between two nodes. The definition of "cheap" is the
     * lowest possible cost to get from the start node to the end node, where
     * the cost is returned from {@code costEvaluator}. These returned paths
     * cannot contain loops (i.e. a node cannot occur more than once in any
     * returned path).
     * 
     * See http://en.wikipedia.org/wiki/Dijkstra%27s_algorithm for more
     * information.
     * 
     * @see Dijkstra
     * @param expander the {@link RelationshipExpander} to use for expanding
     * {@link Relationship}s for each {@link Node}.
     * @param costEvaluator evaluator that can return the cost represented
     * by each relationship the algorithm traverses.
     * @return an algorithm which finds the cheapest path between two nodes
     * using the Dijkstra algorithm.
     */
    public static PathFinder<WeightedPath> dijkstra( RelationshipExpander expander,
            CostEvaluator<Double> costEvaluator )
    {
        return new Dijkstra( expander, costEvaluator );
    }
    
    /**
     * See {@link #dijkstra(RelationshipExpander, CostEvaluator)}.
     * 
     * Uses a cost evaluator which uses the supplied property key to
     * represent the cost (values of type <bold>double</bold>). 
     * 
     * @param expander the {@link RelationshipExpander} to use for expanding
     * {@link Relationship}s for each {@link Node}.
     * @param relationshipPropertyRepresentingCost the property to represent cost
     * on each relationship the algorithm traverses.
     * @return an algorithm which finds the cheapest path between two nodes
     * using the Dijkstra algorithm.
     */
    public static PathFinder<WeightedPath> dijkstra( RelationshipExpander expander,
            String relationshipPropertyRepresentingCost )
    {
        return dijkstra( expander, new DoubleEvaluator( relationshipPropertyRepresentingCost ) );
    }
}
