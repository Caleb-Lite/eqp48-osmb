package main;

import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.ui.component.tabs.skill.SkillType;
import com.osmb.api.ui.component.tabs.skill.SkillsTabComponent;
import com.osmb.api.visual.drawing.Canvas;
import data.Locations;
import data.Locations.MiningLocation;
import javafx.scene.Scene;
import utils.Webhook;
import utils.Webhook.WebhookData;
import tasks.BankTask;
import tasks.MineTask;
import tasks.SetupTask;
import utils.Task;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.osmb.api.location.position.types.WorldPosition;

@ScriptDefinition(
  author = "eqp48",
  name = "Gem Miner",
  description = "Mines gem rocks in Shilo Village and banks the gems.",
  skillCategory = SkillCategory.MINING,
  version = 1.1
)
public class GemMinerScript extends Script {
  private static final String VERSION = "1.1";

  public enum State {
    SETUP,
    MINING,
    BANKING
  }

  public static State state = State.SETUP;
  public static boolean setupComplete = false;
  public static long startTimeMs = 0;
  public static int gemsMined = 0;
  public static double startMiningXp = 0;
  public static int startMiningLevel = 0;
  public static final Set<WorldPosition> waitingRespawn = new HashSet<>();
  public static boolean lastMineGainedXp = false;
  public static WorldPosition lastWalkTarget = null;
  public static Double gemXpPerRock = null;
  public static MiningLocation selectedLocation = Locations.UPPER;
  public static boolean zoomConfigured = false;
  public static long lastZoomAttemptMs = 0;
  public static final long ZOOM_RETRY_MS = 4_000;
  public static boolean statsInitialized = false;
  public static Integer lastKnownMiningLevel = null;

  private GUI gui;
  private Webhook webhook;
  private volatile boolean settingsConfirmed = false;
  private List<Task> tasks;

  public GemMinerScript(Object scriptCore) {
    super(scriptCore);
    webhook = new Webhook(this::buildWebhookData, s -> {});
  }

  @Override
  public void onStart() {
    gui = new GUI(selectedLocation, webhook.getConfig());
    gui.setOnStart(() -> {
      selectedLocation = gui.getSelectedLocation();
      webhook.applyConfig(gui.buildWebhookConfig());
      if (selectedLocation == null) {
        settingsConfirmed = false;
        return;
      }
      settingsConfirmed = true;
      gui.closeWindow();

      tasks = new ArrayList<>();
      tasks.add(new SetupTask(this));
      tasks.add(new BankTask(this));
      tasks.add(new MineTask(this));
    });
    Scene scene = new Scene(gui);
    getStageController().show(scene, "Gem Miner Settings", false);
  }

  @Override
  public int[] regionsToPrioritise() {
    return new int[]{11310, 11410};
  }

  @Override
  public int poll() {
    if (!settingsConfirmed) {
      return 600;
    }

    if (getWidgetManager().getGameState() != com.osmb.api.ui.GameState.LOGGED_IN) {
      return 600;
    }

    webhook.ensureStarted(() -> webhook.enqueueEvent("Stopped"));
    webhook.queuePeriodicWebhookIfDue();
    webhook.dispatchPendingWebhooks();

    if (tasks != null) {
      for (Task task : tasks) {
        if (task.activate()) {
          task.execute();
          return 0;
        }
      }
    }
    return 0;
  }

  public static double getMiningXp(Script script) {
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

  public static int getMiningLevel(Script script) {
    if (script.getWidgetManager().getGameState() != com.osmb.api.ui.GameState.LOGGED_IN) {
      return lastKnownMiningLevel != null ? lastKnownMiningLevel : 0;
    }
    try {
      SkillsTabComponent.SkillLevel skill = script.getWidgetManager().getSkillTab().getSkillLevel(SkillType.MINING);
      if (skill != null) {
        lastKnownMiningLevel = skill.getLevel();
        return lastKnownMiningLevel;
      }
    } catch (Exception e) {
      return lastKnownMiningLevel != null ? lastKnownMiningLevel : 0;
    }
    return lastKnownMiningLevel != null ? lastKnownMiningLevel : 0;
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
      int height = padding * 2 + lineHeight * 5;

      c.fillRect(x, y, width, height, new Color(10, 10, 10, 190).getRGB(), 1);
      c.drawRect(x, y, width, height, Color.WHITE.getRGB());

      int textY = y + padding + 12;
      c.drawText("Gem Miner - v" + VERSION, x + padding, textY, Color.YELLOW.getRGB(), new Font("Arial", Font.BOLD, 12));
      textY += lineHeight;
      c.drawText("Location: " + selectedLocation.displayName(), x + padding, textY, Color.WHITE.getRGB(), new Font("Arial", Font.BOLD, 12));
      textY += lineHeight;
      c.drawText("State: " + state.name(), x + padding, textY, Color.WHITE.getRGB(), new Font("Arial", Font.BOLD, 12));
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
    double currentXp = getMiningXp(this);
    int currentLevel = getMiningLevel(this);
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
