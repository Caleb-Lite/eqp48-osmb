import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ZoomType;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSObject;
import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.ui.component.tabs.skill.SkillType;
import com.osmb.api.utils.timing.Timer;
import com.osmb.api.utils.UIResult;
import com.osmb.api.item.ItemID;
import com.osmb.api.ui.spellbook.LunarSpellbook;
import com.osmb.api.ui.spellbook.SpellNotFoundException;
import com.osmb.api.ui.tabs.Spellbook;
import com.osmb.api.ui.overlay.BuffOverlay;
import com.osmb.api.walker.WalkConfig;
import com.osmb.api.visual.color.ColorModel;
import com.osmb.api.visual.color.tolerance.impl.SingleThresholdComparator;
import com.osmb.api.shape.Rectangle;
import com.osmb.api.shape.Polygon;
import com.osmb.api.visual.drawing.Canvas;
import com.osmb.api.visual.image.Image;
import com.osmb.api.visual.image.ImageSearchResult;
import com.osmb.api.visual.image.SearchableImage;
import com.osmb.api.trackers.experience.XPTracker;

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
  version = 1.1
)
public class SandstoneMinerScript extends Script {
  private static final String VERSION = "1.1";
  private static final String TARGET_ROCK_NAME = "Sandstone rocks";
  private static final WorldPosition GRINDER_POS = new WorldPosition(3152, 2909, 0);
  private static final WorldPosition SANDSTONE_POS = new WorldPosition(3167, 2908, 0);
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

  public SandstoneMinerScript(Object scriptCore) {
    super(scriptCore);
    for (int itemId : WATERSKIN_IDS) {
      waterskinOverlays.add(new BuffOverlay(this, itemId));
      WaterskinTemplate template = buildWaterskinTemplate(itemId);
      if (template != null) {
        waterskinTemplates.add(template);
      }
    }
  }

  @Override
  public void onStart() {
    var settings = getWidgetManager().getSettings();
    UIResult<Integer> zoomResult = settings.getZoomLevel();
    boolean alreadyMaxZoom = zoomResult.isFound() && zoomResult.get() == 0;

    if (!alreadyMaxZoom) {
      settings.setZoomLevel(0);
    }
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

    ItemGroupResult inventory = getWidgetManager().getInventory().search(Set.of(
      ItemID.ASTRAL_RUNE,
      ItemID.WATER_RUNE,
      ItemID.FIRE_RUNE
    ));
    if (inventory == null) {
      return 800;
    }

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

    if (inventory.isFull()) {
      return handleFullInventory();
    }

    WorldPosition myPos = getWorldPosition();
    if (myPos == null) {
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
      walkToSandstoneHome();
      return random(500, 800);
    }

    sandstoneRocks.sort(Comparator.comparingDouble(o -> o.getWorldPosition().distanceTo(myPos)));
    RSObject sandstoneRock = sandstoneRocks.get(0);

    if (sandstoneRock == null) {
      walkToSandstoneHome();
      return random(500, 800);
    }

    if (!waitForPlayerIdle()) {
      return random(400, 700);
    }

    if (!tapRock(sandstoneRock)) {
      return random(400, 700);
    }

    boolean mined = waitForMiningCompletion();
    if (mined && sandstoneRock.getWorldPosition() != null) {
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
    if (tracker instanceof XPTracker xpTracker) {
      return xpTracker.getXp();
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
    return getFinger().tapGameScreen(hull);
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
  }

  private String formatRuntime(long ms) {
    if (ms < 0) ms = 0;
    long totalSeconds = ms / 1000;
    long hours = totalSeconds / 3600;
    long minutes = (totalSeconds % 3600) / 60;
    long seconds = totalSeconds % 60;
    return String.format("%02d:%02d:%02d", hours, minutes, seconds);
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
