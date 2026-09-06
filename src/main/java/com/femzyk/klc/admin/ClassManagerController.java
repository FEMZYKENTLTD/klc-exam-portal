package com.femzyk.klc.admin;

import com.femzyk.klc.auth.AuthService;
import com.femzyk.klc.db.DatabaseManager;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import java.sql.*;

public class ClassManagerController {
    @FXML private TableView<ClassRow> classTable;
    @FXML private TableColumn<ClassRow,String> colClassLevel, colArm, colSession, colTeacher;
    @FXML private ComboBox<String> cClassLevel, cArm, cSession;
    @FXML private TextField cTeacherEmail;
    @FXML private Label cStatus;
    @FXML private TextField sessionNameField;
    @FXML private ComboBox<String> termCurrentBox;
    @FXML private Label academicStatus;

    public static class ClassRow {
        String id, classLevel, arm, session, teacher;
        ClassRow(String id, String cl, String arm, String s, String t){this.id=id; classLevel=cl; this.arm=arm; session=s; teacher=t;}
        public String getClassLevel(){return classLevel;}
        public String getArm(){return arm==null?"":arm;}
        public String getSession(){return session==null?"":session;}
        public String getTeacher(){return teacher==null?"-":teacher;}
    }
    ObservableList<ClassRow> data = FXCollections.observableArrayList();

