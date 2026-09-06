package com.femzyk.klc.util;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.Scene;

/**
 * ThemeService - global dark/light theme toggle (directive F6).
 *
 * JavaFX gives inline FXML {@code style="..."} attributes higher precedence
 * than any stylesheet, so a pure CSS dark theme cannot restyle screens whose
 * colors live inline. The screen set therefore uses two CSS classes owned by
 * the theme layer: {@code .screen-root} (page background) and default label
 * fills. {@link #apply(Scene)} appends {@code /css/klc-dark.css} AFTER the
 * premium (light) stylesheet whenever dark mode is active, so dark rules win
 * at equal specificity; toggling removes/re-adds the sheet on the live scene.
 *
 * The choice is persisted to {@code ~/.klc_theme} (never inside the repo or
 * jar) and is picked up on the next launch too.
 */
public final class ThemeService {

    private static final String DARK_CSS = "/css/klc-dark.css";
    private static volatile boolean dark = readSaved();

    private ThemeService() {}

    private static File prefFile() {
        return new File(System.getProperty("user.home", "."),
            ".klc_theme");
    }

    private static boolean readSaved() {
        try {
            File f = prefFile();
            if (f.exists()) {
                String v = new String(Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8).trim();
                return "dark".equalsIgnoreCase(v);
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static void persist() {
        try {
            Files.write(prefFile().toPath(),
                (dark ? "dark" : "light").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    public static boolean isDark() {
        return dark;
    }

    /** Applies the current theme to a scene (call after building it). */
    public static void apply(Scene scene) {
        if (scene == null) return;
        List<String> sheets = scene.getStylesheets();
        String url = ThemeService.class.getResource(DARK_CSS)
            == null ? null
            : ThemeService.class.getResource(DARK_CSS).toExternalForm();
        if (url == null) return;
        sheets.remove(url);
        if (dark) sheets.add(url);
    }

    /** Applies the current theme to the scene hosting a node. */
    public static void apply(Node node) {
        if (node != null && node.getScene() != null) apply(node.getScene());
    }

    /** Flips the theme and restyles the current scene immediately. */
    public static void toggle() {
        dark = !dark;
        persist();
    }

    /** Flips the theme and applies it to {@code scene} right away. */
    public static void toggle(Scene scene) {
        toggle();
        apply(scene);
    }
}
