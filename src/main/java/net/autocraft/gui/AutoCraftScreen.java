package net.autocraft.gui;

import net.autocraft.config.AutoCraftConfig;
import net.autocraft.crafting.CraftingManager;
import net.autocraft.crafting.CraftingTask.CraftMode;
import net.autocraft.inventory.IngredientFinder;
import net.autocraft.inventory.IngredientFinder.IngredientRequirement;
import net.autocraft.recipe.CraftingRecipe;
import net.autocraft.recipe.RecipeType;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * AutoCraftScreen — основной GUI авто-крафтера.
 *
 * Шаг 14: Полная интеграция с CraftingManager.
 *
 * Кнопка "Старт" → buildTaskAndStart()
 *   1. Берёт выбранный рецепт
 *   2. Берёт количество из поля ввода
 *   3. Берёт режим (FAST / MEDIUM) из переключателя
 *   4. Строит список IngredientRequirement через IngredientFinder
 *   5. Вызывает CraftingManager.start(...)
 *
 * Шаг 13: renderGridPreview() — превью 3x3 рецепта.
 */
public class AutoCraftScreen extends Screen {

    // ── Размеры окна ──────────────────────────────────────────────────────────
    private static final int BG_W = 248;
    private static final int BG_H = 290;

    // ── Превью сетки ──────────────────────────────────────────────────────────
    private static final int CELL      = 18;   // px на ячейку
    private static final int GRID_COLS = 3;

    // ── Список рецептов ───────────────────────────────────────────────────────
    private static final int LIST_W           = 130;
    private static final int LIST_ITEM_H      = 14;
    private static final int LIST_VISIBLE     = 12;

    // ── Состояние экрана ──────────────────────────────────────────────────────
    private final List<CraftingRecipe> allRecipes = new ArrayList<>();
    private List<CraftingRecipe> filteredRecipes  = new ArrayList<>();
    private CraftingRecipe selectedRecipe         = null;

    /** Режим крафта: true = FAST, false = MEDIUM */
    private boolean modeFast = true;

    /** Количество крафтов */
    private int craftAmount = 64;

    private int scrollOffset = 0;

    // ── Виджеты ───────────────────────────────────────────────────────────────
    private TextFieldWidget searchField;
    private TextFieldWidget amountField;
    private ButtonWidget    btnFast;
    private ButtonWidget    btnMedium;
    private ButtonWidget    btnStart;
    private ButtonWidget    btnStop;
    private ButtonWidget    btnPause;

    // ── Позиции (рассчитываются в init) ──────────────────────────────────────
    private int bgX, bgY;
    private int previewX, previewY; // верхний левый угол 3x3 сетки

    // ── Ошибка последнего запуска (null = нет) ────────────────────────────────
    private String lastError = null;

    // ── Конструктор ───────────────────────────────────────────────────────────

    public AutoCraftScreen() {
        super(Text.translatable("gui.autocraft.title"));
        buildDemoRecipes();
        applyFilter("");
    }

    // =========================================================================
    // Screen lifecycle
    // =========================================================================

    @Override
    protected void init() {
        bgX = (width  - BG_W) / 2;
        bgY = (height - BG_H) / 2;

        // Превью начинается правее списка
        previewX = bgX + LIST_W + 14;
        previewY = bgY + 56;

        // ── Поиск ──
        searchField = new TextFieldWidget(
                textRenderer, bgX + 8, bgY + 18, LIST_W - 2, 12,
                Text.literal("")
        );
        searchField.setMaxLength(32);
        searchField.setPlaceholder(Text.translatable("gui.autocraft.search"));
        searchField.setChangedListener(this::applyFilter);
        addDrawableChild(searchField);

        // ── Поле количества ──
        amountField = new TextFieldWidget(
                textRenderer, bgX + LIST_W + 14, bgY + 18, 50, 12,
                Text.literal("")
        );
        amountField.setMaxLength(5);
        amountField.setText(String.valueOf(craftAmount));
        amountField.setChangedListener(this::onAmountChanged);
        addDrawableChild(amountField);

        // ── Кнопки режима FAST / MEDIUM ──
        // Расположены под полем количества
        btnFast = ButtonWidget.builder(
                Text.literal("Быстрый"),
                btn -> setMode(true)
        ).dimensions(bgX + LIST_W + 14, bgY + 34, 50, 12).build();
        addDrawableChild(btnFast);

        btnMedium = ButtonWidget.builder(
                Text.literal("Средний"),
                btn -> setMode(false)
        ).dimensions(bgX + LIST_W + 68, bgY + 34, 50, 12).build();
        addDrawableChild(btnMedium);

        // ── Кнопки управления крафтом ──
        int btnY = bgY + BG_H - 24;

        btnStart = ButtonWidget.builder(
                Text.translatable("gui.autocraft.start"),
                btn -> buildTaskAndStart()          // ← ГЛАВНЫЙ ОБРАБОТЧИК
        ).dimensions(bgX + 8, btnY, 60, 16).build();
        addDrawableChild(btnStart);

        btnStop = ButtonWidget.builder(
                Text.translatable("gui.autocraft.stop"),
                btn -> onStopClicked()
        ).dimensions(bgX + 72, btnY, 60, 16).build();
        addDrawableChild(btnStop);

        btnPause = ButtonWidget.builder(
                Text.literal("Пауза"),
                btn -> CraftingManager.getInstance().togglePause()
        ).dimensions(bgX + 136, btnY, 50, 16).build();
        addDrawableChild(btnPause);

        updateButtonStates();
    }

