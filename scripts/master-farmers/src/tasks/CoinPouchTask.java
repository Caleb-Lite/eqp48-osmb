package tasks;

import main.MasterFarmersScript;
import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ItemSearchResult;
import com.osmb.api.script.Script;
import com.osmb.api.ui.tabs.Tab;
import utils.Task;

import java.util.Optional;
import java.util.Set;

public class CoinPouchTask extends Task {

    private static final int OPEN_THRESHOLD = 28;
    private static final int INTERACT_DELAY_MIN = 220;
    private static final int INTERACT_DELAY_MAX = 420;
    private static final int POST_OPEN_DELAY_MIN = 200;
    private static final int POST_OPEN_DELAY_MAX = 360;

    public CoinPouchTask(Script script) {
        super(script);
    }

    @Override
    public boolean activate() {
        if (!MasterFarmersScript.setupComplete || MasterFarmersScript.stunned || MasterFarmersScript.needToBank) return false;

        ItemGroupResult inv = script.getWidgetManager().getInventory().search(MasterFarmersScript.COIN_POUCH_IDS);
        if (inv == null) return false;

        return findPouchToOpen(inv).isPresent();
    }

    @Override
    public boolean execute() {
        MasterFarmersScript.state = MasterFarmersScript.State.PICKPOCKET;

        if (!ensureInventoryTabOpen()) {
            return false;
        }

        script.getWidgetManager().getInventory().unSelectItemIfSelected();

        ItemGroupResult inv = script.getWidgetManager().getInventory().search(MasterFarmersScript.COIN_POUCH_IDS);
        if (inv == null) return false;

        Optional<Integer> pouchIdOpt = findPouchToOpen(inv);
        if (pouchIdOpt.isEmpty()) return false;

        int pouchId = pouchIdOpt.get();
        ItemSearchResult pouch = inv.getItem(pouchId);
        if (pouch == null) return false;

        int beforeCount = inv.getAmount(pouchId);

        boolean interacted = pouch.interact("Open-all");
        if (!interacted) {
            interacted = pouch.interact();
        }
        if (!interacted) return false;

        script.pollFramesHuman(() -> false, script.random(INTERACT_DELAY_MIN, INTERACT_DELAY_MAX));

        script.pollFramesUntil(() -> {
            ItemGroupResult refreshed = script.getWidgetManager().getInventory().search(MasterFarmersScript.COIN_POUCH_IDS);
            if (refreshed == null) return false;
            if (!refreshed.contains(pouchId)) return true;
            return refreshed.getAmount(pouchId) < beforeCount;
        }, script.random(480, 1000));

        script.pollFramesHuman(() -> false, script.random(POST_OPEN_DELAY_MIN, POST_OPEN_DELAY_MAX));

        MasterFarmersScript.state = MasterFarmersScript.needToBank ? MasterFarmersScript.State.BANK : MasterFarmersScript.State.PICKPOCKET;
        return false;
    }

    private Optional<Integer> findPouchToOpen(ItemGroupResult inv) {
        if (inv == null) return Optional.empty();
        for (Integer id : MasterFarmersScript.COIN_POUCH_IDS) {
            if (id == null) continue;
            if (!inv.contains(id)) continue;
            if (inv.getAmount(id) >= OPEN_THRESHOLD) {
                return Optional.of(id);
            }
        }
        return Optional.empty();
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
            return inventory != null && inventory.search(Set.of()) != null;
        }, script.random(60, 120));
    }
}
