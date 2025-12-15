import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ItemID;
import com.osmb.api.item.ItemSearchResult;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import com.osmb.api.shape.Polygon;
import com.osmb.api.shape.Rectangle;
import com.osmb.api.utils.UIResultList;
import com.osmb.api.utils.RandomUtils;
import com.osmb.api.visual.SearchablePixel;
import com.osmb.api.visual.color.ColorModel;
import com.osmb.api.visual.color.tolerance.impl.SingleThresholdComparator;
import com.osmb.api.visual.drawing.Canvas;
import javafx.scene.Scene;
import utils.Options;
import utils.ShopInterface;
import utils.State;

import java.awt.Color;
import java.awt.Font;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Set;

@ScriptDefinition(
        author = "eqp48",
        name = "Shopper",
        description = "All-in-one shopper",
        version = 1.0,
        skillCategory = SkillCategory.OTHER
)
public class ShopperScript extends Script {
    private Options options;
    private ShopInterface shopInterface;

    private String npcAction = "Trade";
    private int targetItemId;
    private int targetAmount;
    private Mode mode = Mode.BUY;
    // private String shopTitle;

    private int bought;
    private long startTime;
    private String startTileLabel = "";
    private State state = State.OPENING_SHOP;
    private boolean initialised = false;
    private boolean npcInteractionStubLogged = false;
    private final java.util.Map<String, Integer> itemNameCache = new java.util.HashMap<>();
    private boolean itemNameCacheBuilt = false;

    private volatile boolean settingsConfirmed = false;
    private volatile String submittedNpcAction;
    private volatile String submittedItemInput;
    private volatile int submittedTargetAmount;
    private volatile Mode submittedMode = Mode.BUY;
    // private volatile String submittedShopTitle;
    private static final String VERSION = "1.0";
    private static final Font TEXT_BOLD = new Font("Arial", Font.BOLD, 12);
    private static final Font TEXT_REGULAR = new Font("Arial", Font.PLAIN, 12);

    public ShopperScript(Object scriptCore) {
        super(scriptCore);
    }

    @Override
    public void onStart() {
        options = new Options();
        options.setOnStart(this::handleStartClicked);
        Scene scene = new Scene(options);
        getStageController().show(scene, "Settings", false);
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

        if (!initialised) {
            initialiseConfig();
            return 200;
        }

        if (targetItemId <= 0) {
            log(getClass().getSimpleName(), "Missing item id; stopping.");
            state = State.STOPPED;
            return 0;
        }

        if (targetAmount > 0 && bought >= targetAmount) {
            log(getClass().getSimpleName(), "Reached target amount; stopping.");
            state = State.STOPPED;
            return 0;
        }

        ItemGroupResult inventorySnapshot = getWidgetManager().getInventory().search(Set.of(ItemID.COINS_995, targetItemId));
        if (inventorySnapshot == null) {
            return 0;
        }

        if (mode == Mode.BUY) {
            if (inventorySnapshot.getAmount(ItemID.COINS_995) <= 0) {
                log(getClass().getSimpleName(), "Out of coins; stopping script.");
                state = State.STOPPED;
                return 0;
            }

            if (inventorySnapshot.isFull()) {
                log(getClass().getSimpleName(), "Inventory full; stopping script as banking is disabled.");
                state = State.STOPPED;
                return 0;
            }
        } else {
            if (inventorySnapshot.getAmount(targetItemId) <= 0) {
                log(getClass().getSimpleName(), "No items to sell; stopping script.");
                state = State.STOPPED;
                return 0;
            }
        }

        if (shopInterface.isVisible()) {
            if (mode == Mode.BUY) {
                handleShop(inventorySnapshot);
            } else {
                handleSell(inventorySnapshot);
            }
            return 0;
        }

        state = State.OPENING_SHOP;
        openShop();
        return 0;
    }

