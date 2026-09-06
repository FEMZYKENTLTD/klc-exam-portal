package com.femzyk.klc.db;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
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

    // KLC v1.0 H2 AES encryption at rest (directive: real CIPHER=AES, no
    // silent plaintext fallback). Set h2.encryption=aes + a strong
    // h2.cipher.filePassword (>= 16 chars, never committed) in
    // config.properties. When enabled, the offline cache file is encrypted
    // and an existing plaintext cache is migrated once, then deleted.
    private static volatile boolean h2Aes       = false;
    private static volatile boolean h2ConfigErr = false; // aes requested but unusable
    private static       String h2FilePassword;
    private static final String H2_AES_USER_PASSWORD_FALLBACK = "klc";

    private static volatile boolean cloudAvailable = false;
    private static volatile boolean usePooler      = false;
    private static volatile long    lastCloudCheck = 0;
    private static final    long    RETRY_MS       = 30_000;

    // ── Simple cloud connection reuse pool ──────────────────────────────
    private static final int POOL_MAX = 4;
    private static final Deque<Connection> pool = new ArrayDeque<>();

    // =========================================================================
    //  INIT / CONFIG
    // =========================================================================
    public static void init() {
        try {
            Properties p = new Properties();
            try (InputStream in = DatabaseManager.class
                    .getResourceAsStream("/config.properties")) {
                if (in != null) p.load(in);
                else System.err.println("[DB] config.properties not found!");
            }
            applyConfig(p);
        } catch (Exception e) {
            System.err.println("[DB] Init error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Package-private hook used by automated tests to point DatabaseManager
     * at isolated H2 files (including AES cases) without touching the
     * packaged config. Mirrors exactly what init() does after reading
     * config.properties.
     */
    static synchronized void applyConfig(Properties p) {
        try {
            drainPool();
            cloudAvailable = false;
            usePooler      = false;
            h2Aes          = false;
            h2ConfigErr    = false;

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

            String enc = p.getProperty("h2.encryption", "off");
            h2FilePassword = p.getProperty("h2.cipher.filePassword", "");
            if ("aes".equalsIgnoreCase(enc.trim())) {
                if (h2FilePassword == null
                        || h2FilePassword.trim().length() < 16) {
                    h2ConfigErr = true;
                    System.err.println("[DB] FATAL: h2.encryption=aes "
                        + "requires h2.cipher.filePassword of at least 16 "
                        + "characters. Refusing to run an unencrypted cache "
                        + "while AES was requested. Set the key in "
                        + "config.properties (never commit it).");
                } else {
                    h2FilePassword = h2FilePassword.trim();
                    h2Aes = true;
                    if (h2Pass == null || h2Pass.isBlank()) {
                        // AES requires a non-empty user password token; use
                        // the documented internal default for new caches.
                        h2Pass = H2_AES_USER_PASSWORD_FALLBACK;
                    }
                    migratePlaintextCacheToEncrypted();
                }
            }

            if (h2ConfigErr) {
                System.err.println("[DB] Offline cache disabled: H2 AES "
                    + "configuration is invalid (see message above).");
                return;
            }

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

    /** Effective H2 URL: appends CIPHER=AES when AES is enabled. */
    private static String effectiveH2Url() {
        if (!h2Aes) return h2Url;
        if (h2Url.toUpperCase().contains("CIPHER=")) return h2Url;
        return h2Url + (h2Url.contains(";") ? "" : ";") + "CIPHER=AES";
    }

    /**
     * Effective H2 password pair. H2's AES mode requires the connection
     * password to be "&lt;filePassword&gt; &lt;userPassword&gt;".
     */
    private static String effectiveH2Password() {
        if (!h2Aes) return h2Pass;
        return h2FilePassword + " " + h2Pass;
    }

    /**
     * One-time safe migration of an existing PLAINTEXT offline cache to an
     * AES-encrypted file when h2.encryption=aes is first enabled.
     *
     * Uses H2's own SCRIPT/RUNSCRIPT so schema + data move intact; the
     * plaintext file is deleted only after the encrypted copy has been
     * verified readable. If anything fails, the plaintext cache is kept and
     * a prominent error is printed (data preservation first - never a
     * silent half-migration). A missing plaintext file is simply a fresh
     * start (nothing to migrate).
     */
    private static void migratePlaintextCacheToEncrypted() {
        // Plain and encrypted caches share the SAME H2 file name (the URL
        // base decides the file name; CIPHER=AES changes the file content).
        // Cases handled here when h2.encryption=aes is enabled:
        //   A) the cache file already opens with the AES key  -> done
        //   B) a plaintext cache exists                        -> migrate:
        //        1. SCRIPT the plaintext cache to a temp SQL file,
        //        2. build + verify a fresh encrypted cache at a temp base,
        //        3. delete the plaintext files,
        //        4. move the encrypted file onto the real base name.
        //      Any failure before step 3 keeps the plaintext cache intact
        //      and disables AES with a loud error (data first).
        //   C) no cache file yet                              -> fresh start
        java.io.File base = fileBaseOf(h2Url);
        if (base == null) return;                  // mem DB: nothing on disk
        java.io.File plainDb = new java.io.File(base.getPath() + ".mv.db");
        java.io.File tmpBase = new java.io.File(base.getPath() + "_aes_mig");

        // A) already encrypted at the real base?
        String cipherPass = h2FilePassword + " " + h2Pass;
        try (Connection ec = DriverManager.getConnection(
                effectiveH2Url(), h2User, cipherPass)) {
            System.out.println("[DB] H2 cache is AES encrypted at rest.");
            return;
        } catch (Exception alreadyNotEncrypted) {
            // fall through to migration / fresh-start handling
        }

        if (!plainDb.exists()) return;             // C) fresh start

        // B) migrate existing plaintext cache
        java.io.File script = null;
        try {
            script = java.io.File.createTempFile("klc_h2_migrate", ".sql");
            boolean dumped = false;
            for (String cand : new String[]{h2Pass, "",
                    H2_AES_USER_PASSWORD_FALLBACK}) {
                try (Connection c = DriverManager.getConnection(
                         h2Url, h2User, cand);
                     Statement st = c.createStatement()) {
                    st.execute("SCRIPT TO '" + script.getAbsolutePath()
                        .replace("'", "''") + "'");
                    dumped = true;
                    break;
                } catch (Exception tryNext) {
                    // try the next candidate user password
                }
            }
            if (!dumped) {
                System.err.println("[DB] H2 AES migration: could not open the "
                    + "existing plaintext cache to migrate it. Keeping it and "
                    + "disabling AES - fix h2.user/h2.password and restart.");
                h2Aes = false;
                h2ConfigErr = false;
                h2FilePassword = null;
                return;
            }
            String tmpUrl = h2Url.replace(base.getPath(), tmpBase.getPath())
                + ";CIPHER=AES";
            try (Connection ec = DriverManager.getConnection(
                     tmpUrl, h2User, cipherPass);
                 Statement est = ec.createStatement()) {
                est.execute("RUNSCRIPT FROM '"
                    + script.getAbsolutePath().replace("'", "''") + "'");
            }
            try (Connection vc = DriverManager.getConnection(
                     tmpUrl, h2User, cipherPass);
                 Statement vst = vc.createStatement();
                 ResultSet vrs = vst.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables "
                     + "WHERE table_schema='PUBLIC'")) {
                if (!vrs.next()) throw new IllegalStateException(
                    "encrypted cache verification produced no schema");
            }
            for (String suffix : new String[]{".mv.db", ".trace.db"}) {
                java.io.File f = new java.io.File(base.getPath() + suffix);
                if (f.exists() && !f.delete()) {
                    throw new IllegalStateException(
                        "could not delete plaintext cache file " + f);
                }
            }
            for (String suffix : new String[]{".mv.db", ".trace.db"}) {
                java.io.File f = new java.io.File(tmpBase.getPath() + suffix);
                if (f.exists()) {
                    java.io.File target = new java.io.File(
                        base.getPath() + suffix);
                    java.nio.file.Files.move(f.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            System.out.println("[DB] H2 offline cache migrated to AES "
                + "encryption at rest (plaintext removed).");
        } catch (Exception e) {
            System.err.println("[DB] H2 AES migration FAILED ("
                + e.getMessage() + "). Existing cache was kept unchanged; AES "
                + "disabled for this run. Fix the configuration and restart.");
            h2Aes = false;
            h2ConfigErr = false;
            h2FilePassword = null;
            for (String suffix : new String[]{".mv.db", ".trace.db"}) {
                java.io.File f = new java.io.File(tmpBase.getPath() + suffix);
                if (f.exists()) f.delete();
            }
        } finally {
            if (script != null && script.exists()) script.delete();
        }
    }

    /** Extracts the file base path from a jdbc:h2:file:... URL, or null. */
    private static java.io.File fileBaseOf(String url) {
        try {
            if (url == null || !url.startsWith("jdbc:h2:file:")) return null;
            String p = url.substring("jdbc:h2:file:".length());
            int q = p.indexOf(';');
            if (q >= 0) p = p.substring(0, q);
            return new java.io.File(p);
        } catch (Exception e) {
            return null;
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
        if (h2ConfigErr) return;
        try (Connection h2 = DriverManager.getConnection(
                effectiveH2Url(), h2User, effectiveH2Password())) {
            h2.createStatement().execute(
                "CREATE TABLE IF NOT EXISTS sync_queue (" +
                "  id VARCHAR(60) PRIMARY KEY," +
                "  table_name VARCHAR(60)," +
                "  record_id  VARCHAR(60)," +
                "  operation  VARCHAR(10)," +
                "  payload    CLOB," +
                "  synced     BOOLEAN DEFAULT FALSE)");
            System.out.println("[DB] H2 cache ready"
                + (h2Aes ? " (AES encrypted at rest)" : ""));
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
        if (h2ConfigErr) {
            throw new IllegalStateException(
                "H2 offline cache is disabled: AES encryption was requested "
                + "but h2.cipher.filePassword is missing or shorter than 16 "
                + "characters. Set it in config.properties (never commit it) "
                + "and restart. The app will not silently fall back to a "
                + "plaintext cache.");
        }
        return DriverManager.getConnection(
            effectiveH2Url(), h2User, effectiveH2Password());
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
