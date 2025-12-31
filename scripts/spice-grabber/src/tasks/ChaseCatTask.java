package tasks;

import com.osmb.api.input.MenuEntry;
import com.osmb.api.input.MenuHook;
import data.State;
import utils.Task;

public class ChaseCatTask extends Task {
    private static final long CHASE_COOLDOWN_MS = 4_000L;

    public ChaseCatTask(com.osmb.api.script.Script script) {
        super(script);
    }

    @Override
    public boolean activate() {
        if (!State.highlightFound || !State.isNextToUs || State.tapBounds == null) {
            return false;
        }
        if (!isPlayerIdle()) {
            return false;
        }
        long nowMs = System.currentTimeMillis();
        return nowMs - State.lastChaseMs >= CHASE_COOLDOWN_MS;
    }

    @Override
    public boolean execute() {
        MenuHook chaseHook = menuEntries -> {
            for (MenuEntry entry : menuEntries) {
                if (entry == null) {
                    continue;
                }
                String rawText = entry.getRawText();
                if (rawText != null && rawText.contains("chase")) {
                    return entry;
                }
            }
            return null;
        };

        boolean chased = script.getFinger().tapGameScreen(State.tapBounds, chaseHook);
        if (chased) {
            State.lastChaseMs = System.currentTimeMillis();
            State.chaseExecutedThisPoll = true;
        }
        return false;
    }

    private boolean isPlayerIdle() {
        var playerPos = script.getWorldPosition();
        if (playerPos == null) {
            return false;
        }
        var tileCube = script.getSceneProjector().getTileCube(playerPos, 120);
        if (tileCube == null) {
            return false;
        }
        var resized = tileCube.getResized(0.7);
        if (resized == null) {
            return false;
        }
        return !script.getPixelAnalyzer().isAnimating(0.2, resized);
    }
}
