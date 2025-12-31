package tasks;

import main.MasterFarmersScript;
import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ItemSearchResult;
import com.osmb.api.script.Script;
import com.osmb.api.ui.tabs.Tab;
import utils.Task;

import java.util.Set;

public class EatTask extends Task {

    private static final int FULL_HP = 100;
    private static final int EAT_INTERACT_DELAY_MIN = 200;
    private static final int EAT_INTERACT_DELAY_MAX = 360;
    private static final int POST_EAT_DELAY_MIN = 140;
    private static final int POST_EAT_DELAY_MAX = 260;

    public EatTask(Script script) {
        super(script);
    }

    @Override
    public boolean activate() {
        if (!MasterFarmersScript.setupComplete || MasterFarmersScript.stunned) return false;

        handleLowHpWithoutFood();

        if (MasterFarmersScript.needToBank) return false;
        if (MasterFarmersScript.foodItemId == null) return false;

        Integer hpPerc = getHitpointsPercentage();
        if (hpPerc == null || hpPerc >= MasterFarmersScript.HP_THRESHOLD) return false;

        ItemGroupResult inv = script.getWidgetManager().getInventory().search(Set.of(MasterFarmersScript.foodItemId));
        return inv != null && inv.contains(MasterFarmersScript.foodItemId);
    }

    @Override
    public boolean execute() {
        MasterFarmersScript.state = MasterFarmersScript.State.EATING;

        if (!ensureInventoryTabOpen()) {
            return false;
        }

        script.getWidgetManager().getInventory().unSelectItemIfSelected();

        for (int i = 0; i < 12; i++) {
            Integer hp = getHitpointsPercentage();
            if (hp == null) {
                break;
            }
            if (hp >= FULL_HP) {
                break;
            }

            ItemGroupResult inv = script.getWidgetManager().getInventory().search(Set.of(MasterFarmersScript.foodItemId));
            if (inv == null || !inv.contains(MasterFarmersScript.foodItemId)) {
                break;
            }

            ItemSearchResult food = inv.getItem(MasterFarmersScript.foodItemId);
            if (food == null || !food.interact()) {
                script.pollFramesHuman(() -> false, script.random(EAT_INTERACT_DELAY_MIN, EAT_INTERACT_DELAY_MAX));
                continue;
            }

            int beforeCount = inv.getAmount(MasterFarmersScript.foodItemId);
            boolean consumed = script.pollFramesUntil(() -> {
                Integer updatedHp = getHitpointsPercentage();
                ItemGroupResult refreshed = script.getWidgetManager().getInventory().search(Set.of(MasterFarmersScript.foodItemId));
                int afterCount = refreshed != null ? refreshed.getAmount(MasterFarmersScript.foodItemId) : 0;
                return (updatedHp != null && updatedHp > hp) || afterCount < beforeCount;
            }, script.random(320, 780));

            script.pollFramesHuman(() -> false, script.random(POST_EAT_DELAY_MIN, POST_EAT_DELAY_MAX));

            if (!consumed) {
                break;
            }
        }

        MasterFarmersScript.state = MasterFarmersScript.needToBank ? MasterFarmersScript.State.BANK : MasterFarmersScript.State.PICKPOCKET;
        return false;
    }

    private void handleLowHpWithoutFood() {
        Integer hpPerc = getHitpointsPercentage();
        if (hpPerc == null || MasterFarmersScript.foodItemId == null || hpPerc >= MasterFarmersScript.HP_THRESHOLD) {
            return;
        }

        ItemGroupResult inv = script.getWidgetManager().getInventory().search(Set.of(MasterFarmersScript.foodItemId));
        boolean hasFood = inv != null && inv.contains(MasterFarmersScript.foodItemId);
        if (hasFood) {
            return;
        }

        MasterFarmersScript.needToBank = true;
        MasterFarmersScript.state = MasterFarmersScript.State.BANK;

        if (!MasterFarmersScript.needsFoodRestock) {
            MasterFarmersScript.needsFoodRestock = true;
            script.log(getClass().getSimpleName(), "HP at " + hpPerc + "% and no food (itemId=" + MasterFarmersScript.foodItemId + ") available. Banking to restock.");
        }
    }

    private Integer getHitpointsPercentage() {
        return MasterFarmersScript.getHitpointsPercentage(script);
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
