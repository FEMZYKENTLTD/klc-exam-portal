package com.femzyk.klc.db;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

/**
 * Database Manager - KLC CBT Suite v6.3
 * Strategy:
 *   1. Try direct PostgreSQL (port 5432) with user=postgres
 *   2. Try Supabase pooler (port 6543) with user=postgres.PROJECT_REF
 *   3. Fall back to H2 local cache
 * Auto-retries cloud every 30 seconds.
 */
public class DatabaseManager {

    private static String directUrl,  directUser,  directPass;
    private static String poolerUrl,  poolerUser,  poolerPass;
    private static String h2Url,      h2User,      h2Pass;

    private static volatile boolean cloudAvailable = false;
    private static volatile boolean usePooler      = false;
    private static volatile long    lastCloudCheck = 0;
    private static final    long    RETRY_MS       = 30_000;

    // =========================================================================
    //  INIT
    // =========================================================================
    public static void init() {
        try {
            Properties p = new Properties();
            try (InputStream in = DatabaseManager.class
                    .getResourceAsStream("/config.properties")) {
                if (in != null) p.load(in);
                else System.err.println("[DB] config.properties not found!");
            }

            directUrl  = p.getProperty("supabase.db.url",  "");
            directUser = p.getProperty("supabase.db.user", "postgres");
            directPass = p.getProperty("supabase.db.password", "");

            poolerUrl  = p.getProperty("supabase.db.pooler.url",  "");
            poolerUser = p.getProperty("supabase.db.pooler.user", "");
            poolerPass = p.getProperty("supabase.db.pooler.password",
                         directPass);

            h2Url  = p.getProperty("h2.url",
                "jdbc:h2:file:./klc_cache/klc_local;" +
                "AUTO_SERVER=TRUE;" +
                "CASE_INSENSITIVE_IDENTIFIERS=TRUE;" +
                "MODE=PostgreSQL");
            h2User = p.getProperty("h2.user",     "sa");
            h2Pass = p.getProperty("h2.password", "");

            // Try connections at startup
            System.out.println("[DB] Testing direct connection (port 5432)...");
            if (tryConnect(directUrl, directUser, directPass)) {
                cloudAvailable = true;
                usePooler      = false;
                System.out.println("[DB] Direct connection OK");
            } else {
                System.out.println(
                    "[DB] Direct failed. Trying pooler (port 6543)...");
                if (tryConnect(poolerUrl, poolerUser, poolerPass)) {
                    cloudAvailable = true;
                    usePooler      = true;
                    System.out.println("[DB] Pooler connection OK");
                } else {
                    System.out.println(
                        "[DB] Both cloud connections failed." +
                        " Using H2 offline cache.");
                }
            }

            lastCloudCheck = System.currentTimeMillis();
            ensureH2Sync();

        } catch (Exception e) {
            System.err.println("[DB] Init error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // =========================================================================
    //  TEST A CONNECTION - 8 second timeout, no exception thrown
    // =========================================================================
    private static boolean tryConnect(String url, String user, String pass) {
        if (url == null || url.isBlank() ||
            user == null || user.isBlank()) return false;
        try {
            DriverManager.setLoginTimeout(8);
            Connection c = DriverManager.getConnection(url, user, pass);
            c.close();
            return true;
        } catch (Exception e) {
            System.out.println("[DB] Connection test failed: " + e.getMessage());
            return false;
        }
    }

    // =========================================================================
    //  ENSURE H2 SYNC TABLE EXISTS
    // =========================================================================
    private static void ensureH2Sync() {
        try (Connection h2 = DriverManager.getConnection(
                h2Url, h2User, h2Pass)) {
            h2.createStatement().execute(
                "CREATE TABLE IF NOT EXISTS sync_queue (" +
                "  id VARCHAR(60) PRIMARY KEY," +
                "  table_name VARCHAR(60)," +
                "  record_id  VARCHAR(60)," +
                "  operation  VARCHAR(10)," +
                "  payload    CLOB," +
                "  synced     BOOLEAN DEFAULT FALSE)");
            System.out.println("[DB] H2 cache ready");
        } catch (Exception e) {
            System.err.println("[DB] H2 init error: " + e.getMessage());
        }
    }

    // =========================================================================
    //  GET CLOUD CONNECTION
    // =========================================================================
    public static Connection getCloudConnection() throws Exception {
        DriverManager.setLoginTimeout(8);
        if (usePooler) {
            return DriverManager.getConnection(poolerUrl, poolerUser, poolerPass);
        }
        return DriverManager.getConnection(directUrl, directUser, directPass);
    }

    // =========================================================================
    //  GET H2 CONNECTION
    // =========================================================================
    public static Connection getCacheConnection() throws Exception {
        return DriverManager.getConnection(h2Url, h2User, h2Pass);
    }

    // =========================================================================
    //  GET CONNECTION - smart fallback with 30-second retry
    // =========================================================================
    public static Connection getConnection() throws Exception {

        if (cloudAvailable) {
            try {
                return getCloudConnection();
            } catch (Exception e) {
                cloudAvailable = false;
                lastCloudCheck = System.currentTimeMillis();
                System.out.println(
                    "[DB] Cloud lost, switching to H2: " + e.getMessage());
            }
        }

        // Retry cloud every 30 seconds
        long now = System.currentTimeMillis();
        if ((now - lastCloudCheck) > RETRY_MS) {
            System.out.println("[DB] Retrying cloud...");
            if (tryConnect(directUrl, directUser, directPass)) {
                cloudAvailable = true;
                usePooler      = false;
                System.out.println("[DB] Direct reconnected!");
                try { return getCloudConnection(); }
                catch (Exception e) { cloudAvailable = false; }
            } else if (tryConnect(poolerUrl, poolerUser, poolerPass)) {
                cloudAvailable = true;
                usePooler      = true;
                System.out.println("[DB] Pooler reconnected!");
                try { return getCloudConnection(); }
                catch (Exception e) { cloudAvailable = false; }
            }
            lastCloudCheck = System.currentTimeMillis();
        }

        return getCacheConnection();
    }

    public static boolean isCloudAvailable()           { return cloudAvailable; }
    public static void    setCloudAvailable(boolean v) { cloudAvailable = v;    }
}