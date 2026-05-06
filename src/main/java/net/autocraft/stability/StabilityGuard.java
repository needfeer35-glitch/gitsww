package net.autocraft.stability;

import net.autocraft.recipe.GridCleaner;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.CraftingScreenHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StabilityGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger("AutoCraft/StabilityGuard");

    public enum CheckResult { OK, ROLLBACK, FATAL }

    private int rollbackCount    = 0;
    private int itemCountBefore  = 0;
    private static final int MAX_ROLLBACKS = 3;

    public void snapshotBefore(MinecraftClient client, Item expectedItem) {
        itemCountBefore = countInInventory(client, expectedItem);
        LOGGER.debug("[StabilityGuard] Снапшот до крафта: {} x{}", expectedItem, itemCountBefore);
    }

    public CheckResult verifyAfter(MinecraftClient client, Item expectedItem, int expectedCount) {
        int itemCountAfter = countInInventory(client, expectedItem);
        int gained = itemCountAfter - itemCountBefore;

        LOGGER.debug("[StabilityGuard] После крафта: ожидалось +{}, получено +{}",
                expectedCount, gained);

        if (gained >= expectedCount) {
            LOGGER.info("[StabilityGuard] Крафт подтверждён: +{} {}", gained, expectedItem);
            rollbackCount = 0;
            return CheckResult.OK;
        }

        LOGGER.warn("[StabilityGuard] Рассинхронизация! Ожидалось +{}, получено +{}. Откат...",
                expectedCount, gained);
        return performRollback(client);
    }

    public CheckResult performRollback(MinecraftClient client) {
        rollbackCount++;
        LOGGER.warn("[StabilityGuard] Откат #{}/{}", rollbackCount, MAX_ROLLBACKS);
        boolean cleared = GridCleaner.clearGrid(client);
        if (!cleared) LOGGER.error("[StabilityGuard] Очистка grid провалилась");
        if (rollbackCount >= MAX_ROLLBACKS) {
            LOGGER.error("[StabilityGuard] Достигнут лимит откатов ({}) — крафт остановлен",
                    MAX_ROLLBACKS);
            return CheckResult.FATAL;
        }
        return CheckResult.ROLLBACK;
    }

    public boolean isStateClean(MinecraftClient client) {
        if (!(client.currentScreen instanceof CraftingScreen screen)) return false;
        CraftingScreenHandler handler = screen.getScreenHandler();
        ItemStack resultSlot = handler.slots.get(0).getStack();
        if (!resultSlot.isEmpty()) {
            LOGGER.warn("[StabilityGuard] Слот результата не пуст перед новым крафтом: {}",
                    resultSlot.getItem());
            return false;
        }
        return GridCleaner.isGridEmpty(client);
    }

    public void reset() {
        rollbackCount   = 0;
        itemCountBefore = 0;
    }

    public int getRollbackCount() { return rollbackCount; }

    // FIX: только основной инвентарь (0..35), не включаем броню и оффхенд
    private int countInInventory(MinecraftClient client, Item item) {
        if (client.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem().equals(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }
}
