package tasks;

import main.MasterFarmersScript;
import com.osmb.api.script.Script;
import utils.Task;

public class StunHandlerTask extends Task {

    public StunHandlerTask(Script script) {
        super(script);
    }

    @Override
    public boolean activate() {
        return MasterFarmersScript.setupComplete && MasterFarmersScript.stunned;
    }

    @Override
    public boolean execute() {
        MasterFarmersScript.state = MasterFarmersScript.State.STUNNED;
        int stunDurationMs = script.random(4950, 5450);

        long start = System.currentTimeMillis();
        script.pollFramesHuman(() -> System.currentTimeMillis() - start >= stunDurationMs, stunDurationMs);

        MasterFarmersScript.stunned = false;
        MasterFarmersScript.state = MasterFarmersScript.State.PICKPOCKET;
        return false;
    }
}
