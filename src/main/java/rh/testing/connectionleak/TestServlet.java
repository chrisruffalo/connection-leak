package rh.testing.connectionleak;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
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

    @Inject
    private DataSourceManager manager;
    
    private Logger logger;
    
    @PostConstruct
    public void init() {
        this.logger = Logger.getLogger("servlet");
    }
    
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

        final String leakString = req.getParameter("leak");
        final boolean leak = leakString != null && "true".equalsIgnoreCase(leakString);
        
        // create tracking id
        final String id = UUID.randomUUID().toString();
        
        // log
        this.logger.fine("Started request: " + id + " (leak=" + leak + ")");
        
        // create connection
        final Connection connection = this.manager.connect();
        if(connection == null) {
            this.logger.warning("Requested a connection from the pool but it was null");
            // return 500
            resp.setStatus(500);
            return;
        }
        try {
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            this.logger.warning("Could not set auto commit on connection: " + e.getMessage());
        } catch (NullPointerException npe) {
            this.logger.warning("Connection object throws an NPE but is not null, symptom of leaked connection: " + npe.getMessage());
            resp.setStatus(500);
            return;
        }

        // start a transaction
        Savepoint point = null;
        try {
            point = connection.setSavepoint();
        } catch (SQLException e) {
            this.logger.info("Could not start transaction, aborting: " + e.getMessage());
            this.manager.close(connection);
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
           try {
               connection.rollback(point);
           } catch (SQLException rollbackException) {
               this.logger.warning("Could not rollback to savepoint: " + rollbackException.getMessage());
           }
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
        
        // finish the transaction
        if(point != null) {
            // stop using the savepoint
            try {
                connection.releaseSavepoint(point);
            } catch (SQLException e) {
                this.logger.warning("Could not commit release savepoint: " + e.getMessage());
            }

            try {
                connection.commit();
            } catch (SQLException e) {
                this.logger.warning("Could not commit transaction: " + e.getMessage());
            }            
        }
        
        // close result set if we aren't manually triggering a leak
        if(!leak && results != null) {
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
}
