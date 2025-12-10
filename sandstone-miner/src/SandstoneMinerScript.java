import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.ui.component.tabs.skill.SkillType;
import com.osmb.api.utils.timing.Timer;
import com.osmb.api.walker.WalkConfig;
import com.osmb.api.shape.Rectangle;
import com.osmb.api.visual.drawing.Canvas;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.awt.Color;
import java.awt.Font;
import java.awt.Color;

@ScriptDefinition(
  author = "eqp48",
  name = "Sandstone Miner",
  description = "Mines sandstone rocks in the quarry.",
  skillCategory = SkillCategory.MINING,
  version = 1.0
)
public class SandstoneMinerScript extends Script {
  private static final String TARGET_ROCK_NAME = "Sandstone rocks";
  private static final WorldPosition GRINDER_POS = new WorldPosition(3152, 2909, 0);
  private static final WorldPosition SANDSTONE_POS = new WorldPosition(3167, 2908, 0);

  private long startTimeMs = System.currentTimeMillis();
  private int sandstoneMined = 0;

  public SandstoneMinerScript(Object scriptCore) {
    super(scriptCore);
  }

  // Limit searches to the quarry region to speed up object lookups
  @Override
  public int[] regionsToPrioritise() {
    return new int[]{12589};
  }

  @Override
  public int poll() {
    if (startTimeMs == 0) {
      startTimeMs = System.currentTimeMillis();
    }

    ItemGroupResult inventory = getWidgetManager().getInventory().search(Collections.emptySet());
    if (inventory == null) {
      log(getClass(), "Inventory could not be read; waiting...");
      return 800;
    }

    if (inventory.isFull()) {
      return handleFullInventory();
    }

    WorldPosition myPos = getWorldPosition();
    if (myPos == null) {
      log(getClass(), "Player position unavailable; waiting...");
      return 800;
    }

    List<WorldPosition> respawnCircles = getRespawnCirclePositions();

    List<RSObject> sandstoneRocks = getObjectManager().getObjects(object ->
      object != null &&
        object.isInteractableOnScreen() &&
        object.getWorldPosition() != null &&
        allowRock(object.getWorldPosition(), respawnCircles) &&
        object.getName() != null &&
        TARGET_ROCK_NAME.equalsIgnoreCase(object.getName()) &&
        hasMineAction(object)
    );

    if (sandstoneRocks == null || sandstoneRocks.isEmpty()) {
      log(getClass(), "No sandstone rocks visible; walking back to sandstone tile.");
      walkToSandstoneHome();
      return random(500, 800);
    }

    sandstoneRocks.sort(Comparator.comparingDouble(o -> o.getWorldPosition().distanceTo(myPos)));
    RSObject sandstoneRock = sandstoneRocks.get(0);

    if (sandstoneRock == null) {
      log(getClass(), "No sandstone rocks visible; walking back to sandstone tile.");
      walkToSandstoneHome();
      return random(500, 800);
    }

    if (!waitForPlayerIdle()) {
      log(getClass(), "Player still moving or animating; waiting before mining.");
      return random(400, 700);
    }

    if (!sandstoneRock.interact("Mine")) {
      log(getClass(), "Failed to click Mine on sandstone rock.");
      return random(400, 700);
    }

    boolean mined = waitForMiningCompletion();
    if (mined && sandstoneRock.getWorldPosition() != null) {
      waitingRespawn.add(sandstoneRock.getWorldPosition());
    }

    if (!mined) {
      log(getClass(), "Mining attempt ended without XP gain or movement; re-scanning.");
    }

    return mined ? random(75, 100) : random(250, 500);
  }

  private boolean hasMineAction(RSObject object) {
    String[] actions = object.getActions();
    if (actions == null) {
      return false;
    }
    return Arrays.stream(actions).anyMatch(action -> "Mine".equalsIgnoreCase(action));
  }

  private boolean hasDepositAction(RSObject object) {
    String[] actions = object.getActions();
    if (actions == null) {
      return false;
    }
    return Arrays.stream(actions).anyMatch(action -> "Deposit".equalsIgnoreCase(action));
  }

  private int handleFullInventory() {
    RSObject grinder = findGrinder();

    // If grinder is already on-screen and interactable, click it immediately
    if (grinder != null && grinder.isInteractableOnScreen()) {
      if (!grinder.interact("Deposit")) {
        log(getClass(), "Failed to click Deposit on grinder.");
        return random(500, 800);
      }
    } else {
      log(getClass(), "Walking to grinder to deposit.");
      WalkConfig config = new WalkConfig.Builder()
        .breakCondition(() -> {
          RSObject g = findGrinder();
          return g != null && g.isInteractableOnScreen();
        })
        .build();
      getWalker().walkTo(GRINDER_POS, config);

      grinder = findGrinder();
      if (grinder == null || !grinder.isInteractableOnScreen() || !grinder.interact("Deposit")) {
        log(getClass(), "Failed to click Deposit on grinder after walking.");
        return random(500, 800);
      }
    }

    boolean deposited = submitHumanTask(() -> {
      ItemGroupResult inv = getWidgetManager().getInventory().search(Collections.emptySet());
      return inv == null || !inv.isFull();
    }, 8_000);

    if (!deposited) {
      log(getClass(), "Deposit interaction did not clear inventory; retrying soon.");
    }

    return random(400, 700);
  }

