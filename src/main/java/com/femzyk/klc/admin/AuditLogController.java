package com.femzyk.klc.admin;

import com.femzyk.klc.db.DatabaseManager;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;

import java.io.FileWriter;
import java.sql.*;

public class AuditLogController {

    @FXML private TableView<LogRow>              table;
    @FXML private TableColumn<LogRow, String>    colTime, colUser, colEmail,
                                                  colRole, colAction,
                                                  colEntity, colDetails;
    @FXML private Label status;

    public static class LogRow {
        String time, user, email, role, action, entity, details;

        LogRow(String t, String u, String em, String r,
               String a, String e, String d) {
            time = t; user = u; email = em; role = r;
            action = a; entity = e; details = d;
        }

        public String getTime()    { return time; }
        public String getUser()    { return user; }
        public String getEmail()   { return email == null ? "-" : email; }
        public String getRole()    { return role  == null ? "-" : role; }
        public String getAction()  { return action; }
        public String getEntity()  { return entity == null ? "-" : entity; }
        public String getDetails() { return details == null ? "" : details; }
    }

    ObservableList<LogRow> data = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        colTime.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getTime()));
        colUser.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getUser()));

        // New columns
        if (colEmail != null)
            colEmail.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(
                    c.getValue().getEmail()));
        if (colRole != null)
            colRole.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(
                    c.getValue().getRole()));

        colAction.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getAction()));
        colEntity.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getEntity()));
        colDetails.setCellValueFactory(c ->
            new javafx.beans.property.SimpleStringProperty(
                c.getValue().getDetails()));

        table.setItems(data);
        load();
    }

    @FXML
    private void load() {
        data.clear();
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT al.created_at, " +
                 "COALESCE(u.full_name, 'SYSTEM') AS user_name, " +
                 "u.email, " +
                 "u.role, " +
                 "al.action, " +
                 "al.entity_type, " +
                 "al.details " +
                 "FROM audit_logs al " +
                 "LEFT JOIN users u ON u.id = al.user_id " +
                 "ORDER BY al.created_at DESC LIMIT 2000")) {

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                data.add(new LogRow(
                    rs.getTimestamp(1).toLocalDateTime()
                       .toString().replace('T', ' '),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6) == null ? "-" : rs.getString(6),
                    rs.getString(7)
                ));
            }
            status.setText("Audit Log - " + data.size() +
                " entries - Immutable WORM");

        } catch (Exception e) {
            status.setText("Error: " + e.getMessage());
        }
    }

    @FXML
    private void exportCsv() {
        try {
            FileChooser fc = new FileChooser();
            fc.setInitialFileName(
                "KLC_audit_log_" + java.time.LocalDate.now() + ".csv");
            fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV","*.csv"));
            var f = fc.showSaveDialog(table.getScene().getWindow());
            if (f == null) return;

            try (FileWriter w = new FileWriter(f)) {
                w.write("Time,User,Email,Role,Action,Entity,Details\n");
                for (LogRow r : data) {
                    w.write(
                        csv(r.time)    + "," +
                        csv(r.user)    + "," +
                        csv(r.email)   + "," +
                        csv(r.role)    + "," +
                        csv(r.action)  + "," +
                        csv(r.entity)  + "," +
                        csv(r.details) + "\n");
                }
            }
            status.setText("Exported: " + f.getName() +
                " - " + data.size() + " rows");
            new Alert(Alert.AlertType.INFORMATION,
                "Audit log exported:\n" + f.getAbsolutePath()).show();

        } catch (Exception e) {
            status.setText("Export error: " + e.getMessage());
        }
    }

    private String csv(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\"","\"\"") + "\"";
    }
}