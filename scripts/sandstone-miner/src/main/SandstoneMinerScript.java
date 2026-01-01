package main;

import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.ui.component.tabs.skill.SkillType;
import com.osmb.api.ui.component.tabs.skill.SkillsTabComponent;
import com.osmb.api.utils.timing.Timer;
import com.osmb.api.item.ItemID;
import com.osmb.api.ui.spellbook.LunarSpellbook;
import com.osmb.api.ui.spellbook.SpellNotFoundException;
import com.osmb.api.ui.tabs.Spellbook;
import com.osmb.api.walker.WalkConfig;
import com.osmb.api.world.SkillTotal;
import com.osmb.api.world.World;
import com.osmb.api.shape.Polygon;
import com.osmb.api.input.MenuEntry;
import com.osmb.api.visual.drawing.Canvas;
import com.osmb.api.trackers.experience.XPTracker;
import data.SandstoneData;
import javafx.scene.Scene;
import tasks.BankTask;
import tasks.HumidifyTask;
import tasks.MineTask;
import utils.Task;
import utils.WaterskinTracker;
import utils.Webhook;
import utils.Webhook.WebhookData;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ScriptDefinition(
  author = "eqp48",
  name = "Sandstone Miner",
  description = "Mines sandstone rocks in the quarry.",
  skillCategory = SkillCategory.MINING,
  version = 1.4
)
public class SandstoneMinerScript extends Script {
  private static final String VERSION = "1.4";

  private long startTimeMs = 0;
  private int sandstoneMined = 0;
  private long lastHumidifyCastMs = 0;
  private double startMiningXp = 0;
  private int startMiningLevel = 0;
  private GUI gui;
  private boolean settingsConfirmed = false;
  private Webhook webhook;
  private Webhook.MiningLocation miningLocation = Webhook.MiningLocation.NORTH;
  private Webhook.MiningLocation lastMiningLocation = Webhook.MiningLocation.NORTH;
  private WorldPosition lastMovePosition = null;
  private long lastMoveChangeMs = 0;
  private WorldPosition lastAnchorWalkTarget = null;
  private long lastAnchorWalkMs = 0;
  private boolean hopOnNearbyPlayersEnabled = false;
  private int hopRadiusTiles = 0;
  private long lastHopAttemptMs = 0;
  private final Set<WorldPosition> waitingRespawn = new HashSet<>();
  private boolean lastMineGainedXp = false;
  private ItemGroupResult inventorySnapshot = null;
  private Integer waterskinCharges = null;
  private boolean zoomConfigured = false;
  private long lastZoomAttemptMs = 0;
  private static final long ZOOM_RETRY_MS = 2000;

  private final WaterskinTracker waterskinTracker;
  private final List<Task> tasks = new ArrayList<>();

  public SandstoneMinerScript(Object scriptCore) {
    super(scriptCore);
    waterskinTracker = new WaterskinTracker(this, SandstoneData.WATERSKIN_IDS);
    webhook = new Webhook(this::buildWebhookData, this::log);
    tasks.add(new HumidifyTask(this));
    tasks.add(new BankTask(this));
    tasks.add(new MineTask(this));
  }

  @Override
  public void onStart() {
    startMiningXp = getMiningXp();
    startMiningLevel = getMiningLevel();
    gui = new GUI(webhook.getConfig());
    gui.setOnStart(() -> {
      Webhook.MiningLocation selectedLocation = gui.getSelectedLocation();
      if (selectedLocation == null) {
        settingsConfirmed = false;
        return;
      }
      webhook.applyConfig(gui.buildWebhookConfig());
      miningLocation = selectedLocation;
      settingsConfirmed = true;
      gui.closeWindow();
    });
    Scene scene = new Scene(gui);
    getStageController().show(scene, "Sandstone Miner Settings", false);
  }

  @Override
  public int[] regionsToPrioritise() {
    return new int[]{12589};
  }

