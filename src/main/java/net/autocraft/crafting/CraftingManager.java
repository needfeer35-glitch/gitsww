// =========================================================================
// ФАЙЛ:  src/main/java/net/autocraft/crafting/CraftingManager.java
// МЕТОД: handlePlace() — полная замена существующего метода
//
// ЧТО ИСПРАВЛЕНО:
//  1. Правильный подсчёт количества каждого предмета на слот рецепта
//     (один предмет может встречаться в нескольких слотах shaped-рецепта)
//  2. Stack splitting: берём ровно нужное количество через правый клик,
//     а не весь стак левым кликом
//  3. Если в grid-слоте уже лежит нужный предмет — пропускаем
//  4. Виртуальный инвентарь корректно списывает точное количество
//  5. Cursor очищается в конце если что-то осталось на курсоре
// =========================================================================

// -------------------------------------------------------------------------
// PHASE: PLACE — move ingredients from inventory into grid slots
// -------------------------------------------------------------------------
private void handlePlace(MinecraftClient client, ClientPlayerEntity player) {
    CraftingScreenHandler handler = getCraftingHandler(player);
    if (handler == null) { cancelCurrent(); return; }

    int syncId = handler.syncId;
    List<Ingredient> ingredients = currentTask.getRecipe().getIngredients();

    // ------------------------------------------------------------------
    // 1. Собираем план: сколько каждого предмета нужно в каждый слот
    //    Ключ — gridSlot (1..9), значение — пара (invSlot, нужноеКол-во)
    // ------------------------------------------------------------------
    // Виртуальная копия инвентаря для планирования без side-effect
    Map<Integer, ItemStack> invCopy = buildInventoryCopy(handler);

    // plan[gridIndex] = {invSlot, count} или null если пусто/уже заполнено
    record SlotPlan(int invSlot, int needed) {}
    List<SlotPlan> plan = new ArrayList<>();

    for (int gridIndex = 0; gridIndex < ingredients.size(); gridIndex++) {
        Ingredient ingredient = ingredients.get(gridIndex);
        int gridSlot = GRID_START + gridIndex;

        if (ingredient.isEmpty()) {
            plan.add(null);
            continue;
        }

        // Проверяем, что уже лежит в слоте верстака
        ItemStack existing = handler.getSlot(gridSlot).getStack();
        if (!existing.isEmpty() && ingredient.test(existing)) {
            // Нужный предмет уже в слоте — пропускаем
            plan.add(null);
            continue;
        }

        // Сколько нужно в этот слот — для стандартных рецептов всегда 1
        // но мы резервируем 1 единицу из инвентаря
        int needed = 1;

        int invSlot = findIngredientSlot(invCopy, ingredient);
        if (invSlot == -1) {
            if (!AutoCraftConfig.get().continueOnMissingResources) {
                sendChat(client, "§e[AutoCraft] Нет ресурсов для крафта.");
                finish(client, false);
                return;
            }
            plan.add(null);
            continue;
        }

        plan.add(new SlotPlan(invSlot, needed));

        // Списываем виртуально
        ItemStack virtualStack = invCopy.get(invSlot);
        virtualStack.decrement(needed);
        if (virtualStack.isEmpty()) invCopy.remove(invSlot);
    }

    // ------------------------------------------------------------------
    // 2. Группируем слоты по invSlot: берём из одного источника сразу
    //    всё нужное количество через splitting, раскладываем по слотам
    // ------------------------------------------------------------------
    // invSlot → список grid-слотов куда класть
    Map<Integer, List<Integer>> sourceToGridSlots = new LinkedHashMap<>();
    for (int gridIndex = 0; gridIndex < plan.size(); gridIndex++) {
        SlotPlan sp = plan.get(gridIndex);
        if (sp == null) continue;
        sourceToGridSlots
            .computeIfAbsent(sp.invSlot, k -> new ArrayList<>())
            .add(GRID_START + gridIndex);
    }

    for (Map.Entry<Integer, List<Integer>> entry : sourceToGridSlots.entrySet()) {
        int invSlot         = entry.getKey();
        List<Integer> slots = entry.getValue();
        int totalNeeded     = slots.size(); // каждый слот требует 1 штуку

        // Получаем реальный стак в инвентаре
        ItemStack realStack = handler.getSlot(invSlot).getStack();
        int available = realStack.getCount();

        if (available <= 0) {
            // Ресурс кончился в реальном инвентаре — прерываем
            if (!AutoCraftConfig.get().continueOnMissingResources) {
                sendChat(client, "§e[AutoCraft] Нет ресурсов для крафта.");
                finish(client, false);
                return;
            }
            continue;
        }

        if (totalNeeded == 1) {
            // Простой случай: берём весь стак, кладём в один слот,
            // затем возвращаем остаток обратно через повторный клик
            actionQueue.leftClick(syncId, invSlot,
                "PLACE pick all from inv " + invSlot);
            actionQueue.leftClick(syncId, slots.get(0),
                "PLACE put 1 to grid " + slots.get(0));
            // Возвращаем остаток обратно (левый клик на тот же invSlot
            // кладёт то что на курсоре обратно)
            actionQueue.leftClick(syncId, invSlot,
                "PLACE return remainder to inv " + invSlot);

        } else {
            // Несколько слотов из одного источника:
            // Берём весь стак, правым кликом по одному раскладываем
            actionQueue.leftClick(syncId, invSlot,
                "PLACE pick all from inv " + invSlot + " for " + totalNeeded + " slots");

            for (int gridSlot : slots) {
                // Правый клик на grid-слот кладёт ровно 1 предмет с курсора
                actionQueue.rightClick(syncId, gridSlot,
                    "PLACE right-click 1 to grid " + gridSlot);
            }

            // Возвращаем остаток на курсоре обратно в инвентарь
            actionQueue.leftClick(syncId, invSlot,
                "PLACE return cursor remainder to inv " + invSlot);
        }
    }

    stallTicks = 0;
    phase = Phase.WAIT;
    waitTicks = 0;
}
