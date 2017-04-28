package nsa.datawave.webservice.common.cache;

import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import nsa.datawave.webservice.common.connection.AccumuloConnectionFactory;
import nsa.datawave.webservice.common.connection.AccumuloConnectionFactory.Priority;
import nsa.datawave.webservice.common.connection.WrappedConnector;

import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.TableNotFoundException;
import nsa.datawave.accumulo.inmemory.InMemoryInstance;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.user.RegExFilter;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.log4j.Logger;

import com.google.common.collect.Lists;

public class BaseTableCache implements Serializable, TableCache {
    
    private static final long serialVersionUID = 1L;
    
    private final Logger log = Logger.getLogger(this.getClass());
    
    /** should be set by configuration **/
    private String tableName = null;
    private String connectionPoolName = null;
    private String auths = null;
    private long reloadInterval = 0;
    private long maxRows = Long.MAX_VALUE;
    
    /** set programatically **/
    private Date lastRefresh = new Date(0);
    private AccumuloConnectionFactory connectionFactory = null;
    private InMemoryInstance instance = null;
    private SharedCacheCoordinator watcher = null;
    private Future<Boolean> reference = null;
    
    private ReentrantLock lock = new ReentrantLock();
    
    @Override
    public String getTableName() {
        return tableName;
    }
    
    @Override
    public String getConnectionPoolName() {
        return connectionPoolName;
    }
    
    @Override
    public String getAuths() {
        return auths;
    }
    
    @Override
    public long getReloadInterval() {
        return reloadInterval;
    }
    
    @Override
    public Date getLastRefresh() {
        return lastRefresh;
    }
    
    @Override
    public AccumuloConnectionFactory getConnectionFactory() {
        return connectionFactory;
    }
    
    @Override
    public InMemoryInstance getInstance() {
        return instance;
    }
    
    @Override
    public SharedCacheCoordinator getWatcher() {
        return watcher;
    }
    
    @Override
    public Future<Boolean> getReference() {
        return reference;
    }
    
    @Override
    public void setTableName(String tableName) {
        this.tableName = tableName;
    }
    
    @Override
    public void setConnectionPoolName(String connectionPoolName) {
        this.connectionPoolName = connectionPoolName;
    }
    
    @Override
    public void setAuths(String auths) {
        this.auths = auths;
    }
    
    @Override
    public void setReloadInterval(long reloadInterval) {
        this.reloadInterval = reloadInterval;
    }
    
    @Override
    public void setLastRefresh(Date lastRefresh) {
        this.lastRefresh = lastRefresh;
    }
    
    @Override
    public void setConnectionFactory(AccumuloConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }
    
    @Override
    public void setInstance(InMemoryInstance instance) {
        this.instance = instance;
    }
    
    @Override
    public void setWatcher(SharedCacheCoordinator watcher) {
        this.watcher = watcher;
    }
    
    @Override
    public void setReference(Future<Boolean> reference) {
        this.reference = reference;
    }
    
    @Override
    public long getMaxRows() {
        return this.maxRows;
    }
    
    @Override
    public void setMaxRows(long maxRows) {
        this.maxRows = maxRows;
    }
    
    @Override
    public Boolean call() throws Exception {
        if (!lock.tryLock(0, TimeUnit.SECONDS))
            return false;
        // Read from the table in the real Accumulo
        BatchScanner scanner = null;
        BatchWriter writer = null;
        Connector accumuloConn = null;
        
        String tempTableName = tableName + "Temp";
        try {
            Map<String,String> map = connectionFactory.getTrackingMap(Thread.currentThread().getStackTrace());
            accumuloConn = connectionFactory.getConnection(connectionPoolName, Priority.ADMIN, map);
            if (accumuloConn instanceof WrappedConnector) {
                accumuloConn = ((WrappedConnector) accumuloConn).getReal();
            }
            Authorizations authorizations = null;
            if (null == auths) {
                authorizations = accumuloConn.securityOperations().getUserAuthorizations(accumuloConn.whoami());
            } else {
                authorizations = new Authorizations(auths);
            }
            scanner = accumuloConn.createBatchScanner(tableName, authorizations, 10);
            
            Connector instanceConnector = instance.getConnector(AccumuloTableCache.MOCK_USERNAME, AccumuloTableCache.MOCK_PASSWORD);
            instanceConnector.securityOperations().changeUserAuthorizations(AccumuloTableCache.MOCK_USERNAME, authorizations);
            
            if (instanceConnector.tableOperations().exists(tempTableName))
                instanceConnector.tableOperations().delete(tempTableName);
            
            instanceConnector.tableOperations().create(tempTableName);
            
            writer = instanceConnector.createBatchWriter(tempTableName, 10L * (1024L * 1024L), 100L, 1);
            
            setupScanner(scanner);
            
            Iterator<Entry<Key,Value>> iter = scanner.iterator();
            long count = 0;
            while (iter.hasNext()) {
                
                if (count > maxRows)
                    break;
                Entry<Key,Value> value = iter.next();
                
                Key valueKey = value.getKey();
                
                Mutation m = new Mutation(value.getKey().getRow());
                m.put(valueKey.getColumnFamily(), valueKey.getColumnQualifier(), new ColumnVisibility(valueKey.getColumnVisibility()), valueKey.getTimestamp(),
                                value.getValue());
                writer.addMutation(m);
                count++;
            }
            this.lastRefresh = new Date();
            try {
                instanceConnector.tableOperations().delete(tableName);
            } catch (TableNotFoundException e) {
                // the table will not exist the first time this is run
            }
            instanceConnector.tableOperations().rename(tempTableName, tableName);
            log.info("Cached " + count + " k,v for table: " + tableName);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            throw e;
        } finally {
            try {
                if (null != accumuloConn)
                    connectionFactory.returnConnection(accumuloConn);
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
            if (null != scanner)
                scanner.close();
            try {
                if (null != writer)
                    writer.close();
            } catch (Exception e) {
                log.warn("Error closing batch writer for table: " + tempTableName, e);
            }
            lock.unlock();
        }
        return true;
    }
    
    public void setupScanner(BatchScanner scanner) {
        scanner.setRanges(Lists.newArrayList(new Range()));
        Map<String,String> options = new HashMap<>();
        options.put(RegExFilter.COLF_REGEX, "^f$");
        options.put("negate", "true");
        IteratorSetting settings = new IteratorSetting(100, "skipFColumn", RegExFilter.class, options);
        scanner.addScanIterator(settings);
    }
    
    @Override
    public String toString() {
        return "tableName: " + getTableName() + ", connectionPoolName: " + getConnectionPoolName() + ", auths: " + getAuths();
    }
}
