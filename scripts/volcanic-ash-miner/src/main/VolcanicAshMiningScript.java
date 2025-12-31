package main;

import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.ui.component.tabs.skill.SkillType;
import com.osmb.api.ui.component.tabs.skill.SkillsTabComponent;
import com.osmb.api.visual.drawing.Canvas;
import data.State;
import javafx.scene.Scene;
import tasks.BankingTask;
import tasks.MiningTask;
import tasks.SetupTask;
import utils.Task;
import utils.Webhook;
import utils.Webhook.WebhookData;
import data.VolcanicAshData;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * Volcanic ash mining script using modular task composition.
 */
@ScriptDefinition(
    author = "eqp48",
    name = "Volcanic Ash Miner",
    description = "Mines volcanic ash at Fossil Island.",
    skillCategory = SkillCategory.MINING,
    version = 1.1
)
public class VolcanicAshMiningScript extends Script {
    private static final String VERSION = "1.1";

    private List<Task> tasks;
    private MiningTask miningTask;
    private long startTimeMs = 0;
    private double startMiningXp = 0;
    private int startMiningLevel = 0;
    private GUI gui;
    private Webhook webhook;
    private boolean zoomConfigured = false;
    private long lastZoomAttemptMs = 0;
    private static final long ZOOM_RETRY_MS = 4_000;
    private boolean statsInitialized = false;
    private Integer lastKnownMiningLevel = null;
    private volatile boolean settingsConfirmed = false;
    private boolean setupComplete = false;
    private State state = State.SETUP;

    public VolcanicAshMiningScript(Object scriptCore) {
        super(scriptCore);
        webhook = new Webhook(this::buildWebhookData, this::log);
    }

    @Override
    public void onStart() {
        gui = new GUI(webhook.getConfig());
        gui.setOnStart(() -> {
            webhook.applyConfig(gui.buildWebhookConfig());
            settingsConfirmed = true;
            gui.closeWindow();
        });
        Scene scene = new Scene(gui);
        getStageController().show(scene, "Volcanic Ash Miner Settings", false);
        tasks = new ArrayList<>();
        // Add tasks in priority order. Setup first, then banking has higher priority than mining.
        tasks.add(new SetupTask(this));
        tasks.add(new BankingTask(this));
        miningTask = new MiningTask(this);
        tasks.add(miningTask);
        log(getClass(), "Volcanic Ash Miner started.");
    }

    // Limit searches to the volcanic ash area for faster lookups
    @Override
    public int[] regionsToPrioritise() {
        return VolcanicAshData.VOLCANIC_ASH_REGIONS;
    }

    @Override
    public int poll() {
        if (!settingsConfirmed) {
            return 500;
        }
        webhook.ensureStarted(() -> webhook.enqueueEvent("Stopped"));
        webhook.queuePeriodicWebhookIfDue();
        webhook.dispatchPendingWebhooks();
        if (getWidgetManager().getGameState() != com.osmb.api.ui.GameState.LOGGED_IN) {
            return 600;
        }

        for (Task task : tasks) {
            if (task.activate()) {
                task.execute();
                return 0;
            }
        }
        return 0;
    }

    public boolean isSetupComplete() {
        return setupComplete;
    }

    public void markSetupComplete() {
        setupComplete = true;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public void ensureStartTimeInitialized() {
        if (startTimeMs == 0) {
            startTimeMs = System.currentTimeMillis();
        }
    }

    public void ensureZoomConfigured() {
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
        boolean set = getWidgetManager().getSettings().setZoomLevel(0);
        if (set) {
            zoomConfigured = true;
        }
    }

    public void ensureStatsInitialized() {
        if (statsInitialized) {
            return;
        }
        if (getWidgetManager().getGameState() != com.osmb.api.ui.GameState.LOGGED_IN) {
            return;
        }
        startMiningXp = getMiningXp();
        startMiningLevel = getMiningLevel();
        statsInitialized = true;
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
        if (getWidgetManager().getGameState() != com.osmb.api.ui.GameState.LOGGED_IN) {
            return lastKnownMiningLevel != null ? lastKnownMiningLevel : 0;
        }
        try {
            SkillsTabComponent.SkillLevel skill = getWidgetManager().getSkillTab().getSkillLevel(SkillType.MINING);
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
            int height = padding * 2 + lineHeight * 4;

            c.fillRect(x, y, width, height, new Color(10, 10, 10, 190).getRGB(), 1);
            c.drawRect(x, y, width, height, Color.WHITE.getRGB());

            int textY = y + padding + 12;
            c.drawText("Volcanic Ash Miner - v" + VERSION, x + padding, textY, Color.YELLOW.getRGB(), new Font("Arial", Font.BOLD, 12));
            textY += lineHeight;
            c.drawText("Ash mined: " + (miningTask != null ? miningTask.ashMined : 0), x + padding, textY, Color.WHITE.getRGB(), new Font("Arial", Font.BOLD, 12));
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
        int ashMined = miningTask != null ? miningTask.ashMined : 0;
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
