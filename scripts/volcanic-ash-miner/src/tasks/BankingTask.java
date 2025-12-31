package tasks;

import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.scene.RSObject;
import com.osmb.api.script.Script;
import data.State;
import data.VolcanicAshData;
import main.VolcanicAshMiningScript;
import utils.Task;

import java.util.Collections;

/**
 * Handles banking when inventory is full.
 */
public class BankingTask extends Task {
    private final VolcanicAshMiningScript volcanicAsh;

    public BankingTask(Script script) {
        super(script);
        this.volcanicAsh = (VolcanicAshMiningScript) script;
    }

    @Override
    public boolean activate() {
        ItemGroupResult inventory = script.getWidgetManager().getInventory().search(Collections.emptySet());
        return inventory != null && inventory.isFull();
    }

    @Override
    public boolean execute() {
        volcanicAsh.setState(State.BANKING);
        // If bank is already open, deposit and close
        var bank = script.getWidgetManager().getBank();
        if (bank != null && bank.isVisible()) {
            bank.depositAll(Collections.emptySet());
            bank.close();
            script.sleep(script.random(500, 800));
            return true;
        }

        // Walk to bank position until chest is on screen or bank opens
        RSObject chest = script.getObjectManager().getRSObject(object ->
            object != null &&
                object.getWorldPosition() != null &&
                VolcanicAshData.BANK_OBJECT_NAME.equalsIgnoreCase(object.getName()) &&
                hasUseAction(object)
        );

        if (chest == null || !chest.isInteractableOnScreen()) {
            script.getWalker().walkTo(VolcanicAshData.BANK_POSITION, new com.osmb.api.walker.WalkConfig.Builder()
                .breakCondition(() -> script.getWidgetManager().getBank().isVisible() || hasBankChestOnScreen())
                .build());
            chest = script.getObjectManager().getRSObject(object ->
                object != null &&
                    object.getWorldPosition() != null &&
                    VolcanicAshData.BANK_OBJECT_NAME.equalsIgnoreCase(object.getName()) &&
                    hasUseAction(object)
            );
        }

        if (chest != null && chest.isInteractableOnScreen()) {
            chest.interact("Use");
            script.submitHumanTask(() -> {
                var b = script.getWidgetManager().getBank();
                return b != null && b.isVisible();
            }, 8_000);
        }

        // Once open, deposit inventory and close
        bank = script.getWidgetManager().getBank();
        if (bank != null && bank.isVisible()) {
            bank.depositAll(Collections.emptySet());
            bank.close();
            script.submitHumanTask(() -> {
                var b = script.getWidgetManager().getBank();
                return b == null || !b.isVisible();
            }, 5_000);
        }

        volcanicAsh.setState(State.MINING);
        script.sleep(script.random(500, 900));
        return true;
    }

    private boolean hasUseAction(RSObject object) {
        String[] actions = object.getActions();
        if (actions == null) {
            return false;
        }
        for (String action : actions) {
            if ("Use".equalsIgnoreCase(action)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasBankChestOnScreen() {
        RSObject chest = script.getObjectManager().getRSObject(object ->
            object != null &&
                object.isInteractableOnScreen() &&
                object.getName() != null &&
                VolcanicAshData.BANK_OBJECT_NAME.equalsIgnoreCase(object.getName()) &&
                hasUseAction(object)
        );
        return chest != null;
    }
}
