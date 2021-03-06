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
package org.neo4j.management.impl;

import javax.management.NotCompliantMBeanException;

import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.helpers.Service;
import org.neo4j.kernel.KernelExtension.KernelData;
import org.neo4j.kernel.impl.core.NodeManager;
import org.neo4j.kernel.impl.nioneo.store.PropertyStore;
import org.neo4j.management.Primitives;

@Service.Implementation( ManagementBeanProvider.class )
public final class PrimitivesBean extends ManagementBeanProvider
{
    public PrimitivesBean()
    {
        super( Primitives.class );
    }

    @Override
    protected Neo4jMBean createMBean( KernelData kernel ) throws NotCompliantMBeanException
    {
        return new PrimitivesImpl( this, kernel );
    }

    @Description( "Estimates of the numbers of different kinds of Neo4j primitives" )
    private static class PrimitivesImpl extends Neo4jMBean implements Primitives
    {
        protected PrimitivesImpl( ManagementBeanProvider provider, KernelData kernel )
                throws NotCompliantMBeanException
        {
            super( provider, kernel );
            this.nodeManager = kernel.getConfig().getGraphDbModule().getNodeManager();
        }

        private final NodeManager nodeManager;

        @Description( "An estimation of the number of nodes used in this Neo4j instance" )
        public long getNumberOfNodeIdsInUse()
        {
            return nodeManager.getNumberOfIdsInUse( Node.class );
        }

        @Description( "An estimation of the number of relationships used in this Neo4j instance" )
        public long getNumberOfRelationshipIdsInUse()
        {
            return nodeManager.getNumberOfIdsInUse( Relationship.class );
        }

        @Description( "An estimation of the number of properties used in this Neo4j instance" )
        public long getNumberOfPropertyIdsInUse()
        {
            return nodeManager.getNumberOfIdsInUse( PropertyStore.class );
        }

        @Description( "The number of relationship types used in this Neo4j instance" )
        public long getNumberOfRelationshipTypeIdsInUse()
        {
            return nodeManager.getNumberOfIdsInUse( RelationshipType.class );
        }
    }
}
