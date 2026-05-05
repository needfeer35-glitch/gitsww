package net.autocraft.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;

public class AutoCraftConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("autocraft");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("autocraft.json");

    private static AutoCraftConfig INSTANCE = new AutoCraftConfig();

    // --- Config fields ---
    public boolean openWorkbenchAuto = true;
    public boolean continueOnMissingResources = false;
    public boolean soundNotification = true;
    public boolean chatNotification = true;
    public String craftMode = "FAST";  // "FAST" or "MEDIUM"
    public int hotkey = 0; // GLFW key code, 0 = none

    public static AutoCraftConfig get() {
        return INSTANCE;
    }

    public static void load() {
        File file = CONFIG_PATH.toFile();
        if (file.exists()) {
            try (Reader r = new FileReader(file)) {
                INSTANCE = GSON.fromJson(r, AutoCraftConfig.class);
                if (INSTANCE == null) INSTANCE = new AutoCraftConfig();
                LOGGER.info("[AutoCraft] Config loaded.");
            } catch (IOException e) {
                LOGGER.error("[AutoCraft] Failed to load config, using defaults.", e);
                INSTANCE = new AutoCraftConfig();
            }
        } else {
            INSTANCE = new AutoCraftConfig();
            save();
        }
    }

    public static void save() {
        try (Writer w = new FileWriter(CONFIG_PATH.toFile())) {
            GSON.toJson(INSTANCE, w);
            LOGGER.info("[AutoCraft] Config saved.");
        } catch (IOException e) {
            LOGGER.error("[AutoCraft] Failed to save config.", e);
        }
    }
}
