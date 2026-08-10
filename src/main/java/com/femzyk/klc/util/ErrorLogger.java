package com.femzyk.klc.util;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ErrorLogger {

    private static final String LOG_FILE = "klc_error_log.txt";
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void logError(Throwable error, String context) {
        String timestamp = LocalDateTime.now().format(formatter);
        String message = "[" + timestamp + "] " + context + "\n" + getStackTrace(error) + "\n\n";

        try (FileWriter fw = new FileWriter(LOG_FILE, true);
             BufferedWriter bw = new BufferedWriter(fw)) {
            bw.write(message);
        } catch (IOException e) {
            System.err.println("Failed to write to error log: " + e.getMessage());
        }

        try {
            AuditService.log(null, "ERROR", "system", context + " | " + error.getMessage());
        } catch (Exception ignored) {}
    }

    public static void logError(String message) {
        String timestamp = LocalDateTime.now().format(formatter);
        String fullMessage = "[" + timestamp + "] " + message + "\n\n";

        try (FileWriter fw = new FileWriter(LOG_FILE, true);
             BufferedWriter bw = new BufferedWriter(fw)) {
            bw.write(fullMessage);
        } catch (IOException e) {
            System.err.println("Failed to write to error log: " + e.getMessage());
        }
    }

    private static String getStackTrace(Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }
}