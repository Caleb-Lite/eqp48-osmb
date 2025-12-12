import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.ui.component.tabs.skill.SkillType;
import com.osmb.api.ui.component.tabs.skill.SkillsTabComponent;
import com.osmb.api.utils.UIResult;
import com.osmb.api.ui.depositbox.DepositBox;
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
  name = "Gem Miner",
  description = "Mines gem rocks in Shilo Village and banks the gems.",
  skillCategory = SkillCategory.MINING,
  version = 1.0
)
public class GemMinerScript extends Script {
  private static final String VERSION = "1.0";
  private static final String TARGET_OBJECT_NAME = "Gem rocks";
  private static final WorldPosition UPPER_BANK_POSITION = new WorldPosition(2852, 2953, 0);
  private static final WorldPosition UPPER_MINE = new WorldPosition(2823, 2999, 0);
  private static final WorldPosition UNDERGROUND_BANK_POSITION = new WorldPosition(2842, 9383, 0);

  private long startTimeMs = 0;
  private int gemsMined = 0;
  private double startMiningXp = 0;
  private int startMiningLevel = 0;
  private final Set<WorldPosition> waitingRespawn = new HashSet<>();
  private boolean lastMineGainedXp = false;
  private WorldPosition lastWalkTarget = null;
  private Double gemXpPerRock = null;
  private Webhook webhook;
  private volatile MiningLocation selectedLocation = MiningLocation.UPPER;
  private volatile boolean settingsConfirmed = false;

  private MiningLocation getActiveLocation() {
    return selectedLocation != null ? selectedLocation : MiningLocation.UPPER;
  }

  private void onLocationSelected(String selection) {
    selectedLocation = MiningLocation.fromDisplay(selection);
    settingsConfirmed = true;
  }

  public GemMinerScript(Object scriptCore) {
    super(scriptCore);
    webhook = new Webhook(this::buildWebhookData, s -> {});
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
    webhook.showDialogWithLocation(
      List.of(MiningLocation.UPPER.displayName(), MiningLocation.UNDERGROUND.displayName()),
      () -> getActiveLocation().displayName(),
      this::onLocationSelected
    );
  }

  // Focus lookups around the Shilo gem mine.
  @Override
  public int[] regionsToPrioritise() {
    return new int[]{11310, 11410};
  }

