package com.femzyk.klc.admin;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import com.opencsv.CSVReader;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;

import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.UUID;
import at.favre.lib.crypto.bcrypt.BCrypt;

/**
 * StudentManagerController - KLC CBT Suite v1.0
 *
 * RULE 11 REVIEW of your pasted version - fixes:
 * 1. RULE 5: NINE raw ps.setObject(UUID.fromString(...)) binds - ALL
 *    crash in H2 offline mode. Every one replaced with
 *    AuthService.setUuid (importCsv x2, regeneratePin x3, toggleFee,
 *    resetPassword).
 * 2. importCsv did not insert an id for users (relies on DB default -
 *    fine on PostgreSQL, but H2 offline insert then referenced the
 *    Java-side userId that was never stored) - id now set explicitly.
 * 3. toggleFee had dead duplicated logic (first assignment discarded) -
 *    cleaned to one clear PAID -> UNPAID -> PART -> PAID cycle.
 * 4. Session no longer hardcoded - reads school_profile.session_current.
 * FEATURE CONFIRMED: the Result PIN column you requested already exists
 * (colPin) and remains; students see their own PIN in the dashboard
 * PIN bar (already live).
 * All features preserved: CSV import with auto admission/PIN/password,
 * regenerate PIN with collision suffix, fee toggle, password reset.
 */
public class StudentManagerController {
    @FXML private TableView<StudentRow> table;
    @FXML private TableColumn<StudentRow, String> colAdm, colName, colClass, colArm, colPin, colFee;
    @FXML private Label status;

    public static class StudentRow {
        String userId, admission, name, classLevel, arm, pin, fee;
        StudentRow(String uid, String a, String n, String c, String ar, String p, String f){
            userId=uid;admission=a;name=n;classLevel=c;arm=ar;pin=p;fee=f;}
        public String getAdmission(){return admission;}
        public String getName(){return name;}
        public String getClassLevel(){return classLevel;}
        public String getArm(){return arm;}
        public String getPin(){return pin;}
        public String getFee(){return fee;}
    }
    ObservableList<StudentRow> data = FXCollections.observableArrayList();

