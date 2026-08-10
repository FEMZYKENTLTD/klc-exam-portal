package com.femzyk.klc.admin;

import com.femzyk.klc.db.DatabaseManager;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

import java.time.Year;

public class AboutController {

    @FXML private Label versionLabel;

    @FXML
    public void initialize() {
        // Check cloud status
        String dbStatus = DatabaseManager.isCloudAvailable()
            ? "Supabase PostgreSQL 15 (Cloud - Connected)"
            : "H2 Local Cache (Offline Mode)";

        if (versionLabel != null) {
            versionLabel.setText(
                "Application:  KNOWLEDGE LAND COLLEGE CBT SUITE v6.3\n" +
                "Type:         Secondary School Enterprise\n" +
                "Mode:         Cloud Online / Auto-Sync\n\n" +
                "Database:     " + dbStatus + "\n" +
                "Runtime:      Java 17 LTS + JavaFX 17\n" +
                "ORM:          Hibernate 6\n" +
                "PDF Export:   iText PDF 8\n" +
                "Doc Parser:   Apache PDFBox 3 + Apache POI 5\n" +
                "Security:     BCrypt + TOTP 2FA\n" +
                "Platform:     Windows 7 / 8 / 10 / 11 (x86 + x64)\n\n" +
                "Copyright:    c " + Year.now().getValue() +
                " KNOWLEDGE LAND COLLEGE\n" +
                "              All Rights Reserved\n\n" +
                "Registration Codes:\n" +
                "  Super Admin : FEMZYK ENTERPRISES LTD\n" +
                "  Teacher     : FEMZYK\n" +
                "  Student     : FEMZYKENTLTD"
            );
        }
    }
}