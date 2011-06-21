/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.index.impl.lucene;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.graphdb.DynamicRelationshipType;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.index.AutoIndex;
import org.neo4j.graphdb.index.AutoIndexer;
import org.neo4j.kernel.Config;
import org.neo4j.kernel.EmbeddedGraphDatabase;
import org.neo4j.kernel.impl.util.FileUtils;

public class TestAutoIndexing
{
    protected static final File WorkDir = new File( "target" + File.separator
                                                    + "var", "autoIndexer" );

    private GraphDatabaseService graphDb;
    private Transaction tx;
    private Map<String, String> config;

    private static int currentId = 0;

    private void newTransaction()
    {
        if ( tx != null )
        {
            tx.success();
            tx.finish();
        }
        tx = graphDb.beginTx();
    }

    private Map<String, String> getConfig()
    {
        if ( config == null )
        {
            return Collections.emptyMap();
        }
        return config;
    }

    @Before
    public void startDb()
    {
        currentId++;
        File workDir = getWorkDir();
        workDir.mkdirs();
        graphDb = new EmbeddedGraphDatabase( workDir.getAbsolutePath(),
                getConfig() );
    }

    @After
    public void stopDb()
    {
        if ( tx != null )
        {
            tx.finish();
        }
        if ( graphDb != null )
        {
            graphDb.shutdown();
        }
        // For access from the delete thread
        final File currentWorkDir = getWorkDir();
        new Thread(new Runnable() {
            public void run() {
                try
                {
                    FileUtils.deleteRecursively( currentWorkDir );
                }
                catch ( IOException e )
                {
                    System.err.println( "The following exception can be ignored, it is a locking issue on windows. If on linux, investigate" );
                    e.printStackTrace();
                }
            };
        } ).start();
        tx = null;
        config = null;
        graphDb = null;
    }

    private File getWorkDir()
    {
        return new File( WorkDir, Integer.toString( currentId ) );
    }

    @Test
    public void testNodeAutoIndexFromAPISanity()
    {
        AutoIndexer<Node> autoIndexer = graphDb.index().getNodeAutoIndexer();
        autoIndexer.startAutoIndexingProperty( "test_uuid" );
        autoIndexer.setEnabled( true );
        assertEquals( 1, autoIndexer.getAutoIndexedProperties().size() );
        assertTrue( autoIndexer.getAutoIndexedProperties().contains(
                "test_uuid" ) );
        newTransaction();

        Node node1 = graphDb.createNode();
        node1.setProperty( "test_uuid", "node1" );
        Node node2 = graphDb.createNode();
        node2.setProperty( "test_uuid", "node2" );

        // will index on commit
        assertFalse( autoIndexer.getAutoIndex().get( "test_uuid", "node1" ).hasNext() );
        assertFalse( autoIndexer.getAutoIndex().get( "test_uuid", "node2" ).hasNext() );

        newTransaction();

        assertEquals(
                node1,
                autoIndexer.getAutoIndex().get( "test_uuid", "node1" ).getSingle() );
        assertEquals(
                node2,
                autoIndexer.getAutoIndex().get( "test_uuid", "node2" ).getSingle() );
    }

    @Test
    public void testRelationshipAutoIndexFromAPISanity()
    {
        final String propNameToIndex = "test";
        AutoIndexer<Relationship> autoIndexer = graphDb.index().getRelationshipAutoIndexer();
        autoIndexer.startAutoIndexingProperty( propNameToIndex );
        autoIndexer.setEnabled( true );
        newTransaction();

        Node node1 = graphDb.createNode();
        Node node2 = graphDb.createNode();
        Node node3 = graphDb.createNode();

        Relationship rel12 = node1.createRelationshipTo( node2,
                DynamicRelationshipType.withName( "DYNAMIC" ) );
        Relationship rel23 = node2.createRelationshipTo( node3,
                DynamicRelationshipType.withName( "DYNAMIC" ) );

        rel12.setProperty( propNameToIndex, "rel12" );
        rel23.setProperty( propNameToIndex, "rel23" );

        // will index on commit
        assertFalse( autoIndexer.getAutoIndex().get( propNameToIndex,
                "rel12" ).hasNext() );
        assertFalse( autoIndexer.getAutoIndex().get( propNameToIndex,
                "rel23" ).hasNext() );

        newTransaction();

        assertEquals(
                rel12,
                autoIndexer.getAutoIndex().get( propNameToIndex,
                        "rel12" ).getSingle() );
        assertEquals(
                rel23,
                autoIndexer.getAutoIndex().get( propNameToIndex,
                        "rel23" ).getSingle() );
    }