    private void handleShop(ItemGroupResult inventorySnapshot) {
        state = State.BUYING;
        ItemGroupResult shopSnapshot = shopInterface.search(Set.of(targetItemId));
        if (shopSnapshot == null) {
            return;
        }

        ItemSearchResult itemInShop = getItemManager().scanItemGroup(shopInterface, Set.of(targetItemId)).getItem(targetItemId);
        if (itemInShop == null) {
            log(getClass().getSimpleName(), "Could not find target item in shop; closing.");
            shopInterface.close();
            state = State.STOPPED;
            return;
        }

        if (itemInShop.getStackAmount() <= 0) {
            log(getClass().getSimpleName(), "Item out of stock; closing shop.");
            shopInterface.close();
            state = State.STOPPED;
            return;
        }

        int before = getWidgetManager().getInventory().search(Set.of(targetItemId)).getAmount(targetItemId);
        int freeSlots = inventorySnapshot.getFreeSlots();

        int actionQuantity = resolveBuyQuantity(itemInShop.getStackAmount());

        if (!selectBuyQuantity(actionQuantity)) {
            log(getClass().getSimpleName(), "Failed to set buy quantity to " + actionQuantity);
            return;
        }

        if (!itemInShop.interact()) {
            log(getClass().getSimpleName(), "Interaction with shop item failed.");
            return;
        }

        boolean purchased = submitTask(() -> {
            ItemGroupResult afterSnapshot = getWidgetManager().getInventory().search(Set.of(targetItemId));
            if (afterSnapshot == null) {
                return false;
            }
            if (afterSnapshot.isFull() && afterSnapshot.getFreeSlots() == 0 && freeSlots > 0) {
                return true;
            }
            return afterSnapshot.getAmount(targetItemId) > before;
        }, 5000);

        if (purchased) {
            ItemGroupResult after = getWidgetManager().getInventory().search(Set.of(targetItemId));
            int afterAmount = after == null ? before : after.getAmount(targetItemId);
            int boughtNow = Math.max(0, afterAmount - before);
            bought += boughtNow;
            log(getClass().getSimpleName(), "Bought " + boughtNow + " (" + bought + "/" + targetAmount + ")");

            if (after != null && after.isFull()) {
                log(getClass().getSimpleName(), "Inventory full after purchase; stopping.");
                state = State.STOPPED;
            }

            if (targetAmount > 0 && bought >= targetAmount) {
                log(getClass().getSimpleName(), "Reached goal; closing shop.");
                shopInterface.close();
                state = State.STOPPED;
            }
        }
    }

    private void handleSell(ItemGroupResult inventorySnapshot) {
        state = State.BUYING;
        int before = inventorySnapshot.getAmount(targetItemId);
        if (before <= 0) {
            log(getClass().getSimpleName(), "No stock to sell.");
            state = State.STOPPED;
            return;
        }

        ItemSearchResult itemToSell = getItemManager()
            .scanItemGroup(getWidgetManager().getInventory(), Set.of(targetItemId))
            .getItem(targetItemId);
        if (itemToSell == null) {
            log(getClass().getSimpleName(), "Could not find item in inventory to sell; closing.");
            shopInterface.close();
            state = State.STOPPED;
            return;
        }

        int actionQuantity = resolveSellQuantity(before);

        if (!selectSellQuantity(actionQuantity)) {
            log(getClass().getSimpleName(), "Failed to set sell quantity to " + actionQuantity);
            return;
        }

        if (!itemToSell.interact()) {
            log(getClass().getSimpleName(), "Interaction with inventory item failed.");
            return;
        }

        boolean sold = submitTask(() -> {
            ItemGroupResult afterSnapshot = getWidgetManager().getInventory().search(Set.of(targetItemId));
            if (afterSnapshot == null) {
                return false;
            }
            return afterSnapshot.getAmount(targetItemId) < before;
        }, 5000);

        if (sold) {
            ItemGroupResult after = getWidgetManager().getInventory().search(Set.of(targetItemId));
            int afterAmount = after == null ? before : after.getAmount(targetItemId);
            int soldNow = Math.max(0, before - afterAmount);
            bought += soldNow;
            log(getClass().getSimpleName(), "Sold " + soldNow + " (" + bought + "/" + targetAmount + ")");

            if (targetAmount > 0 && bought >= targetAmount) {
                log(getClass().getSimpleName(), "Reached sell goal; closing shop.");
                shopInterface.close();
                state = State.STOPPED;
            }
        }
    }

    private void openShop() {
        if (getWorldPosition() == null) {
            log(getClass().getSimpleName(), "Player position unavailable; waiting.");
            return;
        }

        if (shopInterface == null) {
            log(getClass().getSimpleName(), "Shop interface unavailable; waiting.");
            return;
        }

        Rectangle cyanBounds = findCyanNpcBounds();
        if (cyanBounds == null) {
            if (!npcInteractionStubLogged) {
                log(getClass().getSimpleName(), "No cyan-highlighted NPC found to trade with.");
                npcInteractionStubLogged = true;
            }
            return;
        }

        String action = npcAction == null || npcAction.isBlank() ? "Trade" : npcAction;
        final Rectangle tapBounds = cyanBounds.getResized(0.4) == null ? cyanBounds : cyanBounds.getResized(0.4);

        boolean tapped = submitHumanTask(() -> getFinger().tap(tapBounds, action), gaussianDelay(900, 2400, 1500, 300));
        if (!tapped) {
            log(getClass().getSimpleName(), "Failed to tap cyan-highlighted NPC.");
            return;
        }

        boolean shopOpened = pollFramesHuman(() -> shopInterface.isVisible(), gaussianDelay(3500, 8500, 5500, 900));
        if (shopOpened) {
            log(getClass().getSimpleName(), "Opened shop via cyan-highlighted NPC using action " + action + ".");
        } else {
            log(getClass().getSimpleName(), "Tapped cyan-highlighted NPC but shop not visible.");
        }
    }

    private boolean selectBuyQuantity(int quantity) {
        return switch (quantity) {
            case 1 -> buyOne();
            case 5 -> buyFive();
            case 10 -> buyTen();
            case 50 -> buyFifty();
            default -> shopInterface != null && shopInterface.setSelectedAmount(quantity);
        };
    }

