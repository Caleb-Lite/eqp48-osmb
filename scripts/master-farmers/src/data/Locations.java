package data;

import com.osmb.api.location.area.Area;
import com.osmb.api.location.area.impl.RectangleArea;
import com.osmb.api.location.position.types.WorldPosition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class Locations {

    private Locations() {
    }

    private static final int AREA_PADDING = 6;

    public static final class FarmerLocation {
        private final String name;
        private final List<WorldPosition> farmerTiles;
        private final Area thievingArea;
        private final Area bankArea;
        private final WorldPosition anchor;

        public FarmerLocation(String name, List<WorldPosition> farmerTiles, Area thievingArea, Area bankArea, WorldPosition anchor) {
            this.name = name;
            this.farmerTiles = farmerTiles;
            this.thievingArea = thievingArea;
            this.bankArea = bankArea;
            this.anchor = anchor;
        }

        public String name() {
            return name;
        }

        public List<WorldPosition> farmerTiles() {
            return farmerTiles;
        }

        public Area thievingArea() {
            return thievingArea;
        }

        public Area bankArea() {
            return bankArea;
        }

        public WorldPosition anchor() {
            return anchor;
        }
    }

    public static List<FarmerLocation> allLocations() {
        List<FarmerLocation> locations = new ArrayList<>();

        locations.add(fromCoords("Kastori", new int[][]{
                {1351, 3058}, {1371, 3064}, {1367, 3048}, {1366, 3022}
        }, new int[]{1241, 3121, 0}));

        locations.add(fromCoords("Queztacalli Gorge", new int[][]{
                {1495, 3234}
        }, new int[]{1517, 3228, 0}));

        locations.add(fromCoords("Ortus Farm", new int[][]{
                {1582, 3131}
        }, new int[]{1647, 3114, 0}));

        locations.add(fromCoords("Outer Fortis", new int[][]{
                {1584, 3090}, {1635, 3051}, {1682, 3052}, {1736, 3064}, {1750, 3066}
        }, new int[]{1647, 3114, 0}));

        locations.add(fromCoords("Draynor Village", new int[][]{
                {3080, 3250}
        }, new int[]{3092, 3245, 0}));

        locations.add(fromCoords("East Ardougne", new int[][]{
                {2636, 3364}
        }, new int[]{2652, 3284, 0}));

        locations.add(fromCoords("Hosidius", new int[][]{
                {1761, 3633}, {1787, 3592}
        }, new int[]{2621, 3284, 0}));

        locations.add(fromCoords("Farming Guild", new int[][]{
                {1236, 3727}, {1264, 3725}
        }, new int[]{1712, 3611, 0}));

        return Collections.unmodifiableList(locations);
    }

    private static FarmerLocation fromCoords(String name, int[][] coords, int[] bankCoord) {
        List<WorldPosition> tiles = new ArrayList<>();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (int[] c : coords) {
            WorldPosition pos = new WorldPosition(c[0], c[1], 0);
            tiles.add(pos);
            minX = Math.min(minX, c[0]);
            minY = Math.min(minY, c[1]);
            maxX = Math.max(maxX, c[0]);
            maxY = Math.max(maxY, c[1]);
        }

        int width = (maxX - minX) + 1 + (AREA_PADDING * 2);
        int height = (maxY - minY) + 1 + (AREA_PADDING * 2);
        Area paddedArea = new RectangleArea(minX - AREA_PADDING, minY - AREA_PADDING, width, height, 0);

        Area bankArea;
        if (bankCoord != null && bankCoord.length >= 3) {
            int bx = bankCoord[0];
            int by = bankCoord[1];
            int bp = bankCoord[2];
            int bankWidth = 1 + (AREA_PADDING * 2);
            int bankHeight = 1 + (AREA_PADDING * 2);
            bankArea = new RectangleArea(bx - AREA_PADDING, by - AREA_PADDING, bankWidth, bankHeight, bp);
        } else {
            bankArea = paddedArea;
        }

        WorldPosition anchor = tiles.get(0);
        return new FarmerLocation(name, tiles, paddedArea, bankArea, anchor);
    }

    public static Optional<FarmerLocation> nearestTo(WorldPosition pos, int maxDistance) {
        if (pos == null) return Optional.empty();
        return allLocations().stream()
                .filter(loc -> loc.thievingArea.contains(pos) || loc.farmerTiles.stream().anyMatch(tile -> tile.distanceTo(pos) <= maxDistance))
                .findFirst();
    }
}