    @FXML public void initialize(){
        colAdm.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getAdmission()));
        colName.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getName()));
        colClass.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getClassLevel()));
        colArm.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getArm()));
        colPin.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getPin()));
        colFee.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getFee()));
        table.setItems(data);
        load();
    }

    private String currentSession(Connection c) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT session_current FROM school_profile LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                String s = rs.getString(1);
                if (s != null && !s.isBlank()) return s;
            }
        } catch (Exception ignored) {}
        return "2024/2025";
    }

    @FXML private void load(){
        data.clear();
        try(Connection c=DatabaseManager.getConnection();
            PreparedStatement ps=c.prepareStatement(
                "SELECT u.id, sp.admission_no, u.full_name, sp.class_level, " +
                "sp.arm, sp.result_pin, sp.fee_status " +
                "FROM student_profiles sp JOIN users u ON u.id=sp.user_id " +
                "ORDER BY sp.class_level, sp.admission_no LIMIT 800")){
            ResultSet rs=ps.executeQuery();
            while(rs.next()) data.add(new StudentRow(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7)));
            status.setText("Loaded "+data.size()+" students");
        }catch(Exception e){ status.setText(e.getMessage());}
    }

    @FXML private void importCsv(){
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV","*.csv"));
        var file = fc.showOpenDialog(table.getScene().getWindow());
        if(file==null) return;
        int imported=0, skipped=0;
        try(Connection c=DatabaseManager.getConnection();
            CSVReader r=new CSVReader(new FileReader(file, StandardCharsets.UTF_8))){
            c.setAutoCommit(false);
            try {
                String session = currentSession(c);
                String[] header = r.readNext();
                String[] row;
                while((row = r.readNext()) != null){
                    if(row.length < 5) continue;
                    String fullName = get(row, header, "full_name",0);
                    String email = get(row, header, "email",1);
                    String admission = get(row, header, "admission_no",2);
                    String surname = get(row, header, "surname",3);
                    String classLevel = get(row, header, "class_level",4);
                    String arm = get(row, header, "arm",5);
                    String gender = get(row, header, "gender",6);
                    String parentPhone = get(row, header, "parent_phone",7);

                    if(email.isBlank())
                        email = (admission.toLowerCase().replace("/","."))
                              + "@student.knowledgeland.edu.ng";
                    if(admission.isBlank()){
                        try(PreparedStatement ps=c.prepareStatement(
                                "SELECT COUNT(*) FROM student_profiles WHERE class_level=?")){
                            ps.setString(1, classLevel);
                            ResultSet rs=ps.executeQuery(); rs.next();
                            admission = "KLC/"+classLevel+"/"+
                                String.format("%03d", rs.getInt(1)+1);
                        }
                    }
                    if(surname.isBlank()) surname = fullName.split(" ")[0];
                    String pin = surname.toUpperCase().replaceAll("\\s+","") + classLevel;

                    try(PreparedStatement ps=c.prepareStatement(
                            "SELECT 1 FROM student_profiles WHERE admission_no=?")){
                        ps.setString(1, admission);
                        if(ps.executeQuery().next()){ skipped++; continue; }
                    }
                    String userId = UUID.randomUUID().toString();
                    String hash = BCrypt.withDefaults()
                        .hashToString(12, "Student123".toCharArray());
                    try(PreparedStatement ps=c.prepareStatement(
                            "INSERT INTO users(id, full_name, email, password_hash, role) " +
                            "VALUES(?,?,?,?,?)")){
                        AuthService.setUuid(ps, 1, userId, c);          // FIX Rule 5
                        ps.setString(2, fullName);
                        ps.setString(3, email.toLowerCase());
                        ps.setString(4, hash);
                        ps.setString(5, "STUDENT");
                        ps.executeUpdate();
                    }
                    try(PreparedStatement ps=c.prepareStatement(
                            "INSERT INTO student_profiles(id, user_id, admission_no, surname, " +
                            "other_names, class_level, arm, session, gender, parent_phone, " +
                            "result_pin) VALUES(?,?,?,?,?,?,?,?,?,?,?)")){
                        AuthService.setUuid(ps, 1, UUID.randomUUID().toString(), c);
                        AuthService.setUuid(ps, 2, userId, c);          // FIX Rule 5
                        ps.setString(3, admission);
                        ps.setString(4, surname);
                        ps.setString(5, fullName);
                        ps.setString(6, classLevel);
                        ps.setString(7, arm.isBlank()?null:arm);
                        ps.setString(8, session);                       // FIX: not hardcoded
                        ps.setString(9, gender.isBlank()?null:gender);
                        ps.setString(10, parentPhone.isBlank()?null:parentPhone);
                        ps.setString(11, pin);
                        ps.executeUpdate();
                    }
                    imported++;
                }
                c.commit();
            } catch (Exception inner) {
                c.rollback();
                throw inner;
            } finally {
                c.setAutoCommit(true);
            }
            AuthService.logAudit("STUDENT_CSV_IMPORT","student_profiles", null);
            status.setText("Imported "+imported+" students, skipped "+skipped+
                " duplicates. Default password: Student123");
            load();
        }catch(Exception e){
            status.setText("Import error: "+e.getMessage());
            e.printStackTrace();
        }
    }

    private String get(String[] row, String[] header, String key, int fallback){
        if(header!=null){
            for(int i=0;i<header.length && i<row.length;i++)
                if(header[i].trim().equalsIgnoreCase(key)) return row[i].trim();
        }
        return fallback < row.length ? row[fallback].trim() : "";
    }

    @FXML private void regeneratePin(){
        StudentRow r = table.getSelectionModel().getSelectedItem();
        if(r==null){ status.setText("Select a student"); return; }
        try(Connection c=DatabaseManager.getConnection()){
            String surname="KLC";
            try(PreparedStatement ps=c.prepareStatement(
                    "SELECT surname, class_level FROM student_profiles WHERE user_id=?")){
                AuthService.setUuid(ps, 1, r.userId, c);               // FIX Rule 5
                ResultSet rs=ps.executeQuery();
                if(rs.next()) surname = rs.getString(1);
            }
            String pin = surname.toUpperCase().replaceAll("\\s+","") + r.classLevel;
            try(PreparedStatement ps=c.prepareStatement(
                    "SELECT 1 FROM student_profiles WHERE result_pin=? AND user_id<>?")){
                ps.setString(1, pin);
                AuthService.setUuid(ps, 2, r.userId, c);               // FIX Rule 5
                if(ps.executeQuery().next()){
                    String digits = r.admission.replaceAll("\\D","");
                    pin = pin + digits.substring(Math.max(0, digits.length()-3));
                }
            }
            try(PreparedStatement ps=c.prepareStatement(
                    "UPDATE student_profiles SET result_pin=? WHERE user_id=?")){
                ps.setString(1, pin);
                AuthService.setUuid(ps, 2, r.userId, c);               // FIX Rule 5
                ps.executeUpdate();
            }
            AuthService.logAudit("PIN_REGENERATE", "student_profiles", r.userId);
            status.setText("New PIN for "+r.name+": "+pin);
            load();
        }catch(Exception e){ status.setText(e.getMessage());}
    }

    @FXML private void toggleFee(){
        StudentRow r = table.getSelectionModel().getSelectedItem();
        if(r==null){ status.setText("Select a student"); return; }
        // Clean cycle: PAID -> UNPAID -> PART -> PAID
        String next;
        if ("PAID".equals(r.fee))        next = "UNPAID";
        else if ("UNPAID".equals(r.fee)) next = "PART";
        else                             next = "PAID";
        try(Connection c=DatabaseManager.getConnection();
            PreparedStatement ps=c.prepareStatement(
                "UPDATE student_profiles SET fee_status=? WHERE user_id=?")){
            ps.setString(1, next);
            AuthService.setUuid(ps, 2, r.userId, c);                   // FIX Rule 5
            ps.executeUpdate();
            AuthService.logAudit("FEE_TOGGLE", "student_profiles", r.userId);
            status.setText(r.name+" fee status: "+next);
            load();
        }catch(Exception e){ status.setText(e.getMessage());}
    }

    @FXML private void resetPassword(){
        StudentRow r = table.getSelectionModel().getSelectedItem();
        if(r==null){ status.setText("Select a student"); return; }
        try(Connection c=DatabaseManager.getConnection();
            PreparedStatement ps=c.prepareStatement(
                "UPDATE users SET password_hash=? WHERE id=?")){
            String hash = BCrypt.withDefaults()
                .hashToString(12, "Student123".toCharArray());
            ps.setString(1, hash);
            AuthService.setUuid(ps, 2, r.userId, c);                   // FIX Rule 5
            ps.executeUpdate();
            AuthService.logAudit("PASSWORD_RESET", "users", r.userId);
            status.setText("Password reset to Student123 for "+r.name);
        }catch(Exception e){ status.setText(e.getMessage());}
    }
}