    private boolean buyOne() {
        return shopInterface != null && shopInterface.setSelectedAmount(1);
    }

    private boolean buyFive() {
        return shopInterface != null && shopInterface.setSelectedAmount(5);
    }

    private boolean buyTen() {
        return shopInterface != null && shopInterface.setSelectedAmount(10);
    }

    private boolean buyFifty() {
        return shopInterface != null && shopInterface.setSelectedAmount(50);
    }

    private boolean selectSellQuantity(int quantity) {
        return switch (quantity) {
            case 1 -> sellOne();
            case 5 -> sellFive();
            case 10 -> sellTen();
            case 50 -> sellFifty();
            default -> shopInterface != null && shopInterface.setSelectedAmount(quantity);
        };
    }

    private boolean sellOne() {
        return shopInterface != null && shopInterface.setSelectedAmount(1);
    }

    private boolean sellFive() {
        return shopInterface != null && shopInterface.setSelectedAmount(5);
    }

    private boolean sellTen() {
        return shopInterface != null && shopInterface.setSelectedAmount(10);
    }

    private boolean sellFifty() {
        return shopInterface != null && shopInterface.setSelectedAmount(50);
    }

    private int resolveBuyQuantity(int shopStock) {
        int remainingTarget = targetAmount > 0 ? Math.max(0, targetAmount - bought) : Integer.MAX_VALUE;
        int available = shopStock > 0 ? shopStock : Integer.MAX_VALUE;
        int needed = Math.min(remainingTarget == 0 ? 1 : remainingTarget, available);
        return chooseChunk(needed);
    }

    private int resolveSellQuantity(int inventoryAmount) {
        int remainingTarget = targetAmount > 0 ? Math.max(0, targetAmount - bought) : Integer.MAX_VALUE;
        int available = Math.max(inventoryAmount, 0);
        int needed = Math.min(remainingTarget == 0 ? 1 : remainingTarget, available == 0 ? 1 : available);
        return chooseChunk(needed);
    }

    private int chooseChunk(int needed) {
        int capped = Math.max(1, needed);
        int[] options = new int[]{50, 10, 5, 1};
        for (int opt : options) {
            if (opt <= capped) {
                return opt;
            }
        }
        return 1;
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
            java.nio.file.Path path = java.nio.file.Paths.get("shopper/src/utils/ItemID.java");
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
            for (java.lang.reflect.Field field : utils.ItemID.class.getFields()) {
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

    private int gaussianDelay(int min, int max, double mean, double stdev) {
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
        submittedItemInput = options.getItemInput();
        submittedTargetAmount = options.getTargetAmount();
        submittedMode = Mode.fromString(options.getMode());
        settingsConfirmed = true;
        options.closeWindow();
    }

    private void initialiseConfig() {
        npcAction = "Trade";
        targetItemId = resolveItemId(submittedItemInput);
        targetAmount = submittedTargetAmount;
        mode = submittedMode == null ? Mode.BUY : submittedMode;

        shopInterface = new ShopInterface(this, "");
        getWidgetManager().getInventory().registerInventoryComponent(shopInterface);

        WorldPosition startPos = getWorldPosition();
        if (startPos != null) {
            startTileLabel = startPos.getX() + "," + startPos.getY() + "," + startPos.getPlane();
            log(getClass().getSimpleName(), "Starting tile: " + startTileLabel);
        } else {
            log(getClass().getSimpleName(), "Starting tile unknown (position unavailable).");
        }

        startTime = System.currentTimeMillis();
        initialised = true;
        log(getClass().getSimpleName(), "Configured mode=" + mode + ", action=" + npcAction + ", itemId=" + targetItemId + ", targetAmount=" + targetAmount);
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

    private enum Mode {
        BUY,
        SELL;

        static Mode fromString(String value) {
            if (value == null) {
                return BUY;
            }
            if ("sell".equalsIgnoreCase(value.trim())) {
                return SELL;
            }
            return BUY;
        }
    }

    private Rectangle findCyanNpcBounds() {
        UIResultList<WorldPosition> npcPositions = getWidgetManager().getMinimap().getNPCPositions();
        if (npcPositions == null || npcPositions.isNotVisible()) {
            return null;
        }

        SearchablePixel cyanPixel = new SearchablePixel(-14155777, new SingleThresholdComparator(10), ColorModel.RGB);

        for (WorldPosition npcPosition : npcPositions) {
            if (npcPosition == null) {
                continue;
            }

            Polygon tileCube = getSceneProjector().getTileCube(npcPosition, 200);
            if (tileCube == null) {
                continue;
            }

            Polygon resized = tileCube.getResized(1.2);
            if (resized == null) {
                continue;
            }

            Rectangle highlightBounds = getPixelAnalyzer().getHighlightBounds(resized, cyanPixel);
            if (highlightBounds != null) {
                return highlightBounds;
            }
        }

        return null;
    }

}
