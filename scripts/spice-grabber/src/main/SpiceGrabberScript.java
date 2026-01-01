package main;

import com.osmb.api.script.Script;
import com.osmb.api.script.ScriptDefinition;
import com.osmb.api.script.SkillCategory;
import data.State;
import tasks.ChaseCatTask;
import tasks.ChaseResultTask;
import tasks.EnsureInventoryTabTask;
import tasks.DecantSpiceTask;
import tasks.LootSpice;
import tasks.FindCatTask;
import utils.Task;

import java.util.ArrayList;
import java.util.List;

@ScriptDefinition(
    name = "Spice Grabber",
    description = "Collects spices in Evil Dave's basement",
    skillCategory = SkillCategory.OTHER,
    version = 1.0,
    author = "eqp48"
)
public class SpiceGrabberScript extends Script {
    private List<Task> tasks;
    private DecantSpiceTask decantTask;
    private boolean zoomConfigured = false;
    private long lastZoomAttemptMs = 0;
    private static final long ZOOM_RETRY_MS = 2000;

    public SpiceGrabberScript(Object scriptCore) {
        super(scriptCore);
    }

    @Override
    public int[] regionsToPrioritise() {
        return new int[]{12442};
    }

    @Override
    public void onStart() {
        tasks = new ArrayList<>();
        decantTask = new DecantSpiceTask(this);
        tasks.add(new EnsureInventoryTabTask(this));
        tasks.add(new LootSpice(this));
        tasks.add(new FindCatTask(this));
        tasks.add(new ChaseCatTask(this));
        tasks.add(new ChaseResultTask(this));
    }

    @Override
    public int poll() {
        if (tasks == null) {
            return 0;
        }
        ensureZoomConfigured();

        var inventory = getWidgetManager().getInventory();
        if (inventory != null) {
            var invResult = inventory.search(java.util.Set.of());
            if (invResult != null && invResult.isFull()) {
                if (!State.decantCheckedFull) {
                    State.decanting = true;
                    State.decantCheckedFull = true;
                }
            } else {
                State.decantCheckedFull = false;
                State.decanting = false;
            }
        }

        if (State.decanting && decantTask != null) {
            decantTask.execute();
            return 0;
        }

        for (Task task : tasks) {
            if (!task.activate()) {
                continue;
            }
            if (task.execute()) {
                return 0;
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
        var settings = getWidgetManager().getSettings();
        if (settings == null) {
            return;
        }
        var zoomResult = settings.getZoomLevel();
        boolean alreadyMaxZoom = zoomResult.isFound() && zoomResult.get() == 0;
        if (!alreadyMaxZoom) {
            if (settings.setZoomLevel(0)) {
                zoomConfigured = true;
            }
            return;
        }
        zoomConfigured = true;
    }

}
