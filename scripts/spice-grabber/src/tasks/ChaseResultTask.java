package tasks;

import com.osmb.api.ui.chatbox.Chatbox;
import com.osmb.api.ui.chatbox.ChatboxFilterTab;
import data.State;
import utils.Task;

import java.util.ArrayList;
import java.util.List;

public class ChaseResultTask extends Task {
    public ChaseResultTask(com.osmb.api.script.Script script) {
        super(script);
    }

    @Override
    public boolean activate() {
        return State.chaseExecutedThisPoll && isPlayerIdle();
    }

    @Override
    public boolean execute() {
        if (State.pendingChaseResult) {
            resolvePendingChase();
        }

        seedChatLines();
        State.pendingChaseResult = true;
        State.chaseExecutedThisPoll = false;
        return true;
    }

    private void resolvePendingChase() {
        Chatbox chatbox = script.getWidgetManager().getChatbox();
        if (chatbox == null) {
            return;
        }

        ChatboxFilterTab activeTab = chatbox.getActiveFilterTab();
        if (activeTab == null) {
            return;
        }

        if (activeTab != ChatboxFilterTab.GAME) {
            if (!chatbox.openFilterTab(ChatboxFilterTab.GAME)) {
                return;
            }
        }

        var result = chatbox.getText();
        if (result == null || !result.isFound() || result.isEmpty()) {
            return;
        }

        List<String> lines = result.asList();
        if (lines == null || lines.isEmpty()) {
            return;
        }

        List<String> newLines = getNewChatLines(lines);
        boolean failed = false;
        String latestMessage = null;
        for (String line : newLines) {
            if (line == null) {
                continue;
            }
            latestMessage = line;
            break;
        }

        if (latestMessage != null) {
            String lower = latestMessage.toLowerCase();
            failed = lower.contains("the rat manages to get away");
        }

        if (!failed) {
            State.pendingLoot = true;
        }
        State.pendingChaseResult = false;
    }

    private void seedChatLines() {
        Chatbox chatbox = script.getWidgetManager().getChatbox();
        if (chatbox == null) {
            return;
        }

        ChatboxFilterTab activeTab = chatbox.getActiveFilterTab();
        if (activeTab == null) {
            return;
        }

        if (activeTab != ChatboxFilterTab.GAME) {
            if (!chatbox.openFilterTab(ChatboxFilterTab.GAME)) {
                return;
            }
        }

        var result = chatbox.getText();
        if (result == null || !result.isFound() || result.isEmpty()) {
            return;
        }

        List<String> lines = result.asList();
        if (lines == null || lines.isEmpty()) {
            return;
        }

        State.previousChatLines = new ArrayList<>(lines);
    }

    private List<String> getNewChatLines(List<String> currentLines) {
        if (currentLines == null || currentLines.isEmpty()) {
            return List.of();
        }

        if (State.previousChatLines.isEmpty()) {
            State.previousChatLines = new ArrayList<>(currentLines);
            return List.of();
        }

        if (currentLines.equals(State.previousChatLines)) {
            return List.of();
        }

        int firstDifference = currentLines.size();
        for (int i = 0; i < currentLines.size(); i++) {
            int suffixLen = currentLines.size() - i;
            if (suffixLen > State.previousChatLines.size()) {
                continue;
            }

            boolean match = true;
            for (int j = 0; j < suffixLen; j++) {
                if (!currentLines.get(i + j).equals(State.previousChatLines.get(j))) {
                    match = false;
                    break;
                }
            }

            if (match) {
                firstDifference = i;
                break;
            }
        }

        List<String> newLines = currentLines.subList(0, firstDifference);
        State.previousChatLines = new ArrayList<>(currentLines);
        return newLines;
    }

    private boolean isPlayerIdle() {
        var playerPos = script.getWorldPosition();
        if (playerPos == null) {
            return false;
        }
        var tileCube = script.getSceneProjector().getTileCube(playerPos, 120);
        if (tileCube == null) {
            return false;
        }
        var resized = tileCube.getResized(0.7);
        if (resized == null) {
            return false;
        }
        return !script.getPixelAnalyzer().isAnimating(0.2, resized);
    }
}
