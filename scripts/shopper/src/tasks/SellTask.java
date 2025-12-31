package tasks;

import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ItemSearchResult;
import data.State;
import main.ShopperScript;
import utils.Task;

import java.util.Set;

public class SellTask extends Task {
    private final ShopperScript shopper;

    public SellTask(ShopperScript script) {
        super(script);
        this.shopper = script;
    }

    @Override
    public boolean activate() {
        return shopper.isInitialised()
            && shopper.getMode() == ShopperScript.Mode.SELL
            && shopper.isShopVisible()
            && shopper.getState() != State.STOPPED;
    }

    @Override
    public boolean execute() {
        shopper.setState(State.SELLING);
        shopper.setHopRequested(false);

        if (!validateTargets()) {
            return false;
        }

        ItemGroupResult inventorySnapshot = shopper.getWidgetManager().getInventory().search(Set.of(shopper.getTargetItemId()));
        if (inventorySnapshot == null) {
            return false;
        }

        int before = inventorySnapshot.getAmount(shopper.getTargetItemId());
        if (before <= 0) {
            shopper.log(getClass().getSimpleName(), "No stock to sell.");
            shopper.setState(State.STOPPED);
            shopper.closeShopInterface();
            return true;
        }

        ItemSearchResult itemToSell = shopper.getItemManager()
            .scanItemGroup(shopper.getWidgetManager().getInventory(), Set.of(shopper.getTargetItemId()))
            .getItem(shopper.getTargetItemId());
        if (itemToSell == null) {
            shopper.log(getClass().getSimpleName(), "Could not find item in inventory to sell; closing.");
            shopper.closeShopInterface();
            shopper.setState(State.STOPPED);
            return true;
        }

        int actionQuantity = resolveSellQuantity(before);

        if (!selectSellQuantity(actionQuantity)) {
            shopper.log(getClass().getSimpleName(), "Failed to set sell quantity to " + actionQuantity);
            return false;
        }

        if (!itemToSell.interact()) {
            shopper.log(getClass().getSimpleName(), "Interaction with inventory item failed.");
            return false;
        }

        shopper.pollFramesHuman(() -> false, shopper.random(200, 350));

        boolean sold = true;

        if (sold) {
            int soldNow = Math.max(1, actionQuantity);
            shopper.addBought(soldNow);
            shopper.log(getClass().getSimpleName(), "Sold " + soldNow + " (" + shopper.getBought() + "/" + shopper.getTargetAmount() + ")");

            if (shopper.getTargetAmount() > 0 && shopper.getBought() >= shopper.getTargetAmount()) {
                shopper.log(getClass().getSimpleName(), "Reached sell goal; closing shop.");
                shopper.closeShopInterface();
                shopper.setState(State.STOPPED);
            }
        }
        return true;
    }

    private boolean validateTargets() {
        if (shopper.getTargetItemId() <= 0) {
            shopper.log(getClass().getSimpleName(), "Missing item id; stopping.");
            shopper.setState(State.STOPPED);
            return false;
        }
        if (shopper.getTargetAmount() > 0 && shopper.getBought() >= shopper.getTargetAmount()) {
            shopper.log(getClass().getSimpleName(), "Reached target amount; stopping.");
            shopper.setState(State.STOPPED);
            shopper.closeShopInterface();
            return false;
        }
        return true;
    }

    private int resolveSellQuantity(int inventoryAmount) {
        int remainingTarget = shopper.getTargetAmount() > 0 ? Math.max(0, shopper.getTargetAmount() - shopper.getBought()) : Integer.MAX_VALUE;
        int available = Math.max(inventoryAmount, 0);
        int needed = Math.min(remainingTarget == 0 ? 1 : remainingTarget, available == 0 ? 1 : available);
        return chooseChunk(needed);
    }

    private boolean selectSellQuantity(int quantity) {
        return switch (quantity) {
            case 1 -> shopper.getShopInterface() != null && shopper.getShopInterface().setSelectedAmount(1);
            case 5 -> shopper.getShopInterface() != null && shopper.getShopInterface().setSelectedAmount(5);
            case 10 -> shopper.getShopInterface() != null && shopper.getShopInterface().setSelectedAmount(10);
            case 50 -> shopper.getShopInterface() != null && shopper.getShopInterface().setSelectedAmount(50);
            default -> shopper.getShopInterface() != null && shopper.getShopInterface().setSelectedAmount(quantity);
        };
    }

    private int chooseChunk(int needed) {
        int capped = Math.max(1, needed);
        int[] options = new int[]{50, 10, 5, 1};
        for (int opt : options) {
            if (opt <= capped) {
                return opt;
            }
        }
        return 1;
    }
}
