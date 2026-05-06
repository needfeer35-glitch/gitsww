package net.autocraft;

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
 *  - keybind (клавиша O) → открыть AutoCraftScreen
 *  - tick handler → тикает CraftingManager каждый клиентский тик
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
                "key.autocraft.open_gui",       // translation key (en_us.json)
                InputUtil.Type.KEYSYM,           // тип: клавиша клавиатуры
                GLFW.GLFW_KEY_O,                 // клавиша O по умолчанию
                "category.autocraft.main"        // категория в настройках управления
        ));

        // 3. Tick handler
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            handleKeybind(client);
            CraftingManager.getInstance().tick(client);
        });

        LOGGER.info("[AutoCraft] Готов. Нажми [O] для открытия GUI.");
    }

    // ── Обработчик нажатия клавиши ────────────────────────────────────────────

    /**
     * Вызывается каждый тик.
     *
     * Логика:
     *  - wasPressed() сбрасывает флаг нажатия — безопасно вызывать в цикле
     *  - проверяем что игрок существует (не в главном меню)
     *  - проверяем что AutoCraftScreen ещё не открыт — не открываем второй раз
     *  - проверяем что нет другого открытого экрана (инвентарь, верстак и т.д.)
     */
    private void handleKeybind(MinecraftClient client) {
        // wasPressed() — возвращает true и сбрасывает счётчик нажатий
        while (openGuiKey.wasPressed()) {

            // Игрок должен быть в мире
            if (client.player == null) return;

            Screen currentScreen = client.currentScreen;

            // Если AutoCraftScreen уже открыт — закрываем его (toggle)
            if (currentScreen instanceof AutoCraftScreen) {
                client.setScreen(null);
                return;
            }

            // Если открыт любой другой экран — не перебиваем его
            if (currentScreen != null) return;

            // Открываем GUI
            LOGGER.debug("[AutoCraft] Открываю AutoCraftScreen по нажатию [O]");
            client.setScreen(new AutoCraftScreen());
        }
    }
}
