package rh.testing.connectionleak;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.logging.Logger;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.UserTransaction;

/**
 * Simple servlet that allows the tester/user to decide if they want to
 * leak a connection.  Used for testing how the server deals with leaks
 * when given different connections.
 *
 */
@WebServlet(urlPatterns="/*")
public class TestServlet extends HttpServlet {

    /**
     * 
     */
    private static final long serialVersionUID = 1L;
    
    /**
     * Length of time before a transaction is canceled for taking too long
     * 
     */
    private static final int TX_TIMEOUT = 15;

    @Inject
    private DataSourceManager manager;
    
    @Inject
    private UserTransaction txUser;
    
    private Logger logger;
    
    @PostConstruct
    public void init() {
        this.logger = Logger.getLogger("servlet");
    }
    
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

        final String manageString = req.getParameter("manage");
        final boolean manage = manageString == null || "true".equalsIgnoreCase(manageString);
        
        final String leakString = req.getParameter("leak");
        final boolean leak = leakString != null && "true".equalsIgnoreCase(leakString);
        
        final String transactionString = req.getParameter("transaction");
        final boolean transaction = transactionString != null && "true".equalsIgnoreCase(transactionString);
        
        final String commitString = req.getParameter("commit");
        final boolean commit = commitString == null || "true".equalsIgnoreCase(commitString);
        
        final String txTimeoutString = req.getParameter("txout");
        final boolean txTimeout = txTimeoutString != null && "true".equalsIgnoreCase(txTimeoutString);
        
        final String forceRollbackString = req.getParameter("rollback");
        final boolean rollback = forceRollbackString != null && "true".equalsIgnoreCase(forceRollbackString);
        
        // create tracking id
        final String id = UUID.randomUUID().toString();
        
        // log
        final String logRequest = String.format("Started request: %s ('manage'=%b, 'leak'=%b, 'transaction'=%b, 'commit tx'=%b, 'tx timeout'=%b,'force rollback'=rollback)", id, manage, leak, transaction, commit, txTimeout);
        this.logger.info(logRequest);
        
        // a transaction was provided
        boolean activatedTransaction = false;
        if(transaction) {
            if(this.txUser != null) {
                try {
                    int txStatus = this.txUser.getStatus();
                    this.logger.fine("Transaction with status: " + txStatus);
                    if(Status.STATUS_NO_TRANSACTION == txStatus) {
                        try {
                            this.txUser.begin();
                            activatedTransaction = true;
                            this.logger.fine("Started transaction");
                        } catch (NotSupportedException e) {
                            this.logger.warning("Could not begin transaction: " + e.getMessage());
                        }                    
                    } else {
                        this.logger.info("Transaction is container managed or started outside of this code block.");
                    }
                } catch (SystemException e) {
                    this.logger.warning("Could not get transaction status: " + e.getMessage());
                }
                
                // set tx timeout
                if(txTimeout) {
                    try {
                        this.txUser.setTransactionTimeout(TestServlet.TX_TIMEOUT);
                    } catch (SystemException e) {
                        this.logger.warning("Could not set transaction timeout");
                    }
                }
            }
        }
        
        // create connection
        final Connection connection = this.manager.connect(manage);
        if(connection == null) {
            this.logger.warning("Requested a connection from the pool but it was null");
            // return 500
            resp.setStatus(500);
            return;
        }
        
        // insert into log table
        final String insertTemplate = "INSERT INTO lines VALUES (?,?)";        
        try {
           final PreparedStatement insert = connection.prepareStatement(insertTemplate);
           
           // set values
           insert.setString(1, id);
           insert.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
           
           // execute
           insert.execute();
           
           // just close insert statement, we only want to specifically leak and
           // close this one in either case
           insert.close();
        } catch (SQLException e) {
           this.logger.warning("Could not insert new line: " + e.getMessage());
           this.rollbackTransaction(transaction, activatedTransaction, this.txUser);
           this.manager.close(connection);
           resp.setStatus(500);
           return;
        }        
        
        // query and use result set
        ResultSet results = null;
        
        // create prepared statement
        PreparedStatement statement = null;
        try {
             statement = connection.prepareStatement("select * from lines order by timestamp desc limit 10");
        } catch (SQLException e) {
            this.logger.info("Could not create prepared statement, aborting: " + e.getMessage());
            this.rollbackTransaction(transaction, activatedTransaction, this.txUser);
            this.manager.close(connection);
            resp.setStatus(500);
            return;
        }
        
        // get results
        try {
            results = statement.executeQuery();
            if(results == null) {
                this.logger.info("Null results set");
            }

            StringBuilder builder = new StringBuilder("{\n");
            
            // was there a leak this time?
            builder.append("\tleak: ");
            builder.append(leak);
            builder.append(",\n");
            
            boolean first = true;
            
            // if there are results put them in string
            while(results != null && results.next()) {
                String lineId = results.getString("id");
                Timestamp timestamp = results.getTimestamp("timestamp");
                
                if(first) {
                    builder.append("\tids: [\n");
                    first = false;
                } else {
                    // start next line
                    builder.append(",\n");
                }
                
                // create json line
                builder.append("\t\t{ id: ");
                builder.append(lineId);
                builder.append(", ");
                builder.append("timestamp: '");
                builder.append(timestamp.toString());
                builder.append("' }");
            }
            if(!first) {
                builder.append("\n\t]");
            }
            
            builder.append("\n}");

            // write output
            try (OutputStream stream = resp.getOutputStream()) {
                stream.write(builder.toString().getBytes());
                stream.flush();
            }            
        } catch (SQLException e) {
            this.logger.warning("Could not get results: " + e.getMessage());
        }
        
        // commit transaction
        if(transaction && activatedTransaction) {
            if(rollback) {
                this.rollbackTransaction(transaction, activatedTransaction, this.txUser);
            } else if(commit) {
                try {
                    this.txUser.commit();
                    this.logger.fine("Transaction complete");
                } catch (SecurityException | IllegalStateException
                        | RollbackException | HeuristicMixedException
                        | HeuristicRollbackException | SystemException e) {
                    this.logger.warning("Error while commiting transaction: " + e.getMessage());
                }
            }
        } else {
            this.logger.info("Leaking transaction (ending request before commiting or rolling back)");
        }
        
        // close result set if we aren't manually triggering a leak
        if(!leak && results != null) {
            // close
            this.manager.close(results);
        } else if(leak && results == null) {
            if(statement != null) {
                try {
                    statement.close();
                } catch (SQLException e) {
                    this.logger.warning("ResultSet was null but could not close statement: " + e.getMessage());
                }
            }
            this.manager.close(connection);
        } else if(leak) {
            this.logger.fine("Deliberate leak of connection resource with id: " + id);
            
        }

    }   
    
    private void rollbackTransaction(boolean useTransaction, boolean transactionIsActive, UserTransaction transaction) {
        // do nothing with transaction that isn't in use
        if(!useTransaction || !transactionIsActive || transaction == null) {
            return;
        }
        
        try {
            transaction.rollback();
        } catch (IllegalStateException | SecurityException | SystemException e) {
            this.logger.warning("Could not rollback transaction: " + e.getMessage());
        }
    }
}
