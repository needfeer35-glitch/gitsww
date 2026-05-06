package net.autocraft.stability;

import net.autocraft.recipe.GridCleaner;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.CraftingScreenHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Система стабильности крафта.
 *
 * После каждого крафта проверяет:
 *   1. Что результат действительно попал в инвентарь
 *   2. Что grid очищена
 *
 * При рассинхронизации — откат: очистка grid + сигнал на повтор.
 */
public class StabilityGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger("AutoCraft/StabilityGuard");

    public enum CheckResult {
        OK,           // всё хорошо, продолжать
        ROLLBACK,     // произошёл откат, повторить крафт
        FATAL         // откат не помог, остановить крафт
    }

    private int rollbackCount = 0;
    private static final int MAX_ROLLBACKS = 3;

    // Снапшот инвентаря до крафта
    private int itemCountBefore = 0;

    // -------------------------------------------------------------------------

    /**
     * Сохранить количество целевого предмета в инвентаре ДО крафта.
     * Вызывать перед шагом "Взять результат".
     */
    public void snapshotBefore(MinecraftClient client, Item expectedItem) {
        itemCountBefore = countInInventory(client, expectedItem);
        LOGGER.debug("[StabilityGuard] Снапшот до крафта: {} x{}", expectedItem, itemCountBefore);
    }

    /**
     * Проверить результат ПОСЛЕ крафта.
     *
     * @param expectedItem  предмет который должен был скрафтититься
     * @param expectedCount сколько штук должно было добавиться
     * @return CheckResult — OK / ROLLBACK / FATAL
     */
    public CheckResult verifyAfter(MinecraftClient client, Item expectedItem, int expectedCount) {
        int itemCountAfter = countInInventory(client, expectedItem);
        int gained = itemCountAfter - itemCountBefore;

        LOGGER.debug("[StabilityGuard] После крафта: ожидалось +{}, получено +{}",
                expectedCount, gained);

        // Результат получен корректно
        if (gained >= expectedCount) {
            LOGGER.info("[StabilityGuard] Крафт подтверждён: +{} {}", gained, expectedItem);
            rollbackCount = 0;
            return CheckResult.OK;
        }

        // Результат не получен — рассинхронизация
        LOGGER.warn("[StabilityGuard] Рассинхронизация! Ожидалось +{}, получено +{}. Откат...",
                expectedCount, gained);

        return performRollback(client);
    }

    /**
     * Принудительный откат — очистить grid и вернуть статус.
     */
    public CheckResult performRollback(MinecraftClient client) {
        rollbackCount++;
        LOGGER.warn("[StabilityGuard] Откат #{}/{}", rollbackCount, MAX_ROLLBACKS);

        // Очищаем сетку верстака
        boolean cleared = GridCleaner.clearGrid(client);

        if (!cleared) {
            LOGGER.error("[StabilityGuard] Очистка grid провалилась");
        }

        // Проверяем grid ещё раз через короткую задержку (обрабатывается в следующем тике)
        if (rollbackCount >= MAX_ROLLBACKS) {
            LOGGER.error("[StabilityGuard] Достигнут лимит откатов ({}) — крафт остановлен",
                    MAX_ROLLBACKS);
            return CheckResult.FATAL;
        }

        return CheckResult.ROLLBACK;
    }

    /**
     * Быстрая проверка: grid пуста И результат в слоте 0 тоже пуст.
     * Использовать перед началом нового крафта.
     */
    public boolean isStateClean(MinecraftClient client) {
        if (!(client.currentScreen instanceof CraftingScreen screen)) return false;

        CraftingScreenHandler handler = screen.getScreenHandler();

        // Слот 0 = результат, должен быть пуст перед новым крафтом
        ItemStack resultSlot = handler.slots.get(0).getStack();
        if (!resultSlot.isEmpty()) {
            LOGGER.warn("[StabilityGuard] Слот результата не пуст перед новым крафтом: {}",
                    resultSlot.getItem());
            return false;
        }

        return GridCleaner.isGridEmpty(client);
    }

    public void reset() {
        rollbackCount = 0;
        itemCountBefore = 0;
    }

    public int getRollbackCount() { return rollbackCount; }

    // -------------------------------------------------------------------------

    private int countInInventory(MinecraftClient client, Item item) {
        if (client.player == null) return 0;
        int count = 0;
        for (int i = 0; i < client.player.getInventory().size(); i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem().equals(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }
}
