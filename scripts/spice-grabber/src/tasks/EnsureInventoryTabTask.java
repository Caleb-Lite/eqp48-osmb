package tasks;

import com.osmb.api.ui.tabs.Tab;
import utils.Task;

public class EnsureInventoryTabTask extends Task {
    public EnsureInventoryTabTask(com.osmb.api.script.Script script) {
        super(script);
    }

    @Override
    public boolean activate() {
        if (!isPlayerIdle()) {
            return false;
        }
        var widgets = script.getWidgetManager();
        if (widgets == null || widgets.getTabManager() == null) {
            return false;
        }
        return widgets.getTabManager().getActiveTab() != Tab.Type.INVENTORY;
    }

    @Override
    public boolean execute() {
        var widgets = script.getWidgetManager();
        if (widgets == null || widgets.getTabManager() == null) {
            return false;
        }
        boolean opened = widgets.getTabManager().openTab(Tab.Type.INVENTORY);
        if (opened) {
            data.CatState.setupComplete = true;
        }
        return opened;
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
