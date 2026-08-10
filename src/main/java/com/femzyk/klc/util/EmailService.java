package com.femzyk.klc.util;

import com.femzyk.klc.db.DatabaseManager;
import jakarta.mail.*;
import jakarta.mail.internet.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Properties;
import java.util.UUID;

/**
 * Email Service - KLC CBT Suite v6.3
 * Uses Brevo SMTP (300 free emails/day)
 * Sender name: KNOWLEDGE LAND COLLEGE CBT
 * Falls back to notification_queue if SMTP not configured
 */
public class EmailService {

    private static String smtpHost;
    private static String smtpPort;
    private static String smtpUser;
    private static String smtpPass;
    private static String fromName;
    private static String fromEmail;
    private static boolean enabled = false;

    static {
        try {
            Properties p = new Properties();
            try (var in = EmailService.class
                    .getResourceAsStream("/config.properties")) {
                if (in != null) p.load(in);
            }
            smtpHost  = p.getProperty("smtp.host",       "");
            smtpPort  = p.getProperty("smtp.port",       "587");
            smtpUser  = p.getProperty("smtp.user",       "");
            smtpPass  = p.getProperty("smtp.pass",       "");
            fromName  = p.getProperty("smtp.from.name",
                "KNOWLEDGE LAND COLLEGE CBT");
            fromEmail = p.getProperty("smtp.from.email", smtpUser);

            enabled = smtpHost != null && !smtpHost.isBlank()
                   && smtpUser != null && !smtpUser.isBlank()
                   && smtpPass != null && !smtpPass.isBlank();

            System.out.println("[Email] Service " +
                (enabled ? "ENABLED via " + smtpHost : "DISABLED (queue mode)"));

        } catch (Exception ignored) {}
    }

    public static boolean isEnabled() { return enabled; }

