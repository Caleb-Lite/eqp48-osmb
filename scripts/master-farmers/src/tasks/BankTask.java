package tasks;

import main.MasterFarmersScript;
import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.scene.RSObject;
import com.osmb.api.script.Script;
import com.osmb.api.walker.WalkConfig;
import utils.Task;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.Set;

public class BankTask extends Task {

    private static final String[] BANK_NAMES = {"Bank", "Chest", "Bank booth", "Bank chest", "Grand Exchange booth", "Bank counter", "Bank table"};
    private static final String[] BANK_ACTIONS = {"bank", "open", "use", "bank banker"};

    private static final Predicate<RSObject> bankQuery = gameObject -> {
        if (gameObject.getName() == null || gameObject.getActions() == null) return false;
        if (Arrays.stream(BANK_NAMES).noneMatch(name -> name.equalsIgnoreCase(gameObject.getName()))) return false;
        return Arrays.stream(gameObject.getActions()).anyMatch(action -> Arrays.stream(BANK_ACTIONS).anyMatch(bankAction -> bankAction.equalsIgnoreCase(action)))
                && gameObject.canReach();
    };

    public BankTask(Script script) {
        super(script);
    }

    @Override
    public boolean activate() {
        if (!MasterFarmersScript.setupComplete) return false;
        return MasterFarmersScript.needToBank;
    }

    @Override
    public boolean execute() {
        MasterFarmersScript.state = MasterFarmersScript.State.BANK;

        if (script.getWidgetManager().getBank().isVisible()) {
            return handleBanking();
        }

        if (!isInBankArea()) {
            if (MasterFarmersScript.bankArea == null) return false;
            WalkConfig cfg = new WalkConfig.Builder()
                    .enableRun(true)
                    .breakCondition(this::isInBankArea)
                    .build();
            return script.getWalker().walkTo(MasterFarmersScript.bankArea.getRandomPosition(), cfg);
        }

        openBank();
        return false;
    }

    private boolean isInBankArea() {
        var pos = script.getWorldPosition();
        return pos != null && MasterFarmersScript.bankArea != null && MasterFarmersScript.bankArea.contains(pos);
    }

    private void openBank() {
        if (script.getWidgetManager().getBank().isVisible()) return;

        List<RSObject> banks = script.getObjectManager().getObjects(bankQuery);
        if (banks.isEmpty()) return;

        RSObject closest = (RSObject) script.getUtils().getClosest(banks);
        if (closest == null) return;

        if (!closest.isInteractableOnScreen()) {
            WalkConfig cfg = new WalkConfig.Builder()
                    .enableRun(true)
                    .breakCondition(closest::isInteractableOnScreen)
                    .build();
            script.getWalker().walkTo(closest.getWorldPosition(), cfg);
            return;
        }

        if (!closest.interact(BANK_ACTIONS)) return;

        script.pollFramesHuman(() -> script.getWidgetManager().getBank().isVisible(), script.random(4000, 6000));
    }

    private boolean handleBanking() {
        ItemGroupResult inv = script.getWidgetManager().getInventory().search(Collections.emptySet());
        if (inv == null) return false;

        if (MasterFarmersScript.keepItemIds == null || MasterFarmersScript.keepItemIds.isEmpty()) {
            MasterFarmersScript.keepItemIds = new java.util.HashSet<>(MasterFarmersScript.DEFAULT_KEEP_ITEMS);
        }

        boolean deposited = script.getWidgetManager().getBank().depositAll(MasterFarmersScript.keepItemIds);
        if (!deposited) return false;

        boolean cleared = script.pollFramesUntil(() -> !hasUnkeptItems(), script.random(600, 1200));
        if (!cleared) {
            MasterFarmersScript.needToBank = true;
            return false;
        }

        if (MasterFarmersScript.needsFoodRestock && MasterFarmersScript.foodItemId != null) {
            boolean restocked = restockFood();
            if (!restocked) {
                return false;
            }
        }

        MasterFarmersScript.needToBank = false;
        return finalizeBanking();
    }

    private boolean hasUnkeptItems() {
        ItemGroupResult refreshed = script.getWidgetManager().getInventory().search(Collections.emptySet());
        if (refreshed == null) return true;

        if (MasterFarmersScript.keepItemIds == null || MasterFarmersScript.keepItemIds.isEmpty()) {
            MasterFarmersScript.keepItemIds = new java.util.HashSet<>(MasterFarmersScript.DEFAULT_KEEP_ITEMS);
        }

        return refreshed.getOneOfEachItem().stream()
                .anyMatch(item -> !MasterFarmersScript.keepItemIds.contains(item.getId()));
    }

    private boolean restockFood() {
        var bank = script.getWidgetManager().getBank();
        if (bank == null) return false;

        ItemGroupResult bankFood = null;
        int searchAttempts = 0;
        int maxAttempts = 3;

        while (searchAttempts < maxAttempts) {
            bankFood = bank.search(Set.of(MasterFarmersScript.foodItemId));
            if (bankFood != null && bankFood.contains(MasterFarmersScript.foodItemId)) {
                break;
            }

            searchAttempts++;

            if (searchAttempts >= maxAttempts) {
                MasterFarmersScript.state = MasterFarmersScript.State.STOPPED;
                script.log(getClass().getSimpleName(), "Food not found in bank (itemId=" + MasterFarmersScript.foodItemId + ") after " + searchAttempts + " attempts. Stopping script.");
                script.stop();
                return false;
            }

            script.log(getClass().getSimpleName(), "Food not found in bank (itemId=" + MasterFarmersScript.foodItemId + ") attempt " + searchAttempts + "/" + maxAttempts + ". Retrying...");
            script.pollFramesHuman(() -> false, script.random(250, 600));
        }

        int withdrawAmount = Math.min(5, bankFood.getAmount(MasterFarmersScript.foodItemId));
        ItemGroupResult invSnapshot = script.getWidgetManager().getInventory().search(Collections.emptySet());
        int beforeInvCount = invSnapshot != null ? invSnapshot.getAmount(MasterFarmersScript.foodItemId) : 0;

        if (!bank.withdraw(MasterFarmersScript.foodItemId, withdrawAmount)) {
            MasterFarmersScript.needToBank = true;
            return false;
        }

        boolean received = script.pollFramesUntil(() -> {
            ItemGroupResult refreshed = script.getWidgetManager().getInventory().search(Collections.emptySet());
            if (refreshed == null) return false;
            return refreshed.getAmount(MasterFarmersScript.foodItemId) >= beforeInvCount + withdrawAmount;
        }, script.random(600, 1200));

        if (!received) {
            MasterFarmersScript.needToBank = true;
            return false;
        }

        MasterFarmersScript.needsFoodRestock = false;
        return true;
    }

    private boolean finalizeBanking() {
        MasterFarmersScript.state = MasterFarmersScript.State.PICKPOCKET;

        script.getWidgetManager().getBank().close();
        script.pollFramesHuman(() -> !script.getWidgetManager().getBank().isVisible(), script.random(2000, 4000));

        if (MasterFarmersScript.thievingArea != null && !MasterFarmersScript.thievingArea.contains(script.getWorldPosition()) && MasterFarmersScript.anchorTile != null) {
            WalkConfig cfg = new WalkConfig.Builder()
                    .enableRun(true)
                    .breakCondition(() -> MasterFarmersScript.thievingArea.contains(script.getWorldPosition()))
                    .build();
            script.getWalker().walkTo(MasterFarmersScript.anchorTile, cfg);
        }

        MasterFarmersScript.needToBank = false;
        return false;
    }
}
