package net.autocraft;

import net.autocraft.crafting.CraftingManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoCraftMod implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("AutoCraft");

    @Override
    public void onInitializeClient() {
        LOGGER.info("AutoCraft v2 загружен");

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            CraftingManager.getInstance().tick(client);
        });
    }
}
