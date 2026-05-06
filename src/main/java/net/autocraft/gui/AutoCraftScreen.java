package net.autocraft.gui;

import net.autocraft.recipe.CraftingRecipe;
import net.autocraft.recipe.RecipeType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * AutoCraftScreen — главный GUI мода.
 *
 * Layout (точно по концепту):
 * ┌─────────────────────────────────────────────────┐
 * │  [Авто крафт v2]                                │
 * │ ┌──────────────┐  ┌──────────────────────────┐  │
 * │ │ 🔍 Поиск...  │  │  Выбранный предмет       │  │
 * │ │──────────────│  │  [иконка] Название        │  │
 * │ │ Дубовые доски│  │                           │  │
 * │ │ Палка        │  │  Рецепт:                  │  │
 * │ │ Факел        │  │  [3x3 grid] → [результат] │  │
 * │ │ Верстак      │  │                           │  │
 * │ │ Печь         │  │  Количество: [-][64][+][MAX]│ │
 * │ │ Сундук       │  │  Режим: [Быстрый ▼]       │  │
 * │ │ ...          │  │                           │  │
 * │ └──────────────┘  │  [    СТАРТ    ]           │  │
 * │                   └──────────────────────────┘  │
 * │ [Инвентарь игрока]                              │
 * └─────────────────────────────────────────────────┘
 */
public class AutoCraftScreen extends Screen {

    // ── Размеры окна ──────────────────────────────────────────────────────────
    private static final int GUI_WIDTH  = 500;
    private static final int GUI_HEIGHT = 240;

    // Левая панель (список рецептов)
    private static final int LIST_X     = 8;
    private static final int LIST_Y     = 24;
    private static final int LIST_W     = 160;
    private static final int LIST_H     = 170;
    private static final int ITEM_H     = 18; // высота одной строки списка

    // Правая панель (детали рецепта)
    private static final int DETAIL_X   = 178;
    private static final int DETAIL_Y   = 8;
    private static final int DETAIL_W   = 314;
    private static final int DETAIL_H   = 198;

    // 3x3 grid preview внутри правой панели
    private static final int GRID_X     = 197;
    private static final int GRID_Y     = 80;
    private static final int CELL_SIZE  = 18; // размер одной ячейки сетки

    // Стрелка и результат
    private static final int ARROW_X    = 197 + CELL_SIZE * 3 + 8;
    private static final int RESULT_X   = ARROW_X + 20;
    private static final int RESULT_Y   = GRID_Y + CELL_SIZE; // центр по вертикали

    // ── Цвета ─────────────────────────────────────────────────────────────────
    private static final int COLOR_BG           = 0xFF2B2B2B; // фон окна
    private static final int COLOR_PANEL        = 0xFF3C3C3C; // фон панели
    private static final int COLOR_PANEL_BORDER = 0xFF555555; // рамка панели
    private static final int COLOR_SELECTED     = 0xFF4A7C4A; // выделенный элемент (зелёный)
    private static final int COLOR_HOVER        = 0xFF4A4A4A; // ховер на элементе
    private static final int COLOR_CELL         = 0xFF4A4040; // ячейка grid
    private static final int COLOR_CELL_BORDER  = 0xFF666666;
    private static final int COLOR_TEXT         = 0xFFFFFFFF;
    private static final int COLOR_TEXT_GRAY    = 0xFFAAAAAA;
    private static final int COLOR_TEXT_YELLOW  = 0xFFFFCC00;
    private static final int COLOR_GREEN        = 0xFF4CAF50;
    private static final int COLOR_RED          = 0xFFE53935;

    // ── Состояние экрана ──────────────────────────────────────────────────────
    private int guiLeft;
    private int guiTop;

    private final List<RecipeEntry> recipes = new ArrayList<>();
    private int selectedIndex   = -1;   // индекс выбранного рецепта
    private int scrollOffset    = 0;    // скролл списка
    private int craftAmount     = 64;   // количество для крафта
    private boolean modeFast    = true; // true=Быстрый, false=Средний

    private int hoveredIndex    = -1;   // строка под курсором
    private int mouseX          = 0;
    private int mouseY          = 0;

    // Поле поиска
    private TextFieldWidget searchField;

    // Кнопки
    private ButtonWidget btnStart;
    private ButtonWidget btnMinus;
    private ButtonWidget btnPlus;
    private ButtonWidget btnMax;
    private ButtonWidget btnModeFast;
    private ButtonWidget btnModeMedium;

