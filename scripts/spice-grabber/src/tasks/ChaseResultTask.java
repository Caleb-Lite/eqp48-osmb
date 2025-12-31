package tasks;

import com.osmb.api.ui.chatbox.Chatbox;
import com.osmb.api.ui.chatbox.ChatboxFilterTab;
import data.CatState;
import utils.Task;

import java.util.ArrayList;
import java.util.List;

public class ChaseResultTask extends Task {
    public ChaseResultTask(com.osmb.api.script.Script script) {
        super(script);
    }

    @Override
    public boolean activate() {
        return CatState.chaseExecutedThisPoll && isPlayerIdle();
    }

    @Override
    public boolean execute() {
        if (CatState.pendingChaseResult) {
            resolvePendingChase();
        }

        seedChatLines();
        CatState.pendingChaseResult = true;
        CatState.chaseExecutedThisPoll = false;
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
            CatState.pendingLoot = true;
        }
        CatState.pendingChaseResult = false;
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

        CatState.previousChatLines = new ArrayList<>(lines);
    }

    private List<String> getNewChatLines(List<String> currentLines) {
        if (currentLines == null || currentLines.isEmpty()) {
            return List.of();
        }

        if (CatState.previousChatLines.isEmpty()) {
            CatState.previousChatLines = new ArrayList<>(currentLines);
            return List.of();
        }

        if (currentLines.equals(CatState.previousChatLines)) {
            return List.of();
        }

        int firstDifference = currentLines.size();
        for (int i = 0; i < currentLines.size(); i++) {
            int suffixLen = currentLines.size() - i;
            if (suffixLen > CatState.previousChatLines.size()) {
                continue;
            }

            boolean match = true;
            for (int j = 0; j < suffixLen; j++) {
                if (!currentLines.get(i + j).equals(CatState.previousChatLines.get(j))) {
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
        CatState.previousChatLines = new ArrayList<>(currentLines);
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
