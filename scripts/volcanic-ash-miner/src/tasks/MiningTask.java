package tasks;

import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.script.Script;
import com.osmb.api.shape.Polygon;
import com.osmb.api.shape.Rectangle;
import com.osmb.api.ui.component.tabs.skill.SkillType;
import com.osmb.api.utils.timing.Timer;
import com.osmb.api.input.MenuEntry;
import data.State;
import data.VolcanicAshData;
import main.VolcanicAshMiningScript;
import utils.Task;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Handles mining volcanic ash piles.
 */
public class MiningTask extends Task {
    private final VolcanicAshMiningScript volcanicAsh;
    private final Set<WorldPosition> waitingRespawn = new HashSet<>();
    private WorldPosition lastWalkTarget = null;
    public int ashMined = 0;
    public Double ashXpPerDrop = null;
    public boolean lastMineGainedXp = false;

    public MiningTask(Script script) {
        super(script);
        this.volcanicAsh = (VolcanicAshMiningScript) script;
    }

    @Override
    public boolean activate() {
        // Always active when inventory is not full
        ItemGroupResult inventory = script.getWidgetManager().getInventory().search(Collections.emptySet());
        return inventory != null && !inventory.isFull();
    }

    @Override
    public boolean execute() {
        volcanicAsh.setState(State.MINING);
        WorldPosition myPos = script.getWorldPosition();
        if (myPos == null) {
            script.sleep(600);
            return true;
        }

        List<WorldPosition> respawnCircles = getRespawnCirclePositions();
        List<RSObject> ashPiles = script.getObjectManager().getObjects(object ->
            object != null &&
                object.isInteractableOnScreen() &&
                object.getWorldPosition() != null &&
                allowAshPile(object.getWorldPosition(), respawnCircles) &&
                object.getName() != null &&
                VolcanicAshData.TARGET_OBJECT_NAME.equalsIgnoreCase(object.getName()) &&
                hasMineAction(object)
        );

        if (ashPiles == null || ashPiles.isEmpty()) {
            RSObject nearest = script.getObjectManager().getRSObject(object ->
                object != null &&
                    object.getWorldPosition() != null &&
                    object.getName() != null &&
                    VolcanicAshData.TARGET_OBJECT_NAME.equalsIgnoreCase(object.getName()) &&
                    hasMineAction(object) &&
                    allowAshPile(object.getWorldPosition(), respawnCircles)
            );
            if (nearest != null && nearest.getWorldPosition() != null) {
                lastWalkTarget = nearest.getWorldPosition();
                script.getWalker().walkTo(lastWalkTarget, new com.osmb.api.walker.WalkConfig.Builder()
                    .breakCondition(this::hasMineableAshOnScreen)
                    .build());
            }
            script.sleep(script.random(500, 800));
            return true;
        }

        if (lastWalkTarget != null) {
            ashPiles.sort(Comparator.comparingDouble(o -> o.getWorldPosition().distanceTo(lastWalkTarget)));
        } else {
            ashPiles.sort(Comparator.comparingDouble(o -> o.getWorldPosition().distanceTo(myPos)));
        }
        RSObject ashPile = ashPiles.get(0);
        lastWalkTarget = null;
        if (ashPile == null) {
            script.sleep(script.random(500, 800));
            return true;
        }

        if (!waitForPlayerIdle()) {
            script.sleep(script.random(400, 700));
            return true;
        }

        if (!tapAshPile(ashPile)) {
            script.sleep(script.random(400, 700));
            return true;
        }

        boolean mined = waitForMiningCompletion(ashPile.getWorldPosition());
        if (mined && ashPile.getWorldPosition() != null) {
            waitingRespawn.add(ashPile.getWorldPosition());
        }

        script.sleep(mined ? script.random(120, 240) : script.random(250, 500));
        return true;
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

    private boolean allowAshPile(WorldPosition position, List<WorldPosition> respawnCircles) {
        if (!isWhitelisted(position)) {
            return false;
        }
        boolean hasRespawnCircle = respawnCircles != null && respawnCircles.contains(position);
        if (waitingRespawn.contains(position)) {
            // Block until we have seen the respawn circle at least once
            if (!hasRespawnCircle) {
                return false;
            }
            // Once the respawn circle is seen, remove from the waiting set but still block this tick
            waitingRespawn.remove(position);
            return false;
        }
        if (hasRespawnCircle) {
            return false;
        }
        return true;
    }

    private boolean isWhitelisted(WorldPosition position) {
        if (position == null) {
            return false;
        }
        if (VolcanicAshData.WHITELISTED_ASH_PILES.isEmpty()) {
            return true;
        }
        for (WorldPosition allowed : VolcanicAshData.WHITELISTED_ASH_PILES) {
            if (allowed != null &&
                allowed.getX() == position.getX() &&
                allowed.getY() == position.getY() &&
                allowed.getPlane() == position.getPlane()) {
                return true;
            }
        }
        return false;
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

            boolean stationary = stationaryTimer.timeElapsed() > 600;
            boolean animating = script.getPixelAnalyzer().isPlayerAnimating(0.4);
            return stationary && !animating;
        }, 4_000);
    }

    private boolean waitForMiningCompletion(WorldPosition targetPos) {
        final boolean[] respawnSeen = { false };
        final double[] lastXp = { getMiningXp() };

        boolean completed = script.submitHumanTask(() -> {
            ItemGroupResult inventory = script.getWidgetManager().getInventory().search(Collections.emptySet());
            boolean inventoryFull = inventory != null && inventory.isFull();

            List<WorldPosition> respawnCircles = getRespawnCirclePositions();
            boolean targetRespawned = targetPos != null && respawnCircles != null && respawnCircles.contains(targetPos);
            if (targetRespawned) {
                respawnSeen[0] = true;
            }

            double currentXp = getMiningXp();
            double xpGain = currentXp - lastXp[0];
            if (xpGain > 0) {
                if (ashXpPerDrop == null || xpGain < ashXpPerDrop) {
                    ashXpPerDrop = xpGain;
                }
                double denom = ashXpPerDrop != null && ashXpPerDrop > 0 ? ashXpPerDrop : xpGain;
                int ticks = (int) Math.max(1, Math.round(xpGain / denom));
                ashMined += ticks * 4;
                lastMineGainedXp = true;
            }
            lastXp[0] = currentXp;

            return targetRespawned || inventoryFull;
        }, 20_000);

        if (respawnSeen[0] && targetPos != null) {
            waitingRespawn.add(targetPos);
        }
        return completed;
    }

    private double getMiningXp() {
        var trackers = script.getXPTrackers();
        if (trackers == null) {
            return 0;
        }
        Object tracker = trackers.get(SkillType.MINING);
        if (tracker instanceof com.osmb.api.trackers.experience.XPTracker xpTracker) {
            return xpTracker.getXp();
        }
        return 0;
    }

    private boolean tapAshPile(RSObject rock) {
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
                VolcanicAshData.TARGET_OBJECT_NAME.equalsIgnoreCase(name);
        }, 2_000);
    }

    private boolean hasMineableAshOnScreen() {
        List<WorldPosition> respawnCircles = getRespawnCirclePositions();
        RSObject ash = script.getObjectManager().getRSObject(object ->
            object != null &&
                object.isInteractableOnScreen() &&
                object.getWorldPosition() != null &&
                object.getName() != null &&
                VolcanicAshData.TARGET_OBJECT_NAME.equalsIgnoreCase(object.getName()) &&
                hasMineAction(object) &&
                allowAshPile(object.getWorldPosition(), respawnCircles)
        );
        return ash != null;
    }
}