    // ── Заглушка рецептов ─────────────────────────────────────────────────────
    /**
     * Одна запись в списке рецептов.
     */
    public record RecipeEntry(
            String displayName,
            ItemStack icon,
            Item[] grid9,       // 9 элементов для 3x3 (null = пусто)
            Item result,
            int resultCount
    ) {}

    // ── Конструктор ───────────────────────────────────────────────────────────

    public AutoCraftScreen() {
        super(Text.literal("Авто крафт v2"));
        initRecipes();
    }

    /**
     * Заглушка — заполняем список стандартными рецептами Minecraft.
     * Позже заменится на автоматический сбор из RecipeManager.
     */
    private void initRecipes() {
        // Дубовые доски (заглушка grid)
        recipes.add(new RecipeEntry(
                "Дубовые доски",
                new ItemStack(Items.OAK_PLANKS),
                new Item[]{Items.OAK_LOG, null, null, null, null, null, null, null, null},
                Items.OAK_PLANKS, 4
        ));
        // Палка
        recipes.add(new RecipeEntry(
                "Палка",
                new ItemStack(Items.STICK),
                new Item[]{Items.OAK_PLANKS, null, null, Items.OAK_PLANKS, null, null, null, null, null},
                Items.STICK, 4
        ));
        // Факел
        recipes.add(new RecipeEntry(
                "Факел",
                new ItemStack(Items.TORCH),
                new Item[]{Items.COAL, null, null, Items.STICK, null, null, null, null, null},
                Items.TORCH, 4
        ));
        // Верстак
        recipes.add(new RecipeEntry(
                "Верстак",
                new ItemStack(Items.CRAFTING_TABLE),
                new Item[]{
                        Items.OAK_PLANKS, Items.OAK_PLANKS, null,
                        Items.OAK_PLANKS, Items.OAK_PLANKS, null,
                        null, null, null
                },
                Items.CRAFTING_TABLE, 1
        ));
        // Печь
        recipes.add(new RecipeEntry(
                "Печь",
                new ItemStack(Items.FURNACE),
                new Item[]{
                        Items.COBBLESTONE, Items.COBBLESTONE, Items.COBBLESTONE,
                        Items.COBBLESTONE, null,              Items.COBBLESTONE,
                        Items.COBBLESTONE, Items.COBBLESTONE, Items.COBBLESTONE
                },
                Items.FURNACE, 1
        ));
        // Сундук
        recipes.add(new RecipeEntry(
                "Сундук",
                new ItemStack(Items.CHEST),
                new Item[]{
                        Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS,
                        Items.OAK_PLANKS, null,              Items.OAK_PLANKS,
                        Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS
                },
                Items.CHEST, 1
        ));
        // Каменный меч
        recipes.add(new RecipeEntry(
                "Каменный меч",
                new ItemStack(Items.STONE_SWORD),
                new Item[]{null, Items.COBBLESTONE, null, null, Items.COBBLESTONE, null, null, Items.STICK, null},
                Items.STONE_SWORD, 1
        ));
        // Каменная кирка
        recipes.add(new RecipeEntry(
                "Каменная кирка",
                new ItemStack(Items.STONE_PICKAXE),
                new Item[]{
                        Items.COBBLESTONE, Items.COBBLESTONE, Items.COBBLESTONE,
                        null,              Items.STICK,       null,
                        null,              Items.STICK,       null
                },
                Items.STONE_PICKAXE, 1
        ));
        // Стрела
        recipes.add(new RecipeEntry(
                "Стрела",
                new ItemStack(Items.ARROW),
                new Item[]{null, Items.FLINT, null, null, Items.STICK, null, null, Items.FEATHER, null},
                Items.ARROW, 4
        ));
    }

    // ── init() — создание виджетов ────────────────────────────────────────────

