package net.autocraft.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.recipe.ShapelessRecipe;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AutoCraftScreen — главный GUI мода.
 *
 * Layout:
 * ┌──────────────────────────────────────────────────┐
 * │  Авто крафт v2                                   │
 * │ ┌────────────────┐  ┌───────────────────────────┐│
 * │ │ [🔍 Поиск...] [≡]│  Выбранный предмет         ││
 * │ │────────────────│  │  [икона] Название           ││
 * │ │ Дубовые доски  │  │  Рецепт:                   ││
 * │ │ Палка          │  │  [3x3 grid] → [результат]  ││
 * │ │ Факел          │  │                            ││
 * │ │ Верстак        │  │  Количество: [-][64][+][MAX]││
 * │ │ ...            │  │  Режим: [Быстрый][Средний] ││
 * │ └────────────────┘  │  [      СТАРТ      ]        ││
 * │                     └───────────────────────────┘│
 * └──────────────────────────────────────────────────┘
 */
public class AutoCraftScreen extends Screen {

    // ── Размеры окна ──────────────────────────────────────────────────────────
    private static final int GUI_W = 500;
    private static final int GUI_H = 240;

    // Левая панель
    private static final int LIST_X  = 8;
    private static final int LIST_Y  = 24;
    private static final int LIST_W  = 162;
    private static final int LIST_H  = 198;
    private static final int ITEM_H  = 18;
    private static final int VISIBLE = LIST_H / ITEM_H; // кол-во видимых строк

    // Правая панель
    private static final int DETAIL_X = 178;
    private static final int DETAIL_Y = 8;
    private static final int DETAIL_W = 314;
    private static final int DETAIL_H = 224;

    // Grid preview (3x3)
    private static final int CELL = 18; // размер ячейки

    // ── Цвета ─────────────────────────────────────────────────────────────────
    private static final int C_BG       = 0xFF2B2B2B;
    private static final int C_PANEL    = 0xFF3C3C3C;
    private static final int C_BORDER   = 0xFF555555;
    private static final int C_SELECTED = 0xFF3A6B3A;
    private static final int C_HOVER    = 0xFF4A4A4A;
    private static final int C_CELL     = 0xFF404040;
    private static final int C_CELL_BR  = 0xFF666666;
    private static final int C_WHITE    = 0xFFFFFFFF;
    private static final int C_GRAY     = 0xFFAAAAAA;
    private static final int C_YELLOW   = 0xFFFFCC00;
    private static final int C_GREEN    = 0xFF4CAF50;
    private static final int C_DARKGREEN= 0xFF2E7D32;

    // ── Данные рецептов ───────────────────────────────────────────────────────

    /**
     * Одна запись в списке рецептов GUI.
     * Хранит всё нужное для отображения и запуска крафта.
     */
    public static class RecipeDisplayEntry {
        public final String displayName;      // название для списка
        public final ItemStack icon;          // иконка (результат рецепта)
        public final ItemStack[] grid;        // 9 слотов для 3x3 preview (null = пусто)
        public final ItemStack result;        // результат
        public final int resultCount;         // количество в результате
        public final RecipeEntry<CraftingRecipe> vanillaEntry; // оригинал для CraftingManager

        public RecipeDisplayEntry(RecipeEntry<CraftingRecipe> entry) {
            this.vanillaEntry = entry;
            CraftingRecipe recipe = entry.value();

            // Результат
            ItemStack output = recipe.getResult(MinecraftClient.getInstance()
                    .world.getRegistryManager());
            this.result      = output;
            this.resultCount = output.getCount();
            this.icon        = output.copy();
            this.displayName = output.getName().getString();

            // Строим grid 9 слотов
            this.grid = new ItemStack[9];
            List<net.minecraft.recipe.Ingredient> ingredients = recipe.getIngredients();

            if (recipe instanceof ShapedRecipe shaped) {
                // SHAPED — позиции важны
                int width = shaped.getWidth();
                for (int i = 0; i < ingredients.size(); i++) {
                    net.minecraft.recipe.Ingredient ing = ingredients.get(i);
                    // Получаем позицию в сетке 3x3
                    int row = i / width;
                    int col = i % width;
                    int slot = row * 3 + col;
                    if (slot < 9 && !ing.isEmpty()) {
                        ItemStack[] matching = ing.getMatchingStacks();
                        if (matching.length > 0) {
                            this.grid[slot] = matching[0].copy();
                        }
                    }
                }
            } else {
                // SHAPELESS — заполняем слева направо
                for (int i = 0; i < Math.min(ingredients.size(), 9); i++) {
                    net.minecraft.recipe.Ingredient ing = ingredients.get(i);
                    if (!ing.isEmpty()) {
                        ItemStack[] matching = ing.getMatchingStacks();
                        if (matching.length > 0) {
                            this.grid[i] = matching[0].copy();
                        }
                    }
                }
            }
        }
    }

