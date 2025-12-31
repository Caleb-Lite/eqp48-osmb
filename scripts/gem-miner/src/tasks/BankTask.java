package tasks;

import main.GemMinerScript;
import data.Locations.MiningLocation;
import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.script.Script;
import com.osmb.api.ui.depositbox.DepositBox;
import utils.Task;

import java.util.Collections;

public class BankTask extends Task {

    public BankTask(Script script) {
        super(script);
    }

    @Override
    public boolean activate() {
        var inventoryComponent = script.getWidgetManager().getInventory();
        if (inventoryComponent == null) {
            return false;
        }

        ItemGroupResult inventory = inventoryComponent.search(Collections.emptySet());
        if (inventory == null) {
            return false;
        }

        return inventory.isFull();
    }

    @Override
    public boolean execute() {
        GemMinerScript.state = GemMinerScript.State.BANKING;

        MiningLocation location = GemMinerScript.selectedLocation;
        DepositBox depositBox = script.getWidgetManager().getDepositBox();
        if (depositBox != null && depositBox.isVisible()) {
            depositInventory(depositBox);
            closeDepositBox(depositBox);
            return false;
        }

        RSObject depositTarget = findDepositTarget(location);

        if (depositTarget == null || !depositTarget.isInteractableOnScreen()) {
            WorldPosition bankPosition = location.bankPosition();
            if (bankPosition != null) {
                script.getWalker().walkTo(bankPosition, new com.osmb.api.walker.WalkConfig.Builder()
                    .breakCondition(() -> {
                        var box = script.getWidgetManager().getDepositBox();
                        return (box != null && box.isVisible()) || hasDepositTargetOnScreen(location);
                    })
                    .build());
                depositTarget = findDepositTarget(location);
            }
        }

        if (depositTarget != null && depositTarget.isInteractableOnScreen()) {
            boolean interacted = interactWithDepositTarget(depositTarget, location.depositAction());
            if (interacted) {
                script.submitHumanTask(() -> {
                    var box = script.getWidgetManager().getDepositBox();
                    return box != null && box.isVisible();
                }, 8_000);
            }
        }

        depositBox = script.getWidgetManager().getDepositBox();
        if (depositBox != null && depositBox.isVisible()) {
            depositInventory(depositBox);
            closeDepositBox(depositBox);
            script.submitHumanTask(() -> {
                var box = script.getWidgetManager().getDepositBox();
                return box == null || !box.isVisible();
            }, 2_000);
        }

        return false;
    }

    private RSObject findDepositTarget(MiningLocation location) {
        String targetName = location.depositObjectName();
        String action = location.depositAction();
        return script.getObjectManager().getRSObject(object ->
            object != null &&
                object.getWorldPosition() != null &&
                object.getName() != null &&
                targetName.equalsIgnoreCase(object.getName()) &&
                hasDepositAction(object, action) &&
                object.canReach()
        );
    }

    private boolean hasDepositTargetOnScreen(MiningLocation location) {
        RSObject depositBox = findDepositTarget(location);
        return depositBox != null && depositBox.isInteractableOnScreen();
    }

    private boolean hasDepositAction(RSObject object, String requiredAction) {
        String[] actions = object.getActions();
        if (actions == null) {
            return false;
        }
        for (String action : actions) {
            if (requiredAction.equalsIgnoreCase(action)) {
                return true;
            }
        }
        return false;
    }

    private boolean interactWithDepositTarget(RSObject depositTarget, String action) {
        if (depositTarget == null || action == null) {
            return false;
        }
        return script.submitHumanTask(() -> depositTarget.interact(action), 2_000);
    }

    private boolean depositInventory(DepositBox depositBox) {
        if (depositBox == null) {
            return false;
        }
        boolean deposited = script.submitHumanTask(
            () -> depositBox.depositAll(Collections.emptySet()),
            3_000
        );
        if (!deposited) {
            return false;
        }
        waitForInventoryNotFull();
        return true;
    }

    private void closeDepositBox(DepositBox depositBox) {
        if (depositBox == null) {
            return;
        }
        script.submitHumanTask(depositBox::close, 2_000);
    }

    private void waitForInventoryNotFull() {
        script.submitHumanTask(() -> {
            ItemGroupResult inv = script.getWidgetManager().getInventory().search(Collections.emptySet());
            return inv == null || !inv.isFull();
        }, 5_000);
    }
}
