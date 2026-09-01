package com.femzyk.klc.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * StorageService - KLC CBT Suite v1.0
 *
 * Supabase Storage client for chat attachments so files actually TRANSFER
 * between lab PCs (previously messages stored the sender's local path and
 * receivers always got "File not found").
 *
 * Behaviour:
 *  - upload(): PUT {supabase.url}/storage/v1/object/{bucket}/{path}
 *    Requires config.properties: supabase.url, supabase.key (anon or
 *    service key with storage write), supabase.storage.bucket (default
 *    klc-attachments, public-read bucket - see supabase migration SQL).
 *  - download(): GET a remote object URL into a local file.
 *  - Every call degrades gracefully: on ANY failure (offline lab, missing
 *    config, bucket not provisioned) upload returns null and the caller
 *    falls back to the local-path behaviour. This preserves the
 *    "offline-safe" guarantee in the README.
 */
public class StorageService {

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build();

    private static String baseUrl, apiKey, bucket;

    private static void loadConfig() {
        if (baseUrl != null) return;
        java.util.Properties p = new java.util.Properties();
        try (java.io.InputStream in =
                 StorageService.class.getResourceAsStream("/config.properties")) {
            if (in != null) p.load(in);
        } catch (Exception ignored) {}
        baseUrl = p.getProperty("supabase.url", "").trim();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        apiKey = p.getProperty("supabase.key", "").trim();
        bucket = p.getProperty("supabase.storage.bucket",
                               "klc-attachments").trim();
    }

    public static boolean isConfigured() {
        loadConfig();
        return !baseUrl.isBlank() && !apiKey.isBlank()
            && baseUrl.startsWith("http");
    }

    /**
     * Upload a file. Returns the public download URL, or null when the
     * upload is not possible (offline / unconfigured) - caller then keeps
     * the local path in the message body.
     */
    public static String upload(File f, String ownerFolder) {
        if (!isConfigured() || f == null || !f.exists()) return null;
        try {
            String safeName = System.currentTimeMillis() + "_" +
                f.getName().replaceAll("[^A-Za-z0-9._-]", "_");
            String objectPath = (ownerFolder == null || ownerFolder.isBlank()
                ? "shared" : ownerFolder.replaceAll("[^A-Za-z0-9._-]", ""))
                + "/" + safeName;

            String mime = guessMime(f.getName());
            RequestBody body = RequestBody.create(
                f, MediaType.parse(mime));
            Request req = new Request.Builder()
                .url(baseUrl + "/storage/v1/object/" + bucket + "/"
                     + objectPath)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("x-upsert", "true")
                .put(body)
                .build();

            try (Response resp = HTTP.newCall(req).execute()) {
                if (!resp.isSuccessful() && resp.code() != 409) {
                    System.out.println("[Storage] Upload failed HTTP "
                        + resp.code());
                    return null;
                }
            }
            return baseUrl + "/storage/v1/object/public/" + bucket + "/"
                + objectPath;
        } catch (Exception e) {
            System.out.println("[Storage] Upload error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Download a remote attachment URL to klc_assets/attachments and
     * return the local file (or null on failure).
     */
    public static File download(String remoteUrl, String displayHint) {
        if (remoteUrl == null || !remoteUrl.startsWith("http")) return null;
        try {
            Request req = new Request.Builder().url(remoteUrl)
                .addHeader("Authorization", "Bearer " + apiKey).build();
            try (Response resp = HTTP.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;

                String name = displayHint == null || displayHint.isBlank()
                    ? "attachment_" + System.currentTimeMillis()
                    : displayHint.replaceAll("[^A-Za-z0-9._-]", "_");
                File dir = new File("klc_assets/attachments");
                dir.mkdirs();
                File dest = new File(dir, name);
                // Avoid clobbering different files that collide on name
                if (dest.exists() && dest.length() != resp.body().contentLength()) {
                    dest = new File(dir,
                        System.currentTimeMillis() + "_" + name);
                }
                Files.copy(resp.body().byteStream(), dest.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
                return dest;
            }
        } catch (IOException e) {
            System.out.println("[Storage] Download error: " + e.getMessage());
            return null;
        }
    }

    private static String guessMime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".pdf"))  return "application/pdf";
        if (n.endsWith(".png"))  return "image/png";
        if (n.endsWith(".gif"))  return "image/gif";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg"))
            return "image/jpeg";
        if (n.endsWith(".doc"))  return "application/msword";
        if (n.endsWith(".docx"))
            return "application/vnd.openxmlformats-officedocument"
                 + ".wordprocessingml.document";
        return "application/octet-stream";
    }
}
