package tasks;

import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ItemID;
import com.osmb.api.item.ItemSearchResult;
import data.State;
import main.ShopperScript;
import utils.Task;

import java.util.Set;

public class BuyTask extends Task {
    private final ShopperScript shopper;
    private final HopTask hopTask;
    private final OpenPacksTask openPacksTask;

    public BuyTask(ShopperScript script, HopTask hopTask, OpenPacksTask openPacksTask) {
        super(script);
        this.shopper = script;
        this.hopTask = hopTask;
        this.openPacksTask = openPacksTask;
    }

    @Override
    public boolean activate() {
        return shopper.isInitialised()
            && shopper.getMode() == ShopperScript.Mode.BUY
            && shopper.isShopVisible()
            && shopper.getState() != State.STOPPED;
    }

    @Override
    public boolean execute() {
        shopper.setState(State.BUYING);
        shopper.setHopRequested(false);

        if (!validateTargets()) {
            return false;
        }

        ItemGroupResult inventorySnapshot = shopper.getWidgetManager().getInventory().search(Set.of(ItemID.COINS_995, shopper.getTargetItemId()));
        if (inventorySnapshot == null) {
            return false;
        }

        if (!validateInventory(inventorySnapshot)) {
            return true;
        }

        ItemGroupResult shopSnapshot = shopper.getShopInterface().search(Set.of(shopper.getTargetItemId()));
        if (shopSnapshot == null) {
            return false;
        }

        ItemSearchResult itemInShop = shopper.getItemManager()
            .scanItemGroup(shopper.getShopInterface(), Set.of(shopper.getTargetItemId()))
            .getItem(shopper.getTargetItemId());
        if (itemInShop == null) {
            shopper.log(getClass().getSimpleName(), "Could not find target item in shop; closing.");
            shopper.closeShopInterface();
            shopper.setState(State.STOPPED);
            return true;
        }

        int remainingTarget = shopper.getTargetAmount() > 0 ? Math.max(0, shopper.getTargetAmount() - shopper.getBought()) : Integer.MAX_VALUE;
        int shopStock = itemInShop.getStackAmount();

        if (shopStock <= 0) {
            if (hopTask.maybeHopForStock(remainingTarget, shopStock, "Item out of stock")) {
                return true;
            }
            shopper.log(getClass().getSimpleName(), "Item out of stock; closing shop.");
            shopper.closeShopInterface();
            shopper.setState(State.STOPPED);
            return true;
        }

        if (hopTask.maybeHopForStock(remainingTarget, shopStock, "Stock condition met")) {
            return true;
        }

        int before = shopper.getWidgetManager().getInventory().search(Set.of(shopper.getTargetItemId())).getAmount(shopper.getTargetItemId());
        int freeSlots = inventorySnapshot.getFreeSlots();

        int actionQuantity = resolveBuyQuantity(remainingTarget, shopStock);
        actionQuantity = adjustQuantityForHopCondition(actionQuantity, shopStock);

        if (!selectBuyQuantity(actionQuantity)) {
            shopper.log(getClass().getSimpleName(), "Failed to set buy quantity to " + actionQuantity);
            return false;
        }

        if (!itemInShop.interact()) {
            shopper.log(getClass().getSimpleName(), "Interaction with shop item failed.");
            return false;
        }

        boolean purchased = shopper.submitTask(() -> {
            ItemGroupResult afterSnapshot = shopper.getWidgetManager().getInventory().search(Set.of(shopper.getTargetItemId()));
            if (afterSnapshot == null) {
                return false;
            }
            if (afterSnapshot.isFull() && afterSnapshot.getFreeSlots() == 0 && freeSlots > 0) {
                return true;
            }
            return afterSnapshot.getAmount(shopper.getTargetItemId()) > before;
        }, 5000);

        if (purchased) {
            ItemGroupResult after = shopper.getWidgetManager().getInventory().search(Set.of(shopper.getTargetItemId()));
            int boughtNow = Math.max(1, actionQuantity);
            shopper.addBought(boughtNow);
            shopper.log(getClass().getSimpleName(), "Bought " + boughtNow + " (" + shopper.getBought() + "/" + shopper.getTargetAmount() + ")");

            int remainingAfterPurchase = shopper.getTargetAmount() > 0 ? Math.max(0, shopper.getTargetAmount() - shopper.getBought()) : Integer.MAX_VALUE;

            if (shopper.getTargetAmount() > 0 && shopper.getBought() >= shopper.getTargetAmount()) {
                shopper.log(getClass().getSimpleName(), "Reached goal; closing shop.");
                shopper.closeShopInterface();
                shopper.setState(State.STOPPED);
                return true;
            }

            if (after != null && after.isFull()) {
                if (openPacksTask.openNextPack(true)) {
                    shopper.setState(State.OPENING_SHOP);
                    return true;
                }
                if (!shopper.isOpenPacksEnabled() && !shopper.isHoppingEnabled()) {
                    shopper.log(getClass().getSimpleName(), "Inventory full after purchase; stopping.");
                    shopper.setState(State.STOPPED);
                    return true;
                }
                shopper.log(getClass().getSimpleName(), "Inventory full after purchase; waiting (packs/hopping enabled).");
                shopper.setState(State.OPENING_SHOP);
                return true;
            }

            if (remainingAfterPurchase > 0) {
                ItemGroupResult updatedShop = shopper.getShopInterface().search(Set.of(shopper.getTargetItemId()));
                int updatedStock = updatedShop == null ? 0 : updatedShop.getAmount(shopper.getTargetItemId());
                if (hopTask.maybeHopForStock(remainingAfterPurchase, updatedStock, "Shop stock condition met (" + updatedStock + ")")) {
                    return true;
                }
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

    private boolean validateInventory(ItemGroupResult inventorySnapshot) {
        if (inventorySnapshot.getAmount(ItemID.COINS_995) <= 0) {
            shopper.log(getClass().getSimpleName(), "Out of coins; stopping script.");
            shopper.setState(State.STOPPED);
            shopper.closeShopInterface();
            return false;
        }

        if (inventorySnapshot.isFull()) {
            if (openPacksTask.openNextPack(false)) {
                shopper.setState(State.OPENING_SHOP);
                return false;
            }
            if (!shopper.isOpenPacksEnabled() && !shopper.isHoppingEnabled()) {
                shopper.log(getClass().getSimpleName(), "Inventory full; stopping script.");
                shopper.setState(State.STOPPED);
                shopper.closeShopInterface();
                return false;
            }
            shopper.log(getClass().getSimpleName(), "Inventory full; waiting (packs/hopping enabled).");
            shopper.setState(State.OPENING_SHOP);
            return false;
        }
        return true;
    }

    private int resolveBuyQuantity(int remainingTarget, int shopStock) {
        int available = shopStock > 0 ? shopStock : Integer.MAX_VALUE;
        int needed = Math.min(remainingTarget == 0 ? 1 : remainingTarget, available);
        return chooseChunk(needed);
    }

    private int adjustQuantityForHopCondition(int actionQuantity, int shopStock) {
        if (shopper.getHopWhen() != ShopperScript.HopWhen.STOCK_IS || shopper.getHopStockComparator() == null || shopStock <= 0) {
            return actionQuantity;
        }
        int threshold = Math.max(0, shopper.getHopStockThreshold());
        int quantityNeeded;
        switch (shopper.getHopStockComparator()) {
            case LESS_OR_EQUAL -> quantityNeeded = shopStock - threshold;
            case LESS -> quantityNeeded = shopStock - threshold + 1;
            default -> {
                return actionQuantity;
            }
        }
        quantityNeeded = Math.max(1, quantityNeeded);
        int adjusted = chooseChunkAtLeast(quantityNeeded, shopStock);
        if (adjusted <= 0) {
            adjusted = chooseChunk(quantityNeeded);
        }
        if (adjusted <= 0) {
            adjusted = 1;
        }
        return Math.min(actionQuantity, adjusted);
    }

    private boolean selectBuyQuantity(int quantity) {
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

    private int chooseChunkAtLeast(int needed, int shopStock) {
        int[] options = new int[]{1, 5, 10, 50};
        int best = -1;
        for (int opt : options) {
            if (opt >= needed && opt <= shopStock) {
                if (best == -1 || opt < best) {
                    best = opt;
                }
            }
        }
        if (best != -1) {
            return best;
        }
        for (int i = options.length - 1; i >= 0; i--) {
            int opt = options[i];
            if (opt <= shopStock) {
                return opt;
            }
        }
        return -1;
    }
}
