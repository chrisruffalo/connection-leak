package rh.testing.connectionleak;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Logger;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import javax.enterprise.context.ApplicationScoped;
import javax.sql.DataSource;

/**
 * Manages connections in a single place.
 *
 */
@ApplicationScoped
public class DataSourceManager {

    private static final int CONNECTION_REMOVE_THRESHOLD_SECONDS = 30; // 30 seconds, for testing
    
    @Resource(lookup="java:jboss/datasources/LeakTestDS")
    private DataSource source;
    
    private Logger logger;
    
    private Map<Connection, Date> connectionTracker;
        
    @PostConstruct
    public void init() {
        // create a new object that uses the OBJECT'S IDENTITY as the key.  this means that if two
        // objects are the same instance (same pointer) they will be considered the same by the map
        // but not if they are equal.
        this.connectionTracker = Collections.synchronizedMap(new IdentityHashMap<Connection, Date>(0));
        
        this.logger = Logger.getLogger("datasource-manager");
        if(this.source == null) {
            this.logger.warning("Null data manager!");
        } else {
            this.logger.info("Started manager with connection source: " + this.source.getClass().getName());
        }
        
        
    }
    
    /**
     * Allows a connection to be created, managed or unmanaged, for the
     * datasource to be contacted.  Managed connections are later subject
     * to automatic pruning in the event of a leak.
     * 
     * @param manage
     * @return
     */
    public Connection connect(boolean manage) {
        try {
            // try and get a connection
            Connection connection = this.source.getConnection();
            
            // if the connection is null, prune and try again
            if(connection == null) {
               this.prune();
               connection = this.source.getConnection();
            }
            
            // manage the connection (?)
            if(manage) {
                // add/update new date, it most likely won't collide but if it does... it just "counts" as a reset
                this.connectionTracker.put(connection, new Date());
            }
        
            return connection;
        } catch (SQLException e) {
            return null;
        }
    }
    
    public void close(final Connection connection) {
        if(connection == null) {
            this.logger.info("Connection was null");
            return;
        }
        try {
            if(connection.isClosed()) {
                this.logger.info("Connection '" + connection.toString() + "' is already closed");
                return;
            }
        } catch (SQLException e) {
           this.logger.warning("Could not determine if connection was open: " + connection.toString() + " (" + e.getMessage() + ")");
        }
        try {
            connection.close();
        } catch (SQLException e) {
           this.logger.warning("Could not close connection: " + connection.toString() + " (" + e.getMessage() + ")");
        }        
    }
    
    public void close(final ResultSet resultSet) {
        // from red hat documentation: https://access.redhat.com/solutions/18573
        // (so we left the bad conventions alone.)
        Connection connection = null;
        Statement statement = null;
        if(resultSet != null) {
          try {
            statement = resultSet.getStatement();
          } catch(Exception e) { }
          try {
            if(statement != null)
              connection = statement.getConnection();
          } catch(Exception e) { }

          try { resultSet.close(); } catch(Exception e) { }
          try { if(statement != null) statement.close(); } catch(Exception e) { }
          try { if(connection != null) connection.close(); } catch(Exception e) { }
        }

    }
    
    public void prune() {
        // if this takes a minute it won't be "now" but
        // it will be close enough
        final Date nowDate = new Date();
        final Calendar threshold = Calendar.getInstance();
        
        // set time back by the threshold amount, if the connection's
        // time is older (before) this then it is over the time threshold
        // and needs manual pruning
        threshold.setTime(nowDate);
        threshold.add(Calendar.SECOND, (int)(-1*DataSourceManager.CONNECTION_REMOVE_THRESHOLD_SECONDS));
        
        // iterate, in a thread-safe way
        synchronized(this.connectionTracker) {
            final Iterator<Connection> iterator = this.connectionTracker.keySet().iterator();
            
            // check each one
            while(iterator.hasNext()) {
                Connection next = iterator.next();
                // if one is closed already, just remove it
                try {
                    if(next.isClosed()) {
                        iterator.remove();
                        //this.logger.info("pruned closed connection"); // log off for performance
                        continue;
                    }
                } catch (SQLException e) {
                    this.logger.warning("error checking closed status, skipping");
                    continue;
                }
                
                // the date it was put in
                final Date conDate = this.connectionTracker.get(next);
                final Calendar since = Calendar.getInstance();
                since.setTime(conDate == null ? new Date(0) : conDate); // paranoid null protection
                
                // if there is a connection date then compare it
                // to the threshold time.  if the time of the
                // connection is before the threshold then
                // it is time to manually prune it
                if(since.before(threshold)) {
                    try {
                        next.close();
                        if(conDate != null) {
                            this.logger.info("pruned stale connection that was created at: " + conDate.toString());
                        }
                    } catch (SQLException e) {
                        if(conDate != null) {
                            this.logger.warning("could not close stale connection from: " + conDate.toString());
                        }
                    }
                    iterator.remove();
                }
            }
        }
    }
    
    @PreDestroy
    public void destroy() {
        this.logger.info("shutting down");
        
        // first prune
        this.prune();
        
        // then force everything else to close
        synchronized(this.connectionTracker) {
            for(Connection con : this.connectionTracker.keySet()) {
                try {
                    con.close();
                    this.logger.info("closed hung/stale connection");
                } catch (SQLException e) {
                    this.logger.warning("could not close hung/stale connection");
                }
            }
        }
    }
    
}