    // =========================================================================
    // ШАГ 14 — ГЛАВНЫЙ ОБРАБОТЧИК КНОПКИ "СТАРТ"
    // =========================================================================

    /**
     * buildTaskAndStart() — вызывается при нажатии кнопки "Старт".
     *
     * Алгоритм:
     *  1. Проверяем что рецепт выбран и количество корректно
     *  2. Строим список IngredientRequirement из рецепта
     *  3. Определяем режим (FAST / MEDIUM) из переключателя
     *  4. Передаём всё в CraftingManager.start()
     *  5. Закрываем GUI (крафт продолжается в фоне)
     */
    private void buildTaskAndStart() {
        lastError = null;

        // ── 1. Валидация ──────────────────────────────────────────────────────
        if (selectedRecipe == null) {
            lastError = "§cВыберите рецепт из списка.";
            return;
        }

        if (craftAmount <= 0) {
            lastError = "§cКоличество должно быть > 0.";
            return;
        }

        if (client == null || client.player == null) {
            lastError = "§cИгрок не найден.";
            return;
        }

        // ── 2. Строим список требований ингредиентов ──────────────────────────
        //
        // IngredientRequirement — внутренняя запись IngredientFinder:
        //   record IngredientRequirement(Item item, int count)
        //
        // Для SHAPED: перебираем список длиной 9, пропускаем null (пустые слоты).
        // Для SHAPELESS: перебираем как есть.
        // Одинаковые предметы суммируются: нам нужно знать общее количество каждого.
        //
        List<IngredientRequirement> requirements =
                IngredientFinder.buildRequirements(selectedRecipe.getIngredients());

        // ── 3. Режим крафта ───────────────────────────────────────────────────
        CraftMode mode = modeFast ? CraftMode.FAST : CraftMode.MEDIUM;

        // Сохраняем режим в конфиг (чтобы persisted при следующем открытии)
        AutoCraftConfig.get().craftMode = mode.name();
        AutoCraftConfig.save();

        // ── 4. Название предмета для GUI и чата ──────────────────────────────
        String itemName = getRecipeName(selectedRecipe);

        // ── 5. Запуск CraftingManager ─────────────────────────────────────────
        //
        // start() — публичный метод CraftingManager:
        //   void start(String itemName, int amount,
        //              List<IngredientRequirement> requirements,
        //              CraftingRecipe recipe)
        //
        // Внутри start():
        //   - Проверяет наличие ресурсов (hasEnoughForCrafts)
        //   - Инициализирует ActionQueue и StabilityGuard
        //   - Вызывает buildNextCraftCycle() → цикл тиков в AutoCraftMod
        //
        CraftingManager.getInstance().start(
                itemName,
                craftAmount,
                requirements,
                selectedRecipe
        );

        // ── 6. Закрываем GUI — крафт работает в фоне ──────────────────────────
        //
        // ВАЖНО: не закрываем если CraftingManager отказал (нет ресурсов).
        // Проверяем isActive() — если false, значит start() не смог запустить.
        //
        if (CraftingManager.getInstance().isActive()) {
            client.setScreen(null);   // закрываем GUI, возвращаемся в игровой мир
        }
        // Иначе — GUI остаётся открытым, lastError обновится при следующем рендере
        // через drawCraftStatus() (там отображается последний статус менеджера)
    }

    // =========================================================================
    // Вспомогательные обработчики
    // =========================================================================

