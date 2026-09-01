package com.femzyk.klc.util;

import com.femzyk.klc.db.DatabaseManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

public class ConfigService {
    private static String currentSession = "2024/2025";
    private static String currentTerm = "1st";
    private static Map<String,Object> gradingCache = null;
    private static java.util.Properties appProps = null;

    static { reload(); }

    /**
     * KLC v1.0: app-level boolean flags from config.properties.
     * Used for safeguarding switches (social.allow_student_staff_dm,
     * social.allow_student_attachments). Defaults are the SAFE values,
     * so a deployment with no config gets the restricted behaviour.
     */
    public static boolean flag(String key, boolean def) {
        loadProps();
        String v = appProps.getProperty(key);
        if (v == null || v.isBlank()) return def;
        return v.trim().equalsIgnoreCase("true")
            || v.trim().equalsIgnoreCase("yes")
            || v.trim().equals("1");
    }

    /**
     * KLC v1.0: string settings from config.properties (supabase.url,
     * supabase.key, ...). No secrets are hardcoded in source any more.
     */
    public static String get(String key, String def) {
        loadProps();
        String v = appProps.getProperty(key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    private static synchronized void loadProps() {
        if (appProps != null) return;
        java.util.Properties p = new java.util.Properties();
        try (java.io.InputStream in =
                 ConfigService.class.getResourceAsStream(
                     "/config.properties")) {
            if (in != null) p.load(in);
        } catch (Exception ignored) {}
        appProps = p;
    }

    public static void reload(){
        try(Connection c = DatabaseManager.getConnection();
            PreparedStatement ps = c.prepareStatement("SELECT session_current, term_current FROM school_profile LIMIT 1")){
            ResultSet rs = ps.executeQuery();
            if(rs.next()){
                currentSession = rs.getString(1) == null ? "2024/2025" : rs.getString(1);
                currentTerm = rs.getString(2) == null ? "1st" : rs.getString(2);
            }
        }catch(Exception ignored){}
    }
    public static String getSession(){ return currentSession; }
    public static String getTerm(){ return currentTerm; }
}
