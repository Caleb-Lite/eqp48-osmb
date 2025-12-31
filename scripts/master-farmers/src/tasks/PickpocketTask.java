package tasks;

import main.MasterFarmersScript;
import com.osmb.api.location.position.types.LocalPosition;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.script.Script;
import com.osmb.api.shape.Polygon;
import com.osmb.api.shape.Rectangle;
import com.osmb.api.ui.chatbox.Chatbox;
import com.osmb.api.ui.chatbox.ChatboxFilterTab;
import com.osmb.api.utils.UIResultList;
import com.osmb.api.walker.WalkConfig;
import com.osmb.api.visual.SearchablePixel;
import com.osmb.api.visual.color.ColorModel;
import com.osmb.api.visual.color.tolerance.impl.SingleThresholdComparator;
import utils.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class PickpocketTask extends Task {

    private static final int MAX_RADIUS = 10;
    private static final SearchablePixel MASTER_FARMER_HIGHLIGHT = new SearchablePixel(
            -14221313,
            new SingleThresholdComparator(2),
            ColorModel.RGB
    );
    private List<String> previousChatLines = new ArrayList<>();

    public PickpocketTask(Script script) {
        super(script);
    }

    @Override
    public boolean activate() {
        if (!MasterFarmersScript.setupComplete) return false;
        if (MasterFarmersScript.needToBank || MasterFarmersScript.stunned) return false;
        var inv = script.getWidgetManager().getInventory().search(Set.of());
        if (inv == null) return false;
        if (inv.isFull()) {
            MasterFarmersScript.needToBank = true;
            MasterFarmersScript.state = MasterFarmersScript.State.BANK;
            return false;
        }
        return true;
    }

    @Override
    public boolean execute() {
        MasterFarmersScript.state = MasterFarmersScript.State.PICKPOCKET;

        var inv = script.getWidgetManager().getInventory().search(Set.of());
        if (inv == null) return false;

        if (inv.isFull()) {
            MasterFarmersScript.needToBank = true;
            MasterFarmersScript.state = MasterFarmersScript.State.BANK;
            return false;
        }

        if (!script.getWidgetManager().getInventory().unSelectItemIfSelected()) {
            return false;
        }

        if (script.getWidgetManager().getBank().isVisible()) {
            script.getWidgetManager().getBank().close();
            return false;
        }

        WorldPosition me = script.getWorldPosition();
        if (me == null) return false;
        if (!MasterFarmersScript.thievingArea.contains(me)) {
            WalkConfig cfg = new WalkConfig.Builder()
                    .enableRun(true)
                    .breakCondition(() -> MasterFarmersScript.thievingArea.contains(script.getWorldPosition()))
                    .build();
            return script.getWalker().walkTo(MasterFarmersScript.anchorTile, cfg);
        }

        Optional<WorldPosition> target = findTarget();
        if (target.isEmpty()) return false;

        WorldPosition targetPos = target.get();
        LocalPosition local = targetPos.toLocalPosition(script);
        if (local == null) return false;

        Polygon tile = script.getSceneProjector().getTileCube(local.getX(), local.getY(), local.getPlane(), 150);
        if (tile == null) return false;
        Rectangle highlight = getHighlightBounds(tile);
        if (highlight == null) return false;

        boolean tapped = script.getFinger().tap(highlight, "Pickpocket");
        if (!tapped) {
            evaluateChatbox();
            return false;
        }

        if (!ensureMenuEntry()) {
            evaluateChatbox();
            return false;
        }

        script.pollFramesHuman(() -> false, script.random(280, 1220));
        evaluateChatbox();

        return false;
    }

    private Optional<WorldPosition> findTarget() {
        var minimap = script.getWidgetManager().getMinimap();
        if (minimap == null) return Optional.empty();

        UIResultList<WorldPosition> npcPositions = minimap.getNPCPositions();
        if (npcPositions == null || npcPositions.isNotFound() || npcPositions.isNotVisible() || npcPositions.isEmpty()) {
            return Optional.empty();
        }

        WorldPosition me = script.getWorldPosition();
        if (me == null) return Optional.empty();

        for (WorldPosition pos : npcPositions) {
            if (pos == null) continue;
            if (pos.getPlane() != me.getPlane()) continue;
            if (!MasterFarmersScript.thievingArea.contains(pos)) continue;
            if (!withinRadius(pos, me, MAX_RADIUS)) continue;
            if (!isHighlighted(pos)) continue;
            if (getHighlightBoundsFromPos(pos) == null) continue;
            return Optional.of(pos);
        }

        return Optional.empty();
    }

    private boolean withinRadius(WorldPosition a, WorldPosition b, int radius) {
        if (a == null || b == null) return false;
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        return dx <= radius && dy <= radius;
    }

    private boolean ensureMenuEntry() {
        var menu = script.getWidgetManager().getMiniMenu();
        if (menu == null) return false;
        if (!menu.isVisible()) return false;

        var entries = menu.getMenuEntries();
        if (entries == null || entries.length == 0) return false;

        for (var entry : entries) {
            if (entry == null) continue;
            if ("pickpocket".equalsIgnoreCase(entry.getAction()) && entry.getEntityName() != null && entry.getEntityName().toLowerCase().contains("master farmer")) {
                return true;
            }
        }

        return false;
    }

    private boolean isHighlighted(WorldPosition pos) {
        LocalPosition local = pos.toLocalPosition(script);
        if (local == null) return false;

        Polygon tile = script.getSceneProjector().getTileCube(local.getX(), local.getY(), local.getPlane(), 150);
        if (tile == null) return false;

        var bounds = tile.getBounds();
        if (bounds == null) return false;

        var expanded = bounds.getResized(6);
        if (expanded != null) {
            bounds = expanded;
        }

        return script.getPixelAnalyzer().findPixel(bounds, MASTER_FARMER_HIGHLIGHT) != null;
    }

    private Rectangle getHighlightBounds(Polygon tile) {
        if (tile == null) return null;
        var bounds = tile.getBounds();
        if (bounds == null) return null;
        var expanded = bounds.getResized(6);
        if (expanded != null) {
            bounds = expanded;
        }
        return script.getPixelAnalyzer().getHighlightBounds(bounds, MASTER_FARMER_HIGHLIGHT);
    }

    private Rectangle getHighlightBoundsFromPos(WorldPosition pos) {
        LocalPosition local = pos.toLocalPosition(script);
        if (local == null) return null;

        Polygon tile = script.getSceneProjector().getTileCube(local.getX(), local.getY(), local.getPlane(), 150);
        if (tile == null) return null;

        return getHighlightBounds(tile);
    }

    private void evaluateChatbox() {
        Chatbox chatbox = script.getWidgetManager().getChatbox();
        if (chatbox == null) return;

        ChatboxFilterTab activeTab = chatbox.getActiveFilterTab();
        if (activeTab == null) return;

        if (activeTab != ChatboxFilterTab.GAME) {
            if (!chatbox.openFilterTab(ChatboxFilterTab.GAME)) {
                return;
            }
            return;
        }

        UIResultList<String> result = chatbox.getText();
        if (result == null || !result.isFound() || result.isEmpty()) return;

        List<String> lines = result.asList();
        if (lines.isEmpty()) return;

        List<String> newLines = getNewChatLines(lines);
        if (newLines.isEmpty()) return;

        boolean stunDetected = false;
        boolean actionDetected = false;

        for (String line : newLines) {
            String lower = line.toLowerCase();
            if (lower.contains("you've been stunned")) {
                stunDetected = true;
            } else if (lower.contains("you attempt to pick the master farmer's pocket")) {
                actionDetected = true;
            } else if (lower.contains("you fail to pick the master farmer's pocket")) {
                actionDetected = true;
            } else if (lower.contains("you pick the master farmer's pocket")) {
                actionDetected = true;
            }
        }

        if (stunDetected) {
            MasterFarmersScript.stunned = true;
            MasterFarmersScript.state = MasterFarmersScript.State.STUNNED;
        } else if (actionDetected) {
            MasterFarmersScript.stunned = false;
            MasterFarmersScript.state = MasterFarmersScript.State.PICKPOCKET;
        }
    }

    private List<String> getNewChatLines(List<String> currentLines) {
        if (currentLines == null || currentLines.isEmpty()) return List.of();

        if (previousChatLines.isEmpty()) {
            previousChatLines = new ArrayList<>(currentLines);
            return List.of();
        }

        if (currentLines.equals(previousChatLines)) return List.of();

        int firstDifference = currentLines.size();
        for (int i = 0; i < currentLines.size(); i++) {
            int suffixLen = currentLines.size() - i;
            if (suffixLen > previousChatLines.size()) continue;

            boolean match = true;
            for (int j = 0; j < suffixLen; j++) {
                if (!currentLines.get(i + j).equals(previousChatLines.get(j))) {
                    match = false;
                    break;
                }
            }

            if (match) {
                firstDifference = i;
                break;
            }
        }

        List<String> newLines = currentLines.subList(0, firstDifference);
        previousChatLines = new ArrayList<>(currentLines);
        return newLines;
    }
}
