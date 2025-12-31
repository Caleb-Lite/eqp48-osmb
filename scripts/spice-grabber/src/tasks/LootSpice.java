package tasks;

import com.osmb.api.input.MenuEntry;
import com.osmb.api.input.MenuHook;
import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.scene.RSTile;
import com.osmb.api.shape.Polygon;
import com.osmb.api.ui.component.chatbox.ChatboxComponent;
import com.osmb.api.utils.UIResultList;
import data.State;
import utils.Task;

import java.util.ArrayList;
import java.util.List;

public class LootSpice extends Task {
    private static final int[] SPICE_IDS = {
        7480, 7481, 7482, 7483,
        7484, 7485, 7486, 7487,
        7488, 7489, 7490, 7491,
        7492, 7493, 7494, 7495
    };
    private static final List<String> SPICE_NAMES = new ArrayList<>();

    public LootSpice(com.osmb.api.script.Script script) {
        super(script);
    }

    @Override
    public boolean activate() {
        return State.pendingLoot;
    }

    @Override
    public boolean execute() {
        if (!isPlayerIdle()) {
            return false;
        }
        var minimap = script.getWidgetManager().getMinimap();
        if (minimap == null) {
            return false;
        }

        UIResultList<WorldPosition> itemPositions = minimap.getItemPositions();
        if (itemPositions == null || itemPositions.isNotFound() || itemPositions.isNotVisible() || itemPositions.isEmpty()) {
            return false;
        }

        seedSpiceNames();
        if (SPICE_NAMES.isEmpty()) {
            return false;
        }

        for (WorldPosition itemPos : itemPositions) {
            if (itemPos == null) {
                continue;
            }

            RSTile tile = script.getSceneManager().getTile(itemPos);
            if (tile == null) {
                continue;
            }

            if (!tile.isOnGameScreen(ChatboxComponent.class)) {
                continue;
            }

            if (!tile.canReach()) {
                continue;
            }

            Polygon tilePoly = tile.getTileCube(120);
            if (tilePoly == null) {
                continue;
            }

            Polygon tapPoly = tilePoly.getResized(0.4);
            if (tapPoly == null) {
                tapPoly = tilePoly;
            }

            MenuHook hook = menuEntries -> {
                for (MenuEntry entry : menuEntries) {
                    if (entry == null) {
                        continue;
                    }
                    String rawText = entry.getRawText();
                    if (rawText == null || !rawText.startsWith("take ")) {
                        continue;
                    }
                    for (String name : SPICE_NAMES) {
                        if (rawText.contains(name)) {
                            return entry;
                        }
                    }
                }
                return null;
            };

            if (script.getFinger().tapGameScreen(tapPoly, hook)) {
                State.pendingLoot = false;
                return true;
            }
        }

        return false;
    }

    private void seedSpiceNames() {
        if (!SPICE_NAMES.isEmpty()) {
            return;
        }

        var itemManager = script.getItemManager();
        if (itemManager == null) {
            return;
        }

        for (int id : SPICE_IDS) {
            String name = itemManager.getItemName(id);
            if (name != null && !name.isBlank()) {
                SPICE_NAMES.add(name.toLowerCase());
            }
        }
    }

    private boolean isPlayerIdle() {
        WorldPosition playerPos = script.getWorldPosition();
        if (playerPos == null) {
            return false;
        }
        Polygon tileCube = script.getSceneProjector().getTileCube(playerPos, 120);
        if (tileCube == null) {
            return false;
        }
        Polygon resized = tileCube.getResized(0.7);
        if (resized == null) {
            return false;
        }
        return !script.getPixelAnalyzer().isAnimating(0.2, resized);
    }
}