    // =========================================================================
    //  SEND EMAIL
    // =========================================================================
    public static void send(String to, String subject, String htmlBody) {
        if (!enabled) {
            queue(to, subject, htmlBody);
            return;
        }
        try {
            Properties props = new Properties();
            props.put("mail.smtp.auth",            "true");
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.host",            smtpHost);
            props.put("mail.smtp.port",            smtpPort);
            props.put("mail.smtp.timeout",         "10000");
            props.put("mail.smtp.connectiontimeout", "10000");

            final String user = smtpUser;
            final String pass = smtpPass;

            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(user, pass);
                }
            });

            Message msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(fromEmail, fromName));
            msg.setRecipients(Message.RecipientType.TO,
                InternetAddress.parse(to));
            msg.setSubject(subject);

            // Send as HTML with plain text fallback
            MimeMultipart multipart = new MimeMultipart("alternative");

            // Plain text part
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(htmlBody.replaceAll("<[^>]+>", ""), "utf-8");

            // HTML part
            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setContent(buildHtmlEmail(subject, htmlBody),
                "text/html; charset=utf-8");

            multipart.addBodyPart(textPart);
            multipart.addBodyPart(htmlPart);
            msg.setContent(multipart);

            Transport.send(msg);
            System.out.println("[Email] Sent to: " + to);

        } catch (Exception e) {
            System.err.println("[Email] Failed: " + e.getMessage());
            queue(to, subject, htmlBody);
        }
    }

    // =========================================================================
    //  BUILD HTML EMAIL TEMPLATE
    // =========================================================================
    private static String buildHtmlEmail(String subject, String body) {
        return "<!DOCTYPE html>" +
            "<html><head><meta charset='UTF-8'/>" +
            "<style>" +
            "body{font-family:'Segoe UI',Arial,sans-serif;" +
            "  background:#f5f7fb;margin:0;padding:20px;}" +
            ".container{max-width:600px;margin:0 auto;" +
            "  background:white;border-radius:12px;" +
            "  box-shadow:0 4px 20px rgba(0,0,0,0.1);overflow:hidden;}" +
            ".header{background:linear-gradient(to right,#0f1f3c,#1b2f5a);" +
            "  padding:24px 32px;}" +
            ".header h1{color:white;margin:0;font-size:18px;}" +
            ".header p{color:#d4af37;margin:4px 0 0;font-size:12px;}" +
            ".body{padding:32px;color:#1e293b;line-height:1.6;}" +
            ".footer{background:#f8f9fa;padding:16px 32px;" +
            "  color:#64748b;font-size:11px;border-top:1px solid #e2e8f0;}" +
            "</style></head><body>" +
            "<div class='container'>" +
            "<div class='header'>" +
            "<h1>KNOWLEDGE LAND COLLEGE</h1>" +
            "<p>CBT SUITE v6.3 | Secondary School, Igando, Lagos State</p>" +
            "</div>" +
            "<div class='body'>" +
            body.replace("\n", "<br/>") +
            "</div>" +
            "<div class='footer'>" +
            "This email was sent by KNOWLEDGE LAND COLLEGE CBT Suite.<br/>" +
            "Powered by FEMZYK | Lead Developer: OLUFEMI BENUA KERIPE<br/>" +
            "Do not reply to this email." +
            "</div>" +
            "</div></body></html>";
    }

    // =========================================================================
    //  QUEUE EMAIL (when SMTP not available)
    // =========================================================================
    public static void queue(String to, String subject, String body) {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO notification_queue(" +
                 "  id, recipient_email, subject, body, channel, status) " +
                 "VALUES(?,?,?,?,'EMAIL','PENDING')")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, to);
            ps.setString(3, subject);
            ps.setString(4, body);
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    // =========================================================================
    //  FLUSH QUEUE - send all pending emails
    // =========================================================================
    public static int flushQueue() {
        if (!enabled) return 0;
        int sent = 0;
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT id, recipient_email, subject, body " +
                 "FROM notification_queue " +
                 "WHERE status = 'PENDING' AND channel = 'EMAIL' " +
                 "LIMIT 20")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String id = rs.getString(1);
                try {
                    send(rs.getString(2), rs.getString(3), rs.getString(4));
                    try (PreparedStatement up = c.prepareStatement(
                            "UPDATE notification_queue " +
                            "SET status='SENT', sent_at=CURRENT_TIMESTAMP " +
                            "WHERE id=?")) {
                        up.setString(1, id);
                        up.executeUpdate();
                        sent++;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return sent;
    }

    // =========================================================================
    //  SPECIFIC EMAIL TYPES
    // =========================================================================
    public static void sendResultNotification(
            String studentEmail, String name, String subject, double score) {
        String emailSubject = "Your " + subject + " Result - KLC CBT";
        String body = "Hello " + name + ",\n\n" +
            "Your " + subject + " CBT examination result is now available.\n\n" +
            "Score: " + String.format("%.1f%%", score) + "\n\n" +
            "You can view your full result and download your Report Card " +
            "by logging into the KLC CBT portal.\n\n" +
            "Result PIN: SURNAME + CLASS  (e.g. KERIPESS2)\n\n" +
            "Best regards,\n" +
            "KNOWLEDGE LAND COLLEGE\n" +
            "Secondary School, Igando, Lagos State";
        send(studentEmail, emailSubject, body);
    }

    public static void sendWelcomeEmail(
            String userEmail, String name, String role, String tempPass) {
        String emailSubject = "Welcome to KLC CBT Suite - Your Account Details";
        String body = "Hello " + name + ",\n\n" +
            "Welcome to KNOWLEDGE LAND COLLEGE CBT Suite!\n\n" +
            "Your account has been created with the following details:\n\n" +
            "Email: " + userEmail + "\n" +
            "Role: " + role + "\n" +
            "Temporary Password: " + tempPass + "\n\n" +
            "Please login and change your password immediately.\n\n" +
            "Login at: Open the KLC CBT application\n\n" +
            "Best regards,\n" +
            "KNOWLEDGE LAND COLLEGE Administration";
        send(userEmail, emailSubject, body);
    }

    public static void sendPasswordResetNotification(
            String userEmail, String name) {
        String emailSubject = "Password Reset - KLC CBT Suite";
        String body = "Hello " + name + ",\n\n" +
            "Your KLC CBT password has been reset.\n\n" +
            "If you did not request this, please contact the administrator " +
            "immediately:\n" +
            "Email: femzykenterprisesltd@gmail.com\n" +
            "Phone: +2349049903679\n\n" +
            "Best regards,\n" +
            "KNOWLEDGE LAND COLLEGE";
        send(userEmail, emailSubject, body);
    }

    public static void sendFriendRequestNotification(
            String receiverEmail, String receiverName, String senderName) {
        String emailSubject = senderName + " sent you a friend request - KLC CBT";
        String body = "Hello " + receiverName + ",\n\n" +
            senderName + " has sent you a friend request on KLC CBT.\n\n" +
            "Login to the app to accept or decline the request.\n\n" +
            "Best regards,\n" +
            "KNOWLEDGE LAND COLLEGE";
        send(receiverEmail, emailSubject, body);
    }

    public static void sendMessageNotification(
            String receiverEmail, String receiverName, String senderName) {
        String emailSubject = "New message from " + senderName + " - KLC CBT";
        String body = "Hello " + receiverName + ",\n\n" +
            "You have a new message from " + senderName +
            " on KLC CBT.\n\n" +
            "Login to the app to read and reply.\n\n" +
            "Best regards,\n" +
            "KNOWLEDGE LAND COLLEGE";
        send(receiverEmail, emailSubject, body);
    }
}