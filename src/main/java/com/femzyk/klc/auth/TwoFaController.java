package com.femzyk.klc.auth;

import com.femzyk.klc.db.DatabaseManager;
import dev.samstevens.totp.code.*;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import java.io.ByteArrayInputStream;
import java.sql.*;
import java.util.UUID;

public class TwoFaController {
    @FXML private Label statusLabel, secretLabel;
    @FXML private ImageView qrImageView;
    @FXML private TextField codeField;
    @FXML private CheckBox enableCheck;

    private String secret;

    @FXML public void initialize(){ loadStatus(); }

    private void loadStatus(){
        try(Connection c = DatabaseManager.getConnection();
            PreparedStatement ps = c.prepareStatement("SELECT totp_secret, totp_enabled FROM users WHERE id=?")){
            ps.setObject(1, UUID.fromString(AuthService.Session.userId));
            ResultSet rs = ps.executeQuery();
            if(rs.next()){
                secret = rs.getString(1);
                boolean enabled = rs.getBoolean(2);
                if(enableCheck != null) enableCheck.setSelected(enabled);
                if(secret == null || secret.isBlank()){
                    generateSecret();
                } else {
                    showQr(secret);
                }
                statusLabel.setText(enabled ? "✓ 2FA is ENABLED for your account" : "2FA is currently DISABLED - recommended for Admins");
            }
        }catch(Exception e){ statusLabel.setText(e.getMessage()); }
    }

    private void generateSecret() {
        try{
            secret = new DefaultSecretGenerator(32).generate();
            showQr(secret);
            statusLabel.setText("Scan this QR with Google Authenticator / Authy - then enter 6-digit code to enable");
            // save secret (not enabled yet)
            try(Connection c = DatabaseManager.getConnection();
                PreparedStatement ps = c.prepareStatement("UPDATE users SET totp_secret=? WHERE id=?")){
                ps.setString(1, secret);
                ps.setObject(2, UUID.fromString(AuthService.Session.userId));
                ps.executeUpdate();
            }
        }catch(Exception e){ statusLabel.setText(e.getMessage()); }
    }

    private void showQr(String secret) throws Exception {
        String email = AuthService.Session.email == null ? "admin@knowledgeland.edu.ng" : AuthService.Session.email;
        QrData data = new QrData.Builder()
            .label(email)
            .secret(secret)
            .issuer("KNOWLEDGE LAND COLLEGE")
            .build();
        QrGenerator gen = new ZxingPngQrGenerator();
        byte[] img = gen.generate(data);
        qrImageView.setImage(new Image(new ByteArrayInputStream(img)));
        secretLabel.setText("Manual key: " + secret);
    }

    @FXML private void verifyAndToggle(){
        if(secret == null){ statusLabel.setText("Generate secret first"); return; }
        String code = codeField.getText().trim();
        try{
            CodeVerifier verifier = new DefaultCodeVerifier(new DefaultCodeGenerator(), new SystemTimeProvider());
            boolean valid = verifier.isValidCode(secret, code);
            if(!valid){ statusLabel.setText("Invalid code - check your Authenticator app time sync"); return; }
            boolean enable = enableCheck.isSelected();
            try(Connection c = DatabaseManager.getConnection();
                PreparedStatement ps = c.prepareStatement("UPDATE users SET totp_enabled=?, totp_secret=? WHERE id=?")){
                ps.setBoolean(1, enable);
                ps.setString(2, secret);
                ps.setObject(3, UUID.fromString(AuthService.Session.userId));
                ps.executeUpdate();
            }
            AuthService.logAudit(enable ? "2FA_ENABLED" : "2FA_DISABLED", "users", AuthService.Session.userId);
            statusLabel.setText(enable ? "✓ 2FA ENABLED - you will be asked for code at next login" : "2FA disabled");
        }catch(Exception e){ statusLabel.setText(e.getMessage()); }
    }

    public static boolean verifyLoginCode(String userId, String code){
        try(Connection c = DatabaseManager.getConnection();
            PreparedStatement ps = c.prepareStatement("SELECT totp_secret, totp_enabled FROM users WHERE id=?")){
            ps.setObject(1, UUID.fromString(userId));
            ResultSet rs = ps.executeQuery();
            if(rs.next() && rs.getBoolean(2)){
                String secret = rs.getString(1);
                CodeVerifier verifier = new DefaultCodeVerifier(new DefaultCodeGenerator(), new SystemTimeProvider());
                return verifier.isValidCode(secret, code);
            }
            return true; // 2FA not enabled - allow
        }catch(Exception e){ return false; }
    }
}