  @Override
  public int poll() {
    if (!settingsConfirmed) {
      return 600;
    }
    if (startTimeMs == 0) {
      startTimeMs = System.currentTimeMillis();
    }

    ensureZoomConfigured();
    isPlayerMoving();

    inventorySnapshot = getWidgetManager().getInventory().search(Set.of(
      ItemID.ASTRAL_RUNE,
      ItemID.WATER_RUNE,
      ItemID.FIRE_RUNE
    ));

    webhook.ensureStarted(() -> webhook.enqueueEvent("Stopped"));
    webhook.queuePeriodicWebhookIfDue();
    webhook.dispatchPendingWebhooks();

    if (inventorySnapshot == null) {
      return 800;
    }

    Webhook.MiningLocation selectedLocation = webhook.getMiningLocation();
    if (selectedLocation != lastMiningLocation) {
      waitingRespawn.clear();
      lastMiningLocation = selectedLocation;
    }
    miningLocation = selectedLocation;

    hopOnNearbyPlayersEnabled = webhook.isHopEnabled();
    hopRadiusTiles = Math.max(0, webhook.getHopRadiusTiles());

    waterskinCharges = waterskinTracker.getCharges();

    for (Task task : tasks) {
      if (task.activate()) {
        int delay = task.execute();
        if (delay >= 0) {
          return delay;
        }
      }
    }

    return random(150, 250);
  }

  private void ensureZoomConfigured() {
    if (zoomConfigured) {
      return;
    }
    if (getWidgetManager().getGameState() != com.osmb.api.ui.GameState.LOGGED_IN) {
      return;
    }
    long now = System.currentTimeMillis();
    if (now - lastZoomAttemptMs < ZOOM_RETRY_MS) {
      return;
    }
    lastZoomAttemptMs = now;
    var settings = getWidgetManager().getSettings();
    if (settings == null) {
      return;
    }
    var zoomResult = settings.getZoomLevel();
    boolean alreadyMaxZoom = zoomResult.isFound() && zoomResult.get() == 0;
    if (!alreadyMaxZoom) {
      if (settings.setZoomLevel(0)) {
        zoomConfigured = true;
      }
      return;
    }
    zoomConfigured = true;
  }

  public boolean isInventoryFull() {
    ItemGroupResult inv = inventorySnapshot;
    if (inv == null) {
      inv = getWidgetManager().getInventory().search(Collections.emptySet());
    }
    return inv != null && inv.isFull();
  }

