package tasks;

import utils.Task;
import main.SandstoneMinerScript;

public class HumidifyTask extends Task {
  public HumidifyTask(SandstoneMinerScript script) {
    super(script);
  }

  @Override
  public boolean activate() {
    return script.shouldCastHumidify();
  }

  @Override
  public int execute() {
    if (script.castHumidify()) {
      script.markHumidifyCast();
      return script.random(200, 350);
    }
    return -1;
  }
}
