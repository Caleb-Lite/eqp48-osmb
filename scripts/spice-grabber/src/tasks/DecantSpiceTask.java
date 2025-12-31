package tasks;

import com.osmb.api.item.ItemGroupResult;
import com.osmb.api.item.ItemSearchResult;
import data.State;
import utils.Task;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DecantSpiceTask extends Task {
    private enum SpiceColor {
        RED, ORANGE, BROWN, YELLOW
    }

    private static final class SpiceDef {
        private final int id;
        private final SpiceColor color;
        private final int dose;

        private SpiceDef(int id, SpiceColor color, int dose) {
            this.id = id;
            this.color = color;
            this.dose = dose;
        }
    }

    private static final SpiceDef[] SPICES = {
        new SpiceDef(7483, SpiceColor.RED, 1),
        new SpiceDef(7482, SpiceColor.RED, 2),
        new SpiceDef(7481, SpiceColor.RED, 3),
        new SpiceDef(7480, SpiceColor.RED, 4),

        new SpiceDef(7487, SpiceColor.ORANGE, 1),
        new SpiceDef(7486, SpiceColor.ORANGE, 2),
        new SpiceDef(7485, SpiceColor.ORANGE, 3),
        new SpiceDef(7484, SpiceColor.ORANGE, 4),

        new SpiceDef(7491, SpiceColor.BROWN, 1),
        new SpiceDef(7490, SpiceColor.BROWN, 2),
        new SpiceDef(7489, SpiceColor.BROWN, 3),
        new SpiceDef(7488, SpiceColor.BROWN, 4),

        new SpiceDef(7495, SpiceColor.YELLOW, 1),
        new SpiceDef(7494, SpiceColor.YELLOW, 2),
        new SpiceDef(7493, SpiceColor.YELLOW, 3),
        new SpiceDef(7492, SpiceColor.YELLOW, 4)
    };

    private static final int EMPTY_SPICE_SHAKER = 7496;
    private static final Set<Integer> SPICE_IDS = Set.of(
        7480, 7481, 7482, 7483,
        7484, 7485, 7486, 7487,
        7488, 7489, 7490, 7491,
        7492, 7493, 7494, 7495
    );
    private static final Set<Integer> SPICE_AND_EMPTY_IDS = Set.of(
        7480, 7481, 7482, 7483,
        7484, 7485, 7486, 7487,
        7488, 7489, 7490, 7491,
        7492, 7493, 7494, 7495,
        EMPTY_SPICE_SHAKER
    );

    public DecantSpiceTask(com.osmb.api.script.Script script) {
        super(script);
    }

    @Override
    public boolean activate() {
        return State.decanting;
    }

    @Override
    public boolean execute() {
        if (!isPlayerIdle()) {
            return true;
        }

        var inventory = script.getWidgetManager().getInventory();
        if (inventory == null) {
            return true;
        }

        if (!inventory.unSelectItemIfSelected()) {
            return true;
        }

        ItemGroupResult snapshot = inventory.search(SPICE_AND_EMPTY_IDS);
        if (snapshot == null) {
            return true;
        }

        Map<SpiceColor, Map<Integer, List<ItemSearchResult>>> byColorDose = new EnumMap<>(SpiceColor.class);
        Map<Integer, SpiceDef> defById = new HashMap<>();
        for (SpiceDef def : SPICES) {
            defById.put(def.id, def);
        }

        for (SpiceDef def : SPICES) {
            List<ItemSearchResult> items = snapshot.getAllOfItem(def.id);
            if (items == null || items.isEmpty()) {
                continue;
            }
            Map<Integer, List<ItemSearchResult>> doseMap = byColorDose.computeIfAbsent(def.color, key -> new HashMap<>());
            List<ItemSearchResult> doseList = doseMap.computeIfAbsent(def.dose, key -> new ArrayList<>());
            doseList.addAll(items);
        }

        ItemSearchResult[] pair = findBestPair(byColorDose);
        if (pair == null) {
            if (dropEmptyShakers(snapshot)) {
                return true;
            }
            if (snapshot.isFull()) {
                script.stop();
            }
            State.decanting = false;
            return true;
        }

        ItemSearchResult first = pair[0];
        ItemSearchResult second = pair[1];
        if (first == null || second == null) {
            State.decanting = false;
            return true;
        }

        if (!first.interact("Use")) {
            return true;
        }

        second.interact();
        return true;
    }

    private boolean dropEmptyShakers(ItemGroupResult snapshot) {
        if (snapshot == null) {
            return false;
        }
        List<ItemSearchResult> empties = snapshot.getAllOfItem(EMPTY_SPICE_SHAKER);
        if (empties == null || empties.isEmpty()) {
            return false;
        }
        for (ItemSearchResult empty : empties) {
            if (empty == null) {
                continue;
            }
            empty.interact("Drop");
        }
        return true;
    }

    private ItemSearchResult[] findBestPair(Map<SpiceColor, Map<Integer, List<ItemSearchResult>>> byColorDose) {
        for (SpiceColor color : SpiceColor.values()) {
            Map<Integer, List<ItemSearchResult>> doses = byColorDose.get(color);
            if (doses == null) {
                continue;
            }
            ItemSearchResult[] pair = tryPair(doses, 3, 1);
            if (pair != null) return pair;
            pair = tryPair(doses, 2, 2);
            if (pair != null) return pair;
            pair = tryPair(doses, 3, 2);
            if (pair != null) return pair;
            pair = tryPair(doses, 3, 3);
            if (pair != null) return pair;
            pair = tryPair(doses, 2, 1);
            if (pair != null) return pair;
            pair = tryPair(doses, 1, 1);
            if (pair != null) return pair;
        }
        return null;
    }

    private ItemSearchResult[] tryPair(Map<Integer, List<ItemSearchResult>> doses, int a, int b) {
        List<ItemSearchResult> listA = doses.get(a);
        List<ItemSearchResult> listB = doses.get(b);
        if (listA == null || listB == null) {
            return null;
        }
        if (a == b) {
            if (listA.size() < 2) {
                return null;
            }
            return new ItemSearchResult[]{listA.get(0), listA.get(1)};
        }
        if (listA.isEmpty() || listB.isEmpty()) {
            return null;
        }
        return new ItemSearchResult[]{listA.get(0), listB.get(0)};
    }

    private boolean isPlayerIdle() {
        var playerPos = script.getWorldPosition();
        if (playerPos == null) {
            return false;
        }
        var tileCube = script.getSceneProjector().getTileCube(playerPos, 120);
        if (tileCube == null) {
            return false;
        }
        var resized = tileCube.getResized(0.7);
        if (resized == null) {
            return false;
        }
        return !script.getPixelAnalyzer().isAnimating(0.2, resized);
    }
}
