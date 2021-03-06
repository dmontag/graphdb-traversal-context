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
import java.io.IOException;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.channels.ReadableByteChannel;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.event.KernelEventHandler;
import org.neo4j.graphdb.event.TransactionEventHandler;
import org.neo4j.graphdb.index.IndexManager;
import org.neo4j.helpers.Pair;
import org.neo4j.helpers.Triplet;
import org.neo4j.helpers.collection.IteratorUtil;
import org.neo4j.kernel.ha.BranchedDataException;
import org.neo4j.kernel.ha.Broker;
import org.neo4j.kernel.ha.BrokerFactory;
import org.neo4j.kernel.ha.HaCommunicationException;
import org.neo4j.kernel.ha.Master;
import org.neo4j.kernel.ha.MasterIdGeneratorFactory;
import org.neo4j.kernel.ha.MasterServer;
import org.neo4j.kernel.ha.MasterTxIdGenerator.MasterTxIdGeneratorFactory;
import org.neo4j.kernel.ha.Response;
import org.neo4j.kernel.ha.ResponseReceiver;
import org.neo4j.kernel.ha.SlaveContext;
import org.neo4j.kernel.ha.SlaveIdGenerator.SlaveIdGeneratorFactory;
import org.neo4j.kernel.ha.SlaveLockManager.SlaveLockManagerFactory;
import org.neo4j.kernel.ha.SlaveRelationshipTypeCreator;
import org.neo4j.kernel.ha.SlaveTxIdGenerator.SlaveTxIdGeneratorFactory;
import org.neo4j.kernel.ha.SlaveTxRollbackHook;
import org.neo4j.kernel.ha.TimeUtil;
import org.neo4j.kernel.ha.ToFileStoreWriter;
import org.neo4j.kernel.ha.TxExtractor;
import org.neo4j.kernel.ha.ZooKeeperLastCommittedTxIdSetter;
import org.neo4j.kernel.ha.zookeeper.Machine;
import org.neo4j.kernel.ha.zookeeper.ZooKeeperBroker;
import org.neo4j.kernel.ha.zookeeper.ZooKeeperException;
import org.neo4j.kernel.impl.nioneo.store.StoreId;
import org.neo4j.kernel.impl.transaction.XaDataSourceManager;
import org.neo4j.kernel.impl.transaction.xaframework.XaDataSource;
import org.neo4j.kernel.impl.util.StringLogger;

