package com.femzyk.klc.util;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.control.ComboBox;

/**
 * ComboSearch - typing + live-filtering for entity pickers (directive:
 * "subject field must not be limited to a static dropdown").
 *
 * Makes an existing ComboBox editable and filters its drop-down live as the
 * user types (case-insensitive substring match over the canonical values).
 * The picker never commits an arbitrary string: when focus is lost or the
 * value is committed, the text is matched case-insensitively against the
 * real value list; without a match the picker reverts to the last valid
 * value, so foreign-key relationships always point at a real record.
 *
 * The full list is kept intact as the source of truth - the drop-down still
 * works as a plain dropdown for mouse users.
 */
public final class ComboSearch {

    private ComboSearch() {}

    /**
     * Enables type-to-filter selection on a ComboBox of canonical values.
     *
     * @param box the combo box (its existing items are used as the full list)
     */
    public static void enable(ComboBox<String> box) {
        if (box == null) return;
        List<String> all = new ArrayList<>(box.getItems());
        if (all.isEmpty()) return;
        enable(box, all);
    }

    /**
     * Enables type-to-filter selection from an explicit canonical list
     * (the list is also loaded into the box).
     */
    public static void enable(ComboBox<String> box, List<String> all) {
        if (box == null || all == null || all.isEmpty()) return;
        List<String> canonical = new ArrayList<>(all);
        box.getItems().setAll(canonical);
        box.setEditable(true);

        box.getEditor().textProperty().addListener((ob, ov, nv) -> {
            if (nv == null) return;
            String t = nv.trim().toLowerCase();
            List<String> filtered = new ArrayList<>();
            for (String item : canonical) {
                if (t.isEmpty() || item.toLowerCase().contains(t)) {
                    filtered.add(item);
                }
            }
            box.getItems().setAll(filtered);
            if (filtered.size() == 1
                    && filtered.get(0).equalsIgnoreCase(nv.trim())) {
                box.setValue(filtered.get(0));
            } else if (!filtered.isEmpty()) {
                box.show();
            } else {
                box.hide();
            }
        });

        box.getEditor().focusedProperty().addListener((ob, ov, focused) -> {
            if (!focused) commit(box, canonical);
        });
        box.valueProperty().addListener((ob, ov, nv) -> {
            if (nv != null && !canonical.contains(nv)) {
                box.getEditor().setText(nv); // user-typed free text -> keep
            }
        });
        box.setOnAction(e -> commit(box, canonical));
    }

    private static void commit(ComboBox<String> box, List<String> canonical) {
        String text = box.getEditor().getText();
        if (text != null) {
            String match = null;
            for (String item : canonical) {
                if (item.equalsIgnoreCase(text.trim())) {
                    match = item;
                    break;
                }
            }
            if (match != null) {
                box.setValue(match);
                box.getEditor().setText(match);
            } else if (box.getValue() == null
                    || !canonical.contains(box.getValue())) {
                // No valid selection - revert to a blank state rather than
                // committing an arbitrary subject string.
                box.setValue(null);
                box.getEditor().clear();
            }
        }
        box.getItems().setAll(canonical);
    }
}
