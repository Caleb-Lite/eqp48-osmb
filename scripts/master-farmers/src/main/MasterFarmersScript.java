package main;

import com.osmb.api.location.area.Area;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.item.ItemID;
import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.trackers.experience.XPTracker;
import com.osmb.api.visual.drawing.Canvas;
import data.Locations;
import data.Locations.FarmerLocation;
import tasks.BankTask;
import tasks.CoinPouchTask;
import tasks.EatTask;
import tasks.PickpocketTask;
import tasks.SetupTask;
import tasks.StunHandlerTask;
import utils.Task;
import utils.XPTracking;

import java.awt.Color;
import java.awt.Font;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javafx.scene.Scene;

@ScriptDefinition(
        name = "Master Farmers",
        description = "Pickpockets Master Farmers and banks when inventory is full.",
        skillCategory = SkillCategory.THIEVING,
        version = 1.0,
        author = "eqp48"
)
public class MasterFarmersScript extends Script {

    public enum State {
        SETUP,
        PICKPOCKET,
        BANK,
        STUNNED,
        EATING,
        STOPPED
    }

    public static State state = State.SETUP;
    public static boolean setupComplete = false;
    public static boolean needToBank = false;
    public static boolean needsFoodRestock = false;
    public static boolean stunned = false;
    public static Integer foodItemId = null;
    public static final int HP_THRESHOLD = 50;

    public static Area thievingArea;
    public static Area bankArea;
    public static WorldPosition anchorTile;
    public static String locationLabel = "Unknown";
    public static FarmerLocation selectedLocation;

    public static final Set<Integer> DEFAULT_KEEP_ITEMS = Set.of(
            ItemID.DODGY_NECKLACE,
            ItemID.SALMON,
            ItemID.TROUT,
            ItemID.SWORDFISH,
            ItemID.COIN_POUCH,
            ItemID.COIN_POUCH_22522,
            ItemID.COIN_POUCH_22523,
            ItemID.COIN_POUCH_22524,
            ItemID.COIN_POUCH_22525,
            ItemID.COIN_POUCH_22526,
            ItemID.COIN_POUCH_22527,
            ItemID.COIN_POUCH_22528,
            ItemID.COIN_POUCH_22529,
            ItemID.COIN_POUCH_22530,
            ItemID.COIN_POUCH_22531,
            ItemID.COIN_POUCH_22532,
            ItemID.COIN_POUCH_22533,
            ItemID.COIN_POUCH_22534,
            ItemID.COIN_POUCH_22535,
            ItemID.COIN_POUCH_22536,
            ItemID.COIN_POUCH_22537,
            ItemID.COIN_POUCH_22538,
            ItemID.COIN_POUCH_24703,
            ItemID.COIN_POUCH_28822,
            ItemID.SEED_BOX,
            ItemID.OPEN_SEED_BOX,
            ItemID.EARTH_RUNE,
            ItemID.FIRE_RUNE,
            ItemID.COSMIC_RUNE,
            ItemID.RUNE_POUCH,
            ItemID.RUNE_POUCH_23650,
            ItemID.RUNE_POUCH_27086,
            ItemID.RUNE_POUCH_L,
            ItemID.DIVINE_RUNE_POUCH,
            ItemID.DIVINE_RUNE_POUCH_L,
            ItemID.RUNE_POUCH_30692
    );
    public static final Set<Integer> COIN_POUCH_IDS = Set.of(
            ItemID.COIN_POUCH,
            ItemID.COIN_POUCH_22522,
            ItemID.COIN_POUCH_22523,
            ItemID.COIN_POUCH_22524,
            ItemID.COIN_POUCH_22525,
            ItemID.COIN_POUCH_22526,
            ItemID.COIN_POUCH_22527,
            ItemID.COIN_POUCH_22528,
            ItemID.COIN_POUCH_22529,
            ItemID.COIN_POUCH_22530,
            ItemID.COIN_POUCH_22531,
            ItemID.COIN_POUCH_22532,
            ItemID.COIN_POUCH_22533,
            ItemID.COIN_POUCH_22534,
            ItemID.COIN_POUCH_22535,
            ItemID.COIN_POUCH_22536,
            ItemID.COIN_POUCH_22537,
            ItemID.COIN_POUCH_22538,
            ItemID.COIN_POUCH_24703,
            ItemID.COIN_POUCH_28822
    );
    public static Set<Integer> keepItemIds = new HashSet<>();

    public static final Font ARIAL = new Font("Arial", Font.PLAIN, 14);
    public static final Font ARIAL_BOLD = new Font("Arial", Font.BOLD, 14);
    public static final Font ARIAL_ITALIC = new Font("Arial", Font.ITALIC, 14);

    private List<Task> tasks;
    private GUI gui;
    private volatile boolean settingsConfirmed = false;
    public static long startTime = System.currentTimeMillis();
    private static final double THIEVING_XP_PER_PICKPOCKET = 43.0;
    private XPTracking xpTracking;
    private double thievingXpGained = 0.0;
    private int thievingXpPerHour = 0;
    private long successfulPickpockets = 0L;
    private boolean zoomConfigured = false;
    private long lastZoomAttemptMs = 0;
    private static final long ZOOM_RETRY_MS = 4_000;

    public MasterFarmersScript(Object scriptCore) {
        super(scriptCore);
    }

