package tasks;

import com.osmb.api.script.Script;
import com.osmb.api.ui.tabs.Tab;
import data.State;
import main.VolcanicAshMiningScript;
import utils.Task;

public class SetupTask extends Task {
    private final VolcanicAshMiningScript script;

    public SetupTask(Script script) {
        super(script);
        this.script = (VolcanicAshMiningScript) script;
    }

    @Override
    public boolean activate() {
        return !script.isSetupComplete();
    }

    @Override
    public boolean execute() {
        if (!ensureInventoryTabOpen()) {
            return false;
        }

        script.ensureZoomConfigured();
        script.ensureStatsInitialized();
        script.ensureStartTimeInitialized();

        script.markSetupComplete();
        script.setState(State.MINING);
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

        return script.pollFramesUntil(() -> {
            var inventory = widgets.getInventory();
            return inventory != null && inventory.search(java.util.Collections.emptySet()) != null;
        }, script.random(60, 120));
    }
}