    @Override
    protected void init() {
        guiLeft = (this.width  - GUI_WIDTH)  / 2;
        guiTop  = (this.height - GUI_HEIGHT) / 2;

        // ── Поле поиска ───────────────────────────────────────────────────────
        searchField = new TextFieldWidget(
                this.textRenderer,
                guiLeft + LIST_X + 2,
                guiTop  + LIST_Y - 18,
                LIST_W - 20,
                14,
                Text.literal("Поиск...")
        );
        searchField.setMaxLength(32);
        searchField.setPlaceholder(Text.literal("Поиск..."));
        this.addDrawableChild(searchField);

        // ── Кнопка количества: минус ──────────────────────────────────────────
        btnMinus = ButtonWidget.builder(Text.literal("−"), btn -> {
            if (craftAmount > 1) craftAmount--;
            updateAmountDisplay();
        })
        .dimensions(guiLeft + DETAIL_X - guiLeft + 2, guiTop + 158, 16, 16)
        .build();
        this.addDrawableChild(btnMinus);

        // ── Кнопка количества: плюс ───────────────────────────────────────────
        btnPlus = ButtonWidget.builder(Text.literal("+"), btn -> {
            craftAmount++;
            updateAmountDisplay();
        })
        .dimensions(guiLeft + DETAIL_X - guiLeft + 42, guiTop + 158, 16, 16)
        .build();
        this.addDrawableChild(btnPlus);

        // ── Кнопка MAX ────────────────────────────────────────────────────────
        btnMax = ButtonWidget.builder(Text.literal("MAX"), btn -> {
            craftAmount = 64;
            updateAmountDisplay();
        })
        .dimensions(guiLeft + DETAIL_X - guiLeft + 62, guiTop + 158, 30, 16)
        .build();
        this.addDrawableChild(btnMax);

        // ── Кнопки режима: Быстрый / Средний ─────────────────────────────────
        int modeY = guiTop + 180;
        int modeX = guiLeft + DETAIL_X - guiLeft + 2;

        btnModeFast = ButtonWidget.builder(Text.literal("Быстрый"), btn -> {
            modeFast = true;
            updateModeButtons();
        })
        .dimensions(modeX, modeY, 60, 16)
        .build();
        this.addDrawableChild(btnModeFast);

        btnModeMedium = ButtonWidget.builder(Text.literal("Средний"), btn -> {
            modeFast = false;
            updateModeButtons();
        })
        .dimensions(modeX + 64, modeY, 60, 16)
        .build();
        this.addDrawableChild(btnModeMedium);

        // ── Кнопка СТАРТ ──────────────────────────────────────────────────────
        btnStart = ButtonWidget.builder(Text.literal("Старт"), btn -> onStartClick())
                .dimensions(guiLeft + DETAIL_X - guiLeft + 2, guiTop + 200, 120, 20)
                .build();
        this.addDrawableChild(btnStart);

        updateModeButtons();
        recalcButtonPositions();
    }

    // ── Пересчёт позиций кнопок относительно guiLeft/guiTop ──────────────────

    private void recalcButtonPositions() {
        int rx = guiLeft + DETAIL_X + 4;

        btnMinus.setX(rx);
        btnMinus.setY(guiTop + 160);

        btnPlus.setX(rx + 36);
        btnPlus.setY(guiTop + 160);

        btnMax.setX(rx + 56);
        btnMax.setY(guiTop + 160);

        btnModeFast.setX(rx);
        btnModeFast.setY(guiTop + 182);

        btnModeMedium.setX(rx + 64);
        btnModeMedium.setY(guiTop + 182);

        btnStart.setX(rx);
        btnStart.setY(guiTop + 202);
        btnStart.setWidth(120);

        searchField.setX(guiLeft + LIST_X + 2);
        searchField.setY(guiTop + LIST_Y - 16);
    }

    // ── render() ──────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        // Затемнение фона
        this.renderBackground(ctx, mx, my, delta);

        mouseX = mx;
        mouseY = my;

        // ── Фон всего окна ────────────────────────────────────────────────────
        ctx.fill(guiLeft, guiTop, guiLeft + GUI_WIDTH, guiTop + GUI_HEIGHT, COLOR_BG);
        drawBorder(ctx, guiLeft, guiTop, GUI_WIDTH, GUI_HEIGHT, COLOR_PANEL_BORDER);

        // Заголовок
        ctx.drawText(textRenderer,
                Text.literal("Авто крафт v2"),
                guiLeft + 8, guiTop + 8,
                COLOR_TEXT_YELLOW, false);

        // ── Левая панель: список рецептов ─────────────────────────────────────
        drawLeftPanel(ctx, mx, my);

        // ── Правая панель: детали выбранного рецепта ──────────────────────────
        drawRightPanel(ctx, mx, my);

