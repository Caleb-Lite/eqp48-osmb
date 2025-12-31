package main;

import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.visual.drawing.Canvas;
import javafx.scene.Scene;
import data.State;
import utils.ShopInterface;
import utils.Task;
import tasks.BuyTask;
import tasks.HopTask;
import tasks.OpenPacksTask;
import tasks.OpenShopTask;
import tasks.SellTask;
import tasks.SetupTask;

import java.awt.Color;
import java.awt.Font;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Collections;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import com.osmb.api.utils.RandomUtils;

@ScriptDefinition(
        author = "eqp48",
        name = "Shopper",
        description = "All-in-one shopper",
        version = 1.3,
        skillCategory = SkillCategory.OTHER
)
public class ShopperScript extends Script {
    private GUI gui;
    private ShopInterface shopInterface;

    private String npcAction = "Trade";
    private int targetItemId;
    private int targetAmount;
    private Mode mode = Mode.BUY;
    private HopWhen hopWhen = HopWhen.OUT_OF_STOCK;
    private ComparatorType hopStockComparator = ComparatorType.LESS_OR_EQUAL;
    private int hopStockThreshold = 0;
    private int prioritisedRegionId = 0;
    private boolean hoppingEnabled = true;
    private boolean openPacksEnabled = false;
    private boolean waitingForPacksToClear = false;
    private int waitingPackId = -1;
    private boolean hopRequested = false;

    private int bought;
    private long startTime;
    private String startTileLabel = "";
    private State state = State.SETUP;
    private boolean initialised = false;
    private boolean npcInteractionStubLogged = false;
    private final java.util.Map<String, Integer> itemNameCache = new java.util.HashMap<>();
    private boolean itemNameCacheBuilt = false;
    private boolean zoomConfigured = false;
    private long lastZoomAttemptMs = 0;
    private static final long ZOOM_RETRY_MS = 4_000;

    private List<Task> tasks = new ArrayList<>();

    private volatile boolean settingsConfirmed = false;
    private volatile String submittedNpcAction;
    private volatile String submittedItemInput;
    private volatile int submittedTargetAmount;
    private volatile Mode submittedMode = Mode.BUY;
    private volatile HopWhen submittedHopWhen = HopWhen.OUT_OF_STOCK;
    private volatile ComparatorType submittedStockComparator = ComparatorType.LESS_OR_EQUAL;
    private volatile int submittedStockThreshold = 0;
    private volatile int submittedRegionId = 0;
    private volatile boolean submittedHoppingEnabled = true;
    private volatile boolean submittedOpenPacksEnabled = false;
    // private volatile String submittedShopTitle;
    private static final String VERSION = "1.3";
    private static final Font TEXT_BOLD = new Font("Arial", Font.BOLD, 12);
    private static final Font TEXT_REGULAR = new Font("Arial", Font.PLAIN, 12);
    private static final Set<Integer> PACK_ITEM_IDS = new HashSet<>();

    static {
        buildPackIds();
    }

