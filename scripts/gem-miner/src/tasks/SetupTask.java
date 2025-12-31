package tasks;

import main.GemMinerScript;
import com.osmb.api.script.Script;
import com.osmb.api.ui.tabs.Tab;
import utils.Task;

public class SetupTask extends Task {

    public SetupTask(Script script) {
        super(script);
    }

    @Override
    public boolean activate() {
        return !GemMinerScript.setupComplete;
    }

    @Override
    public boolean execute() {
        GemMinerScript.state = GemMinerScript.State.SETUP;

        if (!ensureInventoryTabOpen()) {
            return false;
        }

        ensureZoomConfigured();
        ensureStatsInitialized();

        if (GemMinerScript.startTimeMs == 0) {
            GemMinerScript.startTimeMs = System.currentTimeMillis();
        }

        GemMinerScript.setupComplete = true;
        GemMinerScript.state = GemMinerScript.State.MINING;
        return false;
    }

    private boolean ensureInventoryTabOpen() {
        var widgets = script.getWidgetManager();
        if (widgets == null) {
            return false;
        }

        var tabManager = widgets.getTabManager();
        if (tabManager == null) {
            return false;
        }

        if (tabManager.getActiveTab() == Tab.Type.INVENTORY) {
            return true;
        }

        if (!tabManager.openTab(Tab.Type.INVENTORY)) {
            return false;
        }

        boolean visible = script.pollFramesUntil(() -> {
            var inventory = widgets.getInventory();
            return inventory != null && inventory.search(java.util.Collections.emptySet()) != null;
        }, script.random(60, 120));

        return visible;
    }

    private void ensureZoomConfigured() {
        if (GemMinerScript.zoomConfigured) {
            return;
        }
        if (script.getWidgetManager().getGameState() != com.osmb.api.ui.GameState.LOGGED_IN) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - GemMinerScript.lastZoomAttemptMs < GemMinerScript.ZOOM_RETRY_MS) {
            return;
        }
        GemMinerScript.lastZoomAttemptMs = now;
        boolean set = script.getWidgetManager().getSettings().setZoomLevel(0);
        if (set) {
            GemMinerScript.zoomConfigured = true;
        }
    }

    private void ensureStatsInitialized() {
        if (GemMinerScript.statsInitialized) {
            return;
        }
        if (script.getWidgetManager().getGameState() != com.osmb.api.ui.GameState.LOGGED_IN) {
            return;
        }
        GemMinerScript.startMiningXp = GemMinerScript.getMiningXp(script);
        GemMinerScript.startMiningLevel = GemMinerScript.getMiningLevel(script);
        GemMinerScript.statsInitialized = true;
    }
}
