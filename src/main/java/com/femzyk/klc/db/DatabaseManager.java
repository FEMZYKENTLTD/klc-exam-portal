package com.femzyk.klc.db;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Properties;

/**
 * Database Manager - KLC CBT Suite v1.0
 *
 * PERFORMANCE FIXES (root cause of "app is very slow"):
 *
 * 1. POOLER FIRST (Rule 8): the old code tried the blocked direct port
 *    5432 FIRST on startup AND on every 30-second retry - an 8 second
 *    dead wait each time before falling through to the pooler. Direct
 *    is now only attempted if no pooler URL is configured at all.
 *
 * 2. CONNECTION REUSE: the old code opened a BRAND NEW TCP + TLS
 *    connection to Supabase (Ireland) for every single query -
 *    300-800ms of network handshake per query, and each admin screen
 *    runs 3-5 queries. Cloud connections are now kept in a small
 *    reuse pool (max 4) and handed back via a proxy on close().
 *    Screens now reuse warm connections = near-instant queries.
 *
 * 3. Login timeout cut from 8s to 4s so a genuine outage degrades to
 *    H2 twice as fast.
 *
 * ALL existing behaviour preserved: H2 fallback, 30s cloud retry,
 * isCloudAvailable(), getCacheConnection(), sync table bootstrap.
 */
public class DatabaseManager {

    private static String directUrl,  directUser,  directPass;
    private static String poolerUrl,  poolerUser,  poolerPass;
    private static String h2Url,      h2User,      h2Pass;

    private static volatile boolean cloudAvailable = false;
    private static volatile boolean usePooler      = false;
    private static volatile long    lastCloudCheck = 0;
    private static final    long    RETRY_MS       = 30_000;

    // ── Simple cloud connection reuse pool ──────────────────────────────
    private static final int POOL_MAX = 4;
    private static final Deque<Connection> pool = new ArrayDeque<>();

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

            // FIX Rule 8: POOLER FIRST - port 5432 is blocked by network.
            // Direct is only a fallback when no pooler is configured.
            if (poolerUrl != null && !poolerUrl.isBlank()) {
                System.out.println("[DB] Testing pooler connection (port 6543)...");
                if (tryConnect(poolerUrl, poolerUser, poolerPass)) {
                    cloudAvailable = true;
                    usePooler      = true;
                    System.out.println("[DB] Pooler connection OK");
                }
            }
            if (!cloudAvailable && directUrl != null && !directUrl.isBlank()) {
                System.out.println("[DB] Pooler unavailable. Trying direct (port 5432)...");
                if (tryConnect(directUrl, directUser, directPass)) {
                    cloudAvailable = true;
                    usePooler      = false;
                    System.out.println("[DB] Direct connection OK");
                }
            }
            if (!cloudAvailable) {
                System.out.println(
                    "[DB] Cloud unavailable. Using H2 offline cache.");
            }

