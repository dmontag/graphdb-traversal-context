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
package org.neo4j.kernel;

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

import javax.transaction.TransactionManager;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.TransactionFailureException;
import org.neo4j.kernel.impl.core.LockReleaser;
import org.neo4j.kernel.impl.nioneo.xa.NioNeoDbPersistenceSource;
import org.neo4j.kernel.impl.transaction.LockManager;
import org.neo4j.kernel.impl.transaction.TxModule;
import org.neo4j.kernel.impl.transaction.xaframework.XaDataSource;
import org.neo4j.kernel.impl.util.FileUtils;
import org.neo4j.kernel.impl.util.StringLogger;

class GraphDbInstance
{
    private boolean started = false;
    private final boolean create;
    private String storeDir;

    GraphDbInstance( String storeDir, boolean create, Config config )
    {
        this.storeDir = storeDir;
        this.create = create;
        this.config = config;
    }

    private final Config config;

    private NioNeoDbPersistenceSource persistenceSource = null;

    public Config getConfig()
    {
        return config;
    }

    /**
     * Starts Neo4j with default configuration
     * @param graphDb The graph database service.
     *
     * @param storeDir path to directory where Neo4j store is located
     * @param create if true a new Neo4j store will be created if no store exist
     *            at <CODE>storeDir</CODE>
     * @param configuration parameters
     * @throws StartupFailedException if unable to start
     */
    public synchronized Map<Object, Object> start( GraphDatabaseService graphDb,
            KernelExtensionLoader kernelExtensionLoader )
    {
        if ( started )
        {
            throw new IllegalStateException( "Neo4j instance already started" );
        }
        Map<Object, Object> params = config.getParams();
        boolean useMemoryMapped = Boolean.parseBoolean( (String) config.getInputParams().get(
                Config.USE_MEMORY_MAPPED_BUFFERS ) );
        boolean dumpToConsole = Boolean.parseBoolean( (String) config.getInputParams().get(
                Config.DUMP_CONFIGURATION ) );
        storeDir = FileUtils.fixSeparatorsInPath( storeDir );
        StringLogger logger = StringLogger.getLogger( storeDir + "/messages.log" );
        AutoConfigurator autoConfigurator = new AutoConfigurator( storeDir, useMemoryMapped, dumpToConsole );
        autoConfigurator.configure( subset( config.getInputParams(), Config.USE_MEMORY_MAPPED_BUFFERS ) );
        params.putAll( config.getInputParams() );

        String separator = System.getProperty( "file.separator" );
        String store = storeDir + separator + "neostore";
        params.put( "store_dir", storeDir );
        params.put( "neo_store", store );
        params.put( "create", String.valueOf( create ) );
        String logicalLog = storeDir + separator + "nioneo_logical.log";
        params.put( "logical_log", logicalLog );
        byte resourceId[] = "414141".getBytes();
        params.put( LockManager.class, config.getLockManager() );
        params.put( LockReleaser.class, config.getLockReleaser() );
        config.getTxModule().registerDataSource( Config.DEFAULT_DATA_SOURCE_NAME,
                Config.NIO_NEO_DB_CLASS, resourceId, params );
        // hack for lucene index recovery if in path
        if ( !config.isReadOnly() || config.isBackupSlave() )
        {
            try
            {
                Class clazz = Class.forName( Config.LUCENE_DS_CLASS );
                cleanWriteLocksInLuceneDirectory( storeDir + "/lucene" );
                byte luceneId[] = "162373".getBytes();
                registerLuceneDataSource( "lucene", clazz.getName(),
                        config.getTxModule(), storeDir + "/lucene",
                        config.getLockManager(), luceneId, params );
            }
            catch ( ClassNotFoundException e )
            { // ok index util not on class path
            }
            catch ( NoClassDefFoundError err )
            { // ok index util not on class path
            }

            try
            {
                Class clazz = Class.forName( Config.LUCENE_FULLTEXT_DS_CLASS );
                cleanWriteLocksInLuceneDirectory( storeDir + "/lucene-fulltext" );
                byte[] luceneId = "262374".getBytes();
                registerLuceneDataSource( "lucene-fulltext",
                        clazz.getName(), config.getTxModule(),
                        storeDir + "/lucene-fulltext", config.getLockManager(),
                        luceneId, params );
            }
            catch ( ClassNotFoundException e )
            { // ok index util not on class path
            }
            catch ( NoClassDefFoundError err )
            { // ok index util not on class path
            }
        }
        persistenceSource = new NioNeoDbPersistenceSource();
        config.setPersistenceSource( Config.DEFAULT_DATA_SOURCE_NAME, create );
        config.getIdGeneratorModule().setPersistenceSourceInstance(
                persistenceSource );
        config.getTxModule().init();
        config.getPersistenceModule().init();
        persistenceSource.init();
        config.getIdGeneratorModule().init();
        config.getGraphDbModule().init();

        kernelExtensionLoader.init();

        config.getTxModule().start();
        config.getPersistenceModule().start( config.getTxModule().getTxManager(),
                persistenceSource, config.getSyncHookFactory() );
        persistenceSource.start( config.getTxModule().getXaDataSourceManager() );
        config.getIdGeneratorModule().start();
        config.getGraphDbModule().start( config.getLockReleaser(),
                config.getPersistenceModule().getPersistenceManager(),
                config.getRelationshipTypeCreator(), params );

        logger.logMessage( "--- CONFIGURATION START ---" );
        logger.logMessage( autoConfigurator.getNiceMemoryInformation() );
        logger.logMessage( "Kernel version: " + Version.get() );
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        logger.logMessage( String.format( "Operating System: %s; version: %s; arch: %s; cpus: %s",
                os.getName(), os.getVersion(), os.getArch(), os.getAvailableProcessors() ) );
        logger.logMessage( "VM Name: " + runtime.getVmName() );
        logger.logMessage( "VM Vendor: " + runtime.getVmVendor() );
        logger.logMessage( "VM Version: " + runtime.getVmVersion() );
        if ( runtime.isBootClassPathSupported() )
        {
            logger.logMessage( "Boot Class Path: " + runtime.getBootClassPath() );
        }
        logger.logMessage( "Class Path: " + runtime.getClassPath() );
        logger.logMessage( "Library Path: " + runtime.getLibraryPath() );
        for ( GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans() )
        {
            logger.logMessage( "Garbage Collector: " + gcBean.getName() + ": "
                               + Arrays.toString( gcBean.getMemoryPoolNames() ) );
        }
        logger.logMessage( "VM Arguments: " + runtime.getInputArguments() );
        logger.logMessage( "" );
        logConfiguration( params, logger, dumpToConsole );
        logger.logMessage( "--- CONFIGURATION END ---" );
        logger.flush();
        started = true;
        return Collections.unmodifiableMap( params );
    }

