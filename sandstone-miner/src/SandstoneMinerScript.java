import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ZoomType;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.ui.component.tabs.skill.SkillType;
import com.osmb.api.ui.component.tabs.skill.SkillsTabComponent;
import com.osmb.api.utils.timing.Timer;
import com.osmb.api.utils.UIResult;
import com.osmb.api.utils.UIResultList;
import com.osmb.api.item.ItemID;
import com.osmb.api.ui.spellbook.LunarSpellbook;
import com.osmb.api.ui.spellbook.SpellNotFoundException;
import com.osmb.api.ui.tabs.Spellbook;
import com.osmb.api.ui.overlay.BuffOverlay;
import com.osmb.api.walker.WalkConfig;
import com.osmb.api.world.SkillTotal;
import com.osmb.api.world.World;
import com.osmb.api.visual.color.ColorModel;
import com.osmb.api.visual.color.tolerance.impl.SingleThresholdComparator;
import com.osmb.api.shape.Rectangle;
import com.osmb.api.shape.Polygon;
import com.osmb.api.input.MenuEntry;
import com.osmb.api.visual.drawing.Canvas;
import com.osmb.api.visual.image.Image;
import com.osmb.api.visual.image.ImageSearchResult;
import com.osmb.api.visual.image.SearchableImage;
import com.osmb.api.trackers.experience.XPTracker;
import utils.Webhook;
import utils.Webhook.WebhookData;

import static com.osmb.api.visual.ocr.fonts.Font.SMALL_FONT;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.awt.Color;
import java.awt.Font;

@ScriptDefinition(
  author = "eqp48",
  name = "Sandstone Miner",
  description = "Mines sandstone rocks in the quarry.",
  skillCategory = SkillCategory.MINING,
  version = 1.4
)
public class SandstoneMinerScript extends Script {
  private static final String VERSION = "1.4";
  private static final String TARGET_ROCK_NAME = "Sandstone rocks";
  private static final WorldPosition GRINDER_POS = new WorldPosition(3152, 2909, 0);
  private static final WorldPosition NORTH_ANCHOR = new WorldPosition(3163, 2914, 0);
  private static final WorldPosition SOUTH_ANCHOR = new WorldPosition(3165, 2906, 0);
  private static final Set<WorldPosition> NORTH_ROCKS = Set.of(
    new WorldPosition(3163, 2915, 0),
    new WorldPosition(3164, 2914, 0)
  );
  private static final Set<WorldPosition> SOUTH_ROCKS = Set.of(
    new WorldPosition(3166, 2906, 0),
    new WorldPosition(3164, 2906, 0)
  );
  private static final int[] WATERSKIN_IDS = new int[] {
    ItemID.WATERSKIN4,
    ItemID.WATERSKIN3,
    ItemID.WATERSKIN2,
    ItemID.WATERSKIN1,
    ItemID.WATERSKIN0
  };

  private long startTimeMs = System.currentTimeMillis();
  private int sandstoneMined = 0;
  private boolean humidifyCast = false;
  private long lastHumidifyCastMs = 0;
  private Integer lastWaterskinCharges = null;
  private final List<BuffOverlay> waterskinOverlays = new ArrayList<>();
  private final List<WaterskinTemplate> waterskinTemplates = new ArrayList<>();
  private double startMiningXp = 0;
  private int startMiningLevel = 0;
  private Webhook webhook;
  private volatile Webhook.MiningLocation lastMiningLocation = Webhook.MiningLocation.NORTH;
  private WorldPosition lastMovePosition = null;
  private long lastMoveChangeMs = 0;
  private WorldPosition lastAnchorWalkTarget = null;
  private long lastAnchorWalkMs = 0;
  private boolean hopOnNearbyPlayersEnabled = false;
  private int hopRadiusTiles = 0;
  private long lastHopAttemptMs = 0;