    // ── Состояние экрана ──────────────────────────────────────────────────────
    private int guiLeft, guiTop;

    private final List<RecipeDisplayEntry> allRecipes      = new ArrayList<>();
    private final List<RecipeDisplayEntry> filteredRecipes = new ArrayList<>();

    private RecipeDisplayEntry selectedRecipe = null;
    private int selectedIndex  = -1;
    private int scrollOffset   = 0;
    private int craftAmount    = 64;
    private boolean modeFast   = true;

    // Виджеты
    private TextFieldWidget searchField;
    private ButtonWidget btnMinus;
    private ButtonWidget btnPlus;
    private ButtonWidget btnMax;
    private ButtonWidget btnModeFast;
    private ButtonWidget btnModeMedium;
    private ButtonWidget btnStart;

    // ── Конструктор ───────────────────────────────────────────────────────────

    public AutoCraftScreen() {
        super(Text.literal("Авто крафт v2"));
    }

    // ── init() ────────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        guiLeft = (this.width  - GUI_W) / 2;
        guiTop  = (this.height - GUI_H) / 2;

        // Загрузить рецепты из RecipeManager
        loadRecipes();

        // ── Поле поиска ───────────────────────────────────────────────────────
        searchField = new TextFieldWidget(
                textRenderer,
                guiLeft + LIST_X + 2,
                guiTop  + LIST_Y - 16,
                LIST_W - 4,
                12,
                Text.literal("")
        );
        searchField.setMaxLength(32);
        searchField.setPlaceholder(Text.literal("Поиск..."));
        searchField.setChangedListener(this::onSearchChanged);
        this.addDrawableChild(searchField);

        // ── Кнопки количества ─────────────────────────────────────────────────
        int rx = guiLeft + DETAIL_X + 4;
        int ry = guiTop;

        btnMinus = ButtonWidget.builder(Text.literal("−"), b -> {
            craftAmount = Math.max(1, craftAmount - 1);
        }).dimensions(rx, ry + 154, 16, 16).build();

        btnPlus = ButtonWidget.builder(Text.literal("+"), b -> {
            craftAmount = Math.min(9999, craftAmount + 1);
        }).dimensions(rx + 36, ry + 154, 16, 16).build();

        btnMax = ButtonWidget.builder(Text.literal("MAX"), b -> {
            craftAmount = calcMax();
        }).dimensions(rx + 56, ry + 154, 34, 16).build();

        // ── Кнопки режима ─────────────────────────────────────────────────────
        btnModeFast = ButtonWidget.builder(Text.literal("Быстрый"), b -> {
            modeFast = true;
            refreshModeButtons();
        }).dimensions(rx, ry + 176, 68, 16).build();

        btnModeMedium = ButtonWidget.builder(Text.literal("Средний"), b -> {
            modeFast = false;
            refreshModeButtons();
        }).dimensions(rx + 72, ry + 176, 68, 16).build();

        // ── Кнопка СТАРТ ──────────────────────────────────────────────────────
        btnStart = ButtonWidget.builder(Text.literal("Старт"), b -> onStart())
                .dimensions(rx, ry + 198, 144, 20).build();

        this.addDrawableChild(btnMinus);
        this.addDrawableChild(btnPlus);
        this.addDrawableChild(btnMax);
        this.addDrawableChild(btnModeFast);
        this.addDrawableChild(btnModeMedium);
        this.addDrawableChild(btnStart);

