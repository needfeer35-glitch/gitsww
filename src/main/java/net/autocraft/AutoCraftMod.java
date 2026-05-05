package net.autocraft;

import net.autocraft.config.AutoCraftConfig;
import net.autocraft.crafting.CraftingManager;
import net.autocraft.gui.AutoCraftScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoCraftMod implements ClientModInitializer {

    public static final String MOD_ID = "autocraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static KeyBinding openGuiKey;
    public static CraftingManager craftingManager;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[AutoCraft] Initializing AutoCraft v2...");

        AutoCraftConfig.load();

        openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autocraft.open_gui",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                "category.autocraft.main"
        ));

        craftingManager = new CraftingManager();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGuiKey.wasPressed()) {
                if (client.player != null) {
                    client.setScreen(new AutoCraftScreen());
                }
            }
            craftingManager.tick(client);
        });

        LOGGER.info("[AutoCraft] Ready.");
    }
}
