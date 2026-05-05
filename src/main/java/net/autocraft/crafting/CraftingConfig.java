package net.autocraft.config;

public class CraftingConfig {

    private boolean autoOpenCrafting = true;
    private boolean continueOnNoResources = false;
    private boolean soundNotification = true;
    private boolean chatNotification = true;
    private int delayMs = 100;
    private String mode = "FAST"; // FAST или MEDIUM
    private int hotkey = 0;

    public boolean isAutoOpenCrafting() { return autoOpenCrafting; }
    public void setAutoOpenCrafting(boolean v) { autoOpenCrafting = v; }

    public boolean isContinueOnNoResources() { return continueOnNoResources; }
    public void setContinueOnNoResources(boolean v) { continueOnNoResources = v; }

    public boolean isSoundNotification() { return soundNotification; }
    public void setSoundNotification(boolean v) { soundNotification = v; }

    public boolean isChatNotification() { return chatNotification; }
    public void setChatNotification(boolean v) { chatNotification = v; }

    public int getDelayMs() { return delayMs; }
    public void setDelayMs(int v) { delayMs = v; }

    public String getMode() { return mode; }
    public void setMode(String v) { mode = v; }

    public int getHotkey() { return hotkey; }
    public void setHotkey(int v) { hotkey = v; }
}
