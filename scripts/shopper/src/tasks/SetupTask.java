package tasks;

import data.State;
import main.ShopperScript;
import utils.Task;

public class SetupTask extends Task {
    private final ShopperScript shopper;

    public SetupTask(ShopperScript script) {
        super(script);
        this.shopper = script;
    }

    @Override
    public boolean activate() {
        if (!shopper.isSettingsConfirmed()) {
            return false;
        }
        if (!shopper.isInitialised()) {
            return true;
        }
        return !shopper.isZoomConfigured();
    }

    @Override
    public boolean execute() {
        shopper.setState(State.SETUP);
        if (!shopper.isInitialised()) {
            shopper.initialiseConfig();
            return true;
        }
        ensureZoomConfigured();
        return true;
    }

    private void ensureZoomConfigured() {
        if (shopper.isZoomConfigured()) {
            return;
        }
        if (shopper.getWidgetManager().getGameState() != com.osmb.api.ui.GameState.LOGGED_IN) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - shopper.getLastZoomAttemptMs() < shopper.getZoomRetryMs()) {
            return;
        }
        shopper.setLastZoomAttemptMs(now);
        boolean set = shopper.getWidgetManager().getSettings().setZoomLevel(0);
        if (set) {
            shopper.setZoomConfigured(true);
            shopper.setState(State.OPENING_SHOP);
        }
    }
}