        refreshModeButtons();
        updateStartButton();
    }

    // ── Загрузка рецептов из RecipeManager ───────────────────────────────────

    private void loadRecipes() {
        allRecipes.clear();
        filteredRecipes.clear();

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;

        // Получаем все рецепты типа CRAFTING (верстак)
        client.world.getRecipeManager()
                .listAllOfType(RecipeType.CRAFTING)
                .stream()
                // Фильтруем только shaped и shapeless — пропускаем специальные
                .filter(e -> e.value() instanceof ShapedRecipe
                          || e.value() instanceof ShapelessRecipe)
                // Сортируем по алфавиту
                .sorted((a, b) -> {
                    String na = a.value().getResult(client.world.getRegistryManager())
                            .getName().getString();
                    String nb = b.value().getResult(client.world.getRegistryManager())
                            .getName().getString();
                    return na.compareToIgnoreCase(nb);
                })
                .forEach(e -> {
                    try {
                        allRecipes.add(new RecipeDisplayEntry(e));
                    } catch (Exception ex) {
                        // Пропускаем битые/нестандартные рецепты без краша
                        net.autocraft.AutoCraftMod.LOGGER.debug(
                                "[AutoCraft] Пропущен рецепт {}: {}",
                                e.id(), ex.getMessage());
                    }
                });

        filteredRecipes.addAll(allRecipes);

        net.autocraft.AutoCraftMod.LOGGER.info(
                "[AutoCraft] Загружено рецептов: {}", allRecipes.size());
    }

    // ── Поиск ────────────────────────────────────────────────────────────────

    private void onSearchChanged(String query) {
        filteredRecipes.clear();
        scrollOffset  = 0;
        selectedIndex = -1;
        selectedRecipe = null;

        String q = query.toLowerCase(Locale.ROOT).trim();

        if (q.isEmpty()) {
            filteredRecipes.addAll(allRecipes);
        } else {
            for (RecipeDisplayEntry e : allRecipes) {
                if (e.displayName.toLowerCase(Locale.ROOT).contains(q)) {
                    filteredRecipes.add(e);
                }
            }
        }

        updateStartButton();
    }

    // ── render() ──────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        renderBackground(ctx, mx, my, delta);

        // Фон окна
        ctx.fill(guiLeft, guiTop, guiLeft + GUI_W, guiTop + GUI_H, C_BG);
        border(ctx, guiLeft, guiTop, GUI_W, GUI_H, C_BORDER);

        // Заголовок
        ctx.drawText(textRenderer, Text.literal("Авто крафт v2"),
                guiLeft + 8, guiTop + 8, C_YELLOW, true);

        drawLeftPanel(ctx, mx, my);
        drawRightPanel(ctx, mx, my);

        // Виджеты поверх
        super.render(ctx, mx, my, delta);

        // Подсказки при наведении
        drawTooltips(ctx, mx, my);
    }

    // ── Левая панель: список рецептов ─────────────────────────────────────────

    private void drawLeftPanel(DrawContext ctx, int mx, int my) {
        int px = guiLeft + LIST_X;
        int py = guiTop  + LIST_Y;

        // Фон панели (включая поисковую строку)
        ctx.fill(px, py - 18, px + LIST_W, py + LIST_H, C_PANEL);
        border(ctx, px, py - 18, LIST_W, LIST_H + 18, C_BORDER);

        // Разделитель под поиском
        ctx.fill(px + 1, py - 2, px + LIST_W - 1, py - 1, C_BORDER);

        // Список
        for (int i = 0; i < VISIBLE; i++) {
            int dataIdx = i + scrollOffset;
            if (dataIdx >= filteredRecipes.size()) break;

            RecipeDisplayEntry entry = filteredRecipes.get(dataIdx);
            int rowX = px + 1;
            int rowY = py + i * ITEM_H;

            boolean isSel   = (dataIdx == selectedIndex);
            boolean isHover = mx >= rowX && mx < px + LIST_W - 1
                           && my >= rowY && my < rowY + ITEM_H;

            // Фон строки
            if (isSel) {
                ctx.fill(rowX, rowY, px + LIST_W - 1, rowY + ITEM_H, C_SELECTED);
            } else if (isHover) {
                ctx.fill(rowX, rowY, px + LIST_W - 1, rowY + ITEM_H, C_HOVER);
            }

            // Иконка предмета
            ctx.drawItem(entry.icon, rowX + 1, rowY + 1);

            // Название (обрезаем если не влезает)
            String name = entry.displayName;
            int maxW    = LIST_W - 24;
            if (textRenderer.getWidth(name) > maxW) {
                name = textRenderer.trimToWidth(name, maxW - 8) + "...";
            }

            ctx.drawText(textRenderer, Text.literal(name),
                    rowX + 19, rowY + 5,
                    isSel ? C_YELLOW : C_WHITE, false);
        }

        // Скроллбар (если рецептов больше чем видно)
        drawScrollbar(ctx, px + LIST_W - 5, py, 4, LIST_H);
    }

    // ── Скроллбар ─────────────────────────────────────────────────────────────

    private void drawScrollbar(DrawContext ctx, int x, int y, int w, int h) {
        if (filteredRecipes.size() <= VISIBLE) return;

        // Фон
        ctx.fill(x, y, x + w, y + h, 0xFF222222);

        // Ползунок
        float ratio     = (float) VISIBLE / filteredRecipes.size();
        int   thumbH    = Math.max(10, (int)(h * ratio));
        float scrollRatio = filteredRecipes.size() > VISIBLE
                ? (float) scrollOffset / (filteredRecipes.size() - VISIBLE) : 0;
        int   thumbY    = y + (int)((h - thumbH) * scrollRatio);

        ctx.fill(x + 1, thumbY, x + w - 1, thumbY + thumbH, 0xFF888888);
    }

    // ── Правая панель: детали рецепта ─────────────────────────────────────────

    private void drawRightPanel(DrawContext ctx, int mx, int my) {
        int px = guiLeft + DETAIL_X;
        int py = guiTop  + DETAIL_Y;

        ctx.fill(px, py, px + DETAIL_W, py + DETAIL_H, C_PANEL);
        border(ctx, px, py, DETAIL_W, DETAIL_H, C_BORDER);

        ctx.drawText(textRenderer, Text.literal("Выбранный предмет"),
                px + 4, py + 4, C_GRAY, false);

        if (selectedRecipe == null) {
            // Подсказка если ничего не выбрано
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("← Выберите предмет из списка"),
                    px + DETAIL_W / 2, py + DETAIL_H / 2 - 4, C_GRAY);
            return;
        }

        // Иконка + название выбранного предмета
        ctx.drawItem(selectedRecipe.icon, px + 4, py + 14);
        ctx.drawItemInSlot(textRenderer, selectedRecipe.icon, px + 4, py + 14);
        ctx.drawText(textRenderer,
                Text.literal(selectedRecipe.displayName),
                px + 22, py + 18, C_YELLOW, true);

        // Подпись "Рецепт"
        ctx.drawText(textRenderer, Text.literal("Рецепт"),
                px + 4, py + 34, C_GRAY, false);

        // ── 3x3 Grid preview ──────────────────────────────────────────────────
        int gx = px + 4;
        int gy = py + 44;

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int cx = gx + col * (CELL + 1);
                int cy = gy + row * (CELL + 1);

                // Фон ячейки
                ctx.fill(cx, cy, cx + CELL, cy + CELL, C_CELL);
                border(ctx, cx, cy, CELL, CELL, C_CELL_BR);

                // Предмет
                ItemStack stack = selectedRecipe.grid[row * 3 + col];
                if (stack != null && !stack.isEmpty()) {
                    ctx.drawItem(stack, cx + 1, cy + 1);
                    ctx.drawItemInSlot(textRenderer, stack, cx + 1, cy + 1);
                }
            }
        }

        // Стрелка →
        int arrowX = gx + 3 * (CELL + 1) + 4;
        int arrowY = gy + CELL + 3;
        ctx.drawText(textRenderer, Text.literal("➜"), arrowX, arrowY, C_WHITE, false);

        // Слот результата
        int resX = arrowX + 18;
        int resY = gy + CELL / 2 - 1;
        ctx.fill(resX, resY, resX + CELL + 4, resY + CELL + 4, C_CELL);
        border(ctx, resX, resY, CELL + 4, CELL + 4, 0xFF888888);
        ctx.drawItem(selectedRecipe.result, resX + 2, resY + 2);
        ctx.drawItemInSlot(textRenderer, selectedRecipe.result, resX + 2, resY + 2);

        // ── Количество ────────────────────────────────────────────────────────
        ctx.drawText(textRenderer, Text.literal("Количество"),
                px + 4, py + 148, C_GRAY, false);

        // Число между кнопками - и +
        int amtX = guiLeft + DETAIL_X + 4 + 18;
        int amtY = guiTop  + 154 + 4;
        ctx.fill(amtX, amtY - 2, amtX + 16, amtY + 10, C_CELL);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal(String.valueOf(craftAmount)),
                amtX + 8, amtY, C_YELLOW);

        // ── Режим ─────────────────────────────────────────────────────────────
        ctx.drawText(textRenderer, Text.literal("Режим"),
                px + 4, py + 172, C_GRAY, false);
    }

    // ── Подсказки при наведении ───────────────────────────────────────────────

    private void drawTooltips(DrawContext ctx, int mx, int my) {
        if (selectedRecipe == null) return;

        int gx = guiLeft + DETAIL_X + 4;
        int gy = guiTop  + DETAIL_Y + 44;

        // Подсказки для ячеек grid
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int cx = gx + col * (CELL + 1);
                int cy = gy + row * (CELL + 1);
                ItemStack stack = selectedRecipe.grid[row * 3 + col];
                if (stack != null && !stack.isEmpty()
                        && mx >= cx && mx < cx + CELL
                        && my >= cy && my < cy + CELL) {
                    ctx.drawItemTooltip(textRenderer, stack, mx, my);
                }
            }
        }
    }

    // ── Mouse events ──────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            int px = guiLeft + LIST_X + 1;
            int py = guiTop  + LIST_Y;

            // Клик по списку
            if (mx >= px && mx < px + LIST_W - 2
                    && my >= py && my < py + LIST_H) {
                int row = (int)(my - py) / ITEM_H;
                int idx = row + scrollOffset;
                if (idx >= 0 && idx < filteredRecipes.size()) {
                    selectRecipe(idx);
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hx, double vy) {
        int px = guiLeft + LIST_X;
        int py = guiTop  + LIST_Y;

        if (mx >= px && mx < px + LIST_W && my >= py && my < py + LIST_H) {
            int maxScroll = Math.max(0, filteredRecipes.size() - VISIBLE);
            scrollOffset  = Math.max(0, Math.min(maxScroll,
                    scrollOffset - (int) Math.signum(vy)));
            return true;
        }
        return super.mouseScrolled(mx, my, hx, vy);
    }

    // ── Выбор рецепта ─────────────────────────────────────────────────────────

    private void selectRecipe(int index) {
        if (index < 0 || index >= filteredRecipes.size()) return;

        selectedIndex  = index;
        selectedRecipe = filteredRecipes.get(index);

        // Сбросить количество на 1 при смене рецепта
        craftAmount = 1;

        updateStartButton();

        net.autocraft.AutoCraftMod.LOGGER.debug(
                "[AutoCraft] Выбран рецепт: {}", selectedRecipe.displayName);
    }

    // ── Кнопка СТАРТ ─────────────────────────────────────────────────────────

    private void onStart() {
        if (selectedRecipe == null) return;

        // TODO: подключить CraftingManager на следующем шаге
        MinecraftClient.getInstance().player.sendMessage(
                Text.literal("§a[Авто крафт] Старт: "
                        + selectedRecipe.displayName
                        + " x" + craftAmount
                        + " [" + (modeFast ? "Быстрый" : "Средний") + "]"),
                false
        );

        this.close();
    }

    // ── Вспомогательные ───────────────────────────────────────────────────────

    /** Посчитать MAX — сколько раз можно скрафтить исходя из инвентаря */
    private int calcMax() {
        if (selectedRecipe == null) return 1;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 1;

        int max = Integer.MAX_VALUE;
        for (ItemStack cell : selectedRecipe.grid) {
            if (cell == null || cell.isEmpty()) continue;
            int inInv = countInInventory(client, cell);
            if (inInv == 0) return 1;
            max = Math.min(max, inInv);
        }
        return max == Integer.MAX_VALUE ? 1 : max;
    }

    private int countInInventory(MinecraftClient client, ItemStack needed) {
        int count = 0;
        for (int i = 0; i < client.player.getInventory().size(); i++) {
            ItemStack s = client.player.getInventory().getStack(i);
            if (!s.isEmpty() && ItemStack.areItemsEqual(s, needed)) {
                count += s.getCount();
            }
        }
        return count;
    }

    private void refreshModeButtons() {
        if (btnModeFast == null || btnModeMedium == null) return;
        btnModeFast.setMessage(
                modeFast ? Text.literal("§a§lБыстрый") : Text.literal("Быстрый"));
        btnModeMedium.setMessage(
                !modeFast ? Text.literal("§a§lСредний") : Text.literal("Средний"));
    }

    private void updateStartButton() {
        if (btnStart == null) return;
        btnStart.active = selectedRecipe != null;
    }

    /** Нарисовать рамку 1px вокруг прямоугольника */
    private void border(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x,         y,         x + w,     y + 1,     color);
        ctx.fill(x,         y + h - 1, x + w,     y + h,     color);
        ctx.fill(x,         y + 1,     x + 1,     y + h - 1, color);
        ctx.fill(x + w - 1, y + 1,     x + w,     y + h - 1, color);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 256) { this.close(); return true; } // Escape
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean shouldPause() { return false; }

    // ── Геттеры ───────────────────────────────────────────────────────────────

    public RecipeDisplayEntry getSelectedRecipe() { return selectedRecipe; }
    public int getCraftAmount()                   { return craftAmount; }
    public boolean isModeFast()                   { return modeFast; }
}