    @Test
    public void testConfigAndAPICompatibility()
    {
        stopDb();
        config = new HashMap<String, String>();
        config.put( Config.NODE_KEYS_INDEXABLE, "nodeProp1, nodeProp2" );
        config.put( Config.RELATIONSHIP_KEYS_INDEXABLE, "relProp1, relProp2" );
        config.put( Config.NODE_AUTO_INDEXING, "true" );
        config.put( Config.RELATIONSHIP_AUTO_INDEXING, "true" );
        startDb();

        assertTrue( graphDb.index().getNodeAutoIndexer().isEnabled() );
        assertTrue( graphDb.index().getRelationshipAutoIndexer().isEnabled() );

        AutoIndexer<Node> autoNodeIndexer = graphDb.index().getNodeAutoIndexer();
        // Start auto indexing a new and an already auto indexed
        autoNodeIndexer.startAutoIndexingProperty( "nodeProp1" );
        autoNodeIndexer.startAutoIndexingProperty( "nodeProp3" );
        assertEquals( 3, autoNodeIndexer.getAutoIndexedProperties().size() );
        assertTrue( autoNodeIndexer.getAutoIndexedProperties().contains(
                "nodeProp1" ) );
        assertTrue( autoNodeIndexer.getAutoIndexedProperties().contains(
                "nodeProp2" ) );
        assertTrue( autoNodeIndexer.getAutoIndexedProperties().contains(
                "nodeProp3" ) );
    }

    @Test
    public void testSmallGraphWithNonIndexableProps() throws Exception
    {
        stopDb();
        config = new HashMap<String, String>();
        config.put( Config.NODE_KEYS_INDEXABLE, "nodeProp1, nodeProp2" );
        config.put( Config.RELATIONSHIP_KEYS_INDEXABLE, "relProp1, relProp2" );
        config.put( Config.NODE_AUTO_INDEXING, "true" );
        config.put( Config.RELATIONSHIP_AUTO_INDEXING, "true" );
        startDb();

        assertTrue( graphDb.index().getNodeAutoIndexer().isEnabled() );
        assertTrue( graphDb.index().getRelationshipAutoIndexer().isEnabled() );

        newTransaction();

        // Build the graph, a 3-cycle
        Node node1 = graphDb.createNode();
        Node node2 = graphDb.createNode();
        Node node3 = graphDb.createNode();

        Relationship rel12 = node1.createRelationshipTo( node2,
                DynamicRelationshipType.withName( "DYNAMIC" ) );
        Relationship rel23 = node2.createRelationshipTo( node3,
                DynamicRelationshipType.withName( "DYNAMIC" ) );
        Relationship rel31 = node3.createRelationshipTo( node1,
                DynamicRelationshipType.withName( "DYNAMIC" ) );

        // Nodes
        node1.setProperty( "nodeProp1", "node1Value1" );
        node1.setProperty( "nodePropNonIndexable1", "node1ValueNonIndexable" );

        node2.setProperty( "nodeProp2", "node2Value1" );
        node2.setProperty( "nodePropNonIndexable2", "node2ValueNonIndexable" );

        node3.setProperty( "nodeProp1", "node3Value1" );
        node3.setProperty( "nodeProp2", "node3Value2" );
        node3.setProperty( "nodePropNonIndexable3", "node3ValueNonIndexable" );

        // Relationships
        rel12.setProperty( "relProp1", "rel12Value1" );
        rel12.setProperty( "relPropNonIndexable1", "rel12ValueNonIndexable" );

        rel23.setProperty( "relProp2", "rel23Value1" );
        rel23.setProperty( "relPropNonIndexable2", "rel23ValueNonIndexable" );

        rel31.setProperty( "relProp1", "rel31Value1" );
        rel31.setProperty( "relProp2", "rel31Value2" );
        rel31.setProperty( "relPropNonIndexable3", "rel31ValueNonIndexable" );

        newTransaction();

        // Committed, time to check
        AutoIndexer<Node> autoNodeIndexer = graphDb.index().getNodeAutoIndexer();
        assertEquals(
                node1,
                autoNodeIndexer.getAutoIndex().get( "nodeProp1", "node1Value1" ).getSingle() );
        assertEquals(
                node2,
                autoNodeIndexer.getAutoIndex().get( "nodeProp2", "node2Value1" ).getSingle() );
        assertEquals(
                node3,
                autoNodeIndexer.getAutoIndex().get( "nodeProp1", "node3Value1" ).getSingle() );
        assertEquals(
                node3,
                autoNodeIndexer.getAutoIndex().get( "nodeProp2", "node3Value2" ).getSingle() );
        assertFalse( autoNodeIndexer.getAutoIndex().get(
                "nodePropNonIndexable1",
                "node1ValueNonIndexable" ).hasNext() );
        assertFalse( autoNodeIndexer.getAutoIndex().get(
                "nodePropNonIndexable2",
                "node2ValueNonIndexable" ).hasNext() );
        assertFalse( autoNodeIndexer.getAutoIndex().get(
                "nodePropNonIndexable3",
                "node3ValueNonIndexable" ).hasNext() );

        AutoIndexer<Relationship> autoRelIndexer = graphDb.index().getRelationshipAutoIndexer();
        assertEquals(
                rel12,
                autoRelIndexer.getAutoIndex().get( "relProp1",
                        "rel12Value1" ).getSingle() );
        assertEquals(
                rel23,
                autoRelIndexer.getAutoIndex().get( "relProp2",
                        "rel23Value1" ).getSingle() );
        assertEquals(
                rel31,
                autoRelIndexer.getAutoIndex().get( "relProp1",
                        "rel31Value1" ).getSingle() );
        assertEquals(
                rel31,
                autoRelIndexer.getAutoIndex().get( "relProp2",
                        "rel31Value2" ).getSingle() );
        assertFalse( autoRelIndexer.getAutoIndex().get(
                "relPropNonIndexable1",
                "rel12ValueNonIndexable" ).hasNext() );
        assertFalse( autoRelIndexer.getAutoIndex().get(
                "relPropNonIndexable2",
                "rel23ValueNonIndexable" ).hasNext() );
        assertFalse( autoRelIndexer.getAutoIndex().get(
                "relPropNonIndexable3",
                "rel31ValueNonIndexable" ).hasNext() );
    }

