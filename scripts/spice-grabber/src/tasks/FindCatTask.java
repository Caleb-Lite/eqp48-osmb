package tasks;

import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.shape.Polygon;
import com.osmb.api.shape.Rectangle;
import com.osmb.api.visual.SearchablePixel;
import com.osmb.api.visual.color.ColorModel;
import com.osmb.api.visual.color.tolerance.impl.SingleThresholdComparator;
import data.State;
import utils.Task;

public class FindCatTask extends Task {
    private static final double NPC_BOX_SCALE = 0.7;
    private static final int TILE_HEIGHT = 200;

    private static final SearchablePixel[] CAT_CLUSTER = new SearchablePixel[] {
        new SearchablePixel(-14155777, new SingleThresholdComparator(10), ColorModel.RGB),
    };

    public FindCatTask(com.osmb.api.script.Script script) {
        super(script);
    }

    @Override
    public boolean activate() {
        return true;
    }

    @Override
    public boolean execute() {
        if (!isPlayerIdle()) {
            State.highlightFound = false;
            State.highlightBounds = null;
            State.tapBounds = null;
            State.tileDistance = null;
            State.isNextToUs = false;
            return false;
        }
        Rectangle highlightBounds = script.getPixelAnalyzer().getHighlightBounds(null, CAT_CLUSTER);
        if (highlightBounds == null) {
            State.highlightFound = false;
            State.highlightBounds = null;
            State.tapBounds = null;
            State.tileDistance = null;
            State.isNextToUs = false;
            return false;
        }

        Rectangle tapBounds = highlightBounds.getResized(NPC_BOX_SCALE);
        if (tapBounds == null) {
            tapBounds = highlightBounds;
        }

        double tileDistance = computeHighlightTileDistance(highlightBounds, 12);
        State.highlightFound = true;
        State.highlightBounds = highlightBounds;
        State.tapBounds = tapBounds;
        State.tileDistance = tileDistance < 0 ? null : tileDistance;
        State.isNextToUs = tileDistance <= 1.0;
        return false;
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

    private double computeHighlightTileDistance(Rectangle highlightBounds, int maxRadius) {
        WorldPosition playerPos = script.getWorldPosition();
        if (playerPos == null || highlightBounds == null) {
            return -1;
        }

        double bestDistance = Double.MAX_VALUE;
        for (int dx = -maxRadius; dx <= maxRadius; dx++) {
            for (int dy = -maxRadius; dy <= maxRadius; dy++) {
                WorldPosition tilePos = new WorldPosition(playerPos.getX() + dx, playerPos.getY() + dy, playerPos.getPlane());
                Polygon tileCube = script.getSceneProjector().getTileCube(tilePos, TILE_HEIGHT);
                if (tileCube == null) {
                    continue;
                }
                Rectangle tileBounds = tileCube.getBounds();
                if (tileBounds != null && tileBounds.intersects(highlightBounds)) {
                    double distance = playerPos.distanceTo(tilePos);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                    }
                }
            }
        }

        return bestDistance == Double.MAX_VALUE ? -1 : bestDistance;
    }
}
