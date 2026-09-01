package com.femzyk.klc;

import com.femzyk.klc.db.DatabaseInitializer;
import com.femzyk.klc.db.DatabaseManager;
import com.femzyk.klc.util.ErrorLogger;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class MainApp extends Application {

    public static Stage primaryStage;
    public static volatile boolean examInProgress = false;

    @Override
    public void start(Stage stage) throws Exception {
        primaryStage = stage;

        // Global uncaught exception handler
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            ErrorLogger.logError(throwable,
                "Uncaught in thread: " + thread.getName());
        });

        // STEP 1: Init database manager (tests cloud connection)
        System.out.println("[App] Starting KLC CBT Suite v1.0...");
        DatabaseManager.init();

        // STEP 2: Create all tables (H2 offline + PostgreSQL column checks)
        System.out.println("[App] Initializing database schema...");
        DatabaseInitializer.initialize();

        // STEP 3: Load splash screen
        FXMLLoader loader = new FXMLLoader(
            getClass().getResource("/fxml/splash.fxml"));
        Scene scene = new Scene(loader.load(), 720, 420);
        scene.getStylesheets().add(
            getClass().getResource("/css/klc-premium.css").toExternalForm());

        stage.setTitle(
            "KNOWLEDGE LAND COLLEGE CBT SUITE v1.0 | Powered by FEMZYK");

        // DECORATED so OS close button is visible
        stage.initStyle(StageStyle.DECORATED);
        stage.setScene(scene);
        stage.setResizable(true);

        // Intercept close - block during exam
        stage.setOnCloseRequest(event -> {
            if (examInProgress) {
                event.consume();
                Alert warn = new Alert(Alert.AlertType.WARNING,
                    "You cannot close the app while an exam is in progress!\n" +
                    "Please submit your exam first.",
                    ButtonType.OK);
                warn.setTitle("Exam In Progress");
                warn.showAndWait();
            } else {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Are you sure you want to exit KLC CBT Suite?",
                    ButtonType.YES, ButtonType.NO);
                confirm.setTitle("Exit KLC CBT Suite");
                confirm.showAndWait().ifPresent(btn -> {
                    if (btn == ButtonType.YES) {
                        Platform.exit();
                        System.exit(0);
                    } else {
                        event.consume();
                    }
                });
            }
        });

        stage.show();
        stage.centerOnScreen();

        System.out.println("[App] Started. Cloud: "
            + DatabaseManager.isCloudAvailable());
    }

    public static void setRoot(String fxml, Object controllerData)
            throws Exception {
        FXMLLoader loader = new FXMLLoader(
            MainApp.class.getResource("/fxml/" + fxml));
        double w = primaryStage.getScene() != null
            ? primaryStage.getScene().getWidth()  : 1150;
        double h = primaryStage.getScene() != null
            ? primaryStage.getScene().getHeight() : 720;
        Scene scene = new Scene(loader.load(), w, h);
        scene.getStylesheets().add(
            MainApp.class.getResource("/css/klc-premium.css").toExternalForm());
        primaryStage.setScene(scene);
        primaryStage.setMaximized(true);
    }

    public static void main(String[] args) {
        launch();
    }
}