    private static Map<Object, Object> subset( Map<Object, Object> source, String... keys )
    {
        Map<Object, Object> result = new HashMap<Object, Object>();
        for ( String key : keys )
        {
            if ( source.containsKey( key ) )
            {
                result.put( key, source.get( key ) );
            }
        }
        return result;
    }

    private void logConfiguration( Map<Object, Object> params, StringLogger logger, boolean dumpToConsole )
    {
        TreeSet<String> stringKeys = new TreeSet<String>();
        for( Object key : params.keySet())
        {
            if (key instanceof String)
            {
                stringKeys.add((String)key);
            }
        }

        for( String key : stringKeys )
        {
            Object value = params.get( key );
            String mess = key + "=" + value;
            if ( dumpToConsole )
            {
                System.out.println( mess );
            }

            logger.logMessage( mess );
        }
    }

    private void cleanWriteLocksInLuceneDirectory( String luceneDir )
    {
        File dir = new File( luceneDir );
        if ( !dir.isDirectory() )
        {
            return;
        }
        for ( File file : dir.listFiles() )
        {
            if ( file.isDirectory() )
            {
                cleanWriteLocksInLuceneDirectory( file.getAbsolutePath() );
            }
            else if ( file.getName().equals( "write.lock" ) )
            {
                boolean success = file.delete();
                assert success;
            }
        }
    }

    private XaDataSource registerLuceneDataSource( String name,
            String className, TxModule txModule, String luceneDirectory,
            LockManager lockManager, byte[] resourceId,
            Map<Object,Object> params )
    {
        params.put( "dir", luceneDirectory );
        params.put( LockManager.class, lockManager );
        return txModule.registerDataSource( name, className, resourceId,
                params, true );
    }

    /**
     * Returns true if Neo4j is started.
     *
     * @return True if Neo4j started
     */
    public boolean started()
    {
        return started;
    }

    /**
     * Shut down Neo4j.
     */
    public synchronized void shutdown()
    {
        if ( started )
        {
            config.getGraphDbModule().stop();
            config.getIdGeneratorModule().stop();
            persistenceSource.stop();
            config.getPersistenceModule().stop();
            config.getTxModule().stop();
            config.getGraphDbModule().destroy();
            config.getIdGeneratorModule().destroy();
            persistenceSource.destroy();
            config.getPersistenceModule().destroy();
            config.getTxModule().destroy();
        }
        started = false;
    }

    public Iterable<RelationshipType> getRelationshipTypes()
    {
        return config.getGraphDbModule().getRelationshipTypes();
    }

    public boolean transactionRunning()
    {
        try
        {
            return config.getTxModule().getTxManager().getTransaction() != null;
        }
        catch ( Exception e )
        {
            throw new TransactionFailureException(
                    "Unable to get transaction.", e );
        }
    }

    public TransactionManager getTransactionManager()
    {
        return config.getTxModule().getTxManager();
    }
}
