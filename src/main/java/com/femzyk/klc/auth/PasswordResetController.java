package com.femzyk.klc.auth;

import java.util.UUID;
import javafx.scene.layout.VBox;
import at.favre.lib.crypto.bcrypt.BCrypt;
import com.femzyk.klc.MainApp;
import com.femzyk.klc.db.DatabaseManager;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import java.sql.*;

public class PasswordResetController {
    @FXML private TextField emailField;
    @FXML private Label questionLabel, statusLabel;
    @FXML private TextField answerField;
    @FXML private PasswordField newPassField;
    @FXML private VBox step1Box, step2Box;

    private String userId;
    private String storedAnswerHash;

    @FXML public void initialize(){
        if(step2Box != null) step2Box.setVisible(false);
    }

    @FXML private void findAccount(){
        try(Connection c = DatabaseManager.getConnection();
            PreparedStatement ps = c.prepareStatement("SELECT id, security_question, security_answer_hash FROM users WHERE email=?")){
            ps.setString(1, emailField.getText().toLowerCase());
            ResultSet rs = ps.executeQuery();
            if(rs.next() && rs.getString(2) != null){
                userId = rs.getString(1);
                questionLabel.setText("Security Question: " + rs.getString(2));
                storedAnswerHash = rs.getString(3);
                step1Box.setVisible(false);
                step2Box.setVisible(true);
                statusLabel.setText("Answer your security question to reset password - offline friendly");
            } else if(rs.next()){
                statusLabel.setText("No security question set for this account - contact Super Admin OLUFEMI BENUA KERIPE to reset");
            } else {
                statusLabel.setText("Account not found");
            }
        }catch(Exception e){ statusLabel.setText(e.getMessage()); }
    }

    @FXML private void doReset(){
        if(userId == null) return;
        boolean ok = false;
        try{ ok = BCrypt.verifyer().verify(answerField.getText().toLowerCase().trim().toCharArray(), storedAnswerHash).verified; }catch(Exception ignored){}
        if(!ok){ statusLabel.setText("Security answer incorrect"); return; }
        if(newPassField.getText().length() < 6){ statusLabel.setText("Password must be at least 6 characters"); return; }
        try(Connection c = DatabaseManager.getConnection();
            PreparedStatement ps = c.prepareStatement("UPDATE users SET password_hash=?, failed_login_attempts=0, locked_until=NULL WHERE id=?")){
            String hash = BCrypt.withDefaults().hashToString(12, newPassField.getText().toCharArray());
            ps.setString(1, hash);
            ps.setObject(2, UUID.fromString(userId));
            ps.executeUpdate();
            statusLabel.setText("Password reset successful - you can now login");
            new Alert(Alert.AlertType.INFORMATION, "Password reset successful - login with your new password").showAndWait();
            MainApp.setRoot("login.fxml", null);
        }catch(Exception e){ statusLabel.setText(e.getMessage()); }
    }

    @FXML private void backLogin() throws Exception { MainApp.setRoot("login.fxml", null); }
}