  public SandstoneMinerScript(Object scriptCore) {
    super(scriptCore);
    for (int itemId : WATERSKIN_IDS) {
      waterskinOverlays.add(new BuffOverlay(this, itemId));
      WaterskinTemplate template = buildWaterskinTemplate(itemId);
      if (template != null) {
        waterskinTemplates.add(template);
      }
    }
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

    // Update movement state each tick
    isPlayerMoving();

    ItemGroupResult inventory = getWidgetManager().getInventory().search(Set.of(
      ItemID.ASTRAL_RUNE,
      ItemID.WATER_RUNE,
      ItemID.FIRE_RUNE
    ));
    if (!webhook.isSubmitted()) {
      webhook.ensureDialogVisible();
      return 500;
    }
    webhook.ensureStarted(() -> webhook.enqueueEvent("Stopped"));
    webhook.queuePeriodicWebhookIfDue();
    webhook.dispatchPendingWebhooks();
    if (inventory == null) {
      return 800;
    }

    Webhook.MiningLocation miningLocation = webhook.getMiningLocation();
    if (miningLocation != lastMiningLocation) {
      waitingRespawn.clear();
      lastMiningLocation = miningLocation;
    }

    hopOnNearbyPlayersEnabled = webhook.isHopEnabled();
    hopRadiusTiles = Math.max(0, webhook.getHopRadiusTiles());

    Integer waterskinCharges = getWaterskinCharges();

    // Highest priority in-loop: cast Humidify once when available
    if (shouldCastHumidify(waterskinCharges, inventory)) {
      boolean cast = castHumidify();
      if (cast) {
        humidifyCast = true;
        lastHumidifyCastMs = System.currentTimeMillis();
        return random(400, 700);
      }
    }

    WorldPosition myPos = getWorldPosition();
    if (myPos == null) {
      return 800;
    }

    WorldPosition anchor = getAnchorForLocation(miningLocation);

    if (maybeHopForNearbyPlayers(anchor, myPos)) {
      return random(800, 1200);
    }

    if (inventory.isFull()) {
      return handleFullInventory();
    }

    double anchorDistance = anchor != null && myPos != null ? myPos.distanceTo(anchor) : Double.MAX_VALUE;
    if (anchor != null && !inventory.isFull() && anchorDistance > 1.0) {
      if (requestAnchorWalk(anchor)) {
        return random(400, 700);
      }
      return random(300, 500);
    }

    List<WorldPosition> respawnCircles = getRespawnCirclePositions();

    List<RSObject> sandstoneRocks = getObjectManager().getObjects(object ->
      object != null &&
        object.isInteractableOnScreen() &&
        object.getWorldPosition() != null &&
        allowRock(object.getWorldPosition(), respawnCircles, myPos, miningLocation) &&
        object.getName() != null &&
        TARGET_ROCK_NAME.equalsIgnoreCase(object.getName()) &&
        hasMineAction(object)
    );

    if (sandstoneRocks == null || sandstoneRocks.isEmpty()) {
      requestAnchorWalk(anchor);
      return random(500, 800);
    }

    sandstoneRocks.sort(Comparator.comparingDouble(o -> o.getWorldPosition().distanceTo(myPos)));
    RSObject sandstoneRock = sandstoneRocks.get(0);

    if (sandstoneRock == null) {
      requestAnchorWalk(anchor);
      return random(500, 800);
    }

    if (!waitForPlayerIdle()) {
      return random(400, 700);
    }

    if (!tapRock(sandstoneRock)) {
      return random(400, 700);
    }

    boolean mined = waitForMiningCompletion();
    if (mined && sandstoneRock.getWorldPosition() != null && isAllowedRockPosition(sandstoneRock.getWorldPosition(), miningLocation)) {
      waitingRespawn.add(sandstoneRock.getWorldPosition());
    }

    return mined ? random(120, 240) : random(250, 500);
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
        return random(500, 800);
      }
    } else {
      if (isPlayerMoving()) {
        return random(400, 700);
      }
      WalkConfig config = new WalkConfig.Builder()
        .breakCondition(() -> {
          RSObject g = findGrinder();
          return g != null && g.isInteractableOnScreen();
        })
        .build();
      getWalker().walkTo(GRINDER_POS, config);

      grinder = findGrinder();
      if (grinder == null || !grinder.isInteractableOnScreen() || !grinder.interact("Deposit")) {
        return random(500, 800);
      }
    }

    boolean deposited = submitHumanTask(() -> {
      ItemGroupResult inv = getWidgetManager().getInventory().search(Collections.emptySet());
      return inv == null || !inv.isFull();
    }, 8_000);

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

  private void walkToAnchor(WorldPosition anchor) {
    if (anchor == null) {
      return;
    }
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
    List<WorldPosition> positions = getUtils().getWorldPositionForRespawnCircles(respawnCircles, 20);
    return positions != null ? positions : Collections.emptyList();
  }

  private WorldPosition getAnchorForLocation(Webhook.MiningLocation location) {
    return location == Webhook.MiningLocation.SOUTH ? SOUTH_ANCHOR : NORTH_ANCHOR;
  }

  private Set<WorldPosition> getAllowedRockPositions(Webhook.MiningLocation location) {
    return location == Webhook.MiningLocation.SOUTH ? SOUTH_ROCKS : NORTH_ROCKS;
  }

  private boolean isAllowedRockPosition(WorldPosition position, Webhook.MiningLocation location) {
    if (position == null) {
      return false;
    }
    return getAllowedRockPositions(location).contains(position);
  }

  private boolean allowRock(WorldPosition position, List<WorldPosition> respawnCircles, WorldPosition playerPos, Webhook.MiningLocation location) {
    if (!isAllowedRockPosition(position, location)) {
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

  private boolean requestAnchorWalk(WorldPosition anchor) {
    if (anchor == null) {
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

  private boolean maybeHopForNearbyPlayers(WorldPosition anchor, WorldPosition myPos) {
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
    UIResultList<WorldPosition> players = minimap.getPlayerPositions();
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

  private boolean isPlayerMoving() {
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

  private final Set<WorldPosition> waitingRespawn = new HashSet<>();
  private boolean lastMineGainedXp = false;

  private boolean tapRock(RSObject rock) {
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
        TARGET_ROCK_NAME.equalsIgnoreCase(name);
    }, 2_000);
  }

  private boolean canCastHumidify(ItemGroupResult inventory) {
    try {
      var spellbook = getWidgetManager().getSpellbook();
      if (spellbook == null) {
        return false;
      }

      ItemGroupResult inv = inventory != null ? inventory : getWidgetManager().getInventory().search(Set.of(
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
      boolean hasAstral = astral >= 1;
      boolean hasWater = water >= 3;
      boolean hasFire = fire >= 1;
      return hasAstral && hasWater && hasFire;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private boolean castHumidify() {
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

  private Integer getWaterskinChargesFromBuffOverlay() {
    for (BuffOverlay overlay : waterskinOverlays) {
      String text = overlay.getBuffText();
      Integer parsed = parseWaterskinBuffText(text);
      if (parsed != null) {
        lastWaterskinCharges = parsed;
        return parsed;
      }
    }
    return null;
  }

  private WaterskinTemplate buildWaterskinTemplate(int itemId) {
    try {
      Image itemImage = getItemManager().getItemImage(itemId, 999, ZoomType.SIZE_1, 0xFF00FF);
      if (itemImage == null) {
        return null;
      }
      // Crop similarly to BuffOverlay to avoid border noise
      itemImage = itemImage.subImage(0, 0, itemImage.getWidth() - 5, itemImage.getHeight() - 11);
      SearchableImage searchable = itemImage.toSearchableImage(new SingleThresholdComparator(25), ColorModel.RGB);
      // Bottom strip where the number appears
      Rectangle digitArea = new Rectangle(0, Math.max(0, itemImage.getHeight() - 12), itemImage.getWidth(), 12);
      return new WaterskinTemplate(searchable, digitArea);
    } catch (Exception e) {
      return null;
    }
  }

  private Integer getWaterskinChargesFromImageSearch() {
    try {
      for (WaterskinTemplate template : waterskinTemplates) {
        ImageSearchResult result = getImageAnalyzer().findLocation(template.icon());
        if (result == null) {
          continue;
        }
        Rectangle bounds = result.getBounds();
        Rectangle numberBounds = new Rectangle(
          bounds.x + template.digitArea.x,
          bounds.y + template.digitArea.y,
          template.digitArea.width,
          template.digitArea.height
        );
        String text = getOCR().getText(SMALL_FONT, numberBounds, new int[]{-1, -65536});
        Integer parsed = parseWaterskinBuffText(text);
        if (parsed != null) {
          lastWaterskinCharges = parsed;
          return parsed;
        }
      }
    } catch (Exception e) {
      // Swallow and fall back to null to avoid interrupting script loop
    }
    return null;
  }

  private Integer getWaterskinCharges() {
    Integer overlay = getWaterskinChargesFromBuffOverlay();
    if (overlay != null) {
      return overlay;
    }
    return getWaterskinChargesFromImageSearch();
  }

  private Integer parseWaterskinBuffText(String buffText) {
    if (buffText == null || buffText.isEmpty()) {
      return null;
    }
    buffText = buffText.trim();
    for (int i = 0; i < buffText.length(); i++) {
      char c = buffText.charAt(i);
      if (Character.isDigit(c)) {
        int value = Character.getNumericValue(c);
        if (value >= 0 && value <= 4) {
          return value;
        }
      }
    }
    return null;
  }

  private boolean shouldCastHumidify(Integer waterskinCharges, ItemGroupResult inventory) {
    if (!canCastHumidify(inventory)) {
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

      // Background panel for readability
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

  private static class WaterskinTemplate {
    private final SearchableImage icon;
    private final Rectangle digitArea;

    private WaterskinTemplate(SearchableImage icon, Rectangle digitArea) {
      this.icon = icon;
      this.digitArea = digitArea;
    }

    private SearchableImage icon() {
      return icon;
    }

    private Rectangle digitArea() {
      return digitArea;
    }
  }

}
