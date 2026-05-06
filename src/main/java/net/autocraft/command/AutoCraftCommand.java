package net.autocraft.command;

import net.autocraft.gui.AutoCraftScreen;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Регистрирует клиентскую команду /crafter.
 *
 * Клиентские команды выполняются локально — не отправляются на сервер.
 * Работает на любом сервере включая FunTime.
 *
 * Использование:
 *   /crafter         — открыть главный GUI
 *   /crafter stop    — остановить текущий крафт
 *   /crafter pause   — поставить на паузу
 *   /crafter resume  — продолжить
 *   /crafter status  — показать статус в чате
 */
public class AutoCraftCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("AutoCraft/Command");

    /** Вызвать из AutoCraftMod.onInitializeClient() */
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {

            dispatcher.register(
                literal("crafter")

                    // /crafter — открыть GUI
                    .executes(ctx -> {
                        openGui(ctx.getSource());
                        return 1;
                    })

                    // /crafter stop
                    .then(literal("stop").executes(ctx -> {
                        cmdStop(ctx.getSource());
                        return 1;
                    }))

                    // /crafter pause
                    .then(literal("pause").executes(ctx -> {
                        cmdPause(ctx.getSource());
                        return 1;
                    }))

                    // /crafter resume
                    .then(literal("resume").executes(ctx -> {
                        cmdResume(ctx.getSource());
                        return 1;
                    }))

                    // /crafter status
                    .then(literal("status").executes(ctx -> {
                        cmdStatus(ctx.getSource());
                        return 1;
                    }))
            );

        });

        LOGGER.info("[AutoCraft] Команда /crafter зарегистрирована.");
    }

    // ── Обработчики команд ────────────────────────────────────────────────────

    /**
     * /crafter — открыть AutoCraftScreen.
     * Если экран уже открыт — не открывать второй раз.
     */
    private static void openGui(FabricClientCommandSource source) {
        MinecraftClient client = MinecraftClient.getInstance();

        // Команды выполняются в game thread — безопасно работать с GUI напрямую
        client.execute(() -> {
            Screen current = client.currentScreen;

            // Уже открыт — не дублируем
            if (current instanceof AutoCraftScreen) {
                LOGGER.debug("[AutoCraft] /crafter: GUI уже открыт");
                return;
            }

            LOGGER.debug("[AutoCraft] /crafter: открываю GUI");
            client.setScreen(new AutoCraftScreen());
        });
    }

    /**
     * /crafter stop — остановить текущий крафт.
     */
    private static void cmdStop(FabricClientCommandSource source) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            net.autocraft.crafting.CraftingManager mgr =
                    net.autocraft.crafting.CraftingManager.getInstance();

            if (!mgr.isActive()) {
                sendFeedback(source, "§e[Авто крафт] Крафт не запущен.");
                return;
            }

            mgr.stop();
            sendFeedback(source, "§c[Авто крафт] Крафт остановлен командой.");
            LOGGER.info("[AutoCraft] /crafter stop выполнена.");
        });
    }

    /**
     * /crafter pause — поставить на паузу.
     */
    private static void cmdPause(FabricClientCommandSource source) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            net.autocraft.crafting.CraftingManager mgr =
                    net.autocraft.crafting.CraftingManager.getInstance();

            if (!mgr.isActive()) {
                sendFeedback(source, "§e[Авто крафт] Крафт не запущен.");
                return;
            }
            if (mgr.isPaused()) {
                sendFeedback(source, "§e[Авто крафт] Уже на паузе.");
                return;
            }

            mgr.pause();
            sendFeedback(source, "§e[Авто крафт] Пауза.");
            LOGGER.info("[AutoCraft] /crafter pause выполнена.");
        });
    }

    /**
     * /crafter resume — продолжить после паузы.
     */
    private static void cmdResume(FabricClientCommandSource source) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            net.autocraft.crafting.CraftingManager mgr =
                    net.autocraft.crafting.CraftingManager.getInstance();

            if (!mgr.isActive()) {
                sendFeedback(source, "§e[Авто крафт] Крафт не запущен.");
                return;
            }
            if (!mgr.isPaused()) {
                sendFeedback(source, "§e[Авто крафт] Крафт не на паузе.");
                return;
            }

            mgr.resume();
            sendFeedback(source, "§a[Авто крафт] Возобновлено.");
            LOGGER.info("[AutoCraft] /crafter resume выполнена.");
        });
    }

    /**
     * /crafter status — показать текущий статус в чате.
     */
    private static void cmdStatus(FabricClientCommandSource source) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            net.autocraft.crafting.CraftingManager mgr =
                    net.autocraft.crafting.CraftingManager.getInstance();

            if (!mgr.isActive()) {
                sendFeedback(source, "§7[Авто крафт] Статус: §cНеактивен");
                return;
            }

            String status = mgr.isPaused() ? "§eПауза" : "§aАктивен";
            int progress  = (int)(mgr.getProgress() * 100);

            sendFeedback(source,
                "§7[Авто крафт] Статус: " + status
                + "\n§7Предмет: §f"  + mgr.getItemName()
                + "\n§7Прогресс: §f" + mgr.getCraftedCount()
                + "§7/§f"           + mgr.getTargetCount()
                + " §7("            + progress + "%)"
                + "\n§7Откатов: §f" + mgr.getRollbackCount()
            );
        });
    }

    // ── Вспомогательные ───────────────────────────────────────────────────────

    /**
     * Отправить сообщение игроку.
     * Используем player напрямую — надёжнее чем source.sendFeedback на клиенте.
     */
    private static void sendFeedback(FabricClientCommandSource source, String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), false);
        }
    }
}