public class HighlyAvailableGraphDatabase extends AbstractGraphDatabase
        implements GraphDatabaseService, ResponseReceiver
{
    public static final String CONFIG_KEY_HA_MACHINE_ID = "ha.machine_id";
    public static final String CONFIG_KEY_HA_ZOO_KEEPER_SERVERS = "ha.zoo_keeper_servers";
    public static final String CONFIG_KEY_HA_SERVER = "ha.server";
    public static final String CONFIG_KEY_HA_CLUSTER_NAME = "ha.cluster_name";
    private static final String CONFIG_DEFAULT_HA_CLUSTER_NAME = "neo4j.ha";
    private static final int CONFIG_DEFAULT_PORT = 6361;
    public static final String CONFIG_KEY_HA_PULL_INTERVAL = "ha.pull_interval";
    public static final String CONFIG_KEY_ALLOW_INIT_CLUSTER = "ha.allow_init_cluster";

    private final String storeDir;
    private final Map<String, String> config;
    private final BrokerFactory brokerFactory;
    private final Broker broker;
    private volatile EmbeddedGraphDbImpl localGraph;
    private final int machineId;
    private volatile MasterServer masterServer;
    private ScheduledExecutorService updatePuller;
    private volatile long updateTime = 0;
    private volatile RuntimeException causeOfShutdown;

    private final List<KernelEventHandler> kernelEventHandlers =
            new CopyOnWriteArrayList<KernelEventHandler>();
    private final Collection<TransactionEventHandler<?>> transactionEventHandlers =
            new CopyOnWriteArraySet<TransactionEventHandler<?>>();

    private final StringLogger msgLog;

    /**
     * Will instantiate its own ZooKeeper broker
     */
    public HighlyAvailableGraphDatabase( String storeDir, Map<String, String> config )
    {
        this( storeDir, config, null );
    }

    /**
     * Only for testing (and {@link org.neo4j.kernel.ha.BackupFromHaCluster})
     */
    public HighlyAvailableGraphDatabase( String storeDir, Map<String, String> config,
            BrokerFactory brokerFactory )
    {
        if ( config == null )
        {
            throw new IllegalArgumentException( "null config, proper configuration required" );
        }
        this.storeDir = storeDir;
        this.config = config;
        config.put( Config.KEEP_LOGICAL_LOGS, "true" );
        this.brokerFactory = brokerFactory != null ? brokerFactory : defaultBrokerFactory(
                storeDir, config );
        this.machineId = getMachineIdFromConfig( config );
        this.broker = this.brokerFactory.create( storeDir, config );
        this.msgLog = StringLogger.getLogger( storeDir + "/messages.log" );
        
        boolean allowInitFromConfig = getAllowInitFromConfig( config );
        startUp( allowInitFromConfig );
    }

    private void getFreshDatabaseFromMaster( Pair<Master, Machine> master )
    {
        master = master != null ? master : broker.getMasterReally();
        // Assume it's shut down at this point
        
        moveAwayCurrentDatabase();

        Exception exception = null;
        for ( int i = 0; i < 10; i++ )
        {
            try
            {
                copyStoreFromMaster( master );
                return;
            }
            catch ( Exception e )
            {
                msgLog.logMessage( "Problems copying store from master", e );
                sleepWithoutInterruption( 1000, "" );
                exception = e;
            }
        }
        throw new RuntimeException( "Gave up trying to copy store from master", exception );
    }

    private void moveAwayCurrentDatabase()
    {
        // TODO This could be solved by moving the db directory to <dbPath>-broken-<date>
        // maybe <dbPath>/broken-<date> since we it may be the case that the current user
        // only has got permissions on the dbPath, not the parent.
        this.msgLog.logMessage( "Cleaning database " + storeDir + " to make way for new db from master" );
        
        File oldDir = new File( storeDir, "broken-" + System.currentTimeMillis() );
        oldDir.mkdirs();
        for ( File file : new File( storeDir ).listFiles() )
        {
            if ( !file.equals( oldDir ) && !file.getName().equals( "messages.log" ) )
            {
                File dest = new File( oldDir, file.getName() );
                if ( !file.renameTo( dest ) )
                {
                    System.out.println( "Couldn't move " + file.getPath() );
                }
            }
        }
    }
    
    public static Map<String,String> loadConfigurations( String file )
    {
        return EmbeddedGraphDatabase.loadConfigurations( file );
    }

    private synchronized void startUp( boolean allowInit )
    {
        StoreId storeId = null;
        if ( !new File( storeDir, "neostore" ).exists() )
        {
            long endTime = System.currentTimeMillis()+10000;
            Exception exception = null;
            while ( System.currentTimeMillis() < endTime )
            {                
                // Check if the cluster is up
                Pair<Master, Machine> master = broker.getMaster();
                master = master.first() != null ? master : broker.getMasterReally();
                if ( master != null && master.first() != null )
                { // Join the existing cluster
                    try
                    {
                        copyStoreFromMaster( master );
                        exception = null;
                        break;
                    }
                    catch ( Exception e )
                    {
                        exception = e;
                        broker.getMasterReally();
                        msgLog.logMessage( "Problems copying store from master", e );
                    }
                }
                else if ( allowInit )
                { // Try to initialize the cluster and become master
                    exception = null;
                    StoreId myStoreId = new StoreId();
                    storeId = broker.createCluster( myStoreId );
                    if ( storeId.equals( myStoreId ) )
                    { // I am master
                        break;
                    }
                }
                // I am not master, and could not connect to the master:
                // wait for other machine(s) to join.
                sleepWithoutInterruption( 300, "Startup interrupted" );
            }
            
            if ( exception != null )
            {
                throw new RuntimeException( "Tried to join the cluster, but was unable to", exception );
            }
        }
        newMaster( null, storeId, new Exception() );
        localGraph();
    }

    private void sleepWithoutInterruption( long time, String errorMessage )
    {
        try
        {
            Thread.sleep( time );
        }
        catch ( InterruptedException e )
        {
            throw new RuntimeException( errorMessage, e );
        }
    }

    private void copyStoreFromMaster( Pair<Master, Machine> master ) throws Exception
    {
        msgLog.logMessage( "Copying store from master" );
        Response<Void> response = master.first().copyStore( new SlaveContext( machineId, 0, new Pair[0] ),
                new ToFileStoreWriter( storeDir ) );
        EmbeddedGraphDatabase tempDb = new EmbeddedGraphDatabase( storeDir );
        try
        {
            applyReceivedTransactions( response, tempDb.getConfig().getTxModule().getXaDataSourceManager(), new TxHandler()
            {
                private final Set<String> visitedDataSources = new HashSet<String>();
                
                @Override
                public void accept( Triplet<String, Long, TxExtractor> tx, XaDataSource dataSource )
                {
                    if ( visitedDataSources.add( tx.first() ) )
                    {
                        dataSource.setLastCommittedTxId( tx.second()-1 );
                    }
                }
            });
        }
        finally
        {
            tempDb.shutdown();
        }
        msgLog.logMessage( "Done copying store from master" );
    }

    private EmbeddedGraphDbImpl localGraph()
    {
        if ( localGraph == null )
        {
            if ( causeOfShutdown != null )
            {
                throw causeOfShutdown;
            }
            else
            {
                throw new RuntimeException( "Graph database not assigned and no cause of shutdown, " +
                		"maybe not started yet or in the middle of master/slave swap?" );
            }
        }
        return localGraph;
    }

    private BrokerFactory defaultBrokerFactory( final String storeDir,
            final Map<String, String> config )
    {
        return new BrokerFactory()
        {
            public Broker create( String storeDir, Map<String, String> config )
            {
                return new ZooKeeperBroker( storeDir,
                        getClusterNameFromConfig( config ),
                        getMachineIdFromConfig( config ),
                        getZooKeeperServersFromConfig( config ),
                        getHaServerFromConfig( config ), HighlyAvailableGraphDatabase.this );
            }
        };
    }

    private static String getClusterNameFromConfig( Map<?, ?> config )
    {
        String clusterName = (String) config.get( CONFIG_KEY_HA_CLUSTER_NAME );
        if ( clusterName == null ) clusterName = CONFIG_DEFAULT_HA_CLUSTER_NAME;
        return clusterName;
    }

    private static String getHaServerFromConfig( Map<?, ?> config )
    {
        String haServer = (String) config.get( CONFIG_KEY_HA_SERVER );
        if ( haServer == null )
        {
            InetAddress host = null;
            try
            {
                host = InetAddress.getLocalHost();
            }
            catch ( UnknownHostException hostBecomesNull )
            {
            }
            if ( host == null )
            {
                throw new IllegalStateException(
                        "Could not auto configure host name, please supply " + CONFIG_KEY_HA_SERVER );
            }
            haServer = host + ":" + CONFIG_DEFAULT_PORT;
        }
        return haServer;
    }

    private static boolean getAllowInitFromConfig( Map<?, ?> config )
    {
        String allowInit = (String) config.get( CONFIG_KEY_ALLOW_INIT_CLUSTER );
        if ( allowInit == null ) return true;
        return Boolean.parseBoolean( allowInit );
    }

    private static String getZooKeeperServersFromConfig( Map<String, String> config )
    {
        return config.get( CONFIG_KEY_HA_ZOO_KEEPER_SERVERS );
    }

    private static int getMachineIdFromConfig( Map<String, String> config )
    {
        // Fail fast if null
        return Integer.parseInt( config.get( CONFIG_KEY_HA_MACHINE_ID ) );
    }

    public Broker getBroker()
    {
        return this.broker;
    }

    public void pullUpdates()
    {
        try
        {
            if ( masterServer == null )
            {
                receive( broker.getMaster().first().pullUpdates( getSlaveContext( -1 ) ) );
            }
        }
        catch ( ZooKeeperException e )
        {
            newMaster( null, e );
            throw e;
        }
        catch ( HaCommunicationException e )
        {
            newMaster( null, e );
            throw e;
        }
    }

    private void updateTime()
    {
        this.updateTime = System.currentTimeMillis();
    }

    long lastUpdateTime()
    {
        return this.updateTime;
    }

    @Override
    public Config getConfig()
    {
        return localGraph().getConfig();
    }

    @Override
    public String getStoreDir()
    {
        return this.storeDir;
    }

    @Override
    public <T> T getManagementBean( Class<T> type )
    {
        return localGraph().getManagementBean( type );
    }

    protected synchronized void reevaluateMyself( Pair<Master, Machine> master, StoreId storeId )
    {
        if ( master == null )
        {
            master = broker.getMasterReally();
        }
        boolean restarted = false;
        boolean iAmCurrentlyMaster = masterServer != null;
        msgLog.logMessage( "ReevaluateMyself: machineId=" + machineId + " with master[" + master +
                "] (I am master=" + iAmCurrentlyMaster + ")" );
        if ( master.other().getMachineId() == machineId )
        {
            // I am not currently master
            if ( this.localGraph == null || !iAmCurrentlyMaster )
            {
                internalShutdown();
                startAsMaster( storeId );
                restarted = true;
            }
            // fire rebound event
            broker.rebindMaster();
        }
        else
        {
            if ( this.localGraph == null || iAmCurrentlyMaster )
            {
                internalShutdown();
                startAsSlave( storeId );
                restarted = true;
            }
            else
            {
                ((SlaveIdGeneratorFactory) getConfig().getIdGeneratorFactory()).forgetIdAllocationsFromMaster();
            }

            tryToEnsureIAmNotABrokenMachine( broker.getMaster() );
        }
        if ( restarted )
        {
            doAfterLocalGraphStarted();
        }
    }

    private void doAfterLocalGraphStarted()
    {
        broker.setConnectionInformation( this.localGraph.getKernelData() );
        for ( TransactionEventHandler<?> handler : transactionEventHandlers )
        {
            localGraph().registerTransactionEventHandler( handler );
        }
        for ( KernelEventHandler handler : kernelEventHandlers )
        {
            localGraph().registerKernelEventHandler( handler );
        }
    }

    private void startAsSlave( StoreId storeId )
    {
        msgLog.logMessage( "Starting[" + machineId + "] as slave", true );
        this.localGraph = new EmbeddedGraphDbImpl( storeDir, storeId, config, this,
                new SlaveLockManagerFactory( broker, this ),
                new SlaveIdGeneratorFactory( broker, this ),
                new SlaveRelationshipTypeCreator( broker, this ),
                new SlaveTxIdGeneratorFactory( broker, this ),
                new SlaveTxRollbackHook( broker, this ),
                new ZooKeeperLastCommittedTxIdSetter( broker ) );
        instantiateAutoUpdatePullerIfConfigSaysSo();
        msgLog.logMessage( "Started as slave", true );
    }

    private void startAsMaster( StoreId storeId )
    {
        msgLog.logMessage( "Starting[" + machineId + "] as master", true );
        this.localGraph = new EmbeddedGraphDbImpl( storeDir, storeId, config, this,
                CommonFactories.defaultLockManagerFactory(),
                new MasterIdGeneratorFactory(),
                CommonFactories.defaultRelationshipTypeCreator(),
                new MasterTxIdGeneratorFactory( broker ),
                CommonFactories.defaultTxFinishHook(),
                new ZooKeeperLastCommittedTxIdSetter( broker ) );
        this.masterServer = (MasterServer) broker.instantiateMasterServer( this );
        msgLog.logMessage( "Started as master", true );
    }

    private void tryToEnsureIAmNotABrokenMachine( Pair<Master, Machine> master )
    {
        if ( master.other().getMachineId() == machineId )
        {
            return;
        }
        else if ( master.first() == null )
        {
            // Temporarily disconnected from ZK
            RuntimeException cause = new RuntimeException( "Unable to get master from ZK" );
            shutdown( cause, false );
            throw cause;
        }

        XaDataSource nioneoDataSource = getConfig().getTxModule()
                .getXaDataSourceManager().getXaDataSource( Config.DEFAULT_DATA_SOURCE_NAME );
        long myLastCommittedTx = nioneoDataSource.getLastCommittedTxId();
        long highestCommonTxId = Math.min( myLastCommittedTx, master.other().getLastCommittedTxId() );
        int masterForMyHighestCommonTxId = -1;
        try
        {
            masterForMyHighestCommonTxId = nioneoDataSource.getMasterForCommittedTx( highestCommonTxId );;
        }
        catch ( IOException e )
        {
            // This is quite dangerous to just catch here... but the special case is
            // where this db was just now copied from the master where there's data,
            // but no logical logs were transfered and hence no masterId info is here
            msgLog.logMessage( "Couldn't get master ID for txId " + highestCommonTxId +
                    ". It may be that a log file is missing due to the db being copied from master?", e );
            return;
        }

        int masterForMastersHighestCommonTxId = master.first().getMasterIdForCommittedTx( highestCommonTxId );

        // Compare those two, if equal -> good
        if ( masterForMyHighestCommonTxId == masterForMastersHighestCommonTxId )
        {
            msgLog.logMessage( "Master id for last committed tx ok with highestCommonTxId=" +
                    highestCommonTxId + " with masterId=" + masterForMyHighestCommonTxId, true );
            return;
        }
        else
        {
            String msg = "Broken store, my last committed tx,machineId[" +
                myLastCommittedTx + "," + masterForMyHighestCommonTxId +
                "] but master says machine id for that txId is " + masterForMastersHighestCommonTxId;
            msgLog.logMessage( msg, true );
            RuntimeException exception = new BranchedDataException( msg );
            shutdown( exception, false );
            throw exception;
        }
    }

    private void instantiateAutoUpdatePullerIfConfigSaysSo()
    {
        String pullInterval = this.config.get( CONFIG_KEY_HA_PULL_INTERVAL );
        if ( pullInterval != null )
        {
            long timeMillis = TimeUtil.parseTimeMillis( pullInterval );
            updatePuller = new ScheduledThreadPoolExecutor( 1 );
            updatePuller.scheduleWithFixedDelay( new Runnable()
            {
                public void run()
                {
                    try
                    {
                        pullUpdates();
                    }
                    catch ( Exception e )
                    {
                        msgLog.logMessage( "Pull updates failed", e  );
                    }
                }
            }, timeMillis, timeMillis, TimeUnit.MILLISECONDS );
        }
    }

    public Transaction beginTx()
    {
        return localGraph().beginTx();
    }

    public Node createNode()
    {
        return localGraph().createNode();
    }

    /**
     * @deprecated See {@link GraphDatabaseService#enableRemoteShell()}
     */
    @Override
    public boolean enableRemoteShell()
    {
        return localGraph().enableRemoteShell();
    }

    /**
     * @deprecated See {@link GraphDatabaseService#enableRemoteShell(Map)}
     */
    @Override
    public boolean enableRemoteShell( Map<String, Serializable> initialProperties )
    {
        return localGraph().enableRemoteShell( initialProperties );
    }

    public Iterable<Node> getAllNodes()
    {
        return localGraph().getAllNodes();
    }

    public Node getNodeById( long id )
    {
        return localGraph().getNodeById( id );
    }

    public Node getReferenceNode()
    {
        return localGraph().getReferenceNode();
    }

    public Relationship getRelationshipById( long id )
    {
        return localGraph().getRelationshipById( id );
    }

    public Iterable<RelationshipType> getRelationshipTypes()
    {
        return localGraph().getRelationshipTypes();
    }

    public KernelEventHandler registerKernelEventHandler( KernelEventHandler handler )
    {
        this.kernelEventHandlers.add( handler );
        return localGraph().registerKernelEventHandler( handler );
    }

    public <T> TransactionEventHandler<T> registerTransactionEventHandler(
            TransactionEventHandler<T> handler )
    {
        this.transactionEventHandlers.add( handler );
        return localGraph().registerTransactionEventHandler( handler );
    }

    public synchronized void internalShutdown()
    {
        msgLog.logMessage( "Internal shutdown of HA db[" + machineId + "] reference=" + this + ", masterServer=" + masterServer, true );
        if ( this.updatePuller != null )
        {
            msgLog.logMessage( "Internal shutdown updatePuller", true );
            this.updatePuller.shutdown();
            msgLog.logMessage( "Internal shutdown updatePuller DONE", true );
            this.updatePuller = null;
        }
        if ( this.masterServer != null )
        {
            msgLog.logMessage( "Internal shutdown masterServer", true );
            this.masterServer.shutdown();
            msgLog.logMessage( "Internal shutdown masterServer DONE", true );
            this.masterServer = null;
        }
        if ( this.localGraph != null )
        {
            msgLog.logMessage( "Internal shutdown localGraph", true );
            this.localGraph.shutdown();
            msgLog.logMessage( "Internal shutdown localGraph DONE", true );
            this.localGraph = null;
        }
    }

    private synchronized void shutdown( RuntimeException cause, boolean shutdownBroker )
    {
//        if ( causeOfShutdown != null )
//        {
//            return;
//        }

        causeOfShutdown = cause;
        msgLog.logMessage( "Shutdown[" + machineId + "], " + this, true );
        if ( shutdownBroker && this.broker != null )
        {
            this.broker.shutdown();
        }
        internalShutdown();
    }

    public synchronized void shutdown()
    {
        shutdown( new IllegalStateException(), true );
    }

    public KernelEventHandler unregisterKernelEventHandler( KernelEventHandler handler )
    {
        return localGraph().unregisterKernelEventHandler( handler );
    }

    public <T> TransactionEventHandler<T> unregisterTransactionEventHandler(
            TransactionEventHandler<T> handler )
    {
        return localGraph().unregisterTransactionEventHandler( handler );
    }

    public SlaveContext getSlaveContext( int eventIdentifier )
    {
        XaDataSourceManager localDataSourceManager =
            getConfig().getTxModule().getXaDataSourceManager();
        Collection<XaDataSource> dataSources = localDataSourceManager.getAllRegisteredDataSources();
        @SuppressWarnings("unchecked")
        Pair<String, Long>[] txs = new Pair[dataSources.size()];
        int i = 0;
        for ( XaDataSource dataSource : dataSources )
        {
            txs[i++] = Pair.of( dataSource.getName(), dataSource.getLastCommittedTxId() );
        }
        return new SlaveContext( machineId, eventIdentifier, txs );
    }

    public <T> T receive( Response<T> response )
    {
        try
        {
            applyReceivedTransactions( response, getConfig().getTxModule().getXaDataSourceManager(), NO_ACTION );
            updateTime();
            return response.response();
        }
        catch ( IOException e )
        {
            newMaster( broker.getMaster(), e );
            throw new RuntimeException( e );
        }
    }

    private <T> void applyReceivedTransactions( Response<T> response, XaDataSourceManager dataSourceManager, TxHandler txHandler ) throws IOException
    {
        for ( Triplet<String, Long, TxExtractor> tx : IteratorUtil.asIterable( response.transactions() ) )
        {
            String resourceName = tx.first();
            XaDataSource dataSource = dataSourceManager.getXaDataSource( resourceName );
            txHandler.accept( tx, dataSource );
            ReadableByteChannel txStream = tx.third().extract();
            try
            {
                dataSource.applyCommittedTransaction( tx.second(), txStream );
            }
            finally
            {
                txStream.close();
            }
        }
    }

    public void newMaster( Pair<Master, Machine> master, Exception e )
    {
        newMaster( master, null, e );
    }

    private synchronized void newMaster( Pair<Master, Machine> master, StoreId storeId, Exception e )
    {
        try
        {
            doNewMaster( master, storeId, e );
        }
        catch ( BranchedDataException bde )
        {
            System.out.println( "Branched data occured, retrying" );
            getFreshDatabaseFromMaster( master );
            doNewMaster( master, storeId, bde );
        }
    }

    private void doNewMaster( Pair<Master, Machine> master, StoreId storeId, Exception e )
    {
        try
        {
            msgLog.logMessage( "newMaster(" + master + ") called", e, true );
            reevaluateMyself( master, storeId );
        }
        catch ( ZooKeeperException ee )
        {
            msgLog.logMessage( "ZooKeeper exception in newMaster", ee );
        }
        catch ( HaCommunicationException ee )
        {
            msgLog.logMessage( "HaComminucationException in newMaster", ee );
        }
        // BranchedDataException will escape from this method since the catch clause below
        // sees to that.
        catch ( Throwable t )
        {
            t.printStackTrace();
            msgLog.logMessage( "Reevaluation ended in unknown exception " + t
                    + " so shutting down", true );
            shutdown( t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException( t ), false );
            if ( t instanceof RuntimeException )
            {
                throw (RuntimeException) t;
            }
            throw new RuntimeException( t );
        }
    }

    public MasterServer getMasterServerIfMaster()
    {
        return masterServer;
    }

    int getMachineId()
    {
        return machineId;
    }

    public boolean isMaster()
    {
        return broker.iAmMaster();
    }

    @Override
    public boolean isReadOnly()
    {
        return false;
    }

    public IndexManager index()
    {
        return localGraph().index();
    }
    
    private interface TxHandler
    {
        void accept( Triplet<String, Long, TxExtractor> tx, XaDataSource dataSource );
    }
    
    private static final TxHandler NO_ACTION = new TxHandler()
    {
        @Override
        public void accept( Triplet<String, Long, TxExtractor> tx, XaDataSource dataSource )
        {
            // Do nothing
        }
    };
}
