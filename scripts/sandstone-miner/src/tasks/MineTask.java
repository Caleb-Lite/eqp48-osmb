package tasks;

import com.osmb.api.scene.RSObject;
import com.osmb.api.location.position.types.WorldPosition;
import data.SandstoneData;
import utils.Task;
import main.SandstoneMinerScript;

import java.util.Comparator;
import java.util.List;

public class MineTask extends Task {
  public MineTask(SandstoneMinerScript script) {
    super(script);
  }

  @Override
  public boolean activate() {
    return !script.isInventoryFull();
  }

  @Override
  public int execute() {
    WorldPosition myPos = script.getWorldPosition();
    if (myPos == null) {
      return 800;
    }

    WorldPosition anchor = script.getAnchorForLocation();
    if (script.maybeHopForNearbyPlayers(anchor, myPos)) {
      return script.random(400, 600);
    }

    double anchorDistance = anchor != null ? myPos.distanceTo(anchor) : Double.MAX_VALUE;
    if (anchor != null && anchorDistance > 1.0) {
      if (script.requestAnchorWalk(anchor)) {
        return script.random(200, 350);
      }
      return script.random(150, 250);
    }

    List<WorldPosition> respawnCircles = script.getRespawnCirclePositions();
    List<RSObject> sandstoneRocks = script.getObjectManager().getObjects(object ->
      object != null &&
        object.isInteractableOnScreen() &&
        object.getWorldPosition() != null &&
        script.allowRock(object.getWorldPosition(), respawnCircles, myPos) &&
        isSandstoneRock(object) &&
        script.hasMineAction(object)
    );

    if (sandstoneRocks == null || sandstoneRocks.isEmpty()) {
      script.requestAnchorWalk(anchor);
      return script.random(250, 400);
    }

    sandstoneRocks.sort(Comparator.comparingDouble(o -> o.getWorldPosition().distanceTo(myPos)));
    RSObject sandstoneRock = sandstoneRocks.get(0);
    if (sandstoneRock == null) {
      script.requestAnchorWalk(anchor);
      return script.random(250, 400);
    }

    if (!script.waitForPlayerIdle()) {
      return script.random(200, 350);
    }

    if (!script.tapRock(sandstoneRock)) {
      return script.random(200, 350);
    }

    boolean mined = script.waitForMiningCompletion();
    if (mined && sandstoneRock.getWorldPosition() != null && script.isAllowedRockPosition(sandstoneRock.getWorldPosition())) {
      script.getWaitingRespawn().add(sandstoneRock.getWorldPosition());
    }

    return mined ? script.random(60, 120) : script.random(125, 250);
  }

  private boolean isSandstoneRock(RSObject object) {
    if (object == null || object.getName() == null) {
      return false;
    }
    return SandstoneData.TARGET_ROCK_NAME.equalsIgnoreCase(object.getName());
  }
}
