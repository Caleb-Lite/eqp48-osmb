package tasks;

import utils.Task;
import main.SandstoneMinerScript;

public class BankTask extends Task {
  public BankTask(SandstoneMinerScript script) {
    super(script);
  }

  @Override
  public boolean activate() {
    return script.isInventoryFull();
  }

  @Override
  public int execute() {
    return script.handleFullInventory();
  }
}
