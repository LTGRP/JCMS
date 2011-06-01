/*
   Copyright (C) 2002 MySQL AB
   
      This program is free software; you can redistribute it and/or modify
      it under the terms of the GNU General Public License as published by
      the Free Software Foundation; either version 2 of the License, or
      (at your option) any later version.
   
      This program is distributed in the hope that it will be useful,
      but WITHOUT ANY WARRANTY; without even the implied warranty of
      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
      GNU General Public License for more details.
   
      You should have received a copy of the GNU General Public License
      along with this program; if not, write to the Free Software
      Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
      
 */
package com.mysql.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UnsupportedEncodingException;

import java.math.BigDecimal;

import java.net.URL;

import java.sql.Clob;
import java.sql.Date;
import java.sql.ParameterMetaData;
import java.sql.Ref;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Time;
import java.sql.Timestamp;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.TimeZone;


/**
 * A Connection represents a session with a specific database.  Within the
 * context of a Connection, SQL statements are executed and results are
 * returned.
 *
 * <P>A Connection's database is able to provide information describing
 * its tables, its supported SQL grammar, its stored procedures, the
 * capabilities of this connection, etc.  This information is obtained
 * with the getMetaData method.
 *
 * @see java.sql.Connection
 * @author Mark Matthews
 * @version $Id: Connection.java,v 1.31.2.26 2003/05/22 18:48:03 mmatthew Exp $
 */
public class Connection implements java.sql.Connection {
    // The command used to "ping" the database.
    // Newer versions of MySQL server have a ping() command,
    // but this works for everything
    private static final String PING_COMMAND = "SELECT 1";

    /**
     * Map mysql transaction isolation level name to
     * java.sql.Connection.TRANSACTION_XXX
     */
    private static Map mapTransIsolationName2Value = null;

    /**
     * The mapping between MySQL charset names
     * and Java charset names.
     *
     * Initialized by loadCharacterSetMapping()
     */
    private static Map charsetMap;

    /**
     * Table of multi-byte charsets.
     *
     * Initialized by loadCharacterSetMapping()
     */
    private static Map multibyteCharsetsMap;

    /**
     * Default socket factory classname
     */
    private static final String DEFAULT_SOCKET_FACTORY = StandardSocketFactory.class
        .getName();

    static {
        loadCharacterSetMapping();
        mapTransIsolationName2Value = new HashMap(8);
        mapTransIsolationName2Value.put("READ-UNCOMMITED",
            new Integer(TRANSACTION_READ_UNCOMMITTED));
		mapTransIsolationName2Value.put("READ-UNCOMMITTED",
			new Integer(TRANSACTION_READ_UNCOMMITTED));
        mapTransIsolationName2Value.put("READ-COMMITTED",
            new Integer(TRANSACTION_READ_COMMITTED));
        mapTransIsolationName2Value.put("REPEATABLE-READ",
            new Integer(TRANSACTION_REPEATABLE_READ));
        mapTransIsolationName2Value.put("SERIALIZABLE",
            new Integer(TRANSACTION_SERIALIZABLE));
    }

    /**
     * Internal DBMD to use for various database-version
     * specific features
     */
    private DatabaseMetaData dbmd = null;

    /**
     * The list of host(s) to try and connect to
     */
    private List hostList = null;

	/**
	 * A map of statements that have had setMaxRows() called on them
	 */
	private Map statementsUsingMaxRows;
		
    /**
     * The I/O abstraction interface (network conn to
     * MySQL server
     */
    private MysqlIO io = null;

    /**
     * Mutex
     */
    private final Object mutex = new Object();

    /**
     * The driver instance that created us
     */
    private NonRegisteringDriver myDriver;

    /**
     * The map of server variables that we retrieve
     * at connection init.
     */
    private Map serverVariables = null;

    /**
     * Properties for this connection specified by user
     */
    private Properties props = null;

    /**
     * The database we're currently using
     * (called Catalog in JDBC terms).
     */
    private String database = null;

    /**
     * If we're doing unicode character conversions,
     * what encoding do we use?
     */
    private String encoding = null;

    /**
     * The hostname we're connected to
     */
    private String host = null;

    /**
     * The JDBC URL we're using
     */
    private String myURL = null;

    /**
     * The password we used
     */
    private String password = null;

    /**
     * Classname for socket factory
     */
    private String socketFactoryClassName = null;

    /**
     * The user we're connected as
     */
    private String user = null;

    /**
     * The timezone of the server
     */
    private TimeZone serverTimezone = null;

    /**
     * Allow LOAD LOCAL INFILE (defaults to true)
     */
    private boolean allowLoadLocalInfile = true;

    /**
     * Are we in autoCommit mode?
     */
    private boolean autoCommit = true;

    /**
     * Should we capitalize mysql types
     */
    private boolean capitalizeDBMDTypes = false;

    /**
     * Should we continue processing batch commands if
     * one fails. The JDBC spec allows either way, so
     * we let the user choose
     */
    private boolean continueBatchOnError = true;

    /**
     * Should we do unicode character conversions?
     */
    private boolean doUnicode = false;

    /**
     * Are we failed-over to a non-master host
     */
    private boolean failedOver = false;

    /** Does the server suuport isolation levels? */
    private boolean hasIsolationLevels = false;

    /**
     * Does this version of MySQL support quoted identifiers?
     */
    private boolean hasQuotedIdentifiers = false;

    //
    // This is for the high availability :) routines
    //
    private boolean highAvailability = false;

    /**
     * Has this connection been closed?
     */
    private boolean isClosed = true;

    /**
     * Should we tell MySQL that we're an interactive client?
     */
    private boolean isInteractiveClient = false;

    /**
     * Is the server configured to use lower-case
     * table names only?
     */
    private boolean lowerCaseTableNames = false;

    /**
     * Has the max-rows setting been changed from
     * the default?
     */
    private boolean maxRowsChanged = false;

    /**
     * Do we expose sensitive information in exception
     * and error messages?
     */
    private boolean paranoid = false;

    /**
     * Should we do 'extra' sanity checks?
     */
    private boolean pedantic = false;

    /**
     * Are we in read-only mode?
     */
    private boolean readOnly = false;

    /** Do we relax the autoCommit semantics? (For enhydra, for example) */
    private boolean relaxAutoCommit = false;

    /**
     * Do we need to correct endpoint rounding errors
     */
    private boolean strictFloatingPoint = false;

    /**
     * Do we check all keys for updatable result sets?
     */
    private boolean strictUpdates = true;

    /** Are transactions supported by the MySQL server we are connected to? */
    private boolean transactionsSupported = false;

    /**
     * Has ANSI_QUOTES been enabled on the server?
     */
    private boolean useAnsiQuotes = false;

    /**
     * Should we use compression?
     */
    private boolean useCompression = false;

    /**
     * Can we use the "ping" command rather than a
     * query?
     */
    private boolean useFastPing = false;

    /**
     * Should we tack on hostname in DBMD.getTable/ColumnPrivileges()?
     */
    private boolean useHostsInPrivileges = true;

    /**
     * Should we use SSL?
     */
    private boolean useSSL = false;

    /**
     * Should we use stream lengths in prepared statements?
     * (true by default == JDBC compliant)
     */
    private boolean useStreamLengthsInPrepStmts = true;

    /**
     * Should we use timezone information?
     */
    private boolean useTimezone = false;

    /** Should we return PreparedStatements for UltraDev's stupid bug? */
    private boolean useUltraDevWorkAround = false;
    private double initialTimeout = 2.0D;

    /**
     * How many hosts are in the host list?
     */
    private int hostListSize = 0;

    /**
     * isolation level
     */
    private int isolationLevel = java.sql.Connection.TRANSACTION_READ_COMMITTED;

    /**
     * The largest packet we can send (changed
     * once we know what the server supports, we
     * get this at connection init).
     */
    private int maxAllowedPacket = 65536;
    private int maxReconnects = 3;

    /**
     * The max rows that a result set can contain.
     *
     * Defaults to -1, which according to the JDBC
     * spec means "all".
     */
    private int maxRows = -1;
    private int netBufferLength = 16384;

    /**
     * The port number we're connected to
     * (defaults to 3306)
     */
    private int port = 3306;

    /**
     * How many queries should we wait before we try to re-connect
     * to the master, when we are failing over to replicated hosts
     *
     * Defaults to 50
     */
    private int queriesBeforeRetryMaster = 50;

    /**
     * What should we set the socket timeout to?
     */
    private int socketTimeout = 0; // infinite

    /**
     * When did the last query finish?
     */
    private long lastQueryFinishedTime = 0;

    /**
     * When did the master fail?
     */
    private long masterFailTimeMillis = 0L;

    /**
     * Number of queries we've issued since the master
     * failed
     */
    private long queriesIssuedFailedOver = 0;

    /**
     * How many seconds should we wait before retrying to connect
     * to the master if failed over? We fall back when either
     * queriesBeforeRetryMaster or secondsBeforeRetryMaster is
     * reached.
     */
    private long secondsBeforeRetryMaster = 30L;
    
    /**
     * The type map for UDTs (not implemented, but used by
     * some third-party vendors, most notably IBM WebSphere)
     */
    
    private Map typeMap;
    
    /** 
     * Ignore non-transactional table warning for rollback?
     */
    
    private boolean ignoreNonTxTables = false;
    
    /**
     * Should we retrieve 'info' messages from the server?
     */
    private boolean readInfoMsg = false;
    
    /**
     * Creates a connection to a MySQL Server.
     *
     *
     * @param host the hostname of the database server
     * @param port the port number the server is listening on
     * @param info a Properties[] list holding the user and password
     * @param database the database to connect to
     * @param url the URL of the connection
     * @param d the Driver instantation of the connection
     * @exception java.sql.SQLException if a database access error occurs
     */
    Connection(String host, int port, Properties info, String database,
        String url, NonRegisteringDriver d) throws java.sql.SQLException {
        if (Driver.TRACE) {
            Object[] args = { host, new Integer(port), info, database, url, d };
            Debug.methodCall(this, "constructor", args);
        }

        this.serverVariables = new HashMap();
        hostList = new ArrayList();

        if (host == null) {
            this.host = "localhost";
            hostList.add(this.host);
        } else if (host.indexOf(",") != -1) {
            // multiple hosts separated by commas (failover)
            StringTokenizer hostTokenizer = new StringTokenizer(host, ",", false);

            while (hostTokenizer.hasMoreTokens()) {
                hostList.add(hostTokenizer.nextToken().trim());
            }
        } else {
            this.host = host;
            hostList.add(this.host);
        }

        hostListSize = hostList.size();
        this.port = port;

        if (database == null) {
            throw new SQLException("Malformed URL '" + url + "'.", "S1000");
        }

        this.database = database;
        this.myURL = url;
        this.myDriver = d;
        this.user = info.getProperty("user");
        this.password = info.getProperty("password");

        if ((this.user == null) || this.user.equals("")) {
            this.user = "nobody";
        }

        if (this.password == null) {
            this.password = "";
        }

        this.props = info;
        initializeDriverProperties(info);

        if (Driver.DEBUG) {
            System.out.println("Connect: " + this.user + " to " + this.database);
        }

        try {
            createNewIO(false);
            this.dbmd = new DatabaseMetaData(this, this.database);
        } catch (java.sql.SQLException ex) {
            
            cleanup();
        
            // don't clobber SQL exceptions
            throw ex;
        } catch (Exception ex) {
            
            cleanup();
            
            StringBuffer mesg = new StringBuffer();

            if (!useParanoidErrorMessages()) {
                mesg.append("Cannot connect to MySQL server on ");
                mesg.append(this.host);
                mesg.append(":");
                mesg.append(this.port);
                mesg.append(".\n\n");
                mesg.append("Make sure that there is a MySQL server ");
                mesg.append("running on the machine/port you are trying ");
                mesg.append(
                    "to connect to and that the machine this software is "
                    + "running on ");
                mesg.append("is able to connect to this host/port "
                    + "(i.e. not firewalled). ");
                mesg.append(
                    "Also make sure that the server has not been started "
                    + "with the --skip-networking ");
                mesg.append("flag.\n\n");
            } else {
                mesg.append("Unable to connect to database.");
            }

            mesg.append("Underlying exception: \n\n");
            mesg.append(ex.getClass().getName());
            throw new java.sql.SQLException(mesg.toString(), "08S01");
        }
    }

