package tasks;

import com.osmb.api.world.World;
import com.osmb.api.world.WorldType;
import data.State;
import main.ShopperScript;
import utils.Task;

import java.util.ArrayList;
import java.util.List;

public class HopTask extends Task {
    private final ShopperScript shopper;
    private final OpenPacksTask openPacksTask;

    public HopTask(ShopperScript script, OpenPacksTask openPacksTask) {
        super(script);
        this.shopper = script;
        this.openPacksTask = openPacksTask;
    }

    @Override
    public boolean activate() {
        return shopper.isHopRequested();
    }

    @Override
    public boolean execute() {
        triggerWorldHop();
        shopper.setHopRequested(false);
        return true;
    }

    public boolean maybeHopForStock(int remainingTarget, int shopStock, String reason) {
        if (!shouldHopBasedOnConfig(shopStock, remainingTarget)) {
            return false;
        }
        if (openPacksTask.openNextPack(true)) {
            shopper.setState(State.OPENING_SHOP);
            return true;
        }
        String details = shopper.getHopWhen().describe(shopper.getHopStockComparator(), shopper.getHopStockThreshold());
        requestHop(reason + " - condition " + details + " (stock=" + shopStock + ")", remainingTarget);
        return true;
    }

    private boolean shouldHopBasedOnConfig(int shopStock, int remainingTarget) {
        if (shopper.getHopWhen() == null || remainingTarget <= 0) {
            return false;
        }
        if (!shopper.isHoppingEnabled()) {
            return false;
        }
        if (shopper.getTargetAmount() <= 0) {
            return false;
        }
        return shopper.getHopWhen().shouldHop(shopStock, shopper.getHopStockComparator(), shopper.getHopStockThreshold());
    }

    private void requestHop(String reason, int remainingTarget) {
        if (shopper.isHopRequested()) {
            return;
        }
        shopper.setHopRequested(true);
        shopper.log(getClass().getSimpleName(), reason + " - hopping for remaining " + remainingTarget + ".");

        shopper.closeShopInterface();
        shopper.setState(State.OPENING_SHOP);
    }

    private void triggerWorldHop() {
        try {
            Integer currentWorld = shopper.getCurrentWorld();
            if (shopper.getProfileManager() == null) {
                shopper.log(getClass().getSimpleName(), "Profile manager unavailable; cannot hop.");
                shopper.setHopRequested(false);
                return;
            }
            shopper.getProfileManager().forceHop(worlds -> {
                if (worlds == null || worlds.isEmpty()) {
                    shopper.log(getClass().getSimpleName(), "No worlds available to hop to.");
                    shopper.setHopRequested(false);
                    return null;
                }
                List<World> filtered = new ArrayList<>();
                for (World world : worlds) {
                    if (world == null) {
                        continue;
                    }
                    if (currentWorld != null && world.getId() == currentWorld) {
                        continue;
                    }
                    boolean isMember = world.isMembers();
                    boolean isNormal = world.getCategory() == WorldType.NORMAL;
                    boolean isSkillTotal = world.getSkillTotal() != null && world.getSkillTotal() != com.osmb.api.world.SkillTotal.NONE;
                    boolean isIgnored = world.isIgnore();
                    if (isMember && isNormal && !isSkillTotal && !isIgnored) {
                        filtered.add(world);
                    }
                }
                if (!filtered.isEmpty()) {
                    return filtered.get(shopper.random(0, filtered.size()));
                }
                for (World world : worlds) {
                    if (world != null && world.isMembers() && (currentWorld == null || world.getId() != currentWorld)) {
                        return world;
                    }
                }
                return worlds.get(0);
            });
        } catch (Exception e) {
            shopper.log(getClass().getSimpleName(), "Failed to hop worlds: " + e.getMessage());
            shopper.setHopRequested(false);
        }
    }
}