    private static void buildPackIds() {
        try {
            for (java.lang.reflect.Field field : data.ItemID.class.getFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    || !java.lang.reflect.Modifier.isFinal(field.getModifiers())
                    || field.getType() != int.class) {
                    continue;
                }
                String name = field.getName().toLowerCase();
                if (!name.contains("pack")) {
                    continue;
                }
                int id = field.getInt(null);
                PACK_ITEM_IDS.add(id);
            }
        } catch (Exception ignored) {
            // fallback is name-based detection only
        }
    }

    public ShopperScript(Object scriptCore) {
        super(scriptCore);
    }

    @Override
    public void onStart() {
        gui = new GUI();
        gui.setOnStart(this::handleStartClicked);
        Scene scene = new Scene(gui);
        getStageController().show(scene, "Settings", false);

        tasks.clear();
        tasks.add(new SetupTask(this));
    }

    @Override
    public int poll() {
        if (state == State.STOPPED) {
            stop();
            return 0;
        }

        if (!settingsConfirmed) {
            return 200;
        }

        if (getWidgetManager().getGameState() != com.osmb.api.ui.GameState.LOGGED_IN) {
            return 200;
        }

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

    public boolean isSettingsConfirmed() {
        return settingsConfirmed;
    }

    public boolean isInitialised() {
        return initialised;
    }

    public boolean isZoomConfigured() {
        return zoomConfigured;
    }

    public void setZoomConfigured(boolean zoomConfigured) {
        this.zoomConfigured = zoomConfigured;
    }

    public long getLastZoomAttemptMs() {
        return lastZoomAttemptMs;
    }

    public void setLastZoomAttemptMs(long lastZoomAttemptMs) {
        this.lastZoomAttemptMs = lastZoomAttemptMs;
    }

    public long getZoomRetryMs() {
        return ZOOM_RETRY_MS;
    }

    public Mode getMode() {
        return mode;
    }

    public HopWhen getHopWhen() {
        return hopWhen;
    }

    public ComparatorType getHopStockComparator() {
        return hopStockComparator;
    }

    public int getHopStockThreshold() {
        return hopStockThreshold;
    }

    public boolean isHoppingEnabled() {
        return hoppingEnabled;
    }

    public boolean isOpenPacksEnabled() {
        return openPacksEnabled;
    }

    public boolean isWaitingForPacksToClear() {
        return waitingForPacksToClear;
    }

    public void setWaitingForPacksToClear(boolean waitingForPacksToClear) {
        this.waitingForPacksToClear = waitingForPacksToClear;
    }

    public int getWaitingPackId() {
        return waitingPackId;
    }

    public void setWaitingPackId(int waitingPackId) {
        this.waitingPackId = waitingPackId;
    }

    public boolean isHopRequested() {
        return hopRequested;
    }

    public void setHopRequested(boolean hopRequested) {
        this.hopRequested = hopRequested;
    }

    public ShopInterface getShopInterface() {
        return shopInterface;
    }

    public boolean isShopVisible() {
        return shopInterface != null && shopInterface.isVisible();
    }

    public void closeShopInterface() {
        if (shopInterface != null) {
            shopInterface.close();
        }
    }

    public String getNpcAction() {
        return npcAction == null || npcAction.isBlank() ? "Trade" : npcAction;
    }

    public boolean isNpcInteractionStubLogged() {
        return npcInteractionStubLogged;
    }

    public void setNpcInteractionStubLogged(boolean npcInteractionStubLogged) {
        this.npcInteractionStubLogged = npcInteractionStubLogged;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public int getTargetItemId() {
        return targetItemId;
    }

    public int getTargetAmount() {
        return targetAmount;
    }

    public int getBought() {
        return bought;
    }

    public void addBought(int amount) {
        this.bought += amount;
    }

    public Set<Integer> getPackItemIds() {
        return PACK_ITEM_IDS;
    }

    private int resolveItemId(String input) {
        if (input == null || input.trim().isBlank()) {
            log(getClass().getSimpleName(), "Missing item input; stopping.");
            state = State.STOPPED;
            return -1;
        }

        String trimmed = input.trim();
        Integer parsed = parseIntOrNull(trimmed);
        if (parsed != null && parsed > 0) {
            return parsed;
        }

        buildItemNameCache();
        String normalized = normalizeName(trimmed);
        Integer exact = itemNameCache.get(normalized);
        if (exact != null) {
            return exact;
        }

        for (java.util.Map.Entry<String, Integer> entry : itemNameCache.entrySet()) {
            if (entry.getKey().contains(normalized)) {
                return entry.getValue();
            }
        }

        log(getClass().getSimpleName(), "Could not resolve item name \"" + input + "\" to an ID; stopping.");
        state = State.STOPPED;
        return -1;
    }

    private void buildItemNameCache() {
        if (itemNameCacheBuilt) {
            return;
        }
        // 1) Parse generated ItemID.java for comment display names + ids (first occurrence wins)
        try {
            java.nio.file.Path path = java.nio.file.Paths.get("shopper/data/ItemID.java");
            if (java.nio.file.Files.exists(path)) {
                String content = java.nio.file.Files.readString(path);
                java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "/\\*\\*\\s*\\*\\s*(.+?)\\s*\\*/\\s*public static final int\\s+([A-Z0-9_]+)\\s*=\\s*(\\d+)\\s*;",
                    java.util.regex.Pattern.DOTALL);
                java.util.regex.Matcher m = p.matcher(content);
                while (m.find()) {
                    String displayName = m.group(1).trim();
                    int id = Integer.parseInt(m.group(3));
                    String normalized = normalizeName(displayName);
                    itemNameCache.putIfAbsent(normalized, id);
                }
            }
        } catch (Exception e) {
            log(getClass().getSimpleName(), "Failed to parse ItemID.java: " + e.getMessage());
        }

        // 2) Reflection fallback using field names and itemManager display names where available
        try {
            for (java.lang.reflect.Field field : data.ItemID.class.getFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    || !java.lang.reflect.Modifier.isFinal(field.getModifiers())
                    || field.getType() != int.class) {
                    continue;
                }
                int id = field.getInt(null);
                String displayName = null;
                try {
                    displayName = getItemManager() != null ? getItemManager().getItemName(id) : null;
                } catch (Exception ignored) {
                    // continue with fallback
                }
                if (displayName == null || displayName.isBlank()) {
                    displayName = field.getName().replace("_", " ");
                }
                String normalized = normalizeName(displayName);
                itemNameCache.putIfAbsent(normalized, id);
            }
        } catch (Exception e) {
            log(getClass().getSimpleName(), "Failed to build item name cache (reflection): " + e.getMessage());
        }
        itemNameCacheBuilt = true;
    }

    private String normalizeName(String name) {
        String lowered = name.toLowerCase();
        // replace any non-alphanumeric with underscore, then collapse multiple underscores
        String underscored = lowered.replaceAll("[^a-z0-9]+", "_");
        while (underscored.contains("__")) {
            underscored = underscored.replace("__", "_");
        }
        if (underscored.startsWith("_")) {
            underscored = underscored.substring(1);
        }
        if (underscored.endsWith("_")) {
            underscored = underscored.substring(0, underscored.length() - 1);
        }
        return underscored;
    }

    private Integer parseIntOrNull(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return null;
        }
    }

    public int gaussianDelay(int min, int max, double mean, double stdev) {
        int val = RandomUtils.gaussianRandom(min, max, mean, stdev);
        return Math.max(min, Math.min(max, val));
    }

    @Override
    public void onPaint(Canvas c) {
        if (c == null) {
            return;
        }

        try {
            DecimalFormat format = new DecimalFormat("#,###");
            format.setDecimalFormatSymbols(new DecimalFormatSymbols());

            long elapsed = Math.max(0, System.currentTimeMillis() - startTime);
            int perHour = elapsed > 0 ? (int) ((bought * 3_600_000L) / elapsed) : 0;

            int x = 6;
            int y = 32;
            int width = 180;
            int padding = 8;
            int lineHeight = 16;
            int lines = 6;
            int height = padding * 2 + lineHeight * lines;

            c.fillRect(x, y, width, height, new Color(10, 10, 10, 190).getRGB(), 1);
            c.drawRect(x, y, width, height, Color.WHITE.getRGB());

            String actionLabel = mode == Mode.SELL ? "Sold" : "Bought";

            int textY = y + padding + 12;
            c.drawText("Shopper - v" + VERSION, x + padding, textY, Color.YELLOW.getRGB(), TEXT_BOLD);
            textY += lineHeight;
            c.drawText(actionLabel + ": " + format.format(bought), x + padding, textY, Color.WHITE.getRGB(), TEXT_BOLD);
            textY += lineHeight;
            c.drawText("Total amount: " + (targetAmount <= 0 ? "" : format.format(targetAmount)), x + padding, textY, Color.WHITE.getRGB(), TEXT_BOLD);
            textY += lineHeight;
            c.drawText("Per hour: " + format.format(perHour), x + padding, textY, Color.LIGHT_GRAY.getRGB(), TEXT_REGULAR);
            textY += lineHeight;
            c.drawText("Runtime: " + formatTime(elapsed), x + padding, textY, Color.LIGHT_GRAY.getRGB(), TEXT_REGULAR);
            textY += lineHeight;
            c.drawText("Start tile: " + startTileLabel, x + padding, textY, Color.LIGHT_GRAY.getRGB(), TEXT_REGULAR);
        } catch (Exception ignored) {
        }
    }

    private void handleStartClicked() {
        submittedNpcAction = "Trade";
        submittedItemInput = gui.getItemInput();
        submittedTargetAmount = gui.getTargetAmount();
        submittedMode = Mode.fromString(gui.getMode());
        submittedHopWhen = HopWhen.fromSelection(gui.getHopWhenSelection());
        submittedStockComparator = ComparatorType.fromString(gui.getStockComparatorSelection());
        Integer parsedThreshold = gui.getStockThreshold();
        submittedStockThreshold = parsedThreshold == null ? 0 : Math.max(0, parsedThreshold);
        Integer region = gui.getRegionId();
        submittedRegionId = region == null ? 0 : Math.max(0, region);
        submittedHoppingEnabled = gui.isHoppingEnabled();
        submittedOpenPacksEnabled = gui.isOpenPacksEnabled();
        settingsConfirmed = true;
        gui.closeWindow();
    }

    public void initialiseConfig() {
        npcAction = "Trade";
        targetItemId = resolveItemId(submittedItemInput);
        targetAmount = submittedTargetAmount;
        mode = submittedMode == null ? Mode.BUY : submittedMode;
        hopWhen = submittedHopWhen == null ? HopWhen.OUT_OF_STOCK : submittedHopWhen;
        hopStockComparator = submittedStockComparator == null ? ComparatorType.LESS_OR_EQUAL : submittedStockComparator;
        hopStockThreshold = Math.max(0, submittedStockThreshold);
        prioritisedRegionId = Math.max(0, submittedRegionId);
        hoppingEnabled = submittedHoppingEnabled;
        openPacksEnabled = submittedOpenPacksEnabled;
        hopRequested = false;

        if (targetItemId <= 0) {
            state = State.STOPPED;
            initialised = true;
            return;
        }

        shopInterface = new ShopInterface(this, "");
        getWidgetManager().getInventory().registerInventoryComponent(shopInterface);

        SetupTask setupTask = new SetupTask(this);
        OpenPacksTask openPacksTask = new OpenPacksTask(this);
        HopTask hopTask = new HopTask(this, openPacksTask);
        BuyTask buyTask = new BuyTask(this, hopTask, openPacksTask);
        SellTask sellTask = new SellTask(this);
        OpenShopTask openShopTask = new OpenShopTask(this);

        tasks = new ArrayList<>();
        Collections.addAll(tasks, setupTask, openPacksTask, hopTask, openShopTask, buyTask, sellTask);

        WorldPosition startPos = getWorldPosition();
        if (startPos != null) {
            startTileLabel = startPos.getX() + "," + startPos.getY() + "," + startPos.getPlane();
            log(getClass().getSimpleName(), "Starting tile: " + startTileLabel);
        } else {
            log(getClass().getSimpleName(), "Starting tile unknown (position unavailable).");
        }

        startTime = System.currentTimeMillis();
        initialised = true;
        log(
            getClass().getSimpleName(),
            "Configured mode=" + mode
                + ", action=" + npcAction
                + ", itemId=" + targetItemId
                + ", targetAmount=" + targetAmount
                + ", hopWhen=" + hopWhen
                + ", comparator=" + hopStockComparator
                + ", threshold=" + hopStockThreshold
                + ", hoppingEnabled=" + hoppingEnabled
                + ", openPacks=" + openPacksEnabled
                + ", region=" + prioritisedRegionId
        );
    }

    private String formatTime(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    @Override
    public int[] regionsToPrioritise() {
        int region = prioritisedRegionId > 0 ? prioritisedRegionId : submittedRegionId;
        if (region <= 0) {
            return new int[0];
        }
        return new int[]{region};
    }

    public enum Mode {
        BUY,
        SELL;

        public static Mode fromString(String value) {
            if (value == null) {
                return BUY;
            }
            if ("sell".equalsIgnoreCase(value.trim())) {
                return SELL;
            }
            return BUY;
        }
    }

    public enum HopWhen {
        OUT_OF_STOCK,
        STOCK_IS;

        public static HopWhen fromSelection(String value) {
            if (value == null) {
                return OUT_OF_STOCK;
            }
            if ("stock is".equalsIgnoreCase(value.trim())) {
                return STOCK_IS;
            }
            return OUT_OF_STOCK;
        }

        public boolean shouldHop(int stock, ComparatorType comparator, int threshold) {
            return switch (this) {
                case OUT_OF_STOCK -> stock <= 0;
                case STOCK_IS -> comparator != null && comparator.matches(stock, threshold);
            };
        }

        public String describe(ComparatorType comparator, int threshold) {
            if (this == STOCK_IS && comparator != null) {
                return "stock " + comparator.getSymbol() + " " + threshold;
            }
            return "out of stock";
        }
    }

    public enum ComparatorType {
        GREATER(">") {
            @Override
            boolean matches(int stock, int threshold) {
                return stock > threshold;
            }
        },
        LESS("<") {
            @Override
            boolean matches(int stock, int threshold) {
                return stock < threshold;
            }
        },
        GREATER_OR_EQUAL(">=") {
            @Override
            boolean matches(int stock, int threshold) {
                return stock >= threshold;
            }
        },
        LESS_OR_EQUAL("<=") {
            @Override
            boolean matches(int stock, int threshold) {
                return stock <= threshold;
            }
        };

        private final String symbol;

        ComparatorType(String symbol) {
            this.symbol = symbol;
        }

        abstract boolean matches(int stock, int threshold);

        static ComparatorType fromString(String value) {
            if (value == null) {
                return LESS_OR_EQUAL;
            }
            String trimmed = value.trim();
            for (ComparatorType type : values()) {
                if (type.symbol.equalsIgnoreCase(trimmed)) {
                    return type;
                }
            }
            return LESS_OR_EQUAL;
        }

        String getSymbol() {
            return symbol;
        }
    }

}
