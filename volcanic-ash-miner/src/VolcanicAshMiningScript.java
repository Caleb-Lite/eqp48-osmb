import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.ui.component.tabs.skill.SkillType;
import com.osmb.api.ui.component.tabs.skill.SkillsTabComponent;
import com.osmb.api.utils.UIResult;
import com.osmb.api.utils.timing.Timer;
import com.osmb.api.visual.drawing.Canvas;
import com.osmb.api.shape.Polygon;
import com.osmb.api.shape.Rectangle;

import java.awt.Color;
import java.awt.Font;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import utils.Webhook;
import utils.Webhook.WebhookData;
@ScriptDefinition(
  author = "eqp48",
  name = "Volcanic Ash Miner",
  description = "Mines volcanic ash at Fossil Island.",
  skillCategory = SkillCategory.MINING,
  version = 1.0
)
public class VolcanicAshMiningScript extends Script {
  private static final String VERSION = "1.0";
  private static final String TARGET_OBJECT_NAME = "Ash pile";
  private static final String BANK_OBJECT_NAME = "Bank chest";
  private static final WorldPosition BANK_POSITION = new WorldPosition(3819, 3809, 0);

  private long startTimeMs = 0;
  private int ashMined = 0;
  private double startMiningXp = 0;
  private int startMiningLevel = 0;
  private final Set<WorldPosition> waitingRespawn = new HashSet<>();
  private boolean lastMineGainedXp = false;
  private WorldPosition lastWalkTarget = null;
  private Double ashXpPerDrop = null;
  private Webhook webhook;
  // If non-empty, only ash piles at these positions are allowed
  private static final Set<WorldPosition> WHITELISTED_ASH_PILES = Set.of(
    new WorldPosition(3800, 3767, 0),
    new WorldPosition(3789, 3769, 0),
    new WorldPosition(3794, 3773, 0),
    new WorldPosition(3781, 3774, 0),
    new WorldPosition(3810, 3772, 0),
    new WorldPosition(3789, 3757, 0)
  );

  public VolcanicAshMiningScript(Object scriptCore) {
    super(scriptCore);
    webhook = new Webhook(this::buildWebhookData, this::log);
  }

  @Override
  public void onStart() {
    var settings = getWidgetManager().getSettings();
    UIResult<Integer> zoomResult = settings.getZoomLevel();
    boolean alreadyMaxZoom = zoomResult.isFound() && zoomResult.get() == 0;
    if (!alreadyMaxZoom) {
      settings.setZoomLevel(0);
    }

    startMiningXp = getMiningXp();
    startMiningLevel = getMiningLevel();
    webhook.showDialog();
  }

  // Limit searches to the volcanic ash area for faster lookups
  @Override
  public int[] regionsToPrioritise() {
    return new int[]{15162, 15163};
  }

