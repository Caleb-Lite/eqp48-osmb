package tasks;

import main.MasterFarmersScript;
import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.script.Script;
import com.osmb.api.ui.tabs.Tab;
import com.osmb.api.walker.WalkConfig;

import java.util.Set;
import utils.Task;

public class SetupTask extends Task {

    public SetupTask(Script script) {
        super(script);
    }

    @Override
    public boolean activate() {
        return !MasterFarmersScript.setupComplete;
    }

    @Override
    public boolean execute() {
        MasterFarmersScript.state = MasterFarmersScript.State.SETUP;

        if (!ensureInventoryTabOpen()) {
            return false;
        }

        if (MasterFarmersScript.thievingArea == null || MasterFarmersScript.bankArea == null || MasterFarmersScript.anchorTile == null) {
            if (MasterFarmersScript.selectedLocation != null) {
                ((MasterFarmersScript) script).applyLocation(MasterFarmersScript.selectedLocation);
            }
        }

        if (MasterFarmersScript.thievingArea == null || MasterFarmersScript.bankArea == null || MasterFarmersScript.anchorTile == null) {
            MasterFarmersScript.state = MasterFarmersScript.State.STOPPED;
            script.log(getClass().getSimpleName(), "Location not configured. Stop the script and select a location.");
            script.stop();
            return false;
        }

        WorldPosition me = script.getWorldPosition();
        if (me == null) {
            boolean hasPos = script.pollFramesUntil(() -> script.getWorldPosition() != null, script.random(600, 1200));
            if (!hasPos) {
                script.stop();
                return false;
            }
            me = script.getWorldPosition();
        }

        if (handleLowHpWithoutFood()) {
            return false;
        }

        ItemGroupResult inv = script.getWidgetManager().getInventory().search(java.util.Collections.emptySet());
        if (inv != null && inv.isFull()) {
            MasterFarmersScript.needToBank = true;
            MasterFarmersScript.state = MasterFarmersScript.State.BANK;
        }

        if (!MasterFarmersScript.thievingArea.contains(me) && !MasterFarmersScript.needToBank) {
            if (MasterFarmersScript.anchorTile == null) {
                script.stop();
                return false;
            }
            script.getWalker().walkTo(MasterFarmersScript.anchorTile);
            return false;
        }

        MasterFarmersScript.setupComplete = true;
        MasterFarmersScript.state = MasterFarmersScript.needToBank ? MasterFarmersScript.State.BANK : MasterFarmersScript.State.PICKPOCKET;
        return false;
    }

    private boolean handleLowHpWithoutFood() {
        if (MasterFarmersScript.foodItemId == null) {
            return false;
        }

        Integer hpPerc = MasterFarmersScript.getHitpointsPercentage(script);
        if (hpPerc == null || hpPerc >= MasterFarmersScript.HP_THRESHOLD) {
            return false;
        }

        var widgets = script.getWidgetManager();
        if (widgets == null || widgets.getInventory() == null) {
            return false;
        }

        ItemGroupResult inv = widgets.getInventory().search(Set.of(MasterFarmersScript.foodItemId));
        boolean hasFood = inv != null && inv.contains(MasterFarmersScript.foodItemId);
        if (hasFood) {
            return false;
        }

        MasterFarmersScript.needToBank = true;
        boolean firstNotice = !MasterFarmersScript.needsFoodRestock;
        MasterFarmersScript.needsFoodRestock = true;
        MasterFarmersScript.state = MasterFarmersScript.State.BANK;

        if (firstNotice) {
            script.log(getClass().getSimpleName(), "HP at " + hpPerc + "% and no food (itemId=" + MasterFarmersScript.foodItemId + ") available. Banking to restock.");
        }

        if (!isInBankArea() && MasterFarmersScript.bankArea != null) {
            WalkConfig cfg = new WalkConfig.Builder()
                    .enableRun(true)
                    .breakCondition(() -> isInBankArea() || script.getWidgetManager().getBank().isVisible())
                    .build();
            script.getWalker().walkTo(MasterFarmersScript.bankArea.getRandomPosition(), cfg);
        }

        return true;
    }

    private boolean isInBankArea() {
        var pos = script.getWorldPosition();
        return pos != null && MasterFarmersScript.bankArea != null && MasterFarmersScript.bankArea.contains(pos);
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
}
