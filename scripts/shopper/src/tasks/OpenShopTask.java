package tasks;

import com.osmb.api.location.position.types.WorldPosition;
import com.osmb.api.shape.Polygon;
import com.osmb.api.shape.Rectangle;
import com.osmb.api.utils.UIResultList;
import com.osmb.api.visual.SearchablePixel;
import com.osmb.api.visual.color.ColorModel;
import com.osmb.api.visual.color.tolerance.impl.SingleThresholdComparator;
import data.State;
import main.ShopperScript;
import utils.Task;

public class OpenShopTask extends Task {
    private final ShopperScript shopper;

    public OpenShopTask(ShopperScript script) {
        super(script);
        this.shopper = script;
    }

    @Override
    public boolean activate() {
        return shopper.isInitialised()
            && !shopper.isShopVisible()
            && !shopper.isHopRequested()
            && !shopper.isWaitingForPacksToClear()
            && shopper.getState() != State.STOPPED;
    }

    @Override
    public boolean execute() {
        shopper.setState(State.OPENING_SHOP);
        if (shopper.getWorldPosition() == null) {
            shopper.log(getClass().getSimpleName(), "Player position unavailable; waiting.");
            return false;
        }
        if (shopper.getShopInterface() == null) {
            shopper.log(getClass().getSimpleName(), "Shop interface unavailable; waiting.");
            return false;
        }

        Rectangle cyanBounds = findCyanNpcBounds();
        if (cyanBounds == null) {
            if (!shopper.isNpcInteractionStubLogged()) {
                shopper.log(getClass().getSimpleName(), "No cyan-highlighted NPC found to trade with.");
                shopper.setNpcInteractionStubLogged(true);
            }
            return false;
        }

        shopper.setNpcInteractionStubLogged(false);
        String action = shopper.getNpcAction();
        Rectangle tapBounds = cyanBounds.getResized(0.4) == null ? cyanBounds : cyanBounds.getResized(0.4);

        boolean tapped = shopper.submitHumanTask(
            () -> shopper.getFinger().tap(tapBounds, action),
            shopper.gaussianDelay(900, 2400, 1500, 300)
        );
        if (!tapped) {
            shopper.log(getClass().getSimpleName(), "Failed to tap cyan-highlighted NPC.");
            return false;
        }

        boolean shopOpened = shopper.pollFramesHuman(() -> shopper.isShopVisible(), shopper.gaussianDelay(3500, 8500, 5500, 900));
        if (shopOpened) {
            shopper.log(getClass().getSimpleName(), "Opened shop via cyan-highlighted NPC using action " + action + ".");
        } else {
            shopper.log(getClass().getSimpleName(), "Tapped cyan-highlighted NPC but shop not visible.");
        }
        return true;
    }

    private Rectangle findCyanNpcBounds() {
        UIResultList<WorldPosition> npcPositions = shopper.getWidgetManager().getMinimap().getNPCPositions();
        if (npcPositions == null || npcPositions.isNotVisible()) {
            return null;
        }

        SearchablePixel cyanPixel = new SearchablePixel(-14155777, new SingleThresholdComparator(10), ColorModel.RGB);
        WorldPosition playerPos = shopper.getWorldPosition();
        Rectangle closestBounds = null;
        double closestDistance = Double.MAX_VALUE;

        for (WorldPosition npcPosition : npcPositions) {
            if (npcPosition == null) {
                continue;
            }

            Polygon tileCube = shopper.getSceneProjector().getTileCube(npcPosition, 200);
            if (tileCube == null) {
                continue;
            }

            Polygon resized = tileCube.getResized(1.2);
            if (resized == null) {
                continue;
            }

            Rectangle highlightBounds = shopper.getPixelAnalyzer().getHighlightBounds(resized, cyanPixel);
            if (highlightBounds != null) {
                if (playerPos == null) {
                    return highlightBounds;
                }
                double distance = playerPos.distanceTo(npcPosition);
                if (distance < closestDistance) {
                    closestDistance = distance;
                    closestBounds = highlightBounds;
                }
            }
        }

        return closestBounds;
    }
}