  @Override
  public int poll() {
    if (startTimeMs == 0) {
      startTimeMs = System.currentTimeMillis();
    }

    ItemGroupResult inventory = getWidgetManager().getInventory().search(Collections.emptySet());
    if (inventory == null) {
      return 600;
    }
    if (!webhook.isSubmitted()) {
      webhook.ensureDialogVisible();
      return 500;
    }
    webhook.ensureStarted(() -> webhook.enqueueEvent("Stopped"));
    webhook.queuePeriodicWebhookIfDue();
    webhook.dispatchPendingWebhooks();

    if (inventory.isFull()) {
      return handleBanking();
    }

    WorldPosition myPos = getWorldPosition();
    if (myPos == null) {
      return 600;
    }

    List<WorldPosition> respawnCircles = getRespawnCirclePositions();
    List<RSObject> ashPiles = getObjectManager().getObjects(object ->
      object != null &&
        object.isInteractableOnScreen() &&
        object.getWorldPosition() != null &&
        allowAshPile(object.getWorldPosition(), respawnCircles) &&
        object.getName() != null &&
        TARGET_OBJECT_NAME.equalsIgnoreCase(object.getName()) &&
        hasMineAction(object)
    );

    if (ashPiles == null || ashPiles.isEmpty()) {
      RSObject nearest = getObjectManager().getRSObject(object ->
        object != null &&
          object.getWorldPosition() != null &&
          object.getName() != null &&
          TARGET_OBJECT_NAME.equalsIgnoreCase(object.getName()) &&
          hasMineAction(object) &&
          allowAshPile(object.getWorldPosition(), respawnCircles)
      );
      if (nearest != null && nearest.getWorldPosition() != null) {
        lastWalkTarget = nearest.getWorldPosition();
        getWalker().walkTo(lastWalkTarget, new com.osmb.api.walker.WalkConfig.Builder()
          .breakCondition(this::hasMineableAshOnScreen)
          .build());
      }
      return random(500, 800);
    }

    if (lastWalkTarget != null) {
      ashPiles.sort(Comparator.comparingDouble(o -> o.getWorldPosition().distanceTo(lastWalkTarget)));
    } else {
      ashPiles.sort(Comparator.comparingDouble(o -> o.getWorldPosition().distanceTo(myPos)));
    }
    RSObject ashPile = ashPiles.get(0);
    lastWalkTarget = null;
    if (ashPile == null) {
      return random(500, 800);
    }

    if (!waitForPlayerIdle()) {
      return random(400, 700);
    }

    if (!tapAshPile(ashPile)) {
      return random(400, 700);
    }

    boolean mined = waitForMiningCompletion(ashPile.getWorldPosition());
    if (mined && ashPile.getWorldPosition() != null) {
      waitingRespawn.add(ashPile.getWorldPosition());
    }

    return mined ? random(120, 240) : random(250, 500);
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

  private boolean hasUseAction(RSObject object) {
    String[] actions = object.getActions();
    if (actions == null) {
      return false;
    }
    for (String action : actions) {
      if ("Use".equalsIgnoreCase(action)) {
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

  private int handleBanking() {
    // If bank is already open, deposit and close
    var bank = getWidgetManager().getBank();
    if (bank != null && bank.isVisible()) {
      bank.depositAll(Collections.emptySet());
      bank.close();
      return random(500, 800);
    }

    // Walk to bank position until chest is on screen or bank opens
    RSObject chest = getObjectManager().getRSObject(object ->
      object != null &&
        object.getWorldPosition() != null &&
        BANK_OBJECT_NAME.equalsIgnoreCase(object.getName()) &&
        hasUseAction(object)
    );

    if (chest == null || !chest.isInteractableOnScreen()) {
      getWalker().walkTo(BANK_POSITION, new com.osmb.api.walker.WalkConfig.Builder()
        .breakCondition(() -> getWidgetManager().getBank().isVisible() || hasBankChestOnScreen())
        .build());
      chest = getObjectManager().getRSObject(object ->
        object != null &&
          object.getWorldPosition() != null &&
          BANK_OBJECT_NAME.equalsIgnoreCase(object.getName()) &&
          hasUseAction(object)
      );
    }

    if (chest != null && chest.isInteractableOnScreen()) {
      chest.interact("Use");
      submitHumanTask(() -> {
        var b = getWidgetManager().getBank();
        return b != null && b.isVisible();
      }, 8_000);
    }

    // Once open, deposit inventory and close
    bank = getWidgetManager().getBank();
    if (bank != null && bank.isVisible()) {
      bank.depositAll(Collections.emptySet());
      bank.close();
      submitHumanTask(() -> {
        var b = getWidgetManager().getBank();
        return b == null || !b.isVisible();
      }, 5_000);
    }

    return random(500, 900);
  }

  private boolean hasBankChestOnScreen() {
    RSObject chest = getObjectManager().getRSObject(object ->
      object != null &&
        object.isInteractableOnScreen() &&
        object.getName() != null &&
        BANK_OBJECT_NAME.equalsIgnoreCase(object.getName()) &&
        hasUseAction(object)
    );
    return chest != null;
  }

  private boolean isWhitelisted(WorldPosition position) {
    if (position == null) {
      return false;
    }
    if (WHITELISTED_ASH_PILES.isEmpty()) {
      return true;
    }
    for (WorldPosition allowed : WHITELISTED_ASH_PILES) {
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
    List<Rectangle> respawnCircles = getPixelAnalyzer().findRespawnCircles();
    return getUtils().getWorldPositionForRespawnCircles(respawnCircles, 20);
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

  private boolean waitForMiningCompletion(WorldPosition targetPos) {
    final boolean[] respawnSeen = { false };
    final double[] lastXp = { getMiningXp() };

    boolean completed = submitHumanTask(() -> {
      ItemGroupResult inventory = getWidgetManager().getInventory().search(Collections.emptySet());
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
    var trackers = getXPTrackers();
    if (trackers == null) {
      return 0;
    }
    Object tracker = trackers.get(SkillType.MINING);
    if (tracker instanceof com.osmb.api.trackers.experience.XPTracker xpTracker) {
      return xpTracker.getXp();
    }
    return 0;
  }

  private int getMiningLevel() {
    try {
      SkillsTabComponent.SkillLevel skill = getWidgetManager().getSkillTab().getSkillLevel(SkillType.MINING);
      if (skill != null) {
        return skill.getLevel();
      }
    } catch (Exception e) {
      return 0;
    }
    return 0;
  }

  private boolean tapAshPile(RSObject rock) {
    if (rock == null) {
      return false;
    }
    Polygon hull = getSceneProjector().getConvexHull(rock);
    if (hull == null || hull.numVertices() == 0) {
      return false;
    }
    return getFinger().tapGameScreen(hull);
  }

  private boolean hasMineableAshOnScreen() {
    List<WorldPosition> respawnCircles = getRespawnCirclePositions();
    RSObject ash = getObjectManager().getRSObject(object ->
      object != null &&
        object.isInteractableOnScreen() &&
        object.getWorldPosition() != null &&
        object.getName() != null &&
        TARGET_OBJECT_NAME.equalsIgnoreCase(object.getName()) &&
        hasMineAction(object) &&
        allowAshPile(object.getWorldPosition(), respawnCircles)
    );
    return ash != null;
  }

  @Override
  public void onPaint(Canvas c) {
    if (c == null) {
      return;
    }
    try {
      int x = 6;
      int y = 32;
      int width = 180;
      int padding = 8;
      int lineHeight = 16;
      int height = padding * 2 + lineHeight * 4;

      c.fillRect(x, y, width, height, new Color(10, 10, 10, 190).getRGB(), 1);
      c.drawRect(x, y, width, height, Color.WHITE.getRGB());

      int textY = y + padding + 12;
      c.drawText("Volcanic Ash Miner - v" + VERSION, x + padding, textY, Color.YELLOW.getRGB(), new Font("Arial", Font.BOLD, 12));
      textY += lineHeight;
      c.drawText("Ash mined: " + ashMined, x + padding, textY, Color.WHITE.getRGB(), new Font("Arial", Font.BOLD, 12));
      textY += lineHeight;
      c.drawText("Runtime: " + formatRuntime(System.currentTimeMillis() - startTimeMs), x + padding, textY, Color.LIGHT_GRAY.getRGB(), new Font("Arial", Font.PLAIN, 12));
    } catch (Exception e) {
      log("PAINT", "Skipping paint: " + e.getMessage());
    }
  }

  private String formatRuntime(long ms) {
    if (ms < 0) ms = 0;
    long totalSeconds = ms / 1000;
    long hours = totalSeconds / 3600;
    long minutes = (totalSeconds % 3600) / 60;
    long seconds = totalSeconds % 60;
    return String.format("%02d:%02d:%02d", hours, minutes, seconds);
  }

  private WebhookData buildWebhookData() {
    double currentXp = getMiningXp();
    int currentLevel = getMiningLevel();
    long runtimeMs = System.currentTimeMillis() - startTimeMs;
    long xpGained = Math.max(0, Math.round(currentXp - startMiningXp));
    int levelsGained = Math.max(0, currentLevel - startMiningLevel);
    String runtimeText = formatRuntime(runtimeMs);
    int interval = webhook.getIntervalMinutes();
    return new WebhookData(ashMined, xpGained, levelsGained, runtimeText, interval);
  }

  @Override
  public void stop() {
    try {
      webhook.enqueueEvent("Stopped");
      webhook.dispatchPendingWebhooks();
    } catch (Exception ignored) {
    }
    super.stop();
  }
}