    @FXML public void initialize(){
        colClassLevel.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getClassLevel()));
        colArm.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getArm()));
        colSession.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getSession()));
        colTeacher.setCellValueFactory(c-> new javafx.beans.property.SimpleStringProperty(c.getValue().getTeacher()));
        classTable.setItems(data);
        cClassLevel.getItems().addAll("JSS1","JSS2","JSS3","SS1","SS2","SS3");
        cArm.getItems().addAll("A","B","C","Science","Art","Commercial");
        cSession.getItems().addAll("2024/2025","2025/2026","2026/2027","2027/2028","2028/2029","2029/2030");
        cSession.setValue("2024/2025");
        termCurrentBox.getItems().addAll("1st","2nd","3rd");
        loadClasses(); loadAcademic();
    }
    @FXML private void loadClasses(){
        data.clear();
        try(Connection conn = DatabaseManager.getConnection();
            PreparedStatement ps = conn.prepareStatement("""
                SELECT sc.id, sc.class_level, sc.arm, sc.session, u.full_name
                FROM school_classes sc LEFT JOIN users u ON u.id=sc.class_teacher_id
                ORDER BY sc.session DESC, sc.class_level, sc.arm
            """)){
            ResultSet rs = ps.executeQuery();
            while(rs.next()) data.add(new ClassRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
            cStatus.setText("Loaded "+data.size()+" class/arm records - JSS1-SS3 fully managed");
        }catch(Exception e){ cStatus.setText(e.getMessage()); }
    }
    @FXML private void addClass(){
        if(cClassLevel.getValue()==null){ cStatus.setText("Select Class Level"); return; }
        try(Connection conn = DatabaseManager.getConnection()){
            String teacherId = null;
            if(cTeacherEmail.getText()!=null && !cTeacherEmail.getText().isBlank()){
                try(PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE email=?")){
                    ps.setString(1, cTeacherEmail.getText().toLowerCase());
                    ResultSet rs = ps.executeQuery();
                    if(rs.next()) teacherId = rs.getString(1);
                }
            }
            try(PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO school_classes(id, class_level, arm, session, class_teacher_id) VALUES(?,?,?,?,?)")){
                ps.setObject(1, java.util.UUID.randomUUID());
                ps.setString(2, cClassLevel.getValue());
                ps.setString(3, cArm.getValue());
                ps.setString(4, cSession.getValue());
                ps.setObject(5, teacherId==null?null:java.util.UUID.fromString(teacherId));
                ps.executeUpdate();
            }
            cStatus.setText("Class/Arm added: "+cClassLevel.getValue()+" "+(cArm.getValue()==null?"":cArm.getValue())+" - "+cSession.getValue());
            loadClasses();
            AuthService.logAudit("CLASS_ADD","school_classes",null);
        }catch(Exception e){
            cStatus.setText("Error (duplicate Class/Arm/Session?): "+e.getMessage());
        }
    }
    @FXML private void deleteClass(){
        ClassRow r = classTable.getSelectionModel().getSelectedItem();
        if(r==null){ cStatus.setText("Select a class"); return; }
        try(Connection conn = DatabaseManager.getConnection();
            PreparedStatement ps = conn.prepareStatement("DELETE FROM school_classes WHERE id=?")){
            ps.setObject(1, java.util.UUID.fromString(r.id)); ps.executeUpdate();
            loadClasses();
        }catch(Exception e){ cStatus.setText(e.getMessage());}
    }
    private void loadAcademic(){
        try(Connection c=DatabaseManager.getConnection();
            PreparedStatement ps=c.prepareStatement("SELECT session_current, term_current FROM school_profile LIMIT 1")){
            ResultSet rs=ps.executeQuery();
            if(rs.next()){
                if(sessionNameField!=null){
                    sessionNameField.setText(rs.getString(1));
                    if(!cSession.getItems().contains(rs.getString(1))) cSession.getItems().add(rs.getString(1));
                }
                if(termCurrentBox!=null) termCurrentBox.setValue(rs.getString(2));
            }
        }catch(Exception ignored){}
    }
    @FXML private void saveAcademic(){
        String session = sessionNameField.getText();
        String term = termCurrentBox.getValue();
        if(session==null || session.isBlank()){ academicStatus.setText("Enter session e.g. 2025/2026"); return; }
        try(Connection c=DatabaseManager.getConnection();
            PreparedStatement ps=c.prepareStatement("UPDATE school_profile SET session_current=?, term_current=?, updated_at=now()")){
            ps.setString(1, session); ps.setString(2, term); ps.executeUpdate();
            academicStatus.setText("Academic Calendar saved: "+session+" - "+term+" Term - affects all new exams/results - 10+ year archive enabled");
            AuthService.logAudit("ACADEMIC_CALENDAR_UPDATE","school_profile",null);
        }catch(Exception e){ academicStatus.setText(e.getMessage());}
    }
    @FXML private void createSession(){
        String s = sessionNameField.getText();
        if(s==null || !s.matches("\\d{4}/\\d{4}")){ academicStatus.setText("Session format: YYYY/YYYY e.g. 2025/2026"); return; }
        saveAcademic();
    }

    // =========================================================================
    //  CLASS-ARM AUTO-BALANCING TOOL (directive: class-arm balancing).
    //  Planner: util.ArmBalancer (pure + unit tested). This controller only
    //  loads students, previews, and - after explicit admin confirmation -
    //  applies the minimum moves inside one transaction, audited per move.
    // =========================================================================
    private java.util.List<com.femzyk.klc.util.ArmBalancer.Move> pendingMoves
        = new java.util.ArrayList<>();
    private String pendingLevel = null, pendingSession = null;

    private String currentSessionText(){
        if(sessionNameField != null && sessionNameField.getText() != null
                && !sessionNameField.getText().isBlank())
            return sessionNameField.getText().trim();
        return "2024/2025";
    }

    @FXML
    private void previewBalance() {
        String level = cClassLevel == null ? null : cClassLevel.getValue();
        String session = cSession == null ? null : cSession.getValue();
        if (level == null) {
            cStatus.setText("Select a Class Level first (e.g. SS1).");
            return;
        }
        if (session == null || session.isBlank()) session = currentSessionText();
        try (Connection conn = DatabaseManager.getConnection()) {
            // Authoritative arm order: arms configured in school_classes for
            // this class+session; fall back to the arms actually in use.
            java.util.List<String> arms = new java.util.ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT DISTINCT arm FROM school_classes " +
                    "WHERE class_level = ? AND session = ? " +
                    "  AND arm IS NOT NULL AND arm <> '' ORDER BY arm")) {
                ps.setString(1, level); ps.setString(2, session);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) arms.add(rs.getString(1));
            }
            if (arms.isEmpty()) {
                java.util.Set<String> used = new java.util.TreeSet<>();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT DISTINCT arm FROM student_profiles " +
                        "WHERE class_level = ? AND session = ? " +
                        "  AND arm IS NOT NULL AND arm <> ''")) {
                    ps.setString(1, level); ps.setString(2, session);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) used.add(rs.getString(1));
                }
                arms.addAll(used);
            }

            java.util.List<com.femzyk.klc.util.ArmBalancer.Student> students
                = new java.util.ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT u.id, COALESCE(sp.surname,'') || ' ' || " +
                    "       COALESCE(sp.other_names,''), " +
                    "       sp.admission_no, COALESCE(sp.arm,'') " +
                    "FROM student_profiles sp " +
                    "JOIN users u ON u.id = sp.user_id " +
                    "WHERE sp.class_level = ? AND sp.session = ? " +
                    "  AND sp.arm IS NOT NULL AND sp.arm <> '' " +
                    "ORDER BY sp.admission_no")) {
                ps.setString(1, level); ps.setString(2, session);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    students.add(new com.femzyk.klc.util.ArmBalancer.Student(
                        rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4)));
                }
            }

            if (students.isEmpty()) {
                cStatus.setText("No enrolled students found for " + level
                    + " " + session + ".");
                return;
            }
            pendingLevel   = level;
            pendingSession = session;
            pendingMoves   = com.femzyk.klc.util.ArmBalancer.plan(
                students, arms);

            java.util.Map<String,Integer> counts = new java.util.TreeMap<>();
            for (com.femzyk.klc.util.ArmBalancer.Student s : students)
                counts.merge(s.arm, 1, Integer::sum);
            StringBuilder sb = new StringBuilder();
            sb.append("Enrolment: ").append(level).append(" - ").append(session)
              .append(" (").append(students.size()).append(" students)\n\n");
            for (java.util.Map.Entry<String,Integer> e : counts.entrySet())
                sb.append("   Arm ").append(e.getKey()).append(": ")
                  .append(e.getValue()).append("\n");

            if (pendingMoves.isEmpty()) {
                cStatus.setText("Arms are already balanced for " + level
                    + " " + session + " - no moves needed.");
                new Alert(Alert.AlertType.INFORMATION, sb.toString()
                    + "\nNo moves needed - arms are balanced.").showAndWait();
                return;
            }
            sb.append("\nProposed minimum moves: ")
              .append(pendingMoves.size()).append("\n");
            for (com.femzyk.klc.util.ArmBalancer.Move m : pendingMoves) {
                sb.append("   ").append(m.name).append(" (")
                  .append(m.admissionNo).append(")  ")
                  .append(m.fromArm).append("  ->  ").append(m.toArm)
                  .append("\n");
            }
            TextArea ta = new TextArea(sb.toString());
            ta.setEditable(false);
            ta.setPrefSize(680, 420);
            Dialog<ButtonType> dlg = new Dialog<>();
            dlg.setTitle("Arm Balance Preview");
            dlg.setHeaderText("Review the proposed arm changes");
            dlg.getDialogPane().setContent(ta);
            dlg.getDialogPane().getButtonTypes()
               .addAll(ButtonType.CLOSE, ButtonType.APPLY);
            dlg.initOwner(classTable.getScene().getWindow());
            java.util.Optional<ButtonType> r = dlg.showAndWait();
            if (r.isPresent() && r.get() == ButtonType.APPLY) applyBalanceNow();
        } catch (Exception e) {
            cStatus.setText("Balance error: " + e.getMessage());
        }
    }

    @FXML
    private void applyBalance() {
        if (pendingMoves == null || pendingMoves.isEmpty()
                || pendingLevel == null) {
            cStatus.setText("Run 'Preview Arm Balance' first - nothing to "
                + "apply yet.");
            return;
        }
        new Alert(Alert.AlertType.CONFIRMATION,
            "Apply " + pendingMoves.size() + " arm move(s) for "
            + pendingLevel + " " + pendingSession + "?\n\n"
            + "Each student's arm is updated and the change is written to "
            + "the audit log. This balances class arms evenly.",
            ButtonType.YES, ButtonType.NO).showAndWait().ifPresent(a -> {
                if (a == ButtonType.YES) applyBalanceNow();
            });
    }

    private void applyBalanceNow() {
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            int done = 0;
            for (com.femzyk.klc.util.ArmBalancer.Move m : pendingMoves) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE student_profiles SET arm = ?, " +
                        "updated_at = now() WHERE user_id = ?")) {
                    ps.setString(1, m.toArm);
                    AuthService.setUuid(ps, 2, m.userId, conn);
                    done += ps.executeUpdate();
                }
                AuthService.logAudit("ARM_BALANCE_MOVE",
                    "student_profiles", m.userId,
                    m.fromArm + " -> " + m.toArm
                        + " (" + m.admissionNo + ")");
            }
            conn.commit();
            pendingMoves.clear();
            cStatus.setText("Arm balance applied: " + done + " student(s) "
                + "moved for " + pendingLevel + " " + pendingSession + ".");
            pendingLevel = pendingSession = null;
            loadClasses();
        } catch (Exception e) {
            cStatus.setText("Apply error: " + e.getMessage());
        }
    }
}
