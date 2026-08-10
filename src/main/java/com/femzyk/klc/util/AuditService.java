package com.femzyk.klc.util;

import com.femzyk.klc.db.DatabaseManager;
import java.sql.*;
import java.util.UUID;

public class AuditService {

    public static void log(String userId, String action, String entityType, String entityId, String details) {
        try (Connection c = DatabaseManager.getConnection()) {
            PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_logs (id, user_id, action, entity_type, entity_id, details, created_at) " +
                "VALUES (?,?,?,?,?,?,NOW())");

            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, userId != null ? UUID.fromString(userId) : null);
            ps.setString(3, action);
            ps.setString(4, entityType);
            ps.setObject(5, entityId != null ? UUID.fromString(entityId) : null);
            ps.setString(6, details);
            ps.executeUpdate();

        } catch (Exception e) {
            System.err.println("[AuditService] Failed to log action: " + e.getMessage());
        }
    }

    public static void log(String userId, String action, String entityType, String details) {
        log(userId, action, entityType, null, details);
    }
}