        // Кнопки и поле поиска поверх
        super.render(ctx, mx, my, delta);
    }

    // ── Левая панель ──────────────────────────────────────────────────────────

    private void drawLeftPanel(DrawContext ctx, int mx, int my) {
        int px = guiLeft + LIST_X;
        int py = guiTop  + LIST_Y;

        // Фон панели
        ctx.fill(px, py - 18, px + LIST_W, py + LIST_H, COLOR_PANEL);
        drawBorder(ctx, px, py - 18, LIST_W, LIST_H + 18, COLOR_PANEL_BORDER);

        // Иконка поиска
        ctx.drawText(textRenderer, Text.literal("🔍"),
                px + LIST_W - 16, py - 14, COLOR_TEXT_GRAY, false);

        // Список рецептов (с учётом поиска и скролла)
        String query = searchField != null ? searchField.getText().toLowerCase() : "";

        int visibleCount = LIST_H / ITEM_H;
        int drawY = py;
        int drawnIndex = 0;

        for (int i = 0; i < recipes.size(); i++) {
            RecipeEntry entry = recipes.get(i);

            // Фильтрация по поиску
            if (!query.isEmpty() && !entry.displayName().toLowerCase().contains(query)) continue;

            // Скролл
            if (drawnIndex < scrollOffset) { drawnIndex++; continue; }
            if (drawY + ITEM_H > py + LIST_H) break;

            boolean isSelected = (i == selectedIndex);
            boolean isHovered  = mx >= px && mx < px + LIST_W
                    && my >= drawY && my < drawY + ITEM_H;

            // Фон строки
            if (isSelected) {
                ctx.fill(px + 1, drawY, px + LIST_W - 1, drawY + ITEM_H, COLOR_SELECTED);
            } else if (isHovered) {
                ctx.fill(px + 1, drawY, px + LIST_W - 1, drawY + ITEM_H, COLOR_HOVER);
            }

            // Иконка предмета
            ctx.drawItem(entry.icon(), px + 2, drawY + 1);

            // Название
            ctx.drawText(textRenderer,
                    Text.literal(entry.displayName()),
                    px + 20, drawY + 5,
                    isSelected ? COLOR_TEXT_YELLOW : COLOR_TEXT,
                    false);

            drawY += ITEM_H;
            drawnIndex++;
        }
    }

    // ── Правая панель ─────────────────────────────────────────────────────────

    private void drawRightPanel(DrawContext ctx, int mx, int my) {
        int px = guiLeft + DETAIL_X;
        int py = guiTop  + DETAIL_Y;

        // Фон панели
        ctx.fill(px, py, px + DETAIL_W, py + DETAIL_H, COLOR_PANEL);
        drawBorder(ctx, px, py, DETAIL_W, DETAIL_H, COLOR_PANEL_BORDER);

        ctx.drawText(textRenderer,
                Text.literal("Выбранный предмет"),
                px + 4, py + 4, COLOR_TEXT_GRAY, false);

        if (selectedIndex < 0 || selectedIndex >= recipes.size()) {
            // Ничего не выбрано
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("Выберите предмет из списка"),
                    px + DETAIL_W / 2, py + DETAIL_H / 2,
                    COLOR_TEXT_GRAY);
            return;
        }

        RecipeEntry entry = recipes.get(selectedIndex);

        // Иконка и название выбранного предмета
        ctx.drawItem(entry.icon(), px + 4, py + 14);
        ctx.drawText(textRenderer,
                Text.literal(entry.displayName()),
                px + 24, py + 18,
                COLOR_TEXT_YELLOW, false);

        // Подпись "Рецепт"
        ctx.drawText(textRenderer,
                Text.literal("Рецепт"),
                px + 4, py + 36, COLOR_TEXT_GRAY, false);

        // ── 3x3 grid preview ──────────────────────────────────────────────────
        int gx = px + 4;
        int gy = py + 46;

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int cellX = gx + col * (CELL_SIZE + 1);
                int cellY = gy + row * (CELL_SIZE + 1);

                // Ячейка
                ctx.fill(cellX, cellY, cellX + CELL_SIZE, cellY + CELL_SIZE, COLOR_CELL);
                drawBorder(ctx, cellX, cellY, CELL_SIZE, CELL_SIZE, COLOR_CELL_BORDER);

                // Предмет в ячейке
                int idx = row * 3 + col;
                if (idx < entry.grid9().length && entry.grid9()[idx] != null) {
                    ctx.drawItem(new ItemStack(entry.grid9()[idx]), cellX + 1, cellY + 1);
                }
            }
        }

        // Стрелка →
        int arrowX = gx + 3 * (CELL_SIZE + 1) + 6;
        int arrowY = gy + CELL_SIZE + 1;
        ctx.drawText(textRenderer, Text.literal("→"), arrowX, arrowY, COLOR_TEXT, false);

        // Слот результата
        int resX = arrowX + 16;
        int resY = gy + 1;
        ctx.fill(resX, resY, resX + CELL_SIZE + 2, resY + CELL_SIZE + 2, COLOR_CELL);
        drawBorder(ctx, resX, resY, CELL_SIZE + 2, CELL_SIZE + 2, 0xFF888888);
        ctx.drawItem(new ItemStack(entry.result()), resX + 2, resY + 2);

        // Количество результата
        if (entry.resultCount() > 1) {
            ctx.drawText(textRenderer,
                    Text.literal("x" + entry.resultCount()),
                    resX + CELL_SIZE - 6, resY + CELL_SIZE - 4,
                    COLOR_TEXT_YELLOW, true);
        }

        // ── Количество для крафта ─────────────────────────────────────────────
        ctx.drawText(textRenderer,
                Text.literal("Количество"),
                px + 4, py + 152, COLOR_TEXT_GRAY, false);

        // Отображение числа между - и +
        String amountStr = String.valueOf(craftAmount);
        ctx.fill(px + 22, py + 159, px + 54, py + 175, COLOR_PANEL_BORDER);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal(amountStr),
                px + 38, py + 162,
                COLOR_TEXT_YELLOW);

        // ── Режим ─────────────────────────────────────────────────────────────
        ctx.drawText(textRenderer,
                Text.literal("Режим"),
                px + 4, py + 174, COLOR_TEXT_GRAY, false);
    }

    // ── Mouse events ──────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // Клик по списку рецептов
        if (button == 0) {
            int px = guiLeft + LIST_X;
            int py = guiTop  + LIST_Y;

            if (mx >= px && mx < px + LIST_W && my >= py && my < py + LIST_H) {
                String query = searchField != null ? searchField.getText().toLowerCase() : "";
                int clickedRow = ((int) my - py) / ITEM_H + scrollOffset;
                int visibleIdx = 0;

                for (int i = 0; i < recipes.size(); i++) {
                    if (!query.isEmpty()
                            && !recipes.get(i).displayName().toLowerCase().contains(query)) continue;
                    if (visibleIdx == clickedRow) {
                        selectedIndex = i;
                        break;
                    }
                    visibleIdx++;
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hx, double vy) {
        // Скролл списка
        int px = guiLeft + LIST_X;
        int py = guiTop  + LIST_Y;

        if (mx >= px && mx < px + LIST_W && my >= py && my < py + LIST_H) {
            scrollOffset = Math.max(0, scrollOffset - (int) Math.signum(vy));
            return true;
        }
        return super.mouseScrolled(mx, hx, my, vy);
    }

    // ── Кнопки ────────────────────────────────────────────────────────────────

    private void onStartClick() {
        if (selectedIndex < 0 || selectedIndex >= recipes.size()) return;

        RecipeEntry entry = recipes.get(selectedIndex);

        // Передадим в CraftingManager (подключим на следующем шаге)
        // CraftingManager.getInstance().start(...)

        MinecraftClient.getInstance().player.sendMessage(
                Text.literal("§a[Авто крафт] Старт: " + entry.displayName()
                        + " x" + craftAmount
                        + " [" + (modeFast ? "Быстрый" : "Средний") + "]"),
                false
        );

        this.close();
    }

    private void updateAmountDisplay() {
        craftAmount = Math.max(1, Math.min(craftAmount, 9999));
    }

    private void updateModeButtons() {
        // Визуально выделяем активный режим (меняем текст кнопки)
        if (btnModeFast != null) {
            btnModeFast.setMessage(
                    modeFast ? Text.literal("§a§lБыстрый") : Text.literal("Быстрый")
            );
        }
        if (btnModeMedium != null) {
            btnModeMedium.setMessage(
                    !modeFast ? Text.literal("§a§lСредний") : Text.literal("Средний")
            );
        }
    }

    // ── Keyboard ──────────────────────────────────────────────────────────────

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Закрытие по Escape
        if (keyCode == 256) {
            this.close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false; // не ставить игру на паузу при открытии GUI
    }

    // ── Вспомогательные ───────────────────────────────────────────────────────

    /**
     * Нарисовать рамку вокруг прямоугольника.
     */
    private void drawBorder(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x,         y,         x + w,     y + 1,     color); // top
        ctx.fill(x,         y + h - 1, x + w,     y + h,     color); // bottom
        ctx.fill(x,         y,         x + 1,     y + h,     color); // left
        ctx.fill(x + w - 1, y,         x + w,     y + h,     color); // right
    }

    // ── Геттеры (для других классов) ─────────────────────────────────────────

    public int getSelectedIndex()       { return selectedIndex; }
    public int getCraftAmount()         { return craftAmount; }
    public boolean isModeFast()         { return modeFast; }
    public List<RecipeEntry> getRecipes() { return recipes; }
}
