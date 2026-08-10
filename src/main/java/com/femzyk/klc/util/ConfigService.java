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

    static { reload(); }

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