            lastCloudCheck = System.currentTimeMillis();
            ensureH2Sync();

        } catch (Exception e) {
            System.err.println("[DB] Init error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // =========================================================================
    //  TEST A CONNECTION - 4 second timeout, no exception thrown
    // =========================================================================
    private static boolean tryConnect(String url, String user, String pass) {
        if (url == null || url.isBlank() ||
            user == null || user.isBlank()) return false;
        try {
            DriverManager.setLoginTimeout(4);
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
    //  RAW CLOUD CONNECTION (new physical connection - internal use)
    // =========================================================================
    private static Connection newCloudConnection() throws Exception {
        DriverManager.setLoginTimeout(4);
        if (usePooler) {
            return DriverManager.getConnection(poolerUrl, poolerUser, poolerPass);
        }
        return DriverManager.getConnection(directUrl, directUser, directPass);
    }

    /** Kept for callers that need a dedicated physical connection. */
    public static Connection getCloudConnection() throws Exception {
        return newCloudConnection();
    }

    // =========================================================================
    //  GET H2 CONNECTION
    // =========================================================================
    public static Connection getCacheConnection() throws Exception {
        return DriverManager.getConnection(h2Url, h2User, h2Pass);
    }

    // =========================================================================
    //  POOL: borrow / return
    // =========================================================================
    private static Connection borrowPooled() throws Exception {
        synchronized (pool) {
            while (!pool.isEmpty()) {
                Connection c = pool.pollFirst();
                try {
                    if (c != null && !c.isClosed() && c.isValid(2)) {
                        return wrap(c);
                    }
                    if (c != null) try { c.close(); } catch (Exception ignored) {}
                } catch (Exception ignored) {
                    if (c != null) try { c.close(); } catch (Exception ignored2) {}
                }
            }
        }
        return wrap(newCloudConnection());
    }

    private static void returnToPool(Connection real) {
        synchronized (pool) {
            try {
                if (real != null && !real.isClosed()
                        && cloudAvailable && pool.size() < POOL_MAX) {
                    // Reset state defensively before reuse
                    try { real.setAutoCommit(true); } catch (Exception ignored) {}
                    pool.addLast(real);
                    return;
                }
            } catch (Exception ignored) {}
            try { if (real != null) real.close(); } catch (Exception ignored) {}
        }
    }

    /** Discard all pooled connections (used when cloud drops). */
    private static void drainPool() {
        synchronized (pool) {
            while (!pool.isEmpty()) {
                Connection c = pool.pollFirst();
                try { if (c != null) c.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Wraps a physical connection so that close() returns it to the pool
     * instead of destroying it. All existing try-with-resources code in
     * every controller keeps working unchanged.
     */
    private static Connection wrap(Connection real) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
            DatabaseManager.class.getClassLoader(),
            new Class<?>[]{ Connection.class },
            (proxy, method, args) -> {
                if ("close".equals(method.getName())) {
                    returnToPool(real);
                    return null;
                }
                if ("isClosed".equals(method.getName())) {
                    return real.isClosed();
                }
                try {
                    return method.invoke(real, args);
                } catch (java.lang.reflect.InvocationTargetException ite) {
                    throw ite.getCause() != null ? ite.getCause() : ite;
                }
            });
    }

    // =========================================================================
    //  GET CONNECTION - pooled cloud, smart fallback, 30-second retry
    // =========================================================================
    public static Connection getConnection() throws Exception {

        if (cloudAvailable) {
            try {
                return borrowPooled();
            } catch (Exception e) {
                cloudAvailable = false;
                lastCloudCheck = System.currentTimeMillis();
                drainPool();
                System.out.println(
                    "[DB] Cloud lost, switching to H2: " + e.getMessage());
            }
        }

        // Retry cloud every 30 seconds - POOLER FIRST (Rule 8)
        long now = System.currentTimeMillis();
        if ((now - lastCloudCheck) > RETRY_MS) {
            System.out.println("[DB] Retrying cloud...");
            if (poolerUrl != null && !poolerUrl.isBlank()
                    && tryConnect(poolerUrl, poolerUser, poolerPass)) {
                cloudAvailable = true;
                usePooler      = true;
                System.out.println("[DB] Pooler reconnected!");
                try { return borrowPooled(); }
                catch (Exception e) { cloudAvailable = false; drainPool(); }
            } else if (directUrl != null && !directUrl.isBlank()
                    && tryConnect(directUrl, directUser, directPass)) {
                cloudAvailable = true;
                usePooler      = false;
                System.out.println("[DB] Direct reconnected!");
                try { return borrowPooled(); }
                catch (Exception e) { cloudAvailable = false; drainPool(); }
            }
            lastCloudCheck = System.currentTimeMillis();
        }

        return getCacheConnection();
    }

    public static boolean isCloudAvailable()           { return cloudAvailable; }
    public static void    setCloudAvailable(boolean v) {
        cloudAvailable = v;
        if (!v) drainPool();
    }
}