    @Override
    public int[] regionsToPrioritise() {
        return new int[]{
                10548,
                4922,
                6968,
                6448,
                6447,
                6703,
                6959,
                5938,
                5423,
                12338,
                12339
        };
    }

    @Override
    public void onStart() {
        gui = new GUI();
        xpTracking = new XPTracking(this);

        gui.setOnStart(() -> {
            selectedLocation = gui.getSelectedLocation();
            keepItemIds = new HashSet<>(DEFAULT_KEEP_ITEMS);
            foodItemId = gui.getFoodItemId();
            if (foodItemId != null) {
                keepItemIds.add(foodItemId);
            }

            if (selectedLocation != null) {
                applyLocation(selectedLocation);
                tasks = new ArrayList<>();
                tasks.add(new SetupTask(this));
                tasks.add(new StunHandlerTask(this));
                tasks.add(new EatTask(this));
                tasks.add(new CoinPouchTask(this));
                tasks.add(new BankTask(this));
                tasks.add(new PickpocketTask(this));
                settingsConfirmed = true;
                gui.closeWindow();
            } else {
                state = State.STOPPED;
                log(getClass().getSimpleName(), "No location selected. Please configure a location before starting.");
                stop();
            }
        });
        Scene scene = new Scene(gui);
        getStageController().show(scene, "Master Farmers Settings", false);
    }

    public void applyLocation(FarmerLocation loc) {
        if (loc == null) return;
        thievingArea = loc.thievingArea();
        bankArea = loc.bankArea();
        anchorTile = loc.anchor();
        locationLabel = loc.name();
    }

    @Override
    public int poll() {
        if (!settingsConfirmed) {
            return 200;
        }
        if (getWidgetManager().getGameState() != com.osmb.api.ui.GameState.LOGGED_IN) {
            return 200;
        }
        ensureZoomConfigured();
        updateThievingStats();

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
        boolean set = getWidgetManager().getSettings().setZoomLevel(0);
        if (set) {
            zoomConfigured = true;
        }
    }

    @Override
    public void onPaint(Canvas c) {
        if (c == null) {
            return;
        }
        updateThievingStats();
        try {
            int x = 6;
            int y = 32;
            int width = 180;
            int padding = 8;
            int lineHeight = 16;
            int height = padding * 2 + lineHeight * 7;

            long elapsed = System.currentTimeMillis() - startTime;
            String runtime = formatRuntime(elapsed);

            c.fillRect(x, y, width, height, new Color(10, 10, 10, 190).getRGB(), 1);
            c.drawRect(x, y, width, height, Color.WHITE.getRGB());

            int textY = y + padding + 12;
            c.drawText("Master Farmers - v" + getClass().getAnnotation(ScriptDefinition.class).version(), x + padding, textY, Color.YELLOW.getRGB(), new Font("Arial", Font.BOLD, 12));
            textY += lineHeight;
            c.drawText("Location: " + locationLabel, x + padding, textY, Color.WHITE.getRGB(), new Font("Arial", Font.BOLD, 12));
            textY += lineHeight;
            c.drawText("State: " + state.name(), x + padding, textY, Color.WHITE.getRGB(), new Font("Arial", Font.BOLD, 12));
            textY += lineHeight;
            c.drawText("Runtime: " + runtime, x + padding, textY, Color.LIGHT_GRAY.getRGB(), new Font("Arial", Font.PLAIN, 12));
            textY += lineHeight;
            c.drawText("Pickpockets: " + formatNumber(successfulPickpockets), x + padding, textY, Color.WHITE.getRGB(), new Font("Arial", Font.BOLD, 12));
            textY += lineHeight;
            c.drawText("Thieving XP: " + formatNumber(thievingXpGained), x + padding, textY, Color.WHITE.getRGB(), new Font("Arial", Font.BOLD, 12));
            textY += lineHeight;
            c.drawText("XP/hr: " + formatNumber(thievingXpPerHour), x + padding, textY, Color.WHITE.getRGB(), new Font("Arial", Font.BOLD, 12));
        } catch (Exception ignored) {
        }
    }

    private String formatRuntime(long millis) {
        long seconds = millis / 1000;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }

    private void updateThievingStats() {
        if (xpTracking == null) return;

        XPTracker tracker = xpTracking.getThievingTracker();
        if (tracker == null) return;

        double currentXpGained = Math.max(0.0, tracker.getXpGained());
        if (currentXpGained < thievingXpGained) {
            successfulPickpockets = 0;
        }
        if (currentXpGained > thievingXpGained) {
            double delta = currentXpGained - thievingXpGained;
            long pickpocketsFromDelta = Math.max(1L, Math.round(delta / THIEVING_XP_PER_PICKPOCKET));
            successfulPickpockets += pickpocketsFromDelta;
        }
        thievingXpGained = currentXpGained;
        thievingXpPerHour = Math.max(0, tracker.getXpPerHour());
    }

    private String formatNumber(double value) {
        NumberFormat formatter = NumberFormat.getInstance();
        formatter.setGroupingUsed(true);
        formatter.setMaximumFractionDigits(0);
        return formatter.format(value);
    }

    public static Integer getHitpointsPercentage(Script script) {
        if (script == null) return null;

        var orbs = script.getWidgetManager().getMinimapOrbs();
        if (orbs == null) return null;

        Integer hp = orbs.getHitpointsPercentage();
        if (hp == null || hp < 0) return null;

        return hp;
    }
}
