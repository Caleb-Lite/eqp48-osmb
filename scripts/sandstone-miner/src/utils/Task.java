package utils;

import main.SandstoneMinerScript;

public abstract class Task {
  protected SandstoneMinerScript script;

  public Task(SandstoneMinerScript script) {
    this.script = script;
  }

  public abstract boolean activate();
  public abstract int execute();
}
