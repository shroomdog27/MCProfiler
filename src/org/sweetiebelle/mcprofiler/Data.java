package org.sweetiebelle.mcprofiler;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.json.simple.parser.ParseException;
import org.sweetiebelle.mcprofiler.NamesFetcher.Response;

/**
 * This class handles data transfer to and from the SQL server.
 *
 */
class Data {
    private Connection connection;
    private final MCProfilerPlugin p;
    private final Settings s;
    private final ArrayUtils au;

    Data(final MCProfilerPlugin p, final Settings s) {
        this.s = s;
        this.p = p;
        au = new ArrayUtils();
        createTables();
    }

    /**
     * Adds the note to the player
     * @param pUUID the user
     * @param pStaffName the staff who added it
     * @param pNote the note
     */
    void addNoteToUser(final UUID pUUID, final String pStaffName, final String pNote) {
        try {
            executeQuery("INSERT INTO " + s.dbPrefix + "notes (uuid, time, lastKnownStaffName, note) VALUES (\"" + pUUID.toString() + "\", NULL, \"" + pStaffName + "\", \"" + pNote + "\");");
        } catch (final SQLException e) {
            error(e);
        }
    }

    /**
     * Creates a new table in the database
     * @param pQuery the query
     * @return
     */
    private boolean createTable(final String pQuery) {
        try {
            executeQuery(pQuery);
            return true;
        } catch (final SQLException e) {
            error(e);
        }
        return false;
    }

    /**
     * Create tables if needed
     */
    private final void createTables() {
        // Generate the information about the various tables
        final String notes = "CREATE TABLE " + s.dbPrefix + "notes (noteid INT NOT NULL AUTO_INCREMENT PRIMARY KEY, uuid VARCHAR(36) NOT NULL, time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, lastKnownStaffName VARCHAR(16) NOT NULL, note VARCHAR(255) NOT NULL)";
        final String profiles = "CREATE TABLE " + s.dbPrefix + "profiles (profileid INT NOT NULL AUTO_INCREMENT PRIMARY KEY, uuid VARCHAR(36) NOT NULL, lastKnownName VARCHAR(16) NOT NULL, ip VARCHAR(39), laston TIMESTAMP, lastpos VARCHAR(75))";
        final String iplog = "CREATE TABLE " + s.dbPrefix + "iplog (ipid INT NOT NULL AUTO_INCREMENT PRIMARY KEY, ip VARCHAR(36) NOT NULL, uuid VARCHAR(36) NOT NULL)";
        // Generate the database tables
        if (!tableExists(s.dbPrefix + "profiles"))
            createTable(profiles);
        if (!tableExists(s.dbPrefix + "iplog"))
            createTable(iplog);
        if (!tableExists(s.dbPrefix + "notes"))
            createTable(notes);
    }

    /**
     * Method used to handle errors
     *
     * @param e Exception
     */
    void error(final Throwable e) {
        if (s.stackTraces) {
            e.printStackTrace();
            return;
        }
        if (e instanceof SQLException) {
            p.getLogger().severe("SQLException: " + e.getMessage());
            return;
        }
        if (e instanceof IllegalArgumentException)
            // It was probably someone not putting in a valid UUID, so we can ignore.
            // p.getLogger().severe("IllegalArgumentException: " + e.getMessage());
            return;
        if (e instanceof NoDataException) {
            // If true, then it was caused by data in Account not being found.
            if (e.getMessage().equals("Set was empty."))
                return;
            p.getLogger().severe("NoDataException: " + e.getMessage());
            return;
        }
        if (e instanceof NoClassDefFoundError)
            // Handle Plugins not found.
            // p.getLogger().severe("NoClassDefFoundError: " + e.getMessage());
            return;
        if (e instanceof IOException) {
            p.getLogger().severe("IOException: " + e.getMessage());
            return;
        }
        if (e instanceof ParseException) {
            p.getLogger().severe("ParseException: " + e.getMessage());
            return;
        }
        // Or e.getCause();
        p.getLogger().severe("Unhandled Exception " + e.getClass().getName() + ": " + e.getMessage());
        e.printStackTrace();
    }