  private RSObject findGrinder() {
    return getObjectManager().getRSObject(object ->
      object != null &&
        object.getWorldPosition() != null &&
        object.getName() != null &&
        "Grinder".equalsIgnoreCase(object.getName()) &&
        hasDepositAction(object)
    );
  }

  private void walkToSandstoneHome() {
    WalkConfig config = new WalkConfig.Builder()
      .breakCondition(this::hasSandstoneOnScreen)
      .build();
    getWalker().walkTo(SANDSTONE_POS, config);
  }

  private boolean hasSandstoneOnScreen() {
    RSObject rock = getObjectManager().getRSObject(object ->
      object != null &&
        object.isInteractableOnScreen() &&
        object.getName() != null &&
        TARGET_ROCK_NAME.equalsIgnoreCase(object.getName()) &&
        hasMineAction(object)
    );
    return rock != null;
  }

  private boolean waitForPlayerIdle() {
    Timer stationaryTimer = new Timer();
    WorldPosition[] lastPosition = { getWorldPosition() };

    return submitHumanTask(() -> {
      WorldPosition current = getWorldPosition();
      if (current == null) {
        return false;
      }

      if (lastPosition[0] == null || !current.equals(lastPosition[0])) {
        lastPosition[0] = current;
        stationaryTimer.reset();
      }

      boolean stationary = stationaryTimer.timeElapsed() > 600;
      boolean animating = getPixelAnalyzer().isPlayerAnimating(0.4);
      return stationary && !animating;
    }, 4_000);
  }

  private boolean waitForMiningCompletion() {
    final double startingMiningXp = getMiningXp();
    Timer noAnimationTimer = new Timer();
    Timer graceTimer = new Timer();

    return submitHumanTask(() -> {
      boolean animating = getPixelAnalyzer().isPlayerAnimating(0.4);
      if (animating) {
        noAnimationTimer.reset();
      }

      double currentXp = getMiningXp();
      boolean gainedXp = currentXp > startingMiningXp;
      if (gainedXp) {
        sandstoneMined++;
      }
      lastMineGainedXp = gainedXp;

      ItemGroupResult inventory = getWidgetManager().getInventory().search(Collections.emptySet());
      boolean inventoryFull = inventory != null && inventory.isFull();

      boolean animationStale = !animating && noAnimationTimer.timeElapsed() > 2_000 && graceTimer.timeElapsed() > 1_000;
      return gainedXp || inventoryFull || animationStale;
    }, 12_000);
  }

  private List<WorldPosition> getRespawnCirclePositions() {
    List<Rectangle> respawnCircles = getPixelAnalyzer().findRespawnCircles();
    return getUtils().getWorldPositionForRespawnCircles(respawnCircles, 20);
  }

  private boolean allowRock(WorldPosition position, List<WorldPosition> respawnCircles) {
    if (waitingRespawn.contains(position)) {
      if (respawnCircles.contains(position)) {
        return false;
      }
      waitingRespawn.remove(position);
    }
    return true;
  }

  private double getMiningXp() {
    Map<?, ?> trackers = getXPTrackers();
    if (trackers == null) {
      return 0;
    }
    Object tracker = trackers.get(SkillType.MINING);
    if (tracker instanceof com.osmb.api.trackers.experience.XPTracker xpTracker) {
      return xpTracker.getXp();
    }
    return 0;
  }

  private final Set<WorldPosition> waitingRespawn = new HashSet<>();
  private boolean lastMineGainedXp = false;

  @Override
  public void onPaint(Canvas c) {
    int x = 6;
    int y = 32;
    int width = 180;
    int padding = 8;
    int lineHeight = 16;
    int height = padding * 2 + lineHeight * 2;

    // Background panel for readability
    c.fillRect(x, y, width, height, new Color(10, 10, 10, 190).getRGB(), 1);
    c.drawRect(x, y, width, height, Color.WHITE.getRGB());

    int textY = y + padding + 12;
    c.drawText("Sandstone mined: " + sandstoneMined, x + padding, textY, Color.WHITE.getRGB(), new Font("Arial", Font.BOLD, 12));
    textY += lineHeight;
    c.drawText("Runtime: " + formatRuntime(System.currentTimeMillis() - startTimeMs), x + padding, textY, Color.LIGHT_GRAY.getRGB(), new Font("Arial", Font.PLAIN, 12));
  }

  private String formatRuntime(long ms) {
    if (ms < 0) ms = 0;
    long totalSeconds = ms / 1000;
    long hours = totalSeconds / 3600;
    long minutes = (totalSeconds % 3600) / 60;
    long seconds = totalSeconds % 60;
    return String.format("%02d:%02d:%02d", hours, minutes, seconds);
  }

}