  @Override
  public int poll() {
    if (!settingsConfirmed) {
      webhook.ensureDialogVisible();
      return 600;
    }

    if (startTimeMs == 0) {
      startTimeMs = System.currentTimeMillis();
    }

    var inventoryComponent = getWidgetManager().getInventory();
    if (inventoryComponent == null) {
      return 600;
    }

    ItemGroupResult inventory = inventoryComponent.search(Collections.emptySet());
    if (inventory == null) {
      return 600;
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
    List<RSObject> gemRocks = getObjectManager().getObjects(object ->
      object != null &&
        object.isInteractableOnScreen() &&
        object.getWorldPosition() != null &&
        allowRock(object.getWorldPosition(), respawnCircles) &&
        isGemRock(object) &&
        hasMineAction(object)
    );

    if (gemRocks == null || gemRocks.isEmpty()) {
      lastWalkTarget = null;
      if (respawnCircles == null || respawnCircles.isEmpty()) {
        WorldPosition mineAnchor = getActiveLocation().minePosition();
        if (mineAnchor != null) {
          getWalker().walkTo(mineAnchor, new com.osmb.api.walker.WalkConfig.Builder()
            .breakCondition(this::hasMineableGemOnScreen)
            .build());
        } else {
        }
      } else {
      }
      return random(500, 800);
    }

    if (lastWalkTarget != null) {
      gemRocks.sort(Comparator.comparingDouble(o -> o.getWorldPosition().distanceTo(lastWalkTarget)));
    } else {
      gemRocks.sort(Comparator.comparingDouble(o -> o.getWorldPosition().distanceTo(myPos)));
    }
    RSObject gemRock = gemRocks.get(0);
    lastWalkTarget = null;
    if (gemRock == null) {
      return random(500, 800);
    }

    if (!waitForPlayerIdle()) {
      return random(400, 700);
    }

    if (!tapGemRock(gemRock)) {
      return random(400, 700);
    }

    boolean mined = waitForMiningCompletion(gemRock.getWorldPosition());
    if (mined && gemRock.getWorldPosition() != null) {
      waitingRespawn.add(gemRock.getWorldPosition());
    }

    return mined ? random(60, 140) : random(250, 500);
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
    if (waitingRespawn.contains(position)) {
      if (!hasRespawnCircle) {
        return false;
      }
      waitingRespawn.remove(position);
      return false;
    }
    return !hasRespawnCircle;
  }

  private int handleBanking() {
    MiningLocation location = getActiveLocation();
    DepositBox depositBox = getWidgetManager().getDepositBox();
    if (depositBox != null && depositBox.isVisible()) {
      depositInventory(depositBox);
      closeDepositBox(depositBox);
      return random(500, 800);
    }

    RSObject depositTarget = findDepositTarget(location);

    if (depositTarget == null || !depositTarget.isInteractableOnScreen()) {
      WorldPosition bankPosition = location.bankPosition();
      if (bankPosition != null) {
        getWalker().walkTo(bankPosition, new com.osmb.api.walker.WalkConfig.Builder()
          .breakCondition(() -> {
            var box = getWidgetManager().getDepositBox();
            return (box != null && box.isVisible()) || hasDepositTargetOnScreen(location);
          })
          .build());
        depositTarget = findDepositTarget(location);
      } else {
      }
    }

    if (depositTarget != null && depositTarget.isInteractableOnScreen()) {
      boolean interacted = interactWithDepositTarget(depositTarget, location.depositAction());
      if (interacted) {
        submitHumanTask(() -> {
          var box = getWidgetManager().getDepositBox();
          return box != null && box.isVisible();
        }, 8_000);
      }
    }

    depositBox = getWidgetManager().getDepositBox();
    if (depositBox != null && depositBox.isVisible()) {
      depositInventory(depositBox);
      closeDepositBox(depositBox);
      submitHumanTask(() -> {
        var box = getWidgetManager().getDepositBox();
        return box == null || !box.isVisible();
      }, 2_000);
    }

    return random(500, 900);
  }

  private RSObject findDepositTarget(MiningLocation location) {
    String targetName = location.depositObjectName();
    String action = location.depositAction();
    return getObjectManager().getRSObject(object ->
      object != null &&
        object.getWorldPosition() != null &&
        object.getName() != null &&
        targetName.equalsIgnoreCase(object.getName()) &&
        hasDepositAction(object, action) &&
        object.canReach()
    );
  }

  private boolean hasDepositTargetOnScreen(MiningLocation location) {
    RSObject depositBox = findDepositTarget(location);
    return depositBox != null && depositBox.isInteractableOnScreen();
  }

  private boolean hasDepositAction(RSObject object, String requiredAction) {
    String[] actions = object.getActions();
    if (actions == null) {
      return false;
    }
    for (String action : actions) {
      if (requiredAction.equalsIgnoreCase(action)) {
        return true;
      }
    }
    return false;
  }

  private boolean interactWithDepositTarget(RSObject depositTarget, String action) {
    if (depositTarget == null || action == null) {
      return false;
    }
    return submitHumanTask(() -> depositTarget.interact(action), 2_000);
  }

  private boolean depositInventory(DepositBox depositBox) {
    if (depositBox == null) {
      return false;
    }
    boolean deposited = submitHumanTask(
      () -> depositBox.depositAll(Collections.emptySet()),
      3_000
    );
    if (!deposited) {
      return false;
    }
    waitForInventoryNotFull();
    return true;
  }

  private void closeDepositBox(DepositBox depositBox) {
    if (depositBox == null) {
      return;
    }
    submitHumanTask(depositBox::close, 2_000);
  }

  private enum MiningLocation {
    UPPER("Upper mine", UPPER_MINE, UPPER_BANK_POSITION, "Bank Deposit Box", "Deposit", new int[]{11310}),
    UNDERGROUND("Underground mine", null, UNDERGROUND_BANK_POSITION, "Bank Deposit Chest", "Deposit", new int[]{11410});

    private final String displayName;
    private final WorldPosition minePosition;
    private final WorldPosition bankPosition;
    private final String depositObjectName;
    private final String depositAction;
    private final int[] priorityRegions;

    MiningLocation(String displayName, WorldPosition minePosition, WorldPosition bankPosition, String depositObjectName, String depositAction, int[] priorityRegions) {
      this.displayName = displayName;
      this.minePosition = minePosition;
      this.bankPosition = bankPosition;
      this.depositObjectName = depositObjectName;
      this.depositAction = depositAction;
      this.priorityRegions = priorityRegions;
    }

    public String displayName() {
      return displayName;
    }

    public WorldPosition minePosition() {
      return minePosition;
    }

    public WorldPosition bankPosition() {
      return bankPosition;
    }

    public String depositObjectName() {
      return depositObjectName;
    }

    public String depositAction() {
      return depositAction;
    }

    public int[] priorityRegions() {
      return priorityRegions;
    }

    public static MiningLocation fromDisplay(String name) {
      if (name != null) {
        for (MiningLocation loc : values()) {
          if (loc.displayName().equalsIgnoreCase(name.trim())) {
            return loc;
          }
        }
      }
      return UPPER;
    }
  }

  private void waitForInventoryNotFull() {
    submitHumanTask(() -> {
      ItemGroupResult inv = getWidgetManager().getInventory().search(Collections.emptySet());
      return inv == null || !inv.isFull();
    }, 5_000);
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

      boolean stationary = stationaryTimer.timeElapsed() > 250;
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
        if (gemXpPerRock == null || xpGain < gemXpPerRock) {
          gemXpPerRock = xpGain;
        }
        double denom = gemXpPerRock != null && gemXpPerRock > 0 ? gemXpPerRock : xpGain;
        int ticks = (int) Math.max(1, Math.round(xpGain / denom));
        gemsMined += ticks;
        lastMineGainedXp = true;
      }
      lastXp[0] = currentXp;

      return targetRespawned || inventoryFull;
    }, 4_000);

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

  private boolean tapGemRock(RSObject rock) {
    if (rock == null) {
      return false;
    }
    Polygon hull = getSceneProjector().getConvexHull(rock);
    if (hull == null || hull.numVertices() == 0) {
      return false;
    }
    return getFinger().tapGameScreen(hull);
  }

  private boolean hasMineableGemOnScreen() {
    List<WorldPosition> respawnCircles = getRespawnCirclePositions();
    RSObject gem = getObjectManager().getRSObject(object ->
      object != null &&
        object.isInteractableOnScreen() &&
        object.getWorldPosition() != null &&
        isGemRock(object) &&
        hasMineAction(object) &&
        allowRock(object.getWorldPosition(), respawnCircles)
    );
    return gem != null;
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
      c.drawText("Gem Miner - v" + VERSION, x + padding, textY, Color.YELLOW.getRGB(), new Font("Arial", Font.BOLD, 12));
      textY += lineHeight;
      c.drawText("Gems mined: " + gemsMined, x + padding, textY, Color.WHITE.getRGB(), new Font("Arial", Font.BOLD, 12));
      textY += lineHeight;
      c.drawText("Runtime: " + formatRuntime(System.currentTimeMillis() - startTimeMs), x + padding, textY, Color.LIGHT_GRAY.getRGB(), new Font("Arial", Font.PLAIN, 12));
    } catch (Exception e) {
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
    return new WebhookData(gemsMined, xpGained, levelsGained, runtimeText, interval);
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