    /**
     * If a connection is in auto-commit mode, than all its SQL
     * statements will be executed and committed as individual
     * transactions.  Otherwise, its SQL statements are grouped
     * into transactions that are terminated by either commit()
     * or rollback().  By default, new connections are in auto-
     * commit mode.  The commit occurs when the statement completes
     * or the next execute occurs, whichever comes first.  In the
     * case of statements returning a ResultSet, the statement
     * completes when the last row of the ResultSet has been retrieved
     * or the ResultSet has been closed.  In advanced cases, a single
     * statement may return multiple results as well as output parameter
     * values.  Here the commit occurs when all results and output param
     * values have been retrieved.
     *
     * <p><b>Note:</b> MySQL does not support transactions, so this
     *                 method is a no-op.
     *
     * @param autoCommit - true enables auto-commit; false disables it
     * @exception java.sql.SQLException if a database access error occurs
     */
    public void setAutoCommit(boolean autoCommit) throws java.sql.SQLException {
        if (Driver.TRACE) {
            Object[] args = { new Boolean(autoCommit) };
            Debug.methodCall(this, "setAutoCommit", args);
        }

        checkClosed();

        if (this.transactionsSupported) {
        	
            // this internal value must be set first as failover depends on it
            // being set to true to fail over (which is done by most
            // app servers and connection pools at the end of
            // a transaction), and the driver issues an implicit set
            // based on this value when it (re)-connects to a server
            // so the value holds across connections
            this.autoCommit = autoCommit;

            String sql = "SET autocommit=" + (autoCommit ? "1" : "0");
            execSQL(sql, -1, this.database);
        } else {
            if ((autoCommit == false) && (this.relaxAutoCommit == false)) {
                throw new SQLException("MySQL Versions Older than 3.23.15 "
                    + "do not support transactions", "08003");
            } else {
                this.autoCommit = autoCommit;
            }
        }

        return;
    }

    /**
     * gets the current auto-commit state
     *
     * @return Current state of the auto-commit mode
     * @exception java.sql.SQLException (why?)
     * @see setAutoCommit
     */
    public boolean getAutoCommit() throws java.sql.SQLException {
        if (Driver.TRACE) {
            Object[] args = new Object[0];
            Debug.methodCall(this, "getAutoCommit", args);
            Debug.returnValue(this, "getAutoCommit",
                new Boolean(this.autoCommit));
        }

        return this.autoCommit;
    }

    /**
     * A sub-space of this Connection's database may be selected by
     * setting a catalog name.  If the driver does not support catalogs,
     * it will silently ignore this request
     *
     * <p><b>Note:</b> MySQL's notion of catalogs are individual databases.
     *
     * @param catalog the database for this connection to use
     * @throws java.sql.SQLException if a database access error occurs
     */
    public void setCatalog(String catalog) throws java.sql.SQLException {
        if (Driver.TRACE) {
            Object[] args = { catalog };
            Debug.methodCall(this, "setCatalog", args);
        }

        checkClosed();

        String quotedId = this.dbmd.getIdentifierQuoteString();

        if ((quotedId == null) || quotedId.equals(" ")) {
            quotedId = "";
        }

        StringBuffer query = new StringBuffer("USE ");
        query.append(quotedId);
        query.append(catalog);
        query.append(quotedId);

        execSQL(query.toString(), -1, catalog);
        this.database = catalog;
    }

    /**
     * Return the connections current catalog name, or null if no
     * catalog name is set, or we dont support catalogs.
     *
     * <p><b>Note:</b> MySQL's notion of catalogs are individual databases.
     * @return the current catalog name or null
     * @exception java.sql.SQLException if a database access error occurs
     */
    public String getCatalog() throws java.sql.SQLException {
        if (Driver.TRACE) {
            Object[] args = new Object[0];
            Debug.methodCall(this, "getCatalog", args);
            Debug.returnValue(this, "getCatalog", this.database);
        }

        return this.database;
    }

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     */
    public boolean isClosed() {
        if (Driver.TRACE) {
            Object[] args = new Object[0];
            Debug.methodCall(this, "isClosed", args);
            Debug.returnValue(this, "isClosed", new Boolean(this.isClosed));
        }

        return this.isClosed;
    }

    /**
     * Returns the character encoding for this Connection
     *
     * @return the character encoding for this connection.
     */
    public String getEncoding() {
        return this.encoding;
    }

    /**
     * @see Connection#setHoldability(int)
     */
    public void setHoldability(int arg0) throws SQLException {
        // do nothing
    }

