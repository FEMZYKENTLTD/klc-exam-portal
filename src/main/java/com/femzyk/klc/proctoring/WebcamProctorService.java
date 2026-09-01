package com.femzyk.klc.proctoring;

import com.femzyk.klc.util.ConfigService;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import com.github.sarxos.webcam.Webcam;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.function.Consumer;

/**
 * WebcamProctorService - KLC CBT Suite v1.0 (spec 6.5)
 *
 * REAL webcam proctoring (was a no-op stub before v1.0):
 *  - captures a photo at exam start AND at a random/randomised interval
 *  - evidence stored under klc_assets/exam_webcams/{studentId}/ for the
 *    Malpractice review workflow
 *  - live thumbnail pushed to the exam UI via the image callback
 *  - capture thread is a daemon; stop() releases the camera
 *  - toggle: config.properties proctor.webcam=true|false (default true for
 *    OFFICIAL exams; practice mode callers decide). When no camera exists
 *    everything degrades silently - the exam never blocks on hardware.
 */
public class WebcamProctorService implements Runnable {

    private static final long BASE_INTERVAL_MS = 60_000;   // ~ every 60s
    private static final long JITTER_MS        = 30_000;   // +- jitter

    private final String studentId;
    private final Consumer<Image> imageCallback;

    private volatile boolean enabled = true;
    private volatile boolean running = false;
    private Thread worker;
    private Webcam webcam;

    public WebcamProctorService(String studentId, Consumer<Image> callback) {
        this.studentId = studentId == null ? "unknown" : studentId;
        this.imageCallback = callback;
        // spec 6.5: optional toggle (default ON; practice mode is decided
        // by the caller, ExamController only enables it for live exams)
        this.enabled = ConfigService.flag("proctor.webcam", true);
        try {
            webcam = Webcam.getDefault();
        } catch (Throwable t) {            // no webcam-capture natives, etc.
            webcam = null;
        }
        if (webcam == null) enabled = false;
    }

    public boolean isEnabled() { return enabled; }

    /** Start the capture loop (photo at start + interval with jitter). */
    public void start() {
        if (!enabled || running) return;
        running = true;
        worker = new Thread(this, "klc-webcam-proctor");
        worker.setDaemon(true);
        worker.start();
        System.out.println("[Proctor] Webcam evidence capture started for: "
            + studentId);
    }

    public void stop() {
        running = false;
        if (worker != null) worker.interrupt();
        closeWebcamQuietly();
        System.out.println("[Proctor] Webcam stopped");
    }

    /** One-off capture - used at exam start even before the first tick. */
    public void captureNow() {
        capture();
    }

    @Override
    public void run() {
        if (!capture()) {                  // camera busy/unavailable: retry
            try { Thread.sleep(3_000); } catch (InterruptedException e) {
                return;
            }
            if (!capture()) { enabled = false; return; }
        }
        long sleep = BASE_INTERVAL_MS
            + (long) (Math.random() * JITTER_MS)
            - JITTER_MS / 2;
        while (running) {
            try {
                Thread.sleep(Math.max(10_000, sleep));
            } catch (InterruptedException e) {
                break;
            }
            if (!running) break;
            capture();
            sleep = BASE_INTERVAL_MS
                + (long) (Math.random() * JITTER_MS) - JITTER_MS / 2;
        }
        closeWebcamQuietly();
    }

    /** Capture one frame to disk + UI. Returns true on success. */
    private boolean capture() {
        if (!enabled || webcam == null) return false;
        try {
            if (!webcam.isOpen() && !webcam.open()) return false;
            BufferedImage raw = webcam.getImage();
            if (raw == null) return false;

            File dir = new File("klc_assets/exam_webcams",
                studentId.replaceAll("[^A-Za-z0-9._-]", "_"));
            dir.mkdirs();
            File out = new File(dir,
                System.currentTimeMillis() + ".jpg");
            ImageIO.write(raw, "JPG", out);

            if (imageCallback != null) {
                // KLC v1.0 FIX: no SwingFXUtils (needs the javafx-swing
                // artifact, not in the pom) - direct ARGB pixel copy.
                int w = raw.getWidth(), h = raw.getHeight();
                int[] argb = raw.getRGB(0, 0, w, h, null, 0, w);
                WritableImage fx = new WritableImage(w, h);
                fx.getPixelWriter().setPixels(0, 0, w, h,
                    PixelFormat.getIntArgbInstance(), argb, 0, w);
                Platform.runLater(() -> imageCallback.accept(fx));
            }
            return true;
        } catch (Throwable t) {
            // headless AWT, missing natives, camera yanked out mid-exam...
            System.out.println("[Proctor] capture failed: "
                + t.getMessage());
            return false;
        }
    }

    private void closeWebcamQuietly() {
        try {
            if (webcam != null && webcam.isOpen()) webcam.close();
        } catch (Throwable ignored) {}
    }
}
