package net.autocraft;

import net.autocraft.command.AutoCraftCommand;
import net.autocraft.config.AutoCraftConfig;
import net.autocraft.crafting.CraftingManager;
import net.autocraft.gui.AutoCraftScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Точка входа клиентского мода.
 *
 * Регистрирует:
 *  - keybind (клавиша O)  → открыть AutoCraftScreen
 *  - команду /crafter     → открыть AutoCraftScreen / управление
 *  - tick handler         → тикает CraftingManager каждый клиентский тик
 */
public class AutoCraftMod implements ClientModInitializer {

    public static final String MOD_ID = "autocraft";
    public static final Logger LOGGER  = LoggerFactory.getLogger("AutoCraft");

    /** Клавиша открытия GUI — по умолчанию O */
    public static KeyBinding openGuiKey;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[AutoCraft] Инициализация...");

        // 1. Загрузить конфиг с диска
        AutoCraftConfig.load();

        // 2. Зарегистрировать keybind
        openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autocraft.open_gui",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                "category.autocraft.main"
        ));

        // 3. Зарегистрировать команду /crafter
        AutoCraftCommand.register();

        // 4. Tick handler
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            handleKeybind(client);
            CraftingManager.getInstance().tick(client);
        });

        LOGGER.info("[AutoCraft] Готов. [O] или /crafter для открытия GUI.");
    }

    // ── Обработчик клавиши ────────────────────────────────────────────────────

    private void handleKeybind(MinecraftClient client) {
        while (openGuiKey.wasPressed()) {

            // Игрок должен быть в мире
            if (client.player == null) return;

            Screen current = client.currentScreen;

            // Toggle — если уже открыт, закрыть
            if (current instanceof AutoCraftScreen) {
                client.setScreen(null);
                return;
            }

            // Не перебивать другие экраны
            if (current != null) return;

            LOGGER.debug("[AutoCraft] Открываю AutoCraftScreen по [O]");
            client.setScreen(new AutoCraftScreen());
        }
    }
}