  public int handleFullInventory() {
    RSObject grinder = findGrinder();

    if (grinder != null && grinder.isInteractableOnScreen()) {
      if (!grinder.interact("Deposit")) {
        return random(250, 400);
      }
    } else {
      if (isPlayerMoving()) {
        return random(200, 350);
      }
      WalkConfig config = new WalkConfig.Builder()
        .breakCondition(() -> {
          RSObject g = findGrinder();
          return g != null && g.isInteractableOnScreen();
        })
        .build();
      getWalker().walkTo(SandstoneData.GRINDER_POS, config);

      grinder = findGrinder();
      if (grinder == null || !grinder.isInteractableOnScreen() || !grinder.interact("Deposit")) {
        return random(250, 400);
      }
    }

    submitHumanTask(() -> {
      ItemGroupResult inv = getWidgetManager().getInventory().search(Collections.emptySet());
      return inv == null || !inv.isFull();
    }, 4_000);

    return random(200, 350);
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

  private boolean hasDepositAction(RSObject object) {
    String[] actions = object.getActions();
    if (actions == null) {
      return false;
    }
    for (String action : actions) {
      if ("Deposit".equalsIgnoreCase(action)) {
        return true;
      }
    }
    return false;
  }

  public boolean hasMineAction(RSObject object) {
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

  public WorldPosition getAnchorForLocation() {
    return SandstoneData.getAnchor(miningLocation);
  }

  public boolean isAllowedRockPosition(WorldPosition position) {
    if (position == null) {
      return false;
    }
    return SandstoneData.getAllowedRocks(miningLocation).contains(position);
  }

  public boolean allowRock(WorldPosition position, List<WorldPosition> respawnCircles, WorldPosition playerPos) {
    if (!isAllowedRockPosition(position)) {
      return false;
    }
    if (isDiagonalToPlayer(position, playerPos)) {
      return false;
    }
    if (waitingRespawn.contains(position)) {
      if (respawnCircles.contains(position)) {
        return false;
      }
      waitingRespawn.remove(position);
    }
    return true;
  }

  private boolean isDiagonalToPlayer(WorldPosition rockPos, WorldPosition playerPos) {
    if (rockPos == null || playerPos == null) {
      return false;
    }
    int dx = Math.abs(rockPos.getX() - playerPos.getX());
    int dy = Math.abs(rockPos.getY() - playerPos.getY());
    return dx == 1 && dy == 1;
  }

  public boolean requestAnchorWalk(WorldPosition anchor) {
    if (anchor == null) {
      return false;
    }
    WorldPosition pos = getWorldPosition();
    if (pos != null && pos.distanceTo(anchor) <= 1.0) {
      return false;
    }
    if (isPlayerMoving()) {
      return false;
    }
    long now = System.currentTimeMillis();
    if (anchor.equals(lastAnchorWalkTarget) && now - lastAnchorWalkMs < 1200) {
      return false;
    }
    walkToAnchor(anchor);
    lastAnchorWalkTarget = anchor;
    lastAnchorWalkMs = now;
    return true;
  }

  private void walkToAnchor(WorldPosition anchor) {
    WalkConfig config = new WalkConfig.Builder()
      .minimapTapDelay(1200, 2000)
      .tileRandomisationRadius(0)
      .breakDistance(0)
      .setWalkMethods(false, true)
      .breakCondition(() -> {
        WorldPosition pos = getWorldPosition();
        return pos != null && pos.distanceTo(anchor) <= 0.0;
      })
      .build();
    getWalker().walkTo(anchor, config);
  }

  public List<WorldPosition> getRespawnCirclePositions() {
    List<com.osmb.api.shape.Rectangle> respawnCircles = getPixelAnalyzer().findRespawnCircles();
    List<WorldPosition> positions = getUtils().getWorldPositionForRespawnCircles(respawnCircles, 20);
    return positions != null ? positions : Collections.emptyList();
  }

  public boolean maybeHopForNearbyPlayers(WorldPosition anchor, WorldPosition myPos) {
    if (!hopOnNearbyPlayersEnabled || hopRadiusTiles <= 0 || anchor == null) {
      return false;
    }
    long now = System.currentTimeMillis();
    if (now - lastHopAttemptMs < 10_000) {
      return false;
    }
    var minimap = getWidgetManager().getMinimap();
    if (minimap == null) {
      return false;
    }
    var players = minimap.getPlayerPositions();
    if (players == null || !players.isFound()) {
      return false;
    }
    for (WorldPosition pos : players.asList()) {
      if (pos == null) {
        continue;
      }
      if (myPos != null && pos.equals(myPos)) {
        continue;
      }
      if (pos.getPlane() != anchor.getPlane()) {
        continue;
      }
      int dx = Math.abs(pos.getX() - anchor.getX());
      int dy = Math.abs(pos.getY() - anchor.getY());
      int chebyshevDistance = Math.max(dx, dy);
      if (chebyshevDistance <= hopRadiusTiles) {
        lastHopAttemptMs = now;
        if (attemptWorldHop()) {
          waitingRespawn.clear();
        }
        return true;
      }
    }
    return false;
  }

  private boolean attemptWorldHop() {
    try {
      Integer currentWorld = getCurrentWorld();
      getProfileManager().forceHop(worlds -> {
        if (worlds == null || worlds.isEmpty()) {
          return null;
        }
        List<World> filtered = new ArrayList<>();
        for (World world : worlds) {
          if (world != null &&
            (currentWorld == null || world.getId() != currentWorld) &&
            world.isMembers() &&
            !world.isIgnore() &&
            (world.getSkillTotal() == null || world.getSkillTotal() == SkillTotal.NONE)) {
            filtered.add(world);
          }
        }
        if (filtered.isEmpty()) {
          for (World world : worlds) {
            if (world != null &&
              (currentWorld == null || world.getId() != currentWorld) &&
              world.isMembers()) {
              return world;
            }
          }
          return worlds.get(0);
        }
        int idx = Math.max(0, Math.min(filtered.size() - 1, random(0, filtered.size())));
        return filtered.get(idx);
      });
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public boolean isPlayerMoving() {
    boolean animating = getPixelAnalyzer().isPlayerAnimating(0.4);
    WorldPosition pos = getWorldPosition();
    if (pos != null && !pos.equals(lastMovePosition)) {
      lastMovePosition = pos;
      lastMoveChangeMs = System.currentTimeMillis();
      return true;
    }
    long sinceMove = System.currentTimeMillis() - lastMoveChangeMs;
    return animating || sinceMove < 800;
  }

  public boolean waitForPlayerIdle() {
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
    }, 2_000);
  }

  public boolean tapRock(RSObject rock) {
    if (rock == null) {
      return false;
    }
    Polygon hull = getSceneProjector().getConvexHull(rock);
    if (hull == null || hull.numVertices() == 0) {
      return false;
    }
    Polygon shrunk = hull.getResized(0.5);
    Polygon targetHull = shrunk != null ? shrunk : hull;
    return submitHumanTask(() -> {
      MenuEntry response = getFinger().tapGetResponse(false, targetHull);
      if (response == null) {
        return false;
      }
      String action = response.getAction();
      String name = response.getEntityName();
      return action != null && name != null &&
        "mine".equalsIgnoreCase(action) &&
        SandstoneData.TARGET_ROCK_NAME.equalsIgnoreCase(name);
    }, 1_000);
  }

  public boolean waitForMiningCompletion() {
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
    }, 6_000);
  }

