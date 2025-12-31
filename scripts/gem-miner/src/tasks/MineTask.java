package tasks;

import main.GemMinerScript;
import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.script.Script;
import com.osmb.api.shape.Polygon;
import com.osmb.api.shape.Rectangle;
import com.osmb.api.ui.component.tabs.skill.SkillType;
import com.osmb.api.utils.timing.Timer;
import com.osmb.api.input.MenuEntry;
import utils.Task;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MineTask extends Task {

    private static final String TARGET_OBJECT_NAME = "Gem rocks";

    public MineTask(Script script) {
        super(script);
    }

    @Override
    public boolean activate() {
        return GemMinerScript.setupComplete && !isInventoryFull();
    }

    @Override
    public boolean execute() {
        GemMinerScript.state = GemMinerScript.State.MINING;

        WorldPosition myPos = script.getWorldPosition();
        if (myPos == null) {
            return false;
        }

        List<WorldPosition> respawnCircles = getRespawnCirclePositions();
        List<RSObject> gemRocks = script.getObjectManager().getObjects(object ->
            object != null &&
                object.isInteractableOnScreen() &&
                object.getWorldPosition() != null &&
                allowRock(object.getWorldPosition(), respawnCircles) &&
                isGemRock(object) &&
                hasMineAction(object)
        );

        if (gemRocks == null || gemRocks.isEmpty()) {
            GemMinerScript.lastWalkTarget = null;
            if (respawnCircles == null || respawnCircles.isEmpty()) {
                WorldPosition mineAnchor = GemMinerScript.selectedLocation.minePosition();
                if (mineAnchor != null) {
                    script.getWalker().walkTo(mineAnchor, new com.osmb.api.walker.WalkConfig.Builder()
                        .breakCondition(this::hasMineableGemOnScreen)
                        .build());
                }
            }
            return false;
        }

        if (GemMinerScript.lastWalkTarget != null) {
            gemRocks.sort(Comparator.comparingDouble(o -> o.getWorldPosition().distanceTo(GemMinerScript.lastWalkTarget)));
        } else {
            gemRocks.sort(Comparator.comparingDouble(o -> o.getWorldPosition().distanceTo(myPos)));
        }
        RSObject gemRock = gemRocks.get(0);
        GemMinerScript.lastWalkTarget = null;
        if (gemRock == null) {
            return false;
        }

        if (!waitForPlayerIdle()) {
            return false;
        }

        if (!tapGemRock(gemRock)) {
            return false;
        }

        boolean mined = waitForMiningCompletion(gemRock.getWorldPosition());
        if (mined && gemRock.getWorldPosition() != null) {
            GemMinerScript.waitingRespawn.add(gemRock.getWorldPosition());
        }

        return false;
    }

    private boolean isInventoryFull() {
        var inventoryComponent = script.getWidgetManager().getInventory();
        if (inventoryComponent == null) {
            return false;
        }

        ItemGroupResult inventory = inventoryComponent.search(Collections.emptySet());
        return inventory != null && inventory.isFull();
    }

    private boolean isGemRock(RSObject object) {
        if (object == null || object.getName() == null) {
            return false;
        }
        String name = object.getName().trim();
        if ("Rocks".equalsIgnoreCase(name)) {
            return false;
        }
        return TARGET_OBJECT_NAME.equalsIgnoreCase(name);
    }

    private boolean hasMineAction(RSObject object) {
        String[] actions = object.getActions();
        if (actions == null) {
            return false;
        }
        for (String action : actions) {
            if ("Mine".equalsIgnoreCase(action)) {
                return true;
            }
        }
        return false;
    }

    private boolean allowRock(WorldPosition position, List<WorldPosition> respawnCircles) {
        boolean hasRespawnCircle = respawnCircles != null && respawnCircles.contains(position);
        if (GemMinerScript.waitingRespawn.contains(position)) {
            if (!hasRespawnCircle) {
                return false;
            }
            GemMinerScript.waitingRespawn.remove(position);
            return false;
        }
        return !hasRespawnCircle;
    }

    private List<WorldPosition> getRespawnCirclePositions() {
        List<Rectangle> respawnCircles = script.getPixelAnalyzer().findRespawnCircles();
        return script.getUtils().getWorldPositionForRespawnCircles(respawnCircles, 20);
    }

    private boolean waitForPlayerIdle() {
        Timer stationaryTimer = new Timer();
        WorldPosition[] lastPosition = { script.getWorldPosition() };

        return script.submitHumanTask(() -> {
            WorldPosition current = script.getWorldPosition();
            if (current == null) {
                return false;
            }

            if (lastPosition[0] == null || !current.equals(lastPosition[0])) {
                lastPosition[0] = current;
                stationaryTimer.reset();
            }

            boolean stationary = stationaryTimer.timeElapsed() > 250;
            boolean animating = script.getPixelAnalyzer().isPlayerAnimating(0.4);
            return stationary && !animating;
        }, 4_000);
    }

    private boolean waitForMiningCompletion(WorldPosition targetPos) {
        final boolean[] respawnSeen = { false };
        final double[] lastXp = { GemMinerScript.getMiningXp(script) };

        boolean completed = script.submitHumanTask(() -> {
            ItemGroupResult inventory = script.getWidgetManager().getInventory().search(Collections.emptySet());
            boolean inventoryFull = inventory != null && inventory.isFull();

            List<WorldPosition> respawnCircles = getRespawnCirclePositions();
            boolean targetRespawned = targetPos != null && respawnCircles != null && respawnCircles.contains(targetPos);
            if (targetRespawned) {
                respawnSeen[0] = true;
            }

            double currentXp = GemMinerScript.getMiningXp(script);
            double xpGain = currentXp - lastXp[0];
            if (xpGain > 0) {
                if (GemMinerScript.gemXpPerRock == null || xpGain < GemMinerScript.gemXpPerRock) {
                    GemMinerScript.gemXpPerRock = xpGain;
                }
                double denom = GemMinerScript.gemXpPerRock != null && GemMinerScript.gemXpPerRock > 0 ? GemMinerScript.gemXpPerRock : xpGain;
                int ticks = (int) Math.max(1, Math.round(xpGain / denom));
                GemMinerScript.gemsMined += ticks;
                GemMinerScript.lastMineGainedXp = true;
            }
            lastXp[0] = currentXp;

            return targetRespawned || inventoryFull;
        }, script.random(6_000, 8_000));

        if (respawnSeen[0] && targetPos != null) {
            GemMinerScript.waitingRespawn.add(targetPos);
        }
        return completed;
    }

    private boolean tapGemRock(RSObject rock) {
        if (rock == null) {
            return false;
        }
        Polygon hull = script.getSceneProjector().getConvexHull(rock);
        if (hull == null || hull.numVertices() == 0) {
            return false;
        }
        Polygon shrunk = hull.getResized(0.7);
        Polygon targetHull = shrunk != null ? shrunk : hull;
        return script.submitHumanTask(() -> {
            MenuEntry response = script.getFinger().tapGetResponse(false, targetHull);
            if (response == null) {
                return false;
            }
            String action = response.getAction();
            String name = response.getEntityName();
            return action != null && name != null &&
                "mine".equalsIgnoreCase(action) &&
                TARGET_OBJECT_NAME.equalsIgnoreCase(name);
        }, 2_000);
    }

    private boolean hasMineableGemOnScreen() {
        List<WorldPosition> respawnCircles = getRespawnCirclePositions();
        RSObject gem = script.getObjectManager().getRSObject(object ->
            object != null &&
                object.isInteractableOnScreen() &&
                object.getWorldPosition() != null &&
                isGemRock(object) &&
                hasMineAction(object) &&
                allowRock(object.getWorldPosition(), respawnCircles)
        );
        return gem != null;
    }
}