    private void onStopClicked() {
        CraftingManager.getInstance().stop();
        updateButtonStates();
    }

    /**
     * Переключение режима FAST / MEDIUM.
     * Меняет цвет активной кнопки и сохраняет в AutoCraftConfig.
     */
    private void setMode(boolean fast) {
        modeFast = fast;
        AutoCraftConfig.get().craftMode = fast ? "FAST" : "MEDIUM";
        AutoCraftConfig.save();
        updateButtonStates();
    }

    /**
     * Синхронизация поля amountField → craftAmount.
     * Игнорирует нечисловой ввод — оставляет предыдущее значение.
     */
    private void onAmountChanged(String text) {
        try {
            int v = Integer.parseInt(text.trim());
            if (v > 0 && v <= 9999) {
                craftAmount = v;
                lastError   = null;
            }
        } catch (NumberFormatException ignored) {
            // Поле пустое или содержит текст — не обновляем
        }
    }

    /**
     * Фильтрация списка рецептов по строке поиска.
     */
    private void applyFilter(String query) {
        String q = query.trim().toLowerCase();
        if (q.isEmpty()) {
            filteredRecipes = new ArrayList<>(allRecipes);
        } else {
            filteredRecipes = allRecipes.stream()
                    .filter(r -> getRecipeName(r).toLowerCase().contains(q))
                    .toList();
        }
        scrollOffset = 0;
    }

    /**
     * Актуализирует enabled/стиль кнопок под текущее состояние менеджера.
     */
    private void updateButtonStates() {
        CraftingManager mgr = CraftingManager.getInstance();
        boolean running = mgr.isActive();

        btnStart.active  = !running && selectedRecipe != null;
        btnStop.active   = running;
        btnPause.active  = running;

        // Визуальная подсветка активного режима через Message
        btnFast.setMessage(Text.literal(modeFast ? "§a▶ Быстрый" : "§7Быстрый"));
        btnMedium.setMessage(Text.literal(!modeFast ? "§a▶ Средний" : "§7Средний"));
    }

    // =========================================================================
    // Рендер
    // =========================================================================

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        renderBackground(ctx, mx, my, delta);
        drawBg(ctx);