    /**
     * @see Connection#getHoldability()
     */
    public int getHoldability() throws SQLException {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    /**
     * NOT JDBC-Compliant, but clients can use this method
     * to determine how long this connection has been idle.
     *
     * This time (reported in milliseconds) is updated once
     * a query has completed.
     *
     * @return number of ms that this connection has
     * been idle, 0 if the driver is busy retrieving results.
     */
    public long getIdleFor() {
        if (this.lastQueryFinishedTime == 0) {
            return 0;
        } else {
            long now = System.currentTimeMillis();
            long idleTime = now - this.lastQueryFinishedTime;

            return idleTime;
        }
    }

    /**
     * Should we tell MySQL that we're an interactive client
     *
     * @return true if isInteractiveClient was set to true.
     */
    public boolean isInteractiveClient() {
        return isInteractiveClient;
    }

    /**
     * A connection's database is able to provide information describing
     * its tables, its supported SQL grammar, its stored procedures, the
     * capabilities of this connection, etc.  This information is made
     * available through a DatabaseMetaData object.
     *
     * @return a DatabaseMetaData object for this connection
     * @exception java.sql.SQLException if a database access error occurs
     */
    public java.sql.DatabaseMetaData getMetaData() throws java.sql.SQLException {
        checkClosed();

        return new DatabaseMetaData(this, this.database);
    }

    /**
     * You can put a connection in read-only mode as a hint to enable
     * database optimizations
     *
     * <B>Note:</B> setReadOnly cannot be called while in the middle
     * of a transaction
     *
     * @param readOnly - true enables read-only mode; false disables it
     * @exception java.sql.SQLException if a database access error occurs
     */
    public void setReadOnly(boolean readOnly) throws java.sql.SQLException {
        if (Driver.TRACE) {
            Object[] args = { new Boolean(readOnly) };
            Debug.methodCall(this, "setReadOnly", args);
            Debug.returnValue(this, "setReadOnly", new Boolean(readOnly));
        }

        checkClosed();
        this.readOnly = readOnly;
    }

    /**
     * Tests to see if the connection is in Read Only Mode.  Note that
     * we cannot really put the database in read only mode, but we pretend
     * we can by returning the value of the readOnly flag
     *
     * @return true if the connection is read only
     * @exception java.sql.SQLException if a database access error occurs
     */
    public boolean isReadOnly() throws java.sql.SQLException {
        if (Driver.TRACE) {
            Object[] args = new Object[0];
            Debug.methodCall(this, "isReadOnly", args);
            Debug.returnValue(this, "isReadOnly", new Boolean(this.readOnly));
        }

        return this.readOnly;
    }

    /**
     * @see Connection#setSavepoint()
     */
    public java.sql.Savepoint setSavepoint() throws SQLException {
        throw new NotImplemented();
    }

    /**
     * @see Connection#setSavepoint(String)
     */
    public java.sql.Savepoint setSavepoint(String arg0)
        throws SQLException {
        throw new NotImplemented();
    }

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     */
    public TimeZone getServerTimezone() {
        return this.serverTimezone;
    }

    /**
     * DOCUMENT ME!
     *
     * @param level DOCUMENT ME!
     * @throws java.sql.SQLException DOCUMENT ME!
     */
    public void setTransactionIsolation(int level) throws java.sql.SQLException {
        if (Driver.TRACE) {
            Object[] args = { new Integer(level) };
            Debug.methodCall(this, "setTransactionIsolation", args);
        }

        checkClosed();

        if (this.hasIsolationLevels) {
            StringBuffer sql = new StringBuffer(
                    "SET SESSION TRANSACTION ISOLATION LEVEL ");

            switch (level) {
            case java.sql.Connection.TRANSACTION_NONE:
                throw new SQLException("Transaction isolation level "
                    + "NONE not supported by MySQL");

            case java.sql.Connection.TRANSACTION_READ_COMMITTED:
                sql.append("READ COMMITTED");

                break;

            case java.sql.Connection.TRANSACTION_READ_UNCOMMITTED:
                sql.append("READ UNCOMMITTED");

                break;

            case java.sql.Connection.TRANSACTION_REPEATABLE_READ:
                sql.append("REPEATABLE READ");

                break;

            case java.sql.Connection.TRANSACTION_SERIALIZABLE:
                sql.append("SERIALIZABLE");

                break;

            default:
                throw new SQLException("Unsupported transaction "
                    + "isolation level '" + level + "'", "S1C00");
            }

            execSQL(sql.toString(), -1, this.database);
            isolationLevel = level;
        } else {
            throw new java.sql.SQLException("Transaction Isolation Levels are "
                + "not supported on MySQL versions older than 3.23.36.", "S1C00");
        }
    }

    /**
     * Get this Connection's current transaction isolation mode.
     *
     * @return the current TRANSACTION_* mode value
     * @exception java.sql.SQLException if a database access error occurs
     */
    public int getTransactionIsolation() throws java.sql.SQLException {
        if (Driver.TRACE) {
            Object[] args = new Object[0];
            Debug.methodCall(this, "getTransactionIsolation", args);
            Debug.returnValue(this, "getTransactionIsolation",
                new Integer(isolationLevel));
        }
        
        if (this.hasIsolationLevels) {
        	java.sql.Statement stmt = null;
        	java.sql.ResultSet rs = null;
        	
        	try {
        		stmt = this.createStatement();
        		
        		String query = null;
        		
        		if (this.io.versionMeetsMinimum(4, 0, 3)) {
        			query = "SHOW VARIABLES LIKE 'tx_isolation'";
        		} else {
        			query = "SHOW VARIABLES LIKE 'transaction_isolation'";
        		}	
        		
        		rs = stmt.executeQuery(query);
        		
        		if (rs.next()) {
        			String s = rs.getString(2);
        			
        			if (s != null) {
						Integer intTI = (Integer) mapTransIsolationName2Value.get(s);

						if (intTI != null) {
							return intTI.intValue();
						}
        			}
        			
        			throw new SQLException("Could not map transaction isolation '" + s + " to a valid JDBC level.", "S1000");
        		} else {
        			throw new SQLException("Could not retrieve transaction isolation level from server", "S1000");
        		}
        		
        	} finally {
        		if (rs != null) {
        			try {
        				rs.close();
        			} catch (Exception ex) {
        				// ignore
        			}
        			
        			rs = null;
        		}
        		
        		if (stmt != null) {
        			try {
        				stmt.close();
        			} catch (Exception ex) {
        				// ignore
        			}
        			
        			stmt = null;
        		}
        	}
        }

        return isolationLevel;
    }

    /**
     * JDBC 2.0
     *
     * Install a type-map object as the default type-map for
     * this connection
     *
     * @param map the type mapping
     * @throws SQLException if a database error occurs.
     */
    public void setTypeMap(java.util.Map map) throws SQLException {
        this.typeMap = map;
    }

    /**
     * JDBC 2.0
     *
     * Get the type-map object associated with this connection.
     * By default, the map returned is empty.
     *
     * @return the type map
     * @throws SQLException if a database error occurs
     */
    public synchronized java.util.Map getTypeMap() throws SQLException {
        if (this.typeMap == null) {
            this.typeMap = new HashMap();
        }
        
        return this.typeMap;
    }

    /**
     * The first warning reported by calls on this Connection is
     * returned.
     *
     * <B>Note:</B> Sebsequent warnings will be changed to this
     * java.sql.SQLWarning
     *
     * @return the first java.sql.SQLWarning or null
     * @exception java.sql.SQLException if a database access error occurs
     */
    public java.sql.SQLWarning getWarnings() throws java.sql.SQLException {
        if (Driver.TRACE) {
            Object[] args = new Object[0];
            Debug.methodCall(this, "getWarnings", args);
            Debug.returnValue(this, "getWarnings", null);
        }

        return null;
    }

    /**
     * Allow use of LOAD LOCAL INFILE?
     *
     * @return true if allowLoadLocalInfile was set to true.
     */
    public boolean allowLoadLocalInfile() {
        return this.allowLoadLocalInfile;
    }

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     */
    public boolean capitalizeDBMDTypes() {
        return this.capitalizeDBMDTypes;
    }

    /**
     * After this call, getWarnings returns null until a new warning
     * is reported for this connection.
     *
     * @exception java.sql.SQLException if a database access error occurs
     */
    public void clearWarnings() throws java.sql.SQLException {
        if (Driver.TRACE) {
            Object[] args = new Object[0];
            Debug.methodCall(this, "clearWarnings", args);
        }

        // firstWarning = null;
    }

    /**
     * In some cases, it is desirable to immediately release a Connection's
     * database and JDBC resources instead of waiting for them to be
     * automatically released (cant think why off the top of my head)
     *
     * <B>Note:</B> A Connection is automatically closed when it is
     * garbage collected.  Certain fatal errors also result in a closed
     * connection.
     *
     * @exception java.sql.SQLException if a database access error occurs
     */
    public void close() throws java.sql.SQLException {
        realClose(true, true);
    }
    
	/**
	  * Closes connection and frees resources.
	  * 
	  * @param calledExplicitly is this being called from close()
	  * @param issueRollback should a rollback() be issued?
	  * @throws SQLException if an error occurs
	  */
	 protected void realClose(boolean calledExplicitly, boolean issueRollback) throws SQLException {
		if (Driver.TRACE) {
					Object[] args = new Object[] {new Boolean(calledExplicitly), new Boolean(issueRollback)};
					Debug.methodCall(this, "realClose", args);
				}

				SQLException sqlEx = null;

				if (!isClosed() && !getAutoCommit() && issueRollback) {
					try {
						rollback();
					} catch (SQLException ex) {
						sqlEx = ex;
					}
				}

				if (this.io != null) {
					try {
						this.io.quit();
					} catch (Exception e) {
						;
					}

					this.io = null;
				}

				this.isClosed = true;

				if (sqlEx != null) {
					throw sqlEx;
				}
	 }

    /**
     * The method commit() makes all changes made since the previous
     * commit/rollback permanent and releases any database locks currently
     * held by the Connection.  This method should only be used when
     * auto-commit has been disabled.
     *
     * <p><b>Note:</b> MySQL does not support transactions, so this
     *                 method is a no-op.
     *
     * @exception java.sql.SQLException if a database access error occurs
     * @see setAutoCommit
     */
    public void commit() throws java.sql.SQLException {
        if (Driver.TRACE) {
            Object[] args = new Object[0];
            Debug.methodCall(this, "commit", args);
        }

        checkClosed();

        // no-op if _relaxAutoCommit == true
        if (this.autoCommit && !this.relaxAutoCommit) {
            throw new SQLException("Can't call commit when autocommit=true");
        } else if (this.transactionsSupported) {
            execSQL("commit", -1, this.database);
        }

        return;
    }

    //--------------------------JDBC 2.0-----------------------------

    /**
     * JDBC 2.0
     *
     * Same as createStatement() above, but allows the default result set
     * type and result set concurrency type to be overridden.
     *
     * @param resultSetType a result set type, see ResultSet.TYPE_XXX
     * @param resultSetConcurrency a concurrency type, see ResultSet.CONCUR_XXX
     * @return a new Statement object
     * @exception SQLException if a database-access error occurs.
     */
    public java.sql.Statement createStatement(int resultSetType,
        int resultSetConcurrency) throws SQLException {
        checkClosed();

        Statement stmt = new com.mysql.jdbc.Statement(this, this.database);
        stmt.setResultSetType(resultSetType);
        stmt.setResultSetConcurrency(resultSetConcurrency);

        return stmt;
    }

    /**
     * SQL statements without parameters are normally executed using
     * Statement objects.  If the same SQL statement is executed many
     * times, it is more efficient to use a PreparedStatement
     *
     * @return a new Statement object
     * @throws SQLException passed through from the constructor
     */
    public java.sql.Statement createStatement() throws SQLException {
        return createStatement(java.sql.ResultSet.TYPE_SCROLL_INSENSITIVE,
            java.sql.ResultSet.CONCUR_READ_ONLY);
    }

    /**
     * @see Connection#createStatement(int, int, int)
     */
    public java.sql.Statement createStatement(int resultSetType,
        int resultSetConcurrency, int resultSetHoldability)
        throws SQLException {
        if (this.pedantic) {
            if (resultSetHoldability != ResultSet.HOLD_CURSORS_OVER_COMMIT) {
                throw new SQLException("HOLD_CUSRORS_OVER_COMMIT is only supported holdability level",
                    "S1009");
            }
        }

        return createStatement(resultSetType, resultSetConcurrency);
    }

    /**
     * DOCUMENT ME!
     *
     * @throws Throwable DOCUMENT ME!
     */
    public void finalize() throws Throwable {
        cleanup();
    }

    /**
     * Is the server configured to use lower-case
     * table names only?
     *
     * @return true if lower_case_table_names is 'on'
     */
    public boolean lowerCaseTableNames() {
        return this.lowerCaseTableNames;
    }

    /**
     * A driver may convert the JDBC sql grammar into its system's
     * native SQL grammar prior to sending it; nativeSQL returns the
     * native form of the statement that the driver would have sent.
     *
     * @param sql a SQL statement that may contain one or more '?'
     *    parameter placeholders
     * @return the native form of this statement
     * @exception java.sql.SQLException if a database access error occurs
     */
    public String nativeSQL(String sql) throws java.sql.SQLException {
        if (Driver.TRACE) {
            Object[] args = { sql };
            Debug.methodCall(this, "nativeSQL", args);
            Debug.returnValue(this, "nativeSQL", sql);
        }

        return EscapeProcessor.escapeSQL(sql);
    }

    /**
     * DOCUMENT ME!
     *
     * @param sql DOCUMENT ME!
     * @return DOCUMENT ME!
     * @throws java.sql.SQLException DOCUMENT ME!
     */
    public java.sql.CallableStatement prepareCall(String sql)
        throws java.sql.SQLException {
        if (this.getUseUltraDevWorkAround()) {
            return new UltraDevWorkAround(prepareStatement(sql));
        } else {
            throw new java.sql.SQLException("Callable statments not "
                + "supported.", "S1C00");
        }
    }

    /**
     * JDBC 2.0
     *
     * Same as prepareCall() above, but allows the default result set
     * type and result set concurrency type to be overridden.
     *
     * @param sql the SQL representing the callable statement
     * @param resultSetType a result set type, see ResultSet.TYPE_XXX
     * @param resultSetConcurrency a concurrency type, see ResultSet.CONCUR_XXX
     * @return a new CallableStatement object containing the
     * pre-compiled SQL statement
     * @exception SQLException if a database-access error occurs.
     */
    public java.sql.CallableStatement prepareCall(String sql,
        int resultSetType, int resultSetConcurrency) throws SQLException {
        return prepareCall(sql);
    }

    /**
     * @see Connection#prepareCall(String, int, int, int)
     */
    public java.sql.CallableStatement prepareCall(String sql,
        int resultSetType, int resultSetConcurrency, int resultSetHoldability)
        throws SQLException {
        if (this.pedantic) {
            if (resultSetHoldability != ResultSet.HOLD_CURSORS_OVER_COMMIT) {
                throw new SQLException("HOLD_CUSRORS_OVER_COMMIT is only supported holdability level",
                    "S1009");
            }
        }

        throw new NotImplemented();
    }

    /**
     * A SQL statement with or without IN parameters can be pre-compiled
     * and stored in a PreparedStatement object.  This object can then
     * be used to efficiently execute this statement multiple times.
     *
     * <p>
     * <B>Note:</B> This method is optimized for handling parametric
     * SQL statements that benefit from precompilation if the driver
     * supports precompilation.
     *
     * In this case, the statement is not sent to the database until the
     * PreparedStatement is executed.  This has no direct effect on users;
     * however it does affect which method throws
     * certain java.sql.SQLExceptions
     *
     * <p>
     * MySQL does not support precompilation of statements, so they
     * are handled by the driver.
     *
     * @param sql a SQL statement that may contain one or more '?' IN
     *    parameter placeholders
     * @return a new PreparedStatement object containing the pre-compiled
     *    statement.
     * @exception java.sql.SQLException if a database access error occurs.
     */
    public java.sql.PreparedStatement prepareStatement(String sql)
        throws java.sql.SQLException {
        return prepareStatement(sql,
            java.sql.ResultSet.TYPE_SCROLL_INSENSITIVE,
            java.sql.ResultSet.CONCUR_READ_ONLY);
    }

    /**
     * JDBC 2.0
     *
     * Same as prepareStatement() above, but allows the default result set
     * type and result set concurrency type to be overridden.
     *
     * @param sql the SQL query containing place holders
     * @param resultSetType a result set type, see ResultSet.TYPE_XXX
     * @param resultSetConcurrency a concurrency type, see ResultSet.CONCUR_XXX
     * @return a new PreparedStatement object containing the
     * pre-compiled SQL statement
     * @exception SQLException if a database-access error occurs.
     */
    public java.sql.PreparedStatement prepareStatement(String sql,
        int resultSetType, int resultSetConcurrency) throws SQLException {
        checkClosed();

        //
        // FIXME: Create warnings if can't create results of the given
        //        type or concurrency
        //
        PreparedStatement pStmt = new com.mysql.jdbc.PreparedStatement(this,
                sql, this.database);
        pStmt.setResultSetType(resultSetType);
        pStmt.setResultSetConcurrency(resultSetConcurrency);

        return pStmt;
    }

    /**
     * @see Connection#prepareStatement(String, int, int, int)
     */
    public java.sql.PreparedStatement prepareStatement(String sql,
        int resultSetType, int resultSetConcurrency, int resultSetHoldability)
        throws SQLException {
        if (this.pedantic) {
            if (resultSetHoldability != ResultSet.HOLD_CURSORS_OVER_COMMIT) {
                throw new SQLException("HOLD_CUSRORS_OVER_COMMIT is only supported holdability level",
                    "S1009");
            }
        }

        return prepareStatement(sql, resultSetType, resultSetConcurrency);
    }

    /**
     * @see Connection#prepareStatement(String, int)
     */
    public java.sql.PreparedStatement prepareStatement(String sql,
        int autoGenKeyIndex) throws SQLException {
		java.sql.PreparedStatement pStmt = prepareStatement(sql);
        
        ((com.mysql.jdbc.PreparedStatement) pStmt).setRetrieveGeneratedKeys(
        	autoGenKeyIndex == Statement.RETURN_GENERATED_KEYS);
        	
        return pStmt;
    }

    /**
     * @see Connection#prepareStatement(String, int[])
     */
    public java.sql.PreparedStatement prepareStatement(String sql,
        int[] autoGenKeyIndexes) throws SQLException {
		java.sql.PreparedStatement pStmt = prepareStatement(sql);
        
		((com.mysql.jdbc.PreparedStatement) pStmt).setRetrieveGeneratedKeys(
			autoGenKeyIndexes != null && autoGenKeyIndexes.length > 0);
        	
		return pStmt;
    }

    /**
     * @see Connection#prepareStatement(String, String[])
     */
    public java.sql.PreparedStatement prepareStatement(String sql,
        String[] autoGenKeyColNames) throws SQLException {
        	
		java.sql.PreparedStatement pStmt = prepareStatement(sql);
        
		((com.mysql.jdbc.PreparedStatement) pStmt).setRetrieveGeneratedKeys(
			autoGenKeyColNames != null && autoGenKeyColNames.length > 0);
        	
		return pStmt;
    }

    /**
     * @see Connection#releaseSavepoint(Savepoint)
     */
    public void releaseSavepoint(Savepoint arg0) throws SQLException {
        throw new NotImplemented();
    }

    /**
     * The method rollback() drops all changes made since the previous
     * commit/rollback and releases any database locks currently held by
     * the Connection.
     *
     * @exception java.sql.SQLException if a database access error occurs
     * @see commit
     */
    public void rollback() throws java.sql.SQLException {
        if (Driver.TRACE) {
            Object[] args = new Object[0];
            Debug.methodCall(this, "rollback", args);
        }

        checkClosed();

        // no-op if _relaxAutoCommit == true
        if (this.autoCommit && !this.relaxAutoCommit) {
            throw new SQLException("Can't call rollback when autocommit=true",
                "08003");
        } else if (this.transactionsSupported) {
			try {
				rollbackNoChecks();
			} catch (SQLException sqlEx) {
				// We ignore non-transactional tables if told to do so
				if (this.ignoreNonTxTables 
				&& sqlEx.getErrorCode() != MysqlDefs.ER_WARNING_NOT_COMPLETE_ROLLBACK) {
					throw sqlEx;
				}
			}
            
        }
    }
    
    private void rollbackNoChecks() throws SQLException {
		execSQL("rollback", -1, null);
    }

    /**
     * @see Connection#rollback(Savepoint)
     */
    public void rollback(Savepoint arg0) throws SQLException {
        throw new NotImplemented();
    }

    /**
     * Used by MiniAdmin to shutdown a MySQL server
     *
     * @throws SQLException if the command can not be issued.
     */
    public void shutdownServer() throws SQLException {
        try {
            this.io.sendCommand(MysqlDefs.SHUTDOWN, null, null);
        } catch (Exception ex) {
            throw new SQLException("Unhandled exception '" + ex.toString()
                + "'", "S1000");
        }
    }

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     */
    public boolean supportsIsolationLevel() {
        return this.hasIsolationLevels;
    }

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     */
    public boolean supportsQuotedIdentifiers() {
        return this.hasQuotedIdentifiers;
    }

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     */
    public boolean supportsTransactions() {
        return this.transactionsSupported;
    }

    /**
     * Should we use compression?
     *
     * @return should we use compression to communicate with
     * the server?
     */
    public boolean useCompression() {
        return this.useCompression;
    }

    /**
     * Returns the paranoidErrorMessages.
     * @return boolean if we should be paranoid about
     * error messages.
     */
    public boolean useParanoidErrorMessages() {
        return paranoid;
    }

    /**
     * Should we use SSL?
     *
     * @return should we use SSL to communicate with
     * the server?
     */
    public boolean useSSL() {
        return this.useSSL;
    }

    /**
     * Should we enable work-arounds for floating
     * point rounding errors in the server?
     *
     * @return should we use floating point work-arounds?
     */
    public boolean useStrictFloatingPoint() {
        return this.strictFloatingPoint;
    }

    /**
     * Returns the strictUpdates value.
     * @return boolean
     */
    public boolean useStrictUpdates() {
        return strictUpdates;
    }

    /**
     * DOCUMENT ME!
     *
     * @return DOCUMENT ME!
     */
    public boolean useTimezone() {
        return this.useTimezone;
    }

    /**
     * Should unicode character mapping be used ?
     *
     * @return should we use Unicode character mapping?
     */
    public boolean useUnicode() {
        return this.doUnicode;
    }
    
    /**
     * Should the driver do profiling?
     * 
     * @param flag set to true to enable profiling.
     * @throws SQLException if the connection is closed.
     */
    
    public void setProfileSql(boolean flag) throws SQLException {
    	// For re-connection
    	this.props.setProperty("profileSql", String.valueOf(flag));
    	getIO().setProfileSql(flag);	
    }

    /**
     * Returns the IO channel to the server
     *
     * @throws SQLException if the connection is closed.
     * 
     * @return the IO channel to the server
     */
    protected MysqlIO getIO() throws SQLException {
    	if (this.io == null || this.isClosed) {
        	throw new SQLException("Operation not allowed on closed connection",
        		"08003");
    	}
    	
		return this.io;
    }

    /**
     * Creates an IO channel to the server
     *
     * @param isForReconnect is this request for a re-connect
     * @throws SQLException if a database access error
     * occurs
     * @return a new MysqlIO instance connected to a server
     */
    protected com.mysql.jdbc.MysqlIO createNewIO(boolean isForReconnect)
        throws SQLException {
        MysqlIO newIo = null;

        if (!highAvailability && !this.failedOver) {
            for (int hostIndex = 0; hostIndex < hostListSize; hostIndex++) {
                try {
                    this.io = new MysqlIO(this.hostList.get(hostIndex).toString(),
                            this.port, this.socketFactoryClassName, this.props,
                            this, this.socketTimeout);
                    this.io.doHandshake(this.user, this.password);
                    this.isClosed = false;

                    if (this.database.length() != 0) {
                        this.io.sendCommand(MysqlDefs.INIT_DB, this.database,
                            null);
                    }

                    // save state from old connection
                    boolean autoCommit = getAutoCommit();
                    int oldIsolationLevel = getTransactionIsolation();
                    boolean oldReadOnly = isReadOnly();

                    // Server properties might be different
                    // from previous connection, so initialize
                    // again...
                    initializePropsFromServer(this.props);

                    if (isForReconnect) {
                        // Restore state from old connection
                        setAutoCommit(autoCommit);
                        setTransactionIsolation(oldIsolationLevel);
                    }

                    if (hostIndex != 0) {
                        setFailedOverState();
                    } else {
                    	
						this.failedOver = false;
							
						if (hostListSize > 1) {
							setReadOnly(false);
						} else {
							setReadOnly(oldReadOnly);
						}
                    }

                    break; // low-level connection succeeded
                } catch (SQLException sqlEx) {
                    
                	if (this.io != null) {
                    	this.io.forceClose();
                   	}
                    
                    String sqlState = sqlEx.getSQLState();

                    if ((sqlState == null) || !sqlState.equals("08S01")) {
                        throw sqlEx;
                    }
                    
					if ((hostListSize - 1) == hostIndex) {
						throw sqlEx;
					}
                } catch (Exception unknownException) {
                    
                    if (this.io != null) {
                    	this.io.forceClose();
                    }
                    
                    if ((hostListSize - 1) == hostIndex) {
                        throw new SQLException(
                            "Unable to connect to any hosts due to exception: "
                            + unknownException.toString(), "08S01");
                    }
                }
            }
        } else {
            double timeout = this.initialTimeout;
            boolean connectionGood = false;

            for (int hostIndex = 0; hostIndex < hostListSize; hostIndex++) {
                for (int attemptCount = 0; attemptCount < this.maxReconnects;
                        attemptCount++) {
                    try {
                        if (this.io != null) {
                        	this.io.forceClose(); 
                        }

                        this.io = new MysqlIO(this.hostList.get(hostIndex)
                                                           .toString(),
                                this.port, this.socketFactoryClassName,
                                this.props, this, this.socketTimeout);
                        this.io.doHandshake(this.user, this.password);

                        if (this.database.length() != 0) {
                            this.io.sendCommand(MysqlDefs.INIT_DB,
                                this.database, null);
                        }

                        ping();
                        this.isClosed = false;

                        // save state from old connection
                        boolean autoCommit = getAutoCommit();
                        int oldIsolationLevel = getTransactionIsolation();
						boolean oldReadOnly = isReadOnly();
						
                        // Server properties might be different
                        // from previous connection, so initialize
                        // again...
                        initializePropsFromServer(this.props);

                        if (isForReconnect) {
                            // Restore state from old connection
                            setAutoCommit(autoCommit);
                            setTransactionIsolation(oldIsolationLevel);
                        }

                        connectionGood = true;

                        if (hostIndex != 0) {
                            setFailedOverState();
                        } else {
							
							this.failedOver = false;
							
							if (hostListSize > 1) {
								setReadOnly(false);
							} else {
								setReadOnly(oldReadOnly);
							}
                        }

                        break;
                    } catch (Exception EEE) {
                        connectionGood = false;
                    }

                    if (connectionGood) {
                        break;
                    }

                    try {
                        Thread.sleep((long) timeout * 1000);
                        timeout = timeout * timeout;
                    } catch (InterruptedException IE) {
                        ;
                    }
                }

                if (!connectionGood) {
                    // We've really failed!
                    throw new SQLException(
                        "Server connection failure during transaction. \nAttempted reconnect "
                        + this.maxReconnects + " times. Giving up.", "08001");
                }
            }
        }

        if (paranoid && !highAvailability && (hostListSize <= 1)) {
            password = null;
            user = null;
        }

        return newIo;
    }

    /** Returns the maximum packet size the MySQL server will accept */
    int getMaxAllowedPacket() {
        return this.maxAllowedPacket;
    }

    /** Returns the Mutex all queries are locked against */
    Object getMutex() throws SQLException {
        if (this.io == null) {
            throw new SQLException("Connection.close() has already been called. Invalid operation in this state.",
                "08003");
        }

        return this.mutex;
    }

    /** Returns the packet buffer size the MySQL server reported
     *  upon connection */
    int getNetBufferLength() {
        return this.netBufferLength;
    }

    boolean isPedantic() {
        return this.pedantic;
    }

    int getServerMajorVersion() {
        return this.io.getServerMajorVersion();
    }

    int getServerMinorVersion() {
        return this.io.getServerMinorVersion();
    }

    int getServerSubMinorVersion() {
        return this.io.getServerSubMinorVersion();
    }

    String getServerVersion() {
        return this.io.getServerVersion();
    }

    String getURL() {
        return this.myURL;
    }

    /**
     * Set whether or not this connection
     * should use SSL
     */
    void setUseSSL(boolean flag) {
        this.useSSL = flag;
    }

    String getUser() {
        return this.user;
    }

    boolean continueBatchOnError() {
        return this.continueBatchOnError;
    }

    /**
     * Send a query to the server.  Returns one of the ResultSet
     * objects.
     *
     * This is synchronized, so Statement's queries
     * will be serialized.
     *
     * @param sql the SQL statement to be executed
     * @return a ResultSet holding the results
     * @exception java.sql.SQLException if a database error occurs
     */
    ResultSet execSQL(String sql, int maxRowsToRetreive, String catalog)
        throws java.sql.SQLException {
        if (Driver.TRACE) {
            Object[] args = { sql, new Integer(maxRowsToRetreive) };
            Debug.methodCall(this, "execSQL", args);
        }

        return execSQL(sql, maxRowsToRetreive, null,
            java.sql.ResultSet.CONCUR_READ_ONLY, catalog);
    }

    ResultSet execSQL(String sql, int maxRows, int resultSetType,
        boolean streamResults, boolean queryIsSelectOnly, String catalog)
        throws SQLException {
        return execSQL(sql, maxRows, null, resultSetType, streamResults,
            queryIsSelectOnly, catalog);
    }

    ResultSet execSQL(String sql, int maxRows, Buffer packet, String catalog)
        throws java.sql.SQLException {
        return execSQL(sql, maxRows, packet,
            java.sql.ResultSet.CONCUR_READ_ONLY, catalog);
    }

    ResultSet execSQL(String sql, int maxRows, Buffer packet,
        int resultSetType, String catalog) throws java.sql.SQLException {
        return execSQL(sql, maxRows, packet, resultSetType, true, false, catalog);
    }

    ResultSet execSQL(String sql, int maxRows, Buffer packet,
        int resultSetType, boolean streamResults, boolean queryIsSelectOnly,
        String catalog) throws java.sql.SQLException {
        if (Driver.TRACE) {
            Object[] args = { sql, new Integer(maxRows), packet };
            Debug.methodCall(this, "execSQL", args);
        }

        //
        // Fall-back if the master is back online if we've
        // issued queriesBeforeRetryMaster queries since
        // we failed over
        //
        synchronized (this.mutex) {
            this.lastQueryFinishedTime = 0; // we're busy!

            if (this.failedOver && this.autoCommit) {
                this.queriesIssuedFailedOver++;

                if (shouldFallBack()) {
                    createNewIO(true);

                    String connectedHost = this.io.getHost();

                    if ((connectedHost != null)
                            && this.hostList.get(0).equals(connectedHost)) {
                        this.failedOver = false;
                        this.queriesIssuedFailedOver = 0;
                        setReadOnly(false);
                    }
                }
            }

            if ((this.highAvailability || this.failedOver) && this.autoCommit) {
                try {
                    ping();
                } catch (Exception Ex) {
                    createNewIO(true);
                }
            }

            try {
                int realMaxRows = (maxRows == -1) ? MysqlDefs.MAX_ROWS : maxRows;

                if (packet == null) {
                    String encoding = null;

                    if (useUnicode()) {
                        encoding = getEncoding();
                    }

                    return this.io.sqlQuery(sql, realMaxRows, encoding, this,
                        resultSetType, streamResults, catalog);
                } else {
                    return this.io.sqlQueryDirect(packet, realMaxRows, this,
                        resultSetType, streamResults, catalog);
                }
            } catch (java.sql.SQLException sqlE) {
                // don't clobber SQL exceptions
                
                String sqlState = sqlE.getSQLState();
                
                if (sqlState != null && sqlState.equals("08S01")) {
                	cleanup();
                }
                
                throw sqlE;
                
            } catch (Exception ex) {
            	if (ex instanceof IOException) {
            		cleanup();	
            	}
            	
                String exceptionType = ex.getClass().getName();
                String exceptionMessage = ex.getMessage();
                
                if (!this.useParanoidErrorMessages()) {
                	exceptionMessage += "\n\nNested Stack Trace:\n";
                	exceptionMessage += Util.stackTraceToString(ex);
                }
                
                throw new java.sql.SQLException(
                    "Error during query: Unexpected Exception: "
                    + exceptionType + " message given: " + exceptionMessage,
                    "S1000");
                
            } finally {
                this.lastQueryFinishedTime = System.currentTimeMillis();
            }
        }
    }

  
	/** Has the maxRows value changed? */
	synchronized void maxRowsChanged(Statement stmt) {
		if (this.statementsUsingMaxRows == null) {
			this.statementsUsingMaxRows = new HashMap();
		}

		this.statementsUsingMaxRows.put(stmt, stmt);

		this.maxRowsChanged = true;
	}

	/**
	 * Called by statements on their .close() to let the connection know when it
	 * is safe to set the connection back to 'default' row limits.
	 * @param stmt the statement releasing it's max-rows requirement
	 * @throws SQLException if a database error occurs issuing the
	 * statement that sets the limit default.
	 */
	synchronized void unsetMaxRows(Statement stmt) throws SQLException {
			if (this.statementsUsingMaxRows != null) {
				Object found = this.statementsUsingMaxRows.remove(stmt);

			if ((found != null) && (this.statementsUsingMaxRows.size() == 0)) {
				execSQL("SET OPTION SQL_SELECT_LIMIT=DEFAULT", -1, this.database);
				this.maxRowsChanged = false;
			}
		}
	}
		
    boolean useAnsiQuotedIdentifiers() {
        return this.useAnsiQuotes;
    }

    boolean useHostsInPrivileges() {
        return this.useHostsInPrivileges;
    }

    /** Has maxRows() been set? */
    synchronized boolean useMaxRows() {
        return this.maxRowsChanged;
    }

    boolean useStreamLengthsInPrepStmts() {
        return this.useStreamLengthsInPrepStmts;
    }
    
    boolean isReadInfoMsgEnabled() {
    	return this.readInfoMsg; 
    }
    
    void setReadInfoMsgEnabled(boolean flag) {
    	this.readInfoMsg = flag;
    }

    /**
     * Sets state for a failed-over connection
     */
    private void setFailedOverState() throws SQLException {
        // FIXME: User Selectable?
        setReadOnly(true);
        this.queriesIssuedFailedOver = 0;
        this.failedOver = true;
        this.masterFailTimeMillis = System.currentTimeMillis();
    }

    /**
     * If useUnicode flag is set and explicit client character encoding isn't specified
     * then assign encoding from server if any.
     */
    private void checkServerEncoding() throws SQLException {
        if (this.doUnicode && this.encoding != null) {
            // spec'd by client, don't map
            return;
        }

        String serverEncoding = (String) this.serverVariables.get(
                "character_set");
        String mappedServerEncoding = null;

        if (serverEncoding != null) {
            mappedServerEncoding = (String) charsetMap.get(serverEncoding
                    .toUpperCase());
        }

        //
        // First check if we can do the encoding ourselves
        //
        if (!this.doUnicode && mappedServerEncoding != null) {
            try {
                SingleByteCharsetConverter converter = SingleByteCharsetConverter
                    .getInstance(mappedServerEncoding);

                if (converter != null) { // we know how to convert this ourselves
                    this.doUnicode = true; // force the issue
                    this.encoding = mappedServerEncoding;

                    return;
                }
            } catch (UnsupportedEncodingException uee) {
                // fall through
            }
        }

        //
        // Now, try and find a Java I/O converter that can do
        // the encoding for us
        //
        
        if (serverEncoding != null) {
                if (mappedServerEncoding == null) {
                	// We don't have a mapping for it, so try
                	// and canonicalize the name....
                	
                    if (Character.isLowerCase(serverEncoding.charAt(0))) {
                        char[] ach = serverEncoding.toCharArray();
                        ach[0] = Character.toUpperCase(serverEncoding.charAt(0));
                        this.encoding = new String(ach);
                    }
                }
            

			//
            // Attempt to use the encoding, and bail out if it
            // can't be used
            //
            try {
				"abc".getBytes(mappedServerEncoding);
                this.encoding = mappedServerEncoding;
                this.doUnicode = true;
            } catch (UnsupportedEncodingException UE) {
                throw new SQLException(
                    "The driver can not map the character encoding '"
                    + this.encoding + "' that your server is using "
                    + "to a character encoding your JVM understands. You "
                    + "can specify this mapping manually by adding \"useUnicode=true\" "
                    + "as well as \"characterEncoding=[an_encoding_your_jvm_understands]\" "
                    + "to your JDBC URL.", "0S100");
            }
        }
    }

	/**
	 * Set transaction isolation level to the value received from server if any.
	 * Is called by connectionInit(...)
	 */		
	private void checkTransactionIsolationLevel() throws SQLException {
		String txIsolationName = null;
    	
		if (this.io.versionMeetsMinimum(4, 0, 3)) {
			txIsolationName = "tx_isolation";
		} else {
			txIsolationName = "transaction_isolation";
		}	
						
		String s = (String) this.serverVariables.get(txIsolationName);

		if (s != null) {
			Integer intTI = (Integer) mapTransIsolationName2Value.get(s);

			if (intTI != null) {
				isolationLevel = intTI.intValue();
			}
		}
	}

    /**
     * Destroys this connection and any underlying resources
     */
    private void cleanup() {
    	try {
    	
        	if ((this.io != null) && !isClosed()) {
            	realClose(false, false);
       		} else if (this.io != null) {
        		this.io.forceClose();  
        	}
    	} catch (SQLException sqlEx) {
    		// ignore, we're going away.
    	}

        this.isClosed = true;
    }

    /**
     * Initializes driver properties that come from URL or
     * properties passed to the driver manager.
     */
    private void initializeDriverProperties(Properties info)
        throws SQLException {
        this.socketFactoryClassName = info.getProperty("socketFactory",
                DEFAULT_SOCKET_FACTORY);

        if (info.getProperty("strictUpdates") != null) {
            this.strictUpdates = info.getProperty("strictUpdates")
                                     .equalsIgnoreCase("TRUE");
        }

		if (info.getProperty("ignoreNonTxTables") != null) {
			this.ignoreNonTxTables = info.getProperty("ignoreNonTxTables").equalsIgnoreCase("TRUE"); 
		}
		
        if (info.getProperty("secondsBeforeRetryMaster") != null) {
            String secondsBeforeRetryStr = info.getProperty(
                    "secondsBeforeRetryMaster");

            try {
                int seconds = Integer.parseInt(secondsBeforeRetryStr);

                if (seconds < 1) {
                    throw new SQLException("Illegal (< 1)  value '"
                        + secondsBeforeRetryStr
                        + "' for 'secondsBeforeRetryMaster'", "S1009");
                }

                this.secondsBeforeRetryMaster = seconds;
            } catch (NumberFormatException nfe) {
                throw new SQLException("Illegal non-numeric value '"
                    + secondsBeforeRetryStr
                    + "' for 'secondsBeforeRetryMaster'", "S1009");
            }
        }

        if (info.getProperty("queriesBeforeRetryMaster") != null) {
            String queriesBeforeRetryStr = info.getProperty(
                    "queriesBeforeRetryMaster");

            try {
                this.queriesBeforeRetryMaster = Integer.parseInt(queriesBeforeRetryStr);
            } catch (NumberFormatException nfe) {
                throw new SQLException("Illegal non-numeric value '"
                    + queriesBeforeRetryStr
                    + "' for 'queriesBeforeRetryMaster'", "S1009");
            }
        }

        if (info.getProperty("allowLoadLocalInfile") != null) {
            this.allowLoadLocalInfile = info.getProperty("allowLoadLocalInfile")
                                            .equalsIgnoreCase("TRUE");
        }

        if (info.getProperty("continueBatchOnError") != null) {
            this.continueBatchOnError = info.getProperty("continueBatchOnError")
                                            .equalsIgnoreCase("TRUE");
        }

        if (info.getProperty("pedantic") != null) {
            this.pedantic = info.getProperty("pedantic").equalsIgnoreCase("TRUE");
        }

        if (info.getProperty("useStreamLengthsInPrepStmts") != null) {
            this.useStreamLengthsInPrepStmts = info.getProperty(
                    "useStreamLengthsInPrepStmts").equalsIgnoreCase("TRUE");
        }

        if (info.getProperty("useTimezone") != null) {
            this.useTimezone = info.getProperty("useTimezone").equalsIgnoreCase("TRUE");
        }

        if (info.getProperty("relaxAutoCommit") != null) {
            this.relaxAutoCommit = info.getProperty("relaxAutoCommit")
                                       .equalsIgnoreCase("TRUE");
        } else if (info.getProperty("relaxAutocommit") != null) {
            this.relaxAutoCommit = info.getProperty("relaxAutocommit")
                                       .equalsIgnoreCase("TRUE");
        }

        if (info.getProperty("paranoid") != null) {
            this.paranoid = info.getProperty("paranoid").equalsIgnoreCase("TRUE");
        }

        if (info.getProperty("autoReconnect") != null) {
            this.highAvailability = info.getProperty("autoReconnect")
                                        .equalsIgnoreCase("TRUE");
        }

        if (info.getProperty("capitalizeTypeNames") != null) {
            this.capitalizeDBMDTypes = info.getProperty("capitalizeTypeNames")
                                           .equalsIgnoreCase("TRUE");
        }

        if (info.getProperty("ultraDevHack") != null) {
            this.useUltraDevWorkAround = info.getProperty("ultraDevHack")
                                             .equalsIgnoreCase("TRUE");
        }

        if (info.getProperty("strictFloatingPoint") != null) {
            this.strictFloatingPoint = info.getProperty("strictFloatingPoint")
                                           .equalsIgnoreCase("TRUE");
        }

        if (info.getProperty("useSSL") != null) {
            this.useSSL = info.getProperty("useSSL").equalsIgnoreCase("TRUE");
        }

        if (info.getProperty("useCompression") != null) {
            this.useCompression = info.getProperty("useCompression").equalsIgnoreCase("TRUE");
        }

        if (info.getProperty("socketTimeout") != null) {
            try {
                int n = Integer.parseInt(info.getProperty("socketTimeout"));

                if (n < 0) {
                    throw new SQLException("socketTimeout can not " + "be < 0",
                        "0S100");
                }

                this.socketTimeout = n;
            } catch (NumberFormatException NFE) {
                throw new SQLException("Illegal parameter '"
                    + info.getProperty("socketTimeout") + "' for socketTimeout",
                    "0S100");
            }
        }

        if (this.highAvailability) {
            if (info.getProperty("maxReconnects") != null) {
                try {
                    int n = Integer.parseInt(info.getProperty("maxReconnects"));
                    this.maxReconnects = n;
                } catch (NumberFormatException NFE) {
                    throw new SQLException("Illegal parameter '"
                        + info.getProperty("maxReconnects")
                        + "' for maxReconnects", "0S100");
                }
            }

            if (info.getProperty("initialTimeout") != null) {
                try {
                    double n = Integer.parseInt(info.getProperty(
                                "initialTimeout"));
                    this.initialTimeout = n;
                } catch (NumberFormatException NFE) {
                    throw new SQLException("Illegal parameter '"
                        + info.getProperty("initialTimeout")
                        + "' for initialTimeout", "0S100");
                }
            }
        }

        if (info.getProperty("maxRows") != null) {
            try {
                int n = Integer.parseInt(info.getProperty("maxRows"));

                if (n == 0) {
                    n = -1;
                }
                 // adjust so that it will become MysqlDefs.MAX_ROWS

                // in execSQL()
                this.maxRows = n;
                this.maxRowsChanged = true;
            } catch (NumberFormatException NFE) {
                throw new SQLException("Illegal parameter '"
                    + info.getProperty("maxRows") + "' for maxRows", "0S100");
            }
        }

        if (info.getProperty("useHostsInPrivileges") != null) {
            this.useHostsInPrivileges = info.getProperty("useHostsInPrivileges")
                                            .equalsIgnoreCase("TRUE");
        }

        if (info.getProperty("interactiveClient") != null) {
            this.isInteractiveClient = info.getProperty("interactiveClient")
                                           .equalsIgnoreCase("TRUE");
        }

        if (info.getProperty("useUnicode") != null) {
            this.doUnicode = info.getProperty("useUnicode").equalsIgnoreCase("TRUE");
        }

        if (this.doUnicode) {
            if (info.getProperty("characterEncoding") != null) {
                this.encoding = info.getProperty("characterEncoding");

                // Attempt to use the encoding, and bail out if it
                // can't be used
                try {
                    String testString = "abc";
                    testString.getBytes(this.encoding);
                } catch (UnsupportedEncodingException UE) {
                    throw new SQLException("Unsupported character "
                        + "encoding '" + this.encoding + "'.", "0S100");
                }
            }
        }
    }

    /**
     * Sets varying properties that depend on server information.
     * Called once we have connected to the server.
     */
    private void initializePropsFromServer(Properties info)
        throws SQLException {
        if (this.io.versionMeetsMinimum(3, 22, 1)) {
            this.useFastPing = true;
        }

        this.serverVariables.clear();

        //
        // If version is greater than 3.21.22 get the server
        // variables.
        if (this.io.versionMeetsMinimum(3, 21, 22)) {
            com.mysql.jdbc.Statement stmt = null;
            com.mysql.jdbc.ResultSet results = null;

            try {
                stmt = (com.mysql.jdbc.Statement) createStatement();
                results = (com.mysql.jdbc.ResultSet) stmt.executeQuery(
                        "SHOW VARIABLES");

                while (results.next()) {
                    this.serverVariables.put(results.getString(1),
                        results.getString(2));
                }
            } catch (java.sql.SQLException e) {
                throw e;
            } finally {
                if (results != null) {
                    try {
                        results.close();
                    } catch (java.sql.SQLException sqlE) {
                        ;
                    }
                }

                if (stmt != null) {
                    try {
                        stmt.close();
                    } catch (java.sql.SQLException sqlE) {
                        ;
                    }
                }
            }

            String lowerCaseTables = (String) serverVariables.get(
                    "lower_case_table_names");

            if (lowerCaseTables != null) {
                this.lowerCaseTableNames = lowerCaseTables.trim()
                                                          .equalsIgnoreCase("on");
            }

            if (this.useTimezone
                    && this.serverVariables.containsKey("timezone")) {
                // user can specify/override as property
                String canoncicalTimezone = this.props.getProperty(
                        "serverTimezone");

                if ((canoncicalTimezone == null)
                        || (canoncicalTimezone.length() == 0)) {
                    String serverTimezoneStr = (String) this.serverVariables
                        .get("timezone");
                        
                    try {
                    
                    	canoncicalTimezone = TimeUtil.getCanoncialTimezone(serverTimezoneStr);

                    	if (canoncicalTimezone == null) {
                        	throw new SQLException("Can't map timezone '"
                            	+ serverTimezoneStr + "' to "
                            	+ " canonical timezone.", "S1009");
                    	}
                    } catch (IllegalArgumentException iae) {
                    	throw new SQLException(iae.getMessage(), "S1000");
                    }
                }

                serverTimezone = TimeZone.getTimeZone(canoncicalTimezone);

				//
				// The Calendar class has the behavior of mapping
				// unknown timezones to 'GMT' instead of throwing an 
				// exception, so we must check for this...
				//
				
                if (!canoncicalTimezone.equalsIgnoreCase("GMT")
                        && serverTimezone.getID().equals("GMT")) {
                    throw new SQLException("No timezone mapping entry for '"
                        + canoncicalTimezone + "'", "S1009");
                }
            }

            if (this.serverVariables.containsKey("max_allowed_packet")) {
                this.maxAllowedPacket = Integer.parseInt((String) this.serverVariables
                        .get("max_allowed_packet"));
            }

            if (this.serverVariables.containsKey("net_buffer_length")) {
                this.netBufferLength = Integer.parseInt((String) this.serverVariables
                        .get("net_buffer_length"));
            }

            checkTransactionIsolationLevel();
            checkServerEncoding();
            this.io.checkForCharsetMismatch();
        }

        if (this.io.versionMeetsMinimum(3, 23, 15)) {
            this.transactionsSupported = true;
            setAutoCommit(true); // to override anything
                                 // the server is set to...reqd
                                 // by JDBC spec.
        } else {
            this.transactionsSupported = false;
        }

        if (this.io.versionMeetsMinimum(3, 23, 36)) {
            this.hasIsolationLevels = true;
        } else {
            this.hasIsolationLevels = false;
        }

        // Start logging perf/profile data if the user has requested it.
        String profileSql = info.getProperty("profileSql");

        if ((profileSql != null) && profileSql.trim().equalsIgnoreCase("true")) {
            this.io.setProfileSql(true);
        } else {
            this.io.setProfileSql(false);
        }

        this.hasQuotedIdentifiers = this.io.versionMeetsMinimum(3, 23, 6);

		if (this.serverVariables.containsKey("sql_mode")) {
        	
			int sqlMode = 0;
                        
			try {
				sqlMode = Integer.parseInt((String) this.serverVariables.get("sql_mode"));
			} catch (NumberFormatException nfe) {
				sqlMode = 0;
			}
                        

			if ((sqlMode & 4) > 0) {
				this.useAnsiQuotes = true;
			} else {
				this.useAnsiQuotes = false;
			}
		}

        this.io.resetMaxBuf();
    }

	/**
     * Loads the mapping between MySQL character sets and
     * Java character sets
     */
    private static void loadCharacterSetMapping() {
        multibyteCharsetsMap = new HashMap();

        Iterator multibyteCharsets = CharsetMapping.MULTIBYTE_CHARSETS.keySet()
                                                                      .iterator();

        while (multibyteCharsets.hasNext()) {
            String charset = ((String) multibyteCharsets.next()).toUpperCase();
            multibyteCharsetsMap.put(charset, charset);
        }

        //
        // Now change all server encodings to upper-case to "future-proof"
        // this mapping
        //
        Iterator keys = CharsetMapping.CHARSETMAP.keySet().iterator();
        charsetMap = new HashMap();

        while (keys.hasNext()) {
            String mysqlCharsetName = ((String) keys.next()).trim();
            String javaCharsetName = CharsetMapping.CHARSETMAP.get(mysqlCharsetName)
                                                              .toString().trim();
            charsetMap.put(mysqlCharsetName.toUpperCase(), javaCharsetName);
            charsetMap.put(mysqlCharsetName, javaCharsetName);
        }
    }

    private boolean getUseUltraDevWorkAround() {
        return useUltraDevWorkAround;
    }

    private void checkClosed() throws SQLException {
        if (this.isClosed) {
            throw new SQLException("No operations allowed after connection closed",
                "08003");
        }
    }

    // *********************************************************************
    //
    //                END OF PUBLIC INTERFACE
    //
    // *********************************************************************

    /**
     *  Detect if the connection is still good
     */
    private void ping() throws Exception {
        if (this.useFastPing) {
            this.io.sendCommand(MysqlDefs.PING, null, null);
        } else {
            this.io.sqlQuery(PING_COMMAND, MysqlDefs.MAX_ROWS, this.encoding,
                this, java.sql.ResultSet.CONCUR_READ_ONLY, false, this.database);
        }
    }

    /**
     * Should we try to connect back to the master?
     *
     * We try when we've been failed over >= this.secondsBeforeRetryMaster
     * _or_ we've issued > this.queriesIssuedFailedOver
     */
    private boolean shouldFallBack() {
        long secondsSinceFailedOver = (System.currentTimeMillis()
            - this.masterFailTimeMillis) / 1000;

        return ((secondsSinceFailedOver >= this.secondsBeforeRetryMaster)
        || ((this.queriesIssuedFailedOver % this.queriesBeforeRetryMaster) == 0));
    }

    /**
     * Wrapper class for UltraDev CallableStatements that
     * are really PreparedStatments.
     *
     * Nice going, UltraDev developers.
     */
    class UltraDevWorkAround implements java.sql.CallableStatement {
        private java.sql.PreparedStatement delegate = null;

        UltraDevWorkAround(java.sql.PreparedStatement pstmt) {
            delegate = pstmt;
        }

        public void setArray(int p1, final java.sql.Array p2)
            throws java.sql.SQLException {
            delegate.setArray(p1, p2);
        }

        public java.sql.Array getArray(int p1) throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        /**
         * @see CallableStatement#getArray(String)
         */
        public java.sql.Array getArray(String arg0) throws SQLException {
            throw new NotImplemented();
        }

        public void setAsciiStream(int p1, final java.io.InputStream p2, int p3)
            throws java.sql.SQLException {
            delegate.setAsciiStream(p1, p2, p3);
        }

        /**
         * @see CallableStatement#setAsciiStream(String, InputStream, int)
         */
        public void setAsciiStream(String arg0, InputStream arg1, int arg2)
            throws SQLException {
            throw new NotImplemented();
        }

        public void setBigDecimal(int p1, final java.math.BigDecimal p2)
            throws java.sql.SQLException {
            delegate.setBigDecimal(p1, p2);
        }

        /**
         * @see CallableStatement#setBigDecimal(String, BigDecimal)
         */
        public void setBigDecimal(String arg0, BigDecimal arg1)
            throws SQLException {
            throw new NotImplemented();
        }

        public java.math.BigDecimal getBigDecimal(int p1)
            throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        public java.math.BigDecimal getBigDecimal(int p1, int p2)
            throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        /**
         * @see CallableStatement#getBigDecimal(String)
         */
        public BigDecimal getBigDecimal(String arg0) throws SQLException {
            return null;
        }

        public void setBinaryStream(int p1, final java.io.InputStream p2, int p3)
            throws java.sql.SQLException {
            delegate.setBinaryStream(p1, p2, p3);
        }

        /**
         * @see CallableStatement#setBinaryStream(String, InputStream, int)
         */
        public void setBinaryStream(String arg0, InputStream arg1, int arg2)
            throws SQLException {
            throw new NotImplemented();
        }

        public void setBlob(int p1, final java.sql.Blob p2)
            throws java.sql.SQLException {
            delegate.setBlob(p1, p2);
        }

        public java.sql.Blob getBlob(int p1) throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        /**
         * @see CallableStatement#getBlob(String)
         */
        public java.sql.Blob getBlob(String arg0) throws SQLException {
            throw new NotImplemented();
        }

        public void setBoolean(int p1, boolean p2) throws java.sql.SQLException {
            delegate.setBoolean(p1, p2);
        }

        /**
         * @see CallableStatement#setBoolean(String, boolean)
         */
        public void setBoolean(String arg0, boolean arg1)
            throws SQLException {
            throw new NotImplemented();
        }

        public boolean getBoolean(int p1) throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        /**
         * @see CallableStatement#getBoolean(String)
         */
        public boolean getBoolean(String arg0) throws SQLException {
            throw new NotImplemented();
        }

        public void setByte(int p1, byte p2) throws java.sql.SQLException {
            delegate.setByte(p1, p2);
        }

        /**
         * @see CallableStatement#setByte(String, byte)
         */
        public void setByte(String arg0, byte arg1) throws SQLException {
            throw new NotImplemented();
        }

        public byte getByte(int p1) throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        /**
         * @see CallableStatement#getByte(String)
         */
        public byte getByte(String arg0) throws SQLException {
            throw new NotImplemented();
        }

        public void setBytes(int p1, byte[] p2) throws java.sql.SQLException {
            delegate.setBytes(p1, p2);
        }

        /**
         * @see CallableStatement#setBytes(String, byte[])
         */
        public void setBytes(String arg0, byte[] arg1)
            throws SQLException {
            throw new NotImplemented();
        }

        public byte[] getBytes(int p1) throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        /**
         * @see CallableStatement#getBytes(String)
         */
        public byte[] getBytes(String arg0) throws SQLException {
            throw new NotImplemented();
        }

        public void setCharacterStream(int p1, final java.io.Reader p2, int p3)
            throws java.sql.SQLException {
            delegate.setCharacterStream(p1, p2, p3);
        }

        /**
         * @see CallableStatement#setCharacterStream(String, Reader, int)
         */
        public void setCharacterStream(String arg0, Reader arg1, int arg2)
            throws SQLException {
            throw new NotImplemented();
        }

        public void setClob(int p1, final java.sql.Clob p2)
            throws java.sql.SQLException {
            delegate.setClob(p1, p2);
        }

        public java.sql.Clob getClob(int p1) throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        /**
         * @see CallableStatement#getClob(String)
         */
        public Clob getClob(String arg0) throws SQLException {
            throw new NotImplemented();
        }

        public java.sql.Connection getConnection() throws java.sql.SQLException {
            return delegate.getConnection();
        }

        public void setCursorName(java.lang.String p1)
            throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        public void setDate(int p1, final java.sql.Date p2)
            throws java.sql.SQLException {
            delegate.setDate(p1, p2);
        }

        public void setDate(int p1, final java.sql.Date p2,
            final java.util.Calendar p3) throws java.sql.SQLException {
            delegate.setDate(p1, p2, p3);
        }

        /**
         * @see CallableStatement#setDate(String, Date, Calendar)
         */
        public void setDate(String arg0, Date arg1, Calendar arg2)
            throws SQLException {
            throw new NotImplemented();
        }

        /**
         * @see CallableStatement#setDate(String, Date)
         */
        public void setDate(String arg0, Date arg1) throws SQLException {
            throw new NotImplemented();
        }

        public java.sql.Date getDate(int p1) throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        public java.sql.Date getDate(int p1, final java.util.Calendar p2)
            throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        /**
         * @see CallableStatement#getDate(String, Calendar)
         */
        public Date getDate(String arg0, Calendar arg1)
            throws SQLException {
            throw new NotImplemented();
        }

        /**
         * @see CallableStatement#getDate(String)
         */
        public Date getDate(String arg0) throws SQLException {
            throw new NotImplemented();
        }

        public void setDouble(int p1, double p2) throws java.sql.SQLException {
            delegate.setDouble(p1, p2);
        }

        /**
         * @see CallableStatement#setDouble(String, double)
         */
        public void setDouble(String arg0, double arg1)
            throws SQLException {
            throw new NotImplemented();
        }

        public double getDouble(int p1) throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        /**
         * @see CallableStatement#getDouble(String)
         */
        public double getDouble(String arg0) throws SQLException {
            throw new NotImplemented();
        }

        public void setEscapeProcessing(boolean p1)
            throws java.sql.SQLException {
            delegate.setEscapeProcessing(p1);
        }

        public void setFetchDirection(int p1) throws java.sql.SQLException {
            delegate.setFetchDirection(p1);
        }

        public int getFetchDirection() throws java.sql.SQLException {
            return delegate.getFetchDirection();
        }

        public void setFetchSize(int p1) throws java.sql.SQLException {
            delegate.setFetchSize(p1);
        }

        public int getFetchSize() throws java.sql.SQLException {
            return delegate.getFetchSize();
        }

        public void setFloat(int p1, float p2) throws java.sql.SQLException {
            delegate.setFloat(p1, p2);
        }

        /**
         * @see CallableStatement#setFloat(String, float)
         */
        public void setFloat(String arg0, float arg1) throws SQLException {
            throw new NotImplemented();
        }

        public float getFloat(int p1) throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        /**
         * @see CallableStatement#getFloat(String)
         */
        public float getFloat(String arg0) throws SQLException {
            throw new NotImplemented();
        }

        /**
         * @see Statement#getGeneratedKeys()
         */
        public java.sql.ResultSet getGeneratedKeys() throws SQLException {
            return delegate.getGeneratedKeys();
        }

        public void setInt(int p1, int p2) throws java.sql.SQLException {
            delegate.setInt(p1, p2);
        }

        /**
         * @see CallableStatement#setInt(String, int)
         */
        public void setInt(String arg0, int arg1) throws SQLException {
            throw new NotImplemented();
        }

        public int getInt(int p1) throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        /**
         * @see CallableStatement#getInt(String)
         */
        public int getInt(String arg0) throws SQLException {
            throw new NotImplemented();
        }

        public void setLong(int p1, long p2) throws java.sql.SQLException {
            delegate.setLong(p1, p2);
        }

        /**
         * @see CallableStatement#setLong(String, long)
         */
        public void setLong(String arg0, long arg1) throws SQLException {
            throw new NotImplemented();
        }

        public long getLong(int p1) throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        /**
         * @see CallableStatement#getLong(String)
         */
        public long getLong(String arg0) throws SQLException {
            throw new NotImplemented();
        }

        public void setMaxFieldSize(int p1) throws java.sql.SQLException {
            delegate.setMaxFieldSize(p1);
        }

        public int getMaxFieldSize() throws java.sql.SQLException {
            return delegate.getMaxFieldSize();
        }

        public void setMaxRows(int p1) throws java.sql.SQLException {
            delegate.setMaxRows(p1);
        }

        public int getMaxRows() throws java.sql.SQLException {
            return delegate.getMaxRows();
        }

        public java.sql.ResultSetMetaData getMetaData()
            throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        public boolean getMoreResults() throws java.sql.SQLException {
            return delegate.getMoreResults();
        }

        /**
         * @see Statement#getMoreResults(int)
         */
        public boolean getMoreResults(int arg0) throws SQLException {
            return delegate.getMoreResults();
        }

        public void setNull(int p1, int p2) throws java.sql.SQLException {
            delegate.setNull(p1, p2);
        }

        public void setNull(int p1, int p2, java.lang.String p3)
            throws java.sql.SQLException {
            delegate.setNull(p1, p2, p3);
        }

        /**
         * @see CallableStatement#setNull(String, int, String)
         */
        public void setNull(String arg0, int arg1, String arg2)
            throws SQLException {
            throw new NotImplemented();
        }

        /**
         * @see CallableStatement#setNull(String, int)
         */
        public void setNull(String arg0, int arg1) throws SQLException {
            throw new NotImplemented();
        }

        public void setObject(int p1, final java.lang.Object p2)
            throws java.sql.SQLException {
            delegate.setObject(p1, p2);
        }

        public void setObject(int p1, final java.lang.Object p2, int p3)
            throws java.sql.SQLException {
            delegate.setObject(p1, p2, p3);
        }

        public void setObject(int p1, final java.lang.Object p2, int p3, int p4)
            throws java.sql.SQLException {
            delegate.setObject(p1, p2, p3, p4);
        }

        /**
         * @see CallableStatement#setObject(String, Object, int, int)
         */
        public void setObject(String arg0, Object arg1, int arg2, int arg3)
            throws SQLException {
            throw new NotImplemented();
        }

        /**
         * @see CallableStatement#setObject(String, Object, int)
         */
        public void setObject(String arg0, Object arg1, int arg2)
            throws SQLException {
            throw new NotImplemented();
        }

        /**
         * @see CallableStatement#setObject(String, Object)
         */
        public void setObject(String arg0, Object arg1)
            throws SQLException {
            throw new NotImplemented();
        }

        public java.lang.Object getObject(int p1) throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        public java.lang.Object getObject(int p1, final java.util.Map p2)
            throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        /**
         * @see CallableStatement#getObject(String, Map)
         */
        public Object getObject(String arg0, Map arg1)
            throws SQLException {
            throw new NotImplemented();
        }

        /**
         * @see CallableStatement#getObject(String)
         */
        public Object getObject(String arg0) throws SQLException {
            throw new NotImplemented();
        }

        /**
         * @see PreparedStatement#getParameterMetaData()
         */
        public ParameterMetaData getParameterMetaData()
            throws SQLException {
            return delegate.getParameterMetaData();
        }

        public void setQueryTimeout(int p1) throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        public int getQueryTimeout() throws java.sql.SQLException {
            return delegate.getQueryTimeout();
        }

        public void setRef(int p1, final java.sql.Ref p2)
            throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        public java.sql.Ref getRef(int p1) throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        /**
         * @see CallableStatement#getRef(String)
         */
        public Ref getRef(String arg0) throws SQLException {
            throw new NotImplemented();
        }

        public java.sql.ResultSet getResultSet() throws java.sql.SQLException {
            return delegate.getResultSet();
        }

        public int getResultSetConcurrency() throws java.sql.SQLException {
            return delegate.getResultSetConcurrency();
        }

        /**
         * @see Statement#getResultSetHoldability()
         */
        public int getResultSetHoldability() throws SQLException {
            return delegate.getResultSetHoldability();
        }

        public int getResultSetType() throws java.sql.SQLException {
            return delegate.getResultSetType();
        }

        public void setShort(int p1, short p2) throws java.sql.SQLException {
            delegate.setShort(p1, p2);
        }

        /**
         * @see CallableStatement#setShort(String, short)
         */
        public void setShort(String arg0, short arg1) throws SQLException {
            throw new NotImplemented();
        }

        public short getShort(int p1) throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        /**
         * @see CallableStatement#getShort(String)
         */
        public short getShort(String arg0) throws SQLException {
            throw new NotImplemented();
        }

        public void setString(int p1, java.lang.String p2)
            throws java.sql.SQLException {
            delegate.setString(p1, p2);
        }

        /**
         * @see CallableStatement#setString(String, String)
         */
        public void setString(String arg0, String arg1)
            throws SQLException {
            throw new NotImplemented();
        }

        public java.lang.String getString(int p1) throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        /**
         * @see CallableStatement#getString(String)
         */
        public String getString(String arg0) throws SQLException {
            throw new NotImplemented();
        }

        public void setTime(int p1, final java.sql.Time p2)
            throws java.sql.SQLException {
            delegate.setTime(p1, p2);
        }

        public void setTime(int p1, final java.sql.Time p2,
            final java.util.Calendar p3) throws java.sql.SQLException {
            delegate.setTime(p1, p2, p3);
        }

        /**
         * @see CallableStatement#setTime(String, Time, Calendar)
         */
        public void setTime(String arg0, Time arg1, Calendar arg2)
            throws SQLException {
            throw new NotImplemented();
        }

        /**
         * @see CallableStatement#setTime(String, Time)
         */
        public void setTime(String arg0, Time arg1) throws SQLException {
            throw new NotImplemented();
        }

        public java.sql.Time getTime(int p1) throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        public java.sql.Time getTime(int p1, final java.util.Calendar p2)
            throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        /**
         * @see CallableStatement#getTime(String, Calendar)
         */
        public Time getTime(String arg0, Calendar arg1)
            throws SQLException {
            throw new NotImplemented();
        }

        /**
         * @see CallableStatement#getTime(String)
         */
        public Time getTime(String arg0) throws SQLException {
            throw new NotImplemented();
        }

        public void setTimestamp(int p1, final java.sql.Timestamp p2)
            throws java.sql.SQLException {
            delegate.setTimestamp(p1, p2);
        }

        public void setTimestamp(int p1, final java.sql.Timestamp p2,
            final java.util.Calendar p3) throws java.sql.SQLException {
            delegate.setTimestamp(p1, p2, p3);
        }

        /**
         * @see CallableStatement#setTimestamp(String, Timestamp, Calendar)
         */
        public void setTimestamp(String arg0, Timestamp arg1, Calendar arg2)
            throws SQLException {
            throw new NotImplemented();
        }

        /**
         * @see CallableStatement#setTimestamp(String, Timestamp)
         */
        public void setTimestamp(String arg0, Timestamp arg1)
            throws SQLException {
            throw new NotImplemented();
        }

        public java.sql.Timestamp getTimestamp(int p1)
            throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        public java.sql.Timestamp getTimestamp(int p1,
            final java.util.Calendar p2) throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        /**
         * @see CallableStatement#getTimestamp(String, Calendar)
         */
        public Timestamp getTimestamp(String arg0, Calendar arg1)
            throws SQLException {
            throw new NotImplemented();
        }

        /**
         * @see CallableStatement#getTimestamp(String)
         */
        public Timestamp getTimestamp(String arg0) throws SQLException {
            throw new NotImplemented();
        }

        /**
         * @see CallableStatement#setURL(String, URL)
         */
        public void setURL(String arg0, URL arg1) throws SQLException {
            throw new NotImplemented();
        }

        /**
         * @see PreparedStatement#setURL(int, URL)
         */
        public void setURL(int arg0, URL arg1) throws SQLException {
            delegate.setURL(arg0, arg1);
        }

        /**
         * @see CallableStatement#getURL(int)
         */
        public URL getURL(int arg0) throws SQLException {
            throw new NotImplemented();
        }

        /**
         * @see CallableStatement#getURL(String)
         */
        public URL getURL(String arg0) throws SQLException {
            throw new NotImplemented();
        }

        public void setUnicodeStream(int p1, final java.io.InputStream p2,
            int p3) throws java.sql.SQLException {
            delegate.setUnicodeStream(p1, p2, p3);
        }

        public int getUpdateCount() throws java.sql.SQLException {
            return delegate.getUpdateCount();
        }

        public java.sql.SQLWarning getWarnings() throws java.sql.SQLException {
            return delegate.getWarnings();
        }

        public void addBatch() throws java.sql.SQLException {
            delegate.addBatch();
        }

        public void addBatch(java.lang.String p1) throws java.sql.SQLException {
            delegate.addBatch(p1);
        }

        public void cancel() throws java.sql.SQLException {
            delegate.cancel();
        }

        public void clearBatch() throws java.sql.SQLException {
            delegate.clearBatch();
        }

        public void clearParameters() throws java.sql.SQLException {
            delegate.clearParameters();
        }

        public void clearWarnings() throws java.sql.SQLException {
            delegate.clearWarnings();
        }

        public void close() throws java.sql.SQLException {
            delegate.close();
        }

        public boolean execute() throws java.sql.SQLException {
            return delegate.execute();
        }

        public boolean execute(java.lang.String p1)
            throws java.sql.SQLException {
            return delegate.execute(p1);
        }

        /**
         * @see Statement#execute(String, int)
         */
        public boolean execute(String arg0, int arg1) throws SQLException {
            return delegate.execute(arg0, arg1);
        }

        /**
         * @see Statement#execute(String, int[])
         */
        public boolean execute(String arg0, int[] arg1)
            throws SQLException {
            return delegate.execute(arg0, arg1);
        }

        /**
         * @see Statement#execute(String, String[])
         */
        public boolean execute(String arg0, String[] arg1)
            throws SQLException {
            return delegate.execute(arg0, arg1);
        }

        public int[] executeBatch() throws java.sql.SQLException {
            return delegate.executeBatch();
        }

        public java.sql.ResultSet executeQuery() throws java.sql.SQLException {
            return delegate.executeQuery();
        }

        public java.sql.ResultSet executeQuery(java.lang.String p1)
            throws java.sql.SQLException {
            return delegate.executeQuery(p1);
        }

        public int executeUpdate() throws java.sql.SQLException {
            return delegate.executeUpdate();
        }

        public int executeUpdate(java.lang.String p1)
            throws java.sql.SQLException {
            return delegate.executeUpdate(p1);
        }

        /**
         * @see Statement#executeUpdate(String, int)
         */
        public int executeUpdate(String arg0, int arg1)
            throws SQLException {
            return delegate.executeUpdate(arg0, arg1);
        }

        /**
         * @see Statement#executeUpdate(String, int[])
         */
        public int executeUpdate(String arg0, int[] arg1)
            throws SQLException {
            return delegate.executeUpdate(arg0, arg1);
        }

        /**
         * @see Statement#executeUpdate(String, String[])
         */
        public int executeUpdate(String arg0, String[] arg1)
            throws SQLException {
            return delegate.executeUpdate(arg0, arg1);
        }

        public void registerOutParameter(int p1, int p2)
            throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        public void registerOutParameter(int p1, int p2, int p3)
            throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        public void registerOutParameter(int p1, int p2, java.lang.String p3)
            throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }

        /**
         * @see CallableStatement#registerOutParameter(String, int, int)
         */
        public void registerOutParameter(String arg0, int arg1, int arg2)
            throws SQLException {
            throw new NotImplemented();
        }

        /**
         * @see CallableStatement#registerOutParameter(String, int, String)
         */
        public void registerOutParameter(String arg0, int arg1, String arg2)
            throws SQLException {
            throw new NotImplemented();
        }

        /**
         * @see CallableStatement#registerOutParameter(String, int)
         */
        public void registerOutParameter(String arg0, int arg1)
            throws SQLException {
            throw new NotImplemented();
        }

        public boolean wasNull() throws java.sql.SQLException {
            throw new SQLException("Not supported");
        }
    }
}