    @Test
    public void testDefaultIsOff()
    {
        newTransaction();
        Node node1 = graphDb.createNode();
        node1.setProperty( "testProp", "node1" );

        newTransaction();
        AutoIndexer<Node> autoIndexer = graphDb.index().getNodeAutoIndexer();
        assertFalse( autoIndexer.getAutoIndex().get( "testProp", "node1" ).hasNext() );
    }

    @Test
    public void testDefaulIfOffIsForEverything()
    {
        graphDb.index().getNodeAutoIndexer().setEnabled( true );
        newTransaction();
        Node node1 = graphDb.createNode();
        node1.setProperty( "testProp", "node1" );
        node1.setProperty( "testProp1", "node1" );
        Node node2 = graphDb.createNode();
        node2.setProperty( "testProp", "node2" );
        node2.setProperty( "testProp1", "node2" );

        newTransaction();
        AutoIndexer<Node> autoIndexer = graphDb.index().getNodeAutoIndexer();
        assertFalse( autoIndexer.getAutoIndex().get( "testProp", "node1" ).hasNext() );
        assertFalse( autoIndexer.getAutoIndex().get( "testProp1", "node1" ).hasNext() );
        assertFalse( autoIndexer.getAutoIndex().get( "testProp", "node2" ).hasNext() );
        assertFalse( autoIndexer.getAutoIndex().get( "testProp1", "node2" ).hasNext() );
    }

    @Test
    public void testDefaultIsOffIfExplicit() throws Exception
    {
        stopDb();
        config = new HashMap<String, String>();
        config.put( Config.NODE_KEYS_INDEXABLE, "nodeProp1, nodeProp2" );
        config.put( Config.RELATIONSHIP_KEYS_INDEXABLE, "relProp1, relProp2" );
        config.put( Config.NODE_AUTO_INDEXING, "false" );
        config.put( Config.RELATIONSHIP_AUTO_INDEXING, "false" );
        startDb();

        AutoIndexer<Node> autoIndexer = graphDb.index().getNodeAutoIndexer();
        autoIndexer.startAutoIndexingProperty( "testProp" );
        newTransaction();

        Node node1 = graphDb.createNode();
        node1.setProperty( "nodeProp1", "node1" );
        node1.setProperty( "nodeProp2", "node1" );
        node1.setProperty( "testProp", "node1" );

        newTransaction();

        assertFalse( autoIndexer.getAutoIndex().get( "nodeProp1", "node1" ).hasNext() );
        assertFalse( autoIndexer.getAutoIndex().get( "nodeProp2", "node1" ).hasNext() );
        assertFalse( autoIndexer.getAutoIndex().get( "testProp", "node1" ).hasNext() );
    }