        // Заголовок
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("gui.autocraft.title"),
                bgX + BG_W / 2, bgY + 6, 0xFFFFFF);

        // Метки
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Кол-во:"),
                bgX + LIST_W + 14, bgY + 8, 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Режим:"),
                bgX + LIST_W + 14, bgY + 36, 0xFFFFFF);

        // Список рецептов
        drawRecipeList(ctx, mx, my);

        // Превью 3x3
        if (selectedRecipe != null) {
            renderGridPreview(ctx, selectedRecipe, previewX, previewY);
            drawResultArrow(ctx, selectedRecipe, mx, my);
        } else {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("§8Выберите рецепт"),
                    previewX + GRID_COLS * CELL / 2, previewY + 20, 0xFFFFFF);
        }

        // Статус / прогресс
        drawStatus(ctx);

        // Ошибка запуска
        if (lastError != null) {
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal(lastError),
                    bgX + 8, bgY + BG_H - 36, 0xFFFFFF);
        }

        // Кнопки (super)
        super.render(ctx, mx, my, delta);

        // Обновляем состояние кнопок каждый кадр (менеджер мог завершиться)
        updateButtonStates();

        // Тултипы ячеек
        if (selectedRecipe != null) {
            renderGridTooltips(ctx, selectedRecipe, previewX, previewY, mx, my);
        }
    }

    // =========================================================================
    // ШАГ 13 — ПРЕВЬЮ 3x3
    // =========================================================================

    private void renderGridPreview(DrawContext ctx, CraftingRecipe recipe,
                                   int startX, int startY) {
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("§7Рецепт:"), startX, startY - 10, 0xFFFFFF);

        drawGridBg(ctx, startX, startY);

        if (recipe.getType() == RecipeType.SHAPED) {
            renderShapedGrid(ctx, recipe, startX, startY);
        } else {
            renderShapelessGrid(ctx, recipe, startX, startY);
        }

        String label = recipe.getType() == RecipeType.SHAPED ? "§8Shaped" : "§8Shapeless";
        ctx.drawTextWithShadow(textRenderer, Text.literal(label),
                startX, startY + GRID_COLS * CELL + 2, 0xFFFFFF);
    }

    /** SHAPED: рендер по точным позициям 0–8 */
    private void renderShapedGrid(DrawContext ctx, CraftingRecipe recipe,
                                  int sx, int sy) {
        List<Item> ing = recipe.getIngredients();
        for (int i = 0; i < 9; i++) {
            Item item = (i < ing.size()) ? ing.get(i) : null;
            if (item == null || item == Items.AIR) continue;
            int cx = sx + (i % 3) * CELL + 1;
            int cy = sy + (i / 3) * CELL + 1;
            ctx.drawItem(new ItemStack(item), cx, cy);
        }
    }

    /** SHAPELESS: заполнение слева направо */
    private void renderShapelessGrid(DrawContext ctx, CraftingRecipe recipe,
                                     int sx, int sy) {
        List<Item> ing = recipe.getIngredients();
        int idx = 0;
        for (Item item : ing) {
            if (idx >= 9) break;
            if (item == null || item == Items.AIR) { idx++; continue; }
            int cx = sx + (idx % 3) * CELL + 1;
            int cy = sy + (idx / 3) * CELL + 1;
            ctx.drawItem(new ItemStack(item), cx, cy);
            idx++;
        }
    }

    /** Стрелка → результат правее сетки */
    private void drawResultArrow(DrawContext ctx, CraftingRecipe recipe,
                                 int mx, int my) {
        int ax = previewX + GRID_COLS * CELL + 3;
        int ay = previewY + CELL;
        int rx = ax + 14;
        int ry = previewY + CELL;

        ctx.drawTextWithShadow(textRenderer, Text.literal("→"), ax, ay + 4, 0xFFFFFF);

        // Фон слота результата
        ctx.fill(rx - 1, ry - 1, rx + 17, ry + 17, 0xFF373737);
        ctx.fill(rx,     ry,     rx + 16, ry + 16, 0xFF8B8B8B);

        ItemStack rs = new ItemStack(recipe.getResult(), recipe.getResultCount());
        ctx.drawItem(rs, rx, ry);
        ctx.drawItemInSlot(textRenderer, rs, rx, ry);

        if (mx >= rx && mx < rx + 16 && my >= ry && my < ry + 16) {
            ctx.drawItemTooltip(textRenderer, rs, mx, my);
        }
    }

    /** Тултипы при наведении на ячейки превью */
    private void renderGridTooltips(DrawContext ctx, CraftingRecipe recipe,
                                    int sx, int sy, int mx, int my) {
        List<Item> ing = recipe.getIngredients();
        for (int i = 0; i < 9; i++) {
            int cx = sx + (i % 3) * CELL;
            int cy = sy + (i / 3) * CELL;
            if (mx >= cx && mx < cx + CELL && my >= cy && my < cy + CELL) {
                Item item = null;
                if (recipe.getType() == RecipeType.SHAPED) {
                    item = (i < ing.size()) ? ing.get(i) : null;
                } else {
                    // SHAPELESS: nth непустой
                    int count = 0;
                    for (Item it : ing) {
                        if (it != null && it != Items.AIR) {
                            if (count == i) { item = it; break; }
                            count++;
                        }
                    }
                }
                if (item != null && item != Items.AIR) {
                    ctx.drawItemTooltip(textRenderer, new ItemStack(item), mx, my);
                }
                break;
            }
        }
    }

    // =========================================================================
    // Прочий рендер
    // =========================================================================

    private void drawBg(DrawContext ctx) {
        ctx.fill(bgX, bgY, bgX + BG_W, bgY + BG_H, 0xD0101010);
        ctx.fill(bgX,         bgY,          bgX + BG_W,     bgY + 1,        0xFF555555);
        ctx.fill(bgX,         bgY + BG_H-1, bgX + BG_W,     bgY + BG_H,     0xFF555555);
        ctx.fill(bgX,         bgY,          bgX + 1,         bgY + BG_H,     0xFF555555);
        ctx.fill(bgX + BG_W-1, bgY,         bgX + BG_W,     bgY + BG_H,     0xFF555555);
    }

    private void drawGridBg(DrawContext ctx, int sx, int sy) {
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                int x = sx + c * CELL;
                int y = sy + r * CELL;
                ctx.fill(x, y, x + CELL - 1, y + CELL - 1, 0xFF373737);
                ctx.fill(x + 1, y + 1, x + CELL - 2, y + CELL - 2, 0xFF8B8B8B);
            }
        }
    }

    private void drawRecipeList(DrawContext ctx, int mx, int my) {
        int lx = bgX + 8;
        int ly = bgY + 34;

        ctx.fill(lx, ly, lx + LIST_W, ly + LIST_VISIBLE * LIST_ITEM_H, 0xFF1A1A1A);

        int end = Math.min(scrollOffset + LIST_VISIBLE, filteredRecipes.size());
        for (int i = scrollOffset; i < end; i++) {
            CraftingRecipe r = filteredRecipes.get(i);
            int iy = ly + (i - scrollOffset) * LIST_ITEM_H;

            boolean hov = mx >= lx && mx < lx + LIST_W && my >= iy && my < iy + LIST_ITEM_H;
            boolean sel = r == selectedRecipe;

            ctx.fill(lx, iy, lx + LIST_W, iy + LIST_ITEM_H,
                    sel ? 0xFF2A5A2A : (hov ? 0xFF2A2A2A : 0xFF1A1A1A));

            ctx.drawItem(new ItemStack(r.getResult()), lx + 1, iy);
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal(getRecipeName(r)),
                    lx + 18, iy + 3,
                    sel ? 0x55FF55 : 0xDDDDDD);
        }
    }

    private void drawStatus(DrawContext ctx) {
        CraftingManager mgr = CraftingManager.getInstance();
        int sx = bgX + 8;
        int sy = bgY + BG_H - 50;

        if (mgr.isActive()) {
            int barW = BG_W - 16;
            ctx.fill(sx, sy, sx + barW, sy + 5, 0xFF333333);
            ctx.fill(sx, sy, sx + (int)(mgr.getProgress() * barW), sy + 5, 0xFF44AA44);

            String lbl = (mgr.isPaused() ? "§ePAUSED " : "§aCRAFTING ")
                       + mgr.getItemName()
                       + " §f" + mgr.getCraftedCount() + "/" + mgr.getTargetCount();
            ctx.drawTextWithShadow(textRenderer, Text.literal(lbl), sx, sy + 7, 0xFFFFFF);
        } else {
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal("§8Ожидание..."), sx, sy + 4, 0xFFFFFF);
        }
    }

    // =========================================================================
    // Mouse / Scroll
    // =========================================================================

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        // Клик по списку
        int lx = bgX + 8;
        int ly = bgY + 34;
        if (mx >= lx && mx < lx + LIST_W && my >= ly
         && my < ly + LIST_VISIBLE * LIST_ITEM_H) {
            int idx = scrollOffset + (int)((my - ly) / LIST_ITEM_H);
            if (idx >= 0 && idx < filteredRecipes.size()) {
                selectedRecipe = filteredRecipes.get(idx);
                lastError = null;
                updateButtonStates();
            }
        }

        // +/- количество кнопками мыши (wheel click)
        if (btn == 1) {
            // ПКМ по полю количества — быстрый ввод x64 / x128
        }

        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double ha, double va) {
        int lx = bgX + 8;
        int ly = bgY + 34;
        if (mx >= lx && mx < lx + LIST_W
         && my >= ly && my < ly + LIST_VISIBLE * LIST_ITEM_H) {
            scrollOffset -= (int) va;
            scrollOffset = Math.max(0,
                    Math.min(scrollOffset, Math.max(0, filteredRecipes.size() - LIST_VISIBLE)));
        }
        return super.mouseScrolled(mx, my, ha, va);
    }

    @Override
    public boolean shouldPause() { return false; }

    // =========================================================================
    // Утилиты
    // =========================================================================

    private String getRecipeName(CraftingRecipe r) {
        return Registries.ITEM.getId(r.getResult()).getPath().replace("_", " ");
    }

    /** Тестовые рецепты — будут заменены на RecipeRegistry */
    private void buildDemoRecipes() {
        // Сундук (SHAPED)
        List<Item> chest = new ArrayList<>();
        for (int i = 0; i < 9; i++) chest.add(i == 4 ? null : Items.OAK_PLANKS);
        allRecipes.add(new CraftingRecipe(RecipeType.SHAPED, chest, Items.CHEST, 1));

        // Верстак (SHAPED)
        List<Item> table = List.of(
                Items.OAK_PLANKS, Items.OAK_PLANKS, null,
                Items.OAK_PLANKS, Items.OAK_PLANKS, null,
                null,             null,             null
        );
        allRecipes.add(new CraftingRecipe(RecipeType.SHAPED, new ArrayList<>(table), Items.CRAFTING_TABLE, 1));

        // Факел (SHAPELESS)
        allRecipes.add(new CraftingRecipe(RecipeType.SHAPELESS,
                List.of(Items.COAL, Items.STICK), Items.TORCH, 4));
    }
}
