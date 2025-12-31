package data;

import com.osmb.api.shape.Rectangle;

import java.util.ArrayList;
import java.util.List;

public final class State {
    private State() {
    }

    public static volatile boolean highlightFound = false;
    public static volatile Rectangle highlightBounds = null;
    public static volatile Rectangle tapBounds = null;
    public static volatile Double tileDistance = null;
    public static volatile boolean isNextToUs = false;

    public static volatile long lastChaseMs = 0L;
    public static volatile boolean pendingChaseResult = false;
    public static volatile boolean chaseExecutedThisPoll = false;
    public static volatile boolean pendingLoot = false;
    public static volatile boolean decanting = false;
    public static volatile boolean decantCheckedFull = false;
    public static volatile boolean setupComplete = false;

    public static List<String> previousChatLines = new ArrayList<>();
}
