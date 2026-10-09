package com.maidsmart.craft;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * CraftPlanner —— 委托合成的「配方树展开器」（委托合成 v1 新增）。
 *
 * <p>【职责】给定「目标物品 + 数量」，从服务端 {@link RecipeManager} 的全量配方里
 * 递归展开出一条可执行的合成链，产出三样东西：
 * <ol>
 *   <li>{@link Plan#steps}：**中间合成步骤表**（按依赖排序——先做子材料、再做上级，执行时从前到后跑）；</li>
 *   <li>{@link Plan#needs}：**基础材料需求表**（不再展开的叶子节点）；</li>
 *   <li>{@link Plan#missing}：与库存对账后的**缺口清单**（附「可采集」标记，供采集调度决策）。</li>
 * </ol>
 *
 * <p>【展开规则（v1 口径，其余类型留给后续版本）】
 * <ul>
 *   <li>只认**原版合成台配方**（{@code RecipeType.CRAFTING}，有序/无序都收）——烧炼/酿造
 *       等类型不展开（铁锭这类需加工的中间品 v1 视作「不可得」，如实报告给玩家）；</li>
 *   <li>深度上限（{@link #DEFAULT_MAX_DEPTH}，参数可调）：到达上限的节点直接降级为叶子需求；</li>
 *   <li>**防环**：把「全部候选都已在展开栈上」的配方整体弃用（矿石块 ↔ 锭/粒 这类可逆配方
 *       由此被自然截断，不会展开成 9 锭→块→9 锭 的空转）；某物品的配方全被弃用时降级为叶子；
 *       递归栈直接命中同样降级为叶子（双保险）；</li>
 *   <li>**分支选择**（Ingredient 可能是多候选 tag，如「任意木板」）：优先池里现有货的候选
 *       （存量最多者）→ 其次「可采集」候选 → 最后取第一个。展开按具体候选走，
 *       对账按**候选集合**宽松匹配（库存里有任意等价候选都算数）；</li>
 *   <li>**多配方**：浅评分选择——「材料在库存池里可得」的格数越多分越高（能用手头材料做的
 *       变体优先），同分取先扫到的；</li>
 *   <li>上限保护：展开节点 ≤ {@link #MAX_NODES}、合成步骤 ≤ {@link #MAX_STEPS}，
 *       超限整单判失败（reason 写清楚），绝不跑飞。</li>
 * </ul>
 *
 * <p>【物品守恒】本类**只读**：不消耗任何物品、不修改任何容器——只产出计划与缺口。
 * 实际的取料 / 合成 / 交付由 CraftOrderManager 按计划执行（照弹药补给与智能合成的守恒口径）。
 */
public final class CraftPlanner {
    private CraftPlanner() {
    }

    /** 默认递归深度上限（配置项可调） */
    public static final int DEFAULT_MAX_DEPTH = 3;
    /** 单次委托的合成步骤上限（防展开爆炸） */
    public static final int MAX_STEPS = 64;
    /** 展开节点上限（含叶子；防爆的粗粒度护栏） */
    public static final int MAX_NODES = 512;
    /** 单次委托数量上限（保险钳制；正常由配置项在更外层拦住） */
    public static final int MAX_COUNT = 4096;

    /* ============================ 输出模型 ============================ */

    /**
     * 一条材料需求（叶子节点）。
     * {@link #candidates} 是「等价候选」——对账时任一候选的库存都算数（tag 宽松匹配）。
     */
    public static final class Need {
        /** 需求物品（分支选择的代表；报告展示用） */
        public final Item item;
        /** 等价候选（含代表自身；非 tag 配方时只有一个） */
        public final List<Item> candidates;
        /** 需求数量 */
        public final int count;
        /** 可采集标记（candidates 任一命中调用方给的可采判定） */
        public final boolean gatherable;

        Need(Item item, List<Item> candidates, int count, boolean gatherable) {
            this.item = item;
            this.candidates = candidates;
            this.count = count;
            this.gatherable = gatherable;
        }
    }

    /**
     * 一条加工步骤——两种：
     * <ul>
     *   <li>{@code CRAFT}：把 {@link #ingredients} 每格各 1 个、按原版合成台口径合成 {@link #times} 次；</li>
     *   <li>{@code SMELT}：把 {@link #ingredients} 第 0 格的物品 {@link #times} 份放进熔炉烧炼
     *       （燃料由执行端从背包补，见 CraftOrderManager；V2-A 新增）。</li>
     * </ul>
     * 执行顺序 = {@link Plan#steps} 表内从前到后。
     */
    public static final class Step {
        public enum Kind { CRAFT, SMELT }

        /** 步骤类型 */
        public final Kind kind;
        /** 配方输出（含每次产出个数；执行时按 copy() 使用） */
        public final ItemStack output;
        /** 加工次数（CRAFT=合成次数；SMELT=烧炼份数） */
        public final int times;
        /** 材料（CRAFT=每格 1 个；SMELT=第 0 格为输入，每份 1 个） */
        public final List<Ingredient> ingredients;

        Step(Kind kind, ItemStack output, int times, List<Ingredient> ingredients) {
            this.kind = kind;
            this.output = output;
            this.times = times;
            this.ingredients = ingredients;
        }
    }

    /** 一份完整计划（= 展开器 + 对账的全部产出）。 */
    public static final class Plan {
        /** 是否可制作（有完整配方链且展开没超限） */
        public final boolean craftable;
        /** 不可制作时的原因（craftable = true 时为 null） */
        public final String failReason;
        /** 中间合成步骤表（依赖序） */
        public final List<Step> steps;
        /** 基础材料需求表（叶子） */
        public final List<Need> needs;
        /** 对账后的缺口清单（顺序与 needs 一致） */
        public final List<Need> missing;
        /** 目标物品（回执） */
        public final Item target;
        /** 目标数量（回执；已钳制） */
        public final int targetCount;

        Plan(boolean craftable, String failReason, List<Step> steps, List<Need> needs,
             List<Need> missing, Item target, int targetCount) {
            this.craftable = craftable;
            this.failReason = failReason;
            this.steps = steps;
            this.needs = needs;
            this.missing = missing;
            this.target = target;
            this.targetCount = targetCount;
        }

        /** 一切就绪：配方链完整 + 缺口为空（可以直接开做）。 */
        public boolean complete() {
            return craftable && missing.isEmpty();
        }

        /** 合并同类缺口（按代表物品合并计数），供气泡 / 界面展示。 */
        public List<Need> missingMerged() {
            List<Need> out = new ArrayList<>();
            for (Need m : missing) {
                int idx = -1;
                for (int i = 0; i < out.size(); i++) {
                    if (out.get(i).item == m.item) {
                        idx = i;
                        break;
                    }
                }
                if (idx >= 0) {
                    Need o = out.get(idx);
                    out.set(idx, new Need(o.item, o.candidates, o.count + m.count, o.gatherable));
                } else {
                    out.add(new Need(m.item, m.candidates, m.count, m.gatherable));
                }
            }
            return out;
        }
    }

    /* ============================ 入口 ============================ */

    /**
     * 展开一份计划。
     *
     * @param recipes    服务端 RecipeManager
     * @param access     RegistryAccess（配方结果物品解析用）
     * @param target     目标物品
     * @param count      目标数量（钳进 1..{@link #MAX_COUNT}）
     * @param maxDepth   递归深度上限（< 1 时取 {@link #DEFAULT_MAX_DEPTH} 与 1 的较大值）
     * @param stock      库存池快照（女仆背包 + 主人附近 + 工作点附近的箱子；调用方搜完传入）
     * @param gatherable 可采集判定（candidates 任一命中即标可采；采集执行在后续版本）
     */
    public static Plan plan(RecipeManager recipes, RegistryAccess access, Item target, int count,
                            int maxDepth, Map<Item, Integer> stock, Predicate<Item> gatherable) {
        int want = Math.max(1, Math.min(count, MAX_COUNT));
        Map<Item, Integer> pool = stock == null ? Map.of() : stock;
        Predicate<Item> gather = gatherable == null ? i -> false : gatherable;
        Expander ex = new Expander(recipes, access, Math.max(1, maxDepth), pool, gather);
        ex.expandTop(target, want);
        if (!ex.craftable) {
            return new Plan(false, ex.failReason == null ? "没有可用的合成配方" : ex.failReason,
                    List.of(), List.of(), List.of(), target, want);
        }
        List<Need> missing = reconcile(ex.needs, pool, gather);
        return new Plan(true, null, ex.steps, ex.needs, missing, target, want);
    }

    /**
     * 库存对账：按 needs 顺序从池里「消耗」等价候选，产出缺口清单。
     * 顺序消耗保证同一批库存不会被两条需求重复计数（总缺口正确）。
     */
    private static List<Need> reconcile(List<Need> needs, Map<Item, Integer> stock, Predicate<Item> gatherable) {
        Map<Item, Integer> pool = new HashMap<>(stock);
        List<Need> missing = new ArrayList<>();
        for (Need n : needs) {
            int have = 0;
            for (Item c : n.candidates) {
                have += pool.getOrDefault(c, 0);
            }
            if (have < n.count) {
                missing.add(new Need(n.item, n.candidates, n.count - have, n.gatherable));
            }
            int left = Math.min(n.count, have);
            for (Item c : n.candidates) {
                if (left <= 0) {
                    break;
                }
                int avail = pool.getOrDefault(c, 0);
                int take = Math.min(avail, left);
                if (take > 0) {
                    pool.put(c, avail - take);
                    left -= take;
                }
            }
        }
        return missing;
    }

    /* ============================ 展开器 ============================ */

    private static final class Expander {
        /** 产物物品 → 配方列表（一次全量枚举建好，避免展开中反复 O(N) 扫描） */
        private final Map<Item, List<CraftingRecipe>> byOutput = new HashMap<>();
        /** V2-A：产物物品 → 熔炼配方（SMELTING；一物一炉通常只有一个，取先扫到的） */
        private final Map<Item, SmeltingRecipe> bySmelt = new HashMap<>();
        private final RegistryAccess access;
        private final int maxDepth;
        private final Map<Item, Integer> stock;
        private final Predicate<Item> gatherable;

        private final List<Step> steps = new ArrayList<>();
        private final List<Need> needs = new ArrayList<>();
        private final Set<Item> stack = new HashSet<>();

        private boolean craftable = false;
        private String failReason = null;
        private int nodes = 0;

        Expander(RecipeManager recipes, RegistryAccess access, int maxDepth,
                 Map<Item, Integer> stock, Predicate<Item> gatherable) {
            this.access = access;
            this.maxDepth = maxDepth;
            this.stock = stock;
            this.gatherable = gatherable;
            // 一次全量枚举、按产物物品建索引（与弹药补给同口径：CRAFTING 类型全量扫）
            for (CraftingRecipe r : recipes.m_44013_(RecipeType.f_44107_)) {
                ItemStack out = r.m_8043_(access);
                if (out.m_41619_()) {
                    continue;
                }
                byOutput.computeIfAbsent(out.m_41720_(), k -> new ArrayList<>()).add(r);
            }
            // V2-A：熔炼索引（SMELTING 类型全量扫；一物取先扫到的那个配方）
            for (SmeltingRecipe r : recipes.m_44013_(RecipeType.f_44108_)) {
                ItemStack out = r.m_8043_(access);
                if (out.m_41619_()) {
                    continue;
                }
                bySmelt.putIfAbsent(out.m_41720_(), r);
            }
        }

        void expandTop(Item target, int count) {
            boolean hasCraft = chooseRecipe(target) != null;
            boolean hasSmelt = smeltEnabled() && !gatherable.test(target) && chooseSmelt(target) != null;
            if (!hasCraft && !hasSmelt) {
                failReason = "没有可用的配方（合成/熔炼都没有）";
                craftable = false;
                return;
            }
            expand(target, count, 0, null);
            craftable = failReason == null;
        }

        /** 递归展开一个物品的 count 个需求。via = 从父配方哪个材料格下来的（顶层为 null）。 */
        private void expand(Item item, int count, int depth, Ingredient via) {
            if (count <= 0) {
                return;
            }
            if (++nodes > MAX_NODES) {
                failReason = "配方树过大（超过 " + MAX_NODES + " 个节点）";
                return;
            }
            if (stack.contains(item)) {
                leaf(item, count, via);
                return;
            }
            CraftingRecipe r = null;
            SmeltingRecipe sm = null;
            if (depth < maxDepth) {
                r = chooseRecipe(item);
                sm = smeltEnabled() ? chooseSmelt(item) : null;
                // V2-A：自身可采的物品保持「直接采集」（白名单都是基础材料，与 v1 行为一致），
                // 不因熔炼配方改道；其余物品在「合成台」与「熔炼」之间比材料可得度（库存 ∪ 可采），
                // 平手时合成台优先
                if (sm != null && gatherable.test(item)) {
                    sm = null;
                }
                if (r != null && sm != null) {
                    if (scoreOfSmelt(sm) > scoreOfCraft(r)) {
                        r = null;
                    } else {
                        sm = null;
                    }
                }
            }
            if (r == null && sm == null) {
                leaf(item, count, via);
                return;
            }
            if (sm != null) {
                expandSmelt(item, count, depth, via, sm);
                return;
            }
            if (steps.size() >= MAX_STEPS) {
                failReason = "合成步骤过多（超过 " + MAX_STEPS + " 步）";
                return;
            }
            stack.add(item);
            ItemStack out = r.m_8043_(access);
            int outCount = Math.max(1, out.m_41613_());
            int times = (count + outCount - 1) / outCount;
            List<Ingredient> ings = new ArrayList<>();
            for (Ingredient ing : r.m_7527_()) {
                if (ing == null || ing.m_43908_().length == 0) {
                    continue; // 有序配方的空槽（Ingredient.EMPTY）跳过——照 SmartCraftTool 口径
                }
                ings.add(ing);
                expand(chooseBranch(ing), times, depth + 1, ing);
                if (failReason != null) {
                    stack.remove(item);
                    return;
                }
            }
            // 后序添加：子材料步骤已在前，当前步骤押后——执行时从前到后即依赖序
            steps.add(new Step(Step.Kind.CRAFT, out.m_41777_(), times, ings));
            stack.remove(item);
        }

        /** 降级为叶子需求（记录等价候选集与可采标记）。 */
        private void leaf(Item item, int count, Ingredient via) {
            List<Item> cands = new ArrayList<>();
            if (via != null) {
                for (ItemStack s : via.m_43908_()) {
                    Item it = s.m_41720_();
                    if (it != null && !cands.contains(it)) {
                        cands.add(it);
                    }
                }
            }
            if (cands.isEmpty()) {
                cands.add(item);
            }
            boolean g = false;
            for (Item c : cands) {
                if (gatherable.test(c)) {
                    g = true;
                    break;
                }
            }
            needs.add(new Need(item, cands, count, g));
        }

        /** 选配方：弃用引入环的；其余按「材料在池里可得」的格数浅评分，分高者胜。 */
        private CraftingRecipe chooseRecipe(Item item) {
            List<CraftingRecipe> list = byOutput.get(item);
            if (list == null || list.isEmpty()) {
                return null;
            }
            CraftingRecipe best = null;
            int bestScore = -1;
            for (CraftingRecipe r : list) {
                if (introducesCycle(r)) {
                    continue;
                }
                int score = 0;
                for (Ingredient ing : r.m_7527_()) {
                    if (ing == null || ing.m_43908_().length == 0) {
                        continue;
                    }
                    if (poolHas(ing)) {
                        score++;
                    }
                }
                if (score > bestScore) {
                    best = r;
                    bestScore = score;
                }
            }
            return best;
        }

        /** 环判定：某个材料格的**全部候选**都已在展开栈上 → 该配方弃用（防 9 锭↔块 式空转）。 */
        private boolean introducesCycle(CraftingRecipe r) {
            return introducesCycle(r.m_7527_());
        }

        /** 环判定的通用版（V2-A：熔炼配方同样适用） */
        private boolean introducesCycle(List<Ingredient> list) {
            for (Ingredient ing : list) {
                if (ing == null || ing.m_43908_().length == 0) {
                    continue;
                }
                boolean allOnStack = true;
                for (ItemStack s : ing.m_43908_()) {
                    if (!stack.contains(s.m_41720_())) {
                        allOnStack = false;
                        break;
                    }
                }
                if (allOnStack) {
                    return true;
                }
            }
            return false;
        }

        /* ==================== V2-A：熔炼路径 ==================== */

        /** 熔炼路径选择：有配方 + 不引入环；没有返回 null */
        private SmeltingRecipe chooseSmelt(Item item) {
            SmeltingRecipe r = bySmelt.get(item);
            if (r == null) {
                return null;
            }
            return introducesCycle(r.m_7527_()) ? null : r;
        }

        private boolean smeltEnabled() {
            try {
                return com.maidsmart.config.MaidSmartConfig.CRAFT_ORDER_SMELT_ENABLED.get();
            } catch (Throwable t) {
                return true;
            }
        }

        /** 材料可得度（库存有 ∪ 可采集）——合成/熔炼路径比较用 */
        private boolean obtainable(Ingredient ing) {
            for (ItemStack s : ing.m_43908_()) {
                Item it = s.m_41720_();
                if (stock.getOrDefault(it, 0) > 0 || gatherable.test(it)) {
                    return true;
                }
            }
            return false;
        }

        private int scoreOfCraft(CraftingRecipe r) {
            int score = 0;
            for (Ingredient ing : r.m_7527_()) {
                if (ing == null || ing.m_43908_().length == 0) {
                    continue;
                }
                if (obtainable(ing)) {
                    score++;
                }
            }
            return score;
        }

        private int scoreOfSmelt(SmeltingRecipe r) {
            int score = 0;
            for (Ingredient ing : r.m_7527_()) {
                if (ing == null || ing.m_43908_().length == 0) {
                    continue;
                }
                if (obtainable(ing)) {
                    score++;
                }
            }
            if (fuelInStock()) {
                score++; // 燃料粗判（执行端还会精确补/报）
            }
            return score;
        }

        /** 库存里有没有可用燃料（isFuel 且无耐久——不把装备当柴烧） */
        private boolean fuelInStock() {
            try {
                for (Item it : stock.keySet()) {
                    ItemStack st = new ItemStack(it);
                    if (net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.m_58399_(st)
                            && st.m_41776_() <= 0) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
            return false;
        }

        /** V2-A：熔炼展开——输入（每份 1 个）继续递归，产出一条 SMELT 步骤 */
        private void expandSmelt(Item item, int count, int depth, Ingredient via, SmeltingRecipe r) {
            if (++nodes > MAX_NODES) {
                failReason = "配方树过大（超过 " + MAX_NODES + " 个节点）";
                return;
            }
            ItemStack out = r.m_8043_(access);
            int outCount = Math.max(1, out.m_41613_());
            int times = (count + outCount - 1) / outCount;
            if (steps.size() >= MAX_STEPS) {
                failReason = "合成步骤过多（超过 " + MAX_STEPS + " 步）";
                return;
            }
            stack.add(item);
            List<Ingredient> ings = new ArrayList<>();
            for (Ingredient ing : r.m_7527_()) {
                if (ing == null || ing.m_43908_().length == 0) {
                    continue;
                }
                ings.add(ing);
                expand(chooseBranch(ing), times, depth + 1, ing);
                if (failReason != null) {
                    stack.remove(item);
                    return;
                }
            }
            steps.add(new Step(Step.Kind.SMELT, out.m_41777_(), times, ings));
            stack.remove(item);
        }

        private boolean poolHas(Ingredient ing) {
            for (ItemStack s : ing.m_43908_()) {
                if (stock.getOrDefault(s.m_41720_(), 0) > 0) {
                    return true;
                }
            }
            return false;
        }

        /** 分支选择：池里存量最多的候选 → 可采集候选 → 第一个。 */
        private Item chooseBranch(Ingredient ing) {
            ItemStack[] opts = ing.m_43908_();
            Item best = null;
            int bestCount = 0;
            for (ItemStack s : opts) {
                int c = stock.getOrDefault(s.m_41720_(), 0);
                if (c > bestCount) {
                    best = s.m_41720_();
                    bestCount = c;
                }
            }
            if (best != null) {
                return best;
            }
            for (ItemStack s : opts) {
                if (gatherable.test(s.m_41720_())) {
                    return s.m_41720_();
                }
            }
            return opts[0].m_41720_();
        }
    }
}