    /**
     * Executes an SQL query. Throws an exception to allow for other methods to handle it.
     * @param query the query to execute
     * @return number of rows affected
     * @throws SQLException if an error occurred
     */
    private int executeQuery(final String query) throws SQLException {
        if (s.showQuery)
            p.getLogger().info(query);
        if (connection.isClosed() || connection == null) {
            final String connect = new String("jdbc:mysql://" + s.dbHost + ":" + s.dbPort + "/" + s.dbDatabase);
            connection = DriverManager.getConnection(connect, s.dbUser, s.dbPass);
            p.getLogger().info("Connecting to " + s.dbUser + "@" + connect + "...");
        }
        return connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE).executeUpdate(query);
    }

    /**
     * Private method for getting an SQL connection, then submitting a query. This method throws an SQL Exception to allow another method to handle it.
     * @param query the query to get data from.
     * @return the data
     * @throws SQLException if an error occurs
     */
    private ResultSet getResultSet(final String query) throws SQLException {
        if (s.showQuery)
            p.getLogger().info(query);
        if (connection == null || connection.isClosed()) {
            final String connect = new String("jdbc:mysql://" + s.dbHost + ":" + s.dbPort + "/" + s.dbDatabase);
            connection = DriverManager.getConnection(connect, s.dbUser, s.dbPass);
            p.getLogger().info("Connecting to " + s.dbUser + "@" + connect + "...");
        }
        return connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE).executeQuery(query);
    }

    /**
     * Forces a refresh of the connection object
     */
    void forceConnectionRefresh() {
        try {
            if (connection.isClosed()) {
                final String connect = new String("jdbc:mysql://" + s.dbHost + ":" + s.dbPort + "/" + s.dbDatabase);
                connection = DriverManager.getConnection(connect, s.dbUser, s.dbPass);
                p.getLogger().info("Connecting to " + s.dbUser + "@" + connect + "...");
            } else {
                connection.close();
                final String connect = new String("jdbc:mysql://" + s.dbHost + ":" + s.dbPort + "/" + s.dbDatabase);
                connection = DriverManager.getConnection(connect, s.dbUser, s.dbPass);
                p.getLogger().info("Connecting to " + s.dbUser + "@" + connect + "...");
            }
        } catch (final SQLException e) {
            error(e);
        }
    }

    /**
     * Gets an account from a player name.
     *
     * @param playername player's name
     * @return an {@link Account} if it exists, or null
     * @throws NoDataException if no such account exists
     * 
     */
    Account getAccount(String name, final boolean needsLastTime) {
        UUID uuid = null;
        String laston = null;
        String location = null;
        String ip = null;
        String[] notes = null;
        Response[] names = null;
        ResultSet rs;
        final String query = "SELECT * FROM " + s.dbPrefix + "profiles where lastKnownName = \"" + name + "\";";
        // Get Basic player information.
        try {
            rs = getResultSet(query);
            if (rs.next()) {
                final String stringuuuid = rs.getString("uuid");
                if (stringuuuid == null || stringuuuid.equals("") || stringuuuid.equalsIgnoreCase("null"))
                    return null;
                uuid = UUID.fromString(rs.getString("uuid"));
                name = rs.getString("lastKnownName");
                laston = rs.getString("laston");
                ip = rs.getString("ip");
                location = rs.getString("lastpos");
            } else
                throw new NoDataException();
        } catch (SQLException | IllegalArgumentException | NoDataException e) {
            error(e);
            return null;
        }
        // Get the notes
        try {
            rs = getResultSet("SELECT * FROM " + s.dbPrefix + "notes where UUID = \"" + uuid.toString() + "\";");
            final List<String> nList = new LinkedList<String>();
            while (rs.next()) {
                final String staffName = rs.getString("lastKnownStaffName");
                nList.add("�c" + rs.getString("time") + " �f" + rs.getString("note") + " �c" + staffName);
            }
            // Convert the list to an array
            if (nList.size() == 0)
                throw new NoDataException();
            notes = new String[nList.size()];
            final Iterator<String> it = nList.iterator();
            for (int i = 0; i < nList.size(); i++)
                notes[i] = it.next();
        } catch (SQLException | NoDataException e) {
            error(e);
            if (e instanceof NoDataException)
                notes = new String[] { "�cNo notes were found" };
        }
        // Get their associated names.
        try {
            if (needsLastTime)
                names = NamesFetcher.getPreviousNames(uuid);
        } catch (final IOException e) {
            error(e);
        }
        return new Account(uuid, name, laston, location, ip, notes, names);
    }

    /**
     * Gets an account from a UUID
     *
     * @param uuid player's UUID
     * @return an {@link Account} if it exists, or null
     * @throws NoDataException if no such account exists
     */
    Account getAccount(final UUID uuid, final boolean needsLastTime) {
        String name = null;
        String laston = null;
        String location = null;
        String ip = null;
        String[] notes = null;
        Response[] names = null;
        ResultSet rs;
        try {
            rs = getResultSet("SELECT * FROM " + s.dbPrefix + "profiles where UUID = \"" + uuid.toString() + "\";");
            if (rs.next()) {
                name = rs.getString("lastKnownName");
                laston = rs.getString("laston");
                ip = rs.getString("ip");
                location = rs.getString("lastpos");
            } else {
                rs.close();
                throw new NoDataException();
            }
            rs.close();
        } catch (SQLException | NoDataException e) {
            error(e);
            if (e instanceof NoDataException)
                return null;
        }
        try {
            rs = getResultSet("SELECT * FROM " + s.dbPrefix + "notes where UUID = \"" + uuid.toString() + "\";");
            final List<String> nList = new LinkedList<String>();
            while (rs.next()) {
                final String staffName = rs.getString("lastKnownStaffName");
                nList.add("�c" + rs.getString("time") + " �f" + rs.getString("note") + " �c" + staffName);
            }
            // Convert the list to an array
            if (nList.size() == 0)
                throw new NoDataException();
            notes = new String[nList.size()];
            final Iterator<String> it = nList.iterator();
            for (int i = 0; i < nList.size(); i++)
                notes[i] = it.next();
        } catch (SQLException | NoDataException e) {
            error(e);
            if (e instanceof NoDataException)
                notes = new String[] { "�cNo notes were found" };
        }
        try {
            if (needsLastTime)
                names = NamesFetcher.getPreviousNames(uuid);
            names = NamesFetcher.getPreviousNames(uuid);
        } catch (final IOException e) {
            error(e);
        }
        return new Account(uuid, name, laston, location, ip, notes, names);
    }

    /**
     * Returns a String of all the UUIDs, comma separated, of the player's UUID given.
     *
     * @param pUUID Player UUID
     * @param isRecursive Is the search recursive?
     * @return the alt accounts of the player, or null if an error occurs.
     */
    AltAccount[] getAltsOfPlayer(final UUID pUUID, final boolean isRecursive) {
        ResultSet rs;
        AltAccount[] map = new AltAccount[65536];
        try {
            rs = getResultSet("SELECT * FROM " + s.dbPrefix + "iplog WHERE uuid = \"" + pUUID.toString() + "\";");
            while (rs.next()) {
                final ResultSet ipSet = getResultSet("SELECT * FROM " + s.dbPrefix + "iplog WHERE ip = \"" + rs.getString("ip") + "\";");
                while (ipSet.next()) {
                    map = (AltAccount[]) au.append(map, new AltAccount(UUID.fromString(ipSet.getString("uuid")), ipSet.getString("ip")));
                }
                ipSet.close();
            }
            rs.close();
            if (isRecursive)
                return recursivePlayerSearch(map);
            return map;
        } catch (final SQLException e) {
            error(e);
            return null;
        }
    }

    /**
     * Does a recursive player search with the given params
     *
     * @param uuidToIP A MultiMap associating UUIDs with IPs
     * @return A array of {@link AltAccounts} containing all the alts.
     * @throws SQLException if an error occurs, to allow the method that calls this function to catch it.
     */
    private AltAccount[] recursivePlayerSearch(AltAccount[] map) throws SQLException {
        int finalContinue = 0;
        ResultSet uuidSet;
        ResultSet ipSet;
        boolean ipSetComplete = true;
        boolean uuidSetComplete = true;
        for (int i = 0; i < au.actualSize(map); i++) {
            final AltAccount a = map[i];
            final UUID uuid = a.uuid;
            uuidSet = getResultSet("SELECT * FROM " + s.dbPrefix + "iplog WHERE uuid = \"" + uuid.toString() + "\";");
            while (uuidSet.next()) {
                final UUID uuidUUID = UUID.fromString(uuidSet.getString("uuid"));
                final String uuidIP = uuidSet.getString("ip");
                final AltAccount uuidAlt = new AltAccount(uuidUUID, uuidIP);
                if (au.containsElement(map, uuidAlt))
                    continue;
                uuidSetComplete = false;
                map = (AltAccount[]) au.append(map, uuidAlt);
                ipSet = getResultSet("SELECT * FROM " + s.dbPrefix + "iplog WHERE ip = \"" + uuidIP + "\";");
                while (ipSet.next()) {
                    final AltAccount ipAlt = new AltAccount(UUID.fromString(ipSet.getString("uuid")), ipSet.getString("ip"));
                    if (au.containsElement(map, ipAlt))
                        continue;
                    ipSetComplete = false;
                    map = (AltAccount[]) au.append(map, ipAlt);
                }
            }
            if (uuidSetComplete && ipSetComplete) {
                finalContinue++;
                if (finalContinue == au.actualSize(map)) {
                    return map;
                }
                continue;
            }
        }
        return (AltAccount[]) au.appendAll(map, recursivePlayerSearch(map));
    }

    /**
     * Get a String of IPs from an account
     * @param a the account
     * @return the IP string, separated by commas, or null if an error occurs.
     */
    String getIPsByPlayer(final Account a) {
        try {
            final ResultSet rs = getResultSet("SELECT * FROM " + s.dbPrefix + "iplog WHERE uuid = \"" + a.getUUID().toString() + "\";");
            String ips = "";
            while (rs.next())
                ips += rs.getString("ip") + ",";
            rs.close();
            return ips;
        } catch (final SQLException e) {
            error(e);
            return null;
        }
    }

    /**
     * Gets an IP string of UUIDs separated by commands from one IP.
     * @param pIP the IP
     * @return the string or null if an error occurs.
     */
    String getUsersAssociatedWithIP(final String pIP) {
        try {
            final ResultSet rs = getResultSet("SELECT * FROM " + s.dbPrefix + "iplog WHERE ip = \"" + pIP + "\";");
            String allUUIDs = "";
            // Find all the uuids linked to the specific ip
            while (rs.next())
                allUUIDs += rs.getString("uuid") + ",";
            rs.close();
            return allUUIDs;
        } catch (final SQLException e) {
            error(e);
        }
        return null;
    }

    /**
     * Checks if a string is "null"
     * @param string the string to check
     * @return true if the string is equal to null, the string is empty, the string is "null", or if the string is ",", else false.
     */
    boolean isNull(final String string) {
        if (string == null || string.isEmpty() || string.equalsIgnoreCase("null") || string.equalsIgnoreCase(","))
            return true;
        return false;
    }

    /**
     * Performs the maintenance query from the CommandSupplement.
     * @param query the query to execute
     * @return the number of rows affected, or -1 if an error occures.
     */
    int maintenance(final String query) {
        try {
            return connection.createStatement().executeUpdate(query);
        } catch (final SQLException e) {
            error(e);
            return -1;
        }
    }

    /**
     * Sets the player's location
     * @param pUUID the player's UUID
     * @param pLocation their location
     */
    void setPlayerLastPosition(final UUID pUUID, final Location pLocation) {
        // Need valid data
        if (pUUID == null || pLocation == null)
            return;
        // Build a string of the location
        final int x = pLocation.getBlockX();
        final int y = pLocation.getBlockY();
        final int z = pLocation.getBlockZ();
        final String location = Integer.toString(x) + "," + Integer.toString(y) + "," + Integer.toString(z) + ":" + pLocation.getWorld().getName();
        // Make sure that the data for the player is valid, if the player has logged on before
        try {
            executeQuery("UPDATE " + s.dbPrefix + "profiles SET lastpos = \"" + location + "\" WHERE uuid = \"" + pUUID.toString() + "\";");
        } catch (final SQLException e) {
            error(e);
        }
    }

    /**
     * Stores the player's IP
     * @param pUUID the player's uuid
     * @param pIP their ip
     */
    void storePlayerIP(final UUID pUUID, final String pIP) {
        ResultSet rs;
        try {
            rs = getResultSet("SELECT * FROM " + s.dbPrefix + "iplog WHERE ip = \"" + pIP + "\" AND uuid = \"" + pUUID.toString() + "\";");
            if (rs.next())
                return;
            // Having multiple UUIDs and IPs in an SQL table is THE WHOLE POINT in an sql table!
            executeQuery("INSERT INTO " + s.dbPrefix + "iplog (uuid, ip) VALUES (\"" + pUUID.toString() + "\", \"" + pIP + "\");");
            rs.close();
        } catch (final SQLException e) {
            error(e);
        }
    }

    /**
     * checks if a table exists
     * @param pTable the table name.
     * @return true if the table exists, or false if either the table does not exists, or another error occurs.
     */
    private boolean tableExists(final String pTable) {
        try {
            return getResultSet("SELECT * FROM " + pTable) != null;
        } catch (final SQLException e) {
            if (e.getMessage().equals("Table '" + s.dbDatabase + "." + s.dbPrefix + pTable + "' doesn't exist") || e.getMessage().equals("Table \"" + s.dbDatabase + "." + s.dbPrefix + pTable + "\" doesn't exist") || e.getMessage().equals("Table '" + s.dbDatabase + "." + s.dbPrefix + pTable + "' doesn't exist"))
                return false;
            error(e);
        }
        return false;
    }

    /**
     * Update general player information.
     * @param pUUID Their UUID
     * @param pName their Name
     * @param pIP Their IP
     */
    void updatePlayerInformation(final UUID pUUID, final String pName, final String pIP) {
        ResultSet rs;
        try {
            rs = getResultSet("SELECT * FROM " + s.dbPrefix + "profiles WHERE uuid = \"" + pUUID.toString() + "\" LIMIT 1;");
            if (rs.next()) {
                executeQuery("UPDATE " + s.dbPrefix + "profiles SET lastKnownName = \"" + pName + "\" WHERE uuid = \"" + pUUID.toString() + "\";");
                executeQuery("UPDATE " + s.dbPrefix + "profiles SET ip = \"" + pIP + "\" WHERE uuid = \"" + pUUID.toString() + "\";");
            } else
                executeQuery("INSERT INTO " + s.dbPrefix + "profiles (uuid, lastKnownName, ip, laston, lastpos) VALUES (\"" + pUUID.toString() + "\", \"" + pName + "\", \"" + pIP + "\", NULL, NULL);");
        } catch (final SQLException e) {
            error(e);
        }
    }
}