    @Test
    public void testDefaultsAreSeparateForNodesAndRelationships()
            throws Exception
    {
        stopDb();
        config = new HashMap<String, String>();
        config.put( Config.NODE_KEYS_INDEXABLE, "propName" );
        config.put( Config.NODE_AUTO_INDEXING, "true" );
        // Now only node properties named propName should be indexed.
        startDb();

        newTransaction();

        Node node1 = graphDb.createNode();
        Node node2 = graphDb.createNode();
        node1.setProperty( "propName", "node1" );
        node2.setProperty( "propName", "node2" );
        node2.setProperty( "propName_", "node2" );

        Relationship rel = node1.createRelationshipTo( node2,
                DynamicRelationshipType.withName( "DYNAMIC" ) );
        rel.setProperty( "propName", "rel1" );

        newTransaction();

        AutoIndex<Node> autoIndex = graphDb.index().getNodeAutoIndexer().getAutoIndex();
        assertEquals( node1, autoIndex.get( "propName", "node1" ).getSingle() );
        assertEquals( node2, autoIndex.get( "propName", "node2" ).getSingle() );
        assertFalse( graphDb.index().getRelationshipAutoIndexer().getAutoIndex().get(
                "propName", "rel1" ).hasNext() );
    }

    @Test
    public void testStartStopAutoIndexing() throws Exception
    {
        stopDb();
        config = new HashMap<String, String>();
        config.put( Config.NODE_KEYS_INDEXABLE, "propName" );
        config.put( Config.NODE_AUTO_INDEXING, "true" );
        // Now only node properties named propName should be indexed.
        startDb();

        AutoIndexer<Node> autoIndexer = graphDb.index().getNodeAutoIndexer();

        newTransaction();

        Node node1 = graphDb.createNode();
        Node node2 = graphDb.createNode();
        node1.setProperty( "propName", "node" );
        autoIndexer.setEnabled( false );
        // Committing with auto indexing off, should not be in the index
        newTransaction();

        assertFalse( autoIndexer.getAutoIndex().get( "nodeProp1", "node1" ).hasNext() );
        autoIndexer.setEnabled( true );
        node2.setProperty( "propName", "node" );

        newTransaction();

        assertEquals( node2,
                autoIndexer.getAutoIndex().get( "propName", "node" ).getSingle() );
    }

    @Test
    public void testStopMonitoringProperty()
    {
        AutoIndexer<Node> autoIndexer = graphDb.index().getNodeAutoIndexer();
        autoIndexer.setEnabled( true );
        autoIndexer.startAutoIndexingProperty( "propName" );
        newTransaction();
        Node node1 = graphDb.createNode();
        Node node2 = graphDb.createNode();
        node1.setProperty( "propName", "node" );
        newTransaction();
        assertEquals(
                node1,
                autoIndexer.getAutoIndex().get( "propName", "node" ).getSingle() );
        newTransaction();
        // Setting just another property to autoindex
        autoIndexer.startAutoIndexingProperty( "propName2" );
        autoIndexer.stopAutoIndexingProperty( "propName" );
        node2.setProperty( "propName", "propValue" );
        Node node3 = graphDb.createNode();
        node3.setProperty( "propName2", "propValue" );
        newTransaction();
        // Now node2 must be not there, node3 must be there and node1 should not have been touched
        assertEquals(
                node1,
                autoIndexer.getAutoIndex().get( "propName", "node" ).getSingle() );
        assertEquals(
                node3,
                autoIndexer.getAutoIndex().get( "propName2", "propValue" ).getSingle() );
        // Now, since only propName2 is autoindexed, every other should be
        // removed when touched, such as node1's propName
        node1.setProperty( "propName", "newValue" );
        newTransaction();
        assertFalse( autoIndexer.getAutoIndex().get( "propName", "newValue" ).hasNext() );
    }
}