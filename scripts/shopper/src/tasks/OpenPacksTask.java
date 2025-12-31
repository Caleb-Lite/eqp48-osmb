package tasks;

import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ItemSearchResult;
import com.osmb.api.shape.Rectangle;
import com.osmb.api.utils.UIResult;
import data.State;
import main.ShopperScript;
import utils.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class OpenPacksTask extends Task {
    private final ShopperScript shopper;

    public OpenPacksTask(ShopperScript script) {
        super(script);
        this.shopper = script;
    }

    @Override
    public boolean activate() {
        if (!shopper.isInitialised() || !shopper.isOpenPacksEnabled()) {
            return false;
        }
        if (shopper.getWidgetManager() == null || shopper.getItemManager() == null) {
            return false;
        }
        return shopper.isWaitingForPacksToClear() || hasPacksInInventory();
    }

    @Override
    public boolean execute() {
        if (shopper.isWaitingForPacksToClear()) {
            boolean cleared = shopper.pollFramesHuman(
                () -> !hasPacksInInventory(),
                shopper.gaussianDelay(150, 500, 300, 80)
            );
            if (cleared) {
                shopper.setWaitingForPacksToClear(false);
                shopper.setWaitingPackId(-1);
                shopper.log(getClass().getSimpleName(), "Packs cleared; resuming.");
                shopper.setState(State.OPENING_SHOP);
            }
            return true;
        }

        return openNextPack(shopper.isShopVisible());
    }

    public boolean openNextPack(boolean closeShopFirst) {
        if (!shopper.isOpenPacksEnabled()) {
            return false;
        }
        if (shopper.getWidgetManager() == null || shopper.getItemManager() == null) {
            return false;
        }
        if (closeShopFirst) {
            shopper.closeShopInterface();
        }

        ItemGroupResult inventoryScan = shopper.getItemManager()
            .scanItemGroup(shopper.getWidgetManager().getInventory(), shopper.getPackItemIds());
        if (inventoryScan == null) {
            return false;
        }

        List<ItemSearchResult> items = new ArrayList<>();
        List<ItemSearchResult> recognised = inventoryScan.getAllOfItems(shopper.getPackItemIds());
        if (recognised != null && !recognised.isEmpty()) {
            items.addAll(recognised);
        }
        if (items.isEmpty()) {
            return false;
        }
        items.sort((a, b) -> Integer.compare(slotOf(a), slotOf(b)));

        for (ItemSearchResult item : items) {
            if (!isPackItem(item)) {
                continue;
            }
            UIResult<Rectangle> tappable = item.getTappableBounds();
            if (tappable == null || tappable.isNotVisible() || tappable.isNotFound()) {
                continue;
            }
            Rectangle bounds = tappable.get();
            if (bounds == null) {
                continue;
            }
            shopper.log(getClass().getSimpleName(), "Opening pack: " + safeItemName(item.getId()) + " (single tap)");
            boolean tapped = shopper.submitHumanTask(() -> shopper.getFinger().tap(bounds), shopper.gaussianDelay(300, 900, 500, 150));
            if (tapped) {
                shopper.setWaitingForPacksToClear(true);
                shopper.setWaitingPackId(item.getId());
            }
            return tapped;
        }

        return false;
    }

    private boolean hasPacksInInventory() {
        if (shopper.getWidgetManager() == null || shopper.getItemManager() == null) {
            return false;
        }
        Set<Integer> idsToCheck = new java.util.HashSet<>();
        if (shopper.getWaitingPackId() > 0) {
            idsToCheck.add(shopper.getWaitingPackId());
        }
        if (idsToCheck.isEmpty()) {
            idsToCheck = shopper.getPackItemIds();
        }
        ItemGroupResult inventoryScan = shopper.getItemManager()
            .scanItemGroup(shopper.getWidgetManager().getInventory(), idsToCheck);
        if (inventoryScan == null) {
            return false;
        }
        List<ItemSearchResult> remaining = inventoryScan.getAllOfItems(idsToCheck);
        return remaining != null && !remaining.isEmpty();
    }

    private int slotOf(ItemSearchResult item) {
        if (item == null) {
            return Integer.MAX_VALUE;
        }
        try {
            return item.getItemSlot();
        } catch (Exception ignored) {
        }
        try {
            return item.getSlot();
        } catch (Exception ignored) {
        }
        return Integer.MAX_VALUE;
    }

    private boolean isPackItem(ItemSearchResult item) {
        if (item == null) {
            return false;
        }
        if (shopper.getPackItemIds().contains(item.getId())) {
            return true;
        }
        String name = safeItemName(item.getId());
        return name != null && name.toLowerCase().contains("pack");
    }

    private String safeItemName(int id) {
        try {
            return shopper.getItemManager() != null ? shopper.getItemManager().getItemName(id) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
