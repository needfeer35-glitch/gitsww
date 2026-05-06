package net.autocraft.crafting;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WorkbenchFinder — ищет ближайший верстак в радиусе и открывает его.
 *
 * Использует стандартный interactionManager.interactBlock(),
 * что полностью эквивалентно ручному нажатию ПКМ по верстаку.
 * Работает на любом сервере без серверного мода.
 */
public class WorkbenchFinder {

    private static final Logger LOGGER = LoggerFactory.getLogger("AutoCraft/WorkbenchFinder");

    /** Максимальный радиус поиска верстака (в блоках) */
    public static final int SEARCH_RADIUS = 5;

    private WorkbenchFinder() {}

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Найти ближайший верстак и открыть его.
     *
     * @return true  — верстак найден и запрос на открытие отправлен серверу
     *         false — верстак не найден в радиусе SEARCH_RADIUS
     */
    public static boolean findAndOpen(MinecraftClient client) {
        if (client.player == null || client.world == null
                || client.interactionManager == null) {
            LOGGER.warn("[WorkbenchFinder] client не готов");
            return false;
        }

        BlockPos found = findNearest(client);
        if (found == null) {
            LOGGER.warn("[WorkbenchFinder] Верстак не найден в радиусе {}", SEARCH_RADIUS);
            return false;
        }

        LOGGER.info("[WorkbenchFinder] Верстак найден: {}", found);
        return openWorkbench(client, found);
    }

    /**
     * Проверить — открыт ли сейчас экран верстака.
     */
    public static boolean isWorkbenchOpen(MinecraftClient client) {
        return client.currentScreen instanceof
                net.minecraft.client.gui.screen.ingame.CraftingScreen;
    }

    // ─── Поиск ───────────────────────────────────────────────────────────────

    /**
     * BFS-поиск ближайшего верстака вокруг игрока.
     * Проверяет блоки в кубе [-R, +R] по всем трём осям,
     * возвращает позицию с минимальным расстоянием.
     */
    private static BlockPos findNearest(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        BlockPos playerPos = player.getBlockPos();

        BlockPos best     = null;
        double   bestDist = Double.MAX_VALUE;

        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    BlockPos pos = playerPos.add(dx, dy, dz);

                    if (client.world.getBlockState(pos).isOf(Blocks.CRAFTING_TABLE)) {
                        double dist = playerPos.getSquaredDistance(pos);
                        if (dist < bestDist) {
                            bestDist = dist;
                            best     = pos;
                        }
                    }
                }
            }
        }

        return best;
    }

    // ─── Открытие ─────────────────────────────────────────────────────────────

    /**
     * Отправить серверу запрос на взаимодействие с верстаком.
     *
     * Использует interactionManager.interactBlock() — это в точности то,
     * что происходит при ПКМ по блоку. Vanilla-совместимо, работает на любом сервере.
     *
     * BlockHitResult нужен для корректного пакета: указываем центр верхней грани.
     */
    private static boolean openWorkbench(MinecraftClient client, BlockPos pos) {
        ClientPlayerEntity player = client.player;

        // Хит-результат: центр верхней грани верстака
        Vec3d hitVec = new Vec3d(
                pos.getX() + 0.5,
                pos.getY() + 1.0,
                pos.getZ() + 0.5
        );
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, pos, false);

        try {
            // Убираем предмет с курсора, чтобы не случилось случайного использования
            net.minecraft.util.ActionResult result =
                    client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);

            LOGGER.info("[WorkbenchFinder] interactBlock → {}", result);
            return result != net.minecraft.util.ActionResult.FAIL;

        } catch (Exception e) {
            LOGGER.error("[WorkbenchFinder] interactBlock упал: {}", e.getMessage());
            return false;
        }
    }
}