  private boolean canCastHumidify() {
    try {
      var spellbook = getWidgetManager().getSpellbook();
      if (spellbook == null) {
        return false;
      }

      ItemGroupResult inv = inventorySnapshot != null ? inventorySnapshot : getWidgetManager().getInventory().search(Set.of(
        ItemID.ASTRAL_RUNE,
        ItemID.WATER_RUNE,
        ItemID.FIRE_RUNE
      ));
      if (inv == null) {
        return false;
      }

      int astral = inv.getAmount(ItemID.ASTRAL_RUNE);
      int water = inv.getAmount(ItemID.WATER_RUNE);
      int fire = inv.getAmount(ItemID.FIRE_RUNE);
      return astral >= 1 && water >= 3 && fire >= 1;
    } catch (RuntimeException e) {
      return false;
    }
  }

  public boolean castHumidify() {
    try {
      Spellbook spellbook = getWidgetManager().getSpellbook();
      if (spellbook == null) {
        return false;
      }
      spellbook.open();
      return spellbook.selectSpell(LunarSpellbook.HUMIDIFY, null);
    } catch (SpellNotFoundException e) {
      return false;
    } catch (Exception e) {
      return false;
    }
  }

  public boolean shouldCastHumidify() {
    if (!canCastHumidify()) {
      return false;
    }
    long now = System.currentTimeMillis();
    if (now - lastHumidifyCastMs < 3_000) {
      return false;
    }
    if (waterskinCharges == null) {
      return false;
    }
    return waterskinCharges == 0;
  }

  public void markHumidifyCast() {
    lastHumidifyCastMs = System.currentTimeMillis();
  }

  private double getMiningXp() {
    Map<?, ?> trackers = getXPTrackers();
    if (trackers == null) {
      return 0;
    }
    Object tracker = trackers.get(SkillType.MINING);
    if (tracker instanceof XPTracker xpTracker) {
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

  public Set<WorldPosition> getWaitingRespawn() {
    return waitingRespawn;
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
      c.drawText("Sandstone Miner - v" + VERSION, x + padding, textY, Color.YELLOW.getRGB(), new Font("Arial", Font.BOLD, 12));
      textY += lineHeight;
      c.drawText("Sandstone: " + sandstoneMined, x + padding, textY, Color.WHITE.getRGB(), new Font("Arial", Font.BOLD, 12));
      textY += lineHeight;
      c.drawText("Runtime: " + formatRuntime(System.currentTimeMillis() - startTimeMs), x + padding, textY, Color.LIGHT_GRAY.getRGB(), new Font("Arial", Font.PLAIN, 12));
    } catch (Exception e) {
      log("PAINT", "Skipping paint: " + e.getMessage());
    }
  }

  private String formatRuntime(long ms) {
    if (ms < 0) {
      ms = 0;
    }
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
    return new WebhookData(sandstoneMined, xpGained, levelsGained, runtimeText, interval);
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
