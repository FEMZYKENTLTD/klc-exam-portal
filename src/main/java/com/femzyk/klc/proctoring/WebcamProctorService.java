package com.femzyk.klc.proctoring;

import java.util.function.Consumer;

import javafx.scene.image.Image;

public class WebcamProctorService {

    private boolean enabled = true;
    private String studentId;
    private Consumer<Image> imageCallback;
    private boolean running = false;

    public WebcamProctorService(String studentId, Consumer<Image> callback) {
        this.studentId = studentId;
        this.imageCallback = callback;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void start() {
        if (!enabled) return;
        running = true;
        System.out.println("[Proctor] Webcam started for: " + studentId);
    }

    public void stop() {
        running = false;
        System.out.println("[Proctor] Webcam stopped");
    }

    public void captureNow() {
        // TODO: Implement actual webcam capture using OpenCV or webcam-capture library
    }
}