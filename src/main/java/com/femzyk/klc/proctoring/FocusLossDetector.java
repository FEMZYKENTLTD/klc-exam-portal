package com.femzyk.klc.proctoring;

import javafx.application.Platform;
import javafx.stage.Stage;
import java.util.function.Consumer;

public class FocusLossDetector {
    private int strikes = 0;
    private final int maxStrikes = 3;
    private final Consumer<Integer> onStrike;
    private final Runnable onLockout;

    public FocusLossDetector(Stage stage, Consumer<Integer> onStrike, Runnable onLockout) {
        this.onStrike = onStrike;
        this.onLockout = onLockout;
        stage.iconifiedProperty().addListener((o, ov, nv)-> { if(nv) hit("Minimize"); });
        stage.focusedProperty().addListener((o, ov, nv)-> { if(!nv) hit("Focus Loss / Alt-Tab"); });
    }
    private void hit(String reason){
        strikes++;
        Platform.runLater(()-> onStrike.accept(strikes));
        if(strikes >= maxStrikes){
            Platform.runLater(onLockout);
        }
    }
    public int getStrikes(){ return strikes; }
}
