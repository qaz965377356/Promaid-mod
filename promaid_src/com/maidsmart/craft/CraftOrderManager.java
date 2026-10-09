package com.maidsmart.craft;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.InvWrapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * CraftOrderManager —— 委托合成的服务端调度器（委托合成 v1 新增）。
 *
 * <p>【玩家需求（规格）】「使女仆能帮忙制作玩家所选的某个物品或者道具或者方块。通过一个界面
 * 进行选择，选择后，女仆根据主人附近或者工作区域附近的箱子的材料情况，获取合适的材料并帮忙
 * 合成，完成指定数量后传送到主人旁边。如果箱子中只有部分材料，但是缺失的材料是可采集的，
 * 女仆可以主动到地点去采集后，返回合成。」＋补充「一些简单场景的缺失材料，应该女仆要支持
 * 传送过去进行采集。」
 *
 * <p>【架构：任务 = 调度の"家"】任务 {@code maid_smart:craft} 本身不带行为——状态机由本类
 * （MaidTickEvent 服务）驱动（与 {@code AutoMineManager} 同构）：
 * <pre>
 * ANALYZE  配方树展开 + 存量对账（女仆背包 + 主人附近 + 自身附近箱子）
 *   ├─ 缺口空       → FETCH（走箱把料搬进背包）→ CRAFT
 *   ├─ 缺口含可采    → GATHER（找点→传送→采集→传送回）→ 回 ANALYZE
 *   └─ 缺口含不可采  → STALL（挂起 + 气泡列清单；补料后每秒自动重跑 ANALYZE）
 * CRAFT    按计划逐级合成（节拍 craftInterval；材料只从她背包抽——守恒不凭空）
 * DELIVER  传送回主人身边 → 交付成品（塞不进留她背包）→ 气泡 → 清委托
 * </pre>
 *
 * <p>【中断语义（全部照既有口径，不自创）】
 * <ul>
 *   <li>玩家手动改任务 = 交还控制权（委托作废，状态丢弃；与 AutoMine 同口径）；</li>
 *   <li>近敌 → 切 TLM 原生攻击任务（AutoCombatSwitch 已接管则让位），安全 5 秒切回本阶段任务；</li>
 *   <li>走位打不赢（跟随任务抢 WALK_TARGET）→ 超时诚实回报"走不过去"（弹药补给同款）；</li>
 *   <li>传送 = MaidHeatEscape.teleportSelf 同款链路（清摔落/清速度/音效/属性包）。</li>
 * </ul>
 *
 * <p>【物品守恒】取料只取需要的数量；合成逐次真实消耗背包材料；交付只交"本次做的量"
 * （开工时记基线，只交增量）。全链路不凭空生成、不吞物品。
 */
public final class CraftOrderManager {

    private CraftOrderManager() {
    }

    static final ResourceLocation CRAFT_UID = ResourceLocation.parse("maid_smart:craft");
    static final ResourceLocation MINE_UID = ResourceLocation.parse("maid_smart:mine");
    static final ResourceLocation WOOD_UID = ResourceLocation.parse("maid_smart:woodcut");
    /** TLM 原生攻击任务（javap 实证 TaskAttack 的 uid 常量：touhou_little_maid + attack） */
    static final ResourceLocation ATTACK_UID = new ResourceLocation("touhou_little_maid", "attack");

    /** 到箱判定（平方） */
    private static final double REACH_SQ = 9.0;
    /** 开盖等待（tick） */
    private static final int OPEN_WAIT_TICKS = 8;
    /** 战斗后连续安全多少 ms 切回 */
    private static final long COMBAT_SAFE_MS = 5000L;
    /** 采集点走失判定（被死亡回城/意外传送） */
    private static final double SITE_LOST_DIST_SQ = 128.0 * 128.0;
    /** 找点采样距离两档 */
    private static final int[] GATHER_DISTANCES = {24, 48};

    private enum Phase { ANALYZE, FETCH, GATHER, CRAFT, DELIVER, STALL }

    /** 一条取料任务：去某箱子取 candidates 中的任意物品 count 个 */
    private static final class Fetch {
        final BlockPos pos;
        final List<Item> candidates;
        final int count;

        Fetch(BlockPos pos, List<Item> candidates, int count) {
            this.pos = pos;
            this.candidates = candidates;
            this.count = count;
        }
    }

    private static final class State {
        CraftOrder order;
        CraftPlanner.Plan plan;
        Phase phase = Phase.ANALYZE;
        /** 开工时她背包里目标物品的基线数（交付只交增量） */
        int baseTargetCount;
        /** V2-C：排队中的后续委托（当前单在 order，排队的在这里） */
        final java.util.ArrayDeque<CraftOrder> pendingOrders = new java.util.ArrayDeque<>();
        /** FETCH */
        final List<Fetch> queue = new ArrayList<>();
        BlockPos targetChest;
        boolean chestOpened;
        int openTicks;
        long walkDeadline;
        /** CRAFT */
        int stepIndex;
        int stepDone;
        long nextCraftTick;
        /** V2-A：熔炼子状态（当前熔炉、已收取数、限时） */
        BlockPos meltFurnace;
        int meltCollected;
        long meltDeadline;
        /** GATHER */
        GatherHook.Kind gatherKind;
        List<Item> gatherCandidates = List.of();
        int gatherCount;
        long gatherDeadline;
        BlockPos gatherSite;
        BlockPos gatherBack;
        /** 战斗打断 */
        boolean combatByMe;
        long safeSince;
        ResourceLocation resumeTask;
        /** 气泡节流（30 秒） */
        long lastBubble = -100000L;
        String lastBubbleText = "";

        boolean busy() {
            return order != null;
        }
    }

    private static final Map<EntityMaid, State> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    /* ==================== 下单 / 取消（网络包入口） ==================== */

    /** 下单。返回空串 = 成功；否则是给界面/聊天框显示的原因。 */
    public static String submit(ServerPlayer player, EntityMaid maid, Item target, int count, boolean allowGather) {
        try {
            if (!com.maidsmart.config.MaidSmartConfig.CRAFT_ORDER_ENABLE.get()) {
                return "委托合成已被关闭（配置面板 → 生产与工作 → 委托合成）";
            }
            if (maid == null || !maid.m_6084_()) {
                return "找不到这位女仆";
            }
            if (maid.m_269323_() != player) {
                return "她不是你的女仆";
            }
            if (target == null) {
                return "没选物品";
            }
            int want = Math.max(1, Math.min(count,
                    com.maidsmart.config.MaidSmartConfig.CRAFT_ORDER_MAX_COUNT.get()));
            long nowGame = maid.m_9236_() instanceof ServerLevel lv ? lv.m_46467_() : 0L;
            CraftOrder newOrder = new CraftOrder(player.m_20148_(), target,
                    Math.min(want, CraftPlanner.MAX_COUNT), allowGather, nowGame);
            State old = STATES.get(maid);
            if (old != null && old.busy()) {
                // V2-C：已有委托在跑 → 排队（上限 queueLimit）
                int limit = Math.max(1, com.maidsmart.config.MaidSmartConfig.CRAFT_ORDER_QUEUE_LIMIT.get());
                if (old.pendingOrders.size() >= limit) {
                    return "她的委托队列满了（最多 " + limit + " 张排队）";
                }
                old.pendingOrders.add(newOrder);
                sayBubbleRare(maid, old, "又接了一张：「" + name(target) + "」×" + newOrder.count
                        + "（排在第 " + old.pendingOrders.size() + " 位）～");
                return "";
            }
            State s = STATES.computeIfAbsent(maid, k -> new State());
            s.order = newOrder;
            s.plan = null;
            s.phase = Phase.ANALYZE;
            s.queue.clear();
            s.targetChest = null;
            s.meltFurnace = null;
            s.meltCollected = 0;
            s.stepIndex = 0;
            s.stepDone = 0;
            s.combatByMe = false;
            s.baseTargetCount = countInInv(maid.getAvailableBackpackInv(), List.of(target));
            if (!switchTask(maid, CRAFT_UID)) {
                s.order = null;
                return "她正忙（任务切换失败），稍后再试";
            }
            say(maid, s, "收到委托：「" + name(target) + "」×" + s.order.count + "，我去看看材料～");
            com.maidsmart.tool.PromaidLog.log("委托合成",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 接单：" + name(target) + "×" + s.order.count);
            return "";
        } catch (Throwable t) {
            return "下单失败：" + t;
        }
    }

    /** 取消当前委托（V2-C：有排队则自动接下一张）。返回是否真的取消了。 */
    public static boolean cancel(EntityMaid maid) {
        try {
            State s = STATES.get(maid);
            if (s == null || !s.busy()) {
                return false;
            }
            GatherHook.end(maid);
            if (!s.pendingOrders.isEmpty()) {
                advanceToNext(maid, s, "这张先取消，接下一张～");
            } else {
                say(maid, s, "好吧，委托先取消～");
                STATES.remove(maid);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 清空全部委托（当前 + 排队）。返回是否真的清了。 */
    public static boolean cancelAll(EntityMaid maid) {
        try {
            State s = STATES.get(maid);
            if (s == null || !s.busy()) {
                return false;
            }
            GatherHook.end(maid);
            s.pendingOrders.clear();
            say(maid, s, "好吧，全部委托都取消～");
            STATES.remove(maid);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** V2-C：推进到下一张排队的委托（交付完成与取消当前共用） */
    private static void advanceToNext(EntityMaid maid, State s, String msg) {
        CraftOrder next = s.pendingOrders.poll();
        if (next == null) {
            STATES.remove(maid);
            return;
        }
        s.order = next;
        s.plan = null;
        s.phase = Phase.ANALYZE;
        s.targetChest = null;
        s.meltFurnace = null;
        s.meltCollected = 0;
        s.stepIndex = 0;
        s.stepDone = 0;
        s.combatByMe = false;
        s.baseTargetCount = countInInv(maid.getAvailableBackpackInv(), List.of(next.target));
        say(maid, s, msg + "新单：「" + name(next.target) + "」×" + next.count
                + (s.pendingOrders.isEmpty() ? "" : "（后面还排着 " + s.pendingOrders.size() + " 张）"));
    }

    /** 界面快照：{hasOrder, 目标行, 阶段行, 缺口行, 备注行}（服务端算好，客户端只管显示） */
    public static String[] snapshot(EntityMaid maid) {
        try {
            State s = STATES.get(maid);
            if (s == null || !s.busy()) {
                return new String[]{"0", "空闲：等她接单", "", "", ""};
            }
            ServerLevel level = maid.m_9236_() instanceof ServerLevel lv ? lv : null;
            long now = level == null ? 0 : level.m_46467_();
            String l1 = "目标：" + name(s.order.target) + " ×" + s.order.count
                    + (s.order.allowGather ? "（允许自采）" : "（只用手头材料）")
                    + (s.pendingOrders.isEmpty() ? "" : "　§e[后面还排着 " + s.pendingOrders.size() + " 张]");
            String l2;
            String l3 = "";
            String l4 = "";
            switch (s.phase) {
                case ANALYZE -> l2 = "阶段：分析材料中…";
                case FETCH -> {
                    l2 = "阶段：取料中（还剩 " + s.queue.size() + " 个箱子）";
                    l3 = "从箱子把材料搬进她的背包";
                }
                case GATHER -> {
                    long left = Math.max(0, s.gatherDeadline - now) / 20;
                    l2 = "阶段：外出采集「" + name(s.gatherCandidates.isEmpty()
                            ? s.order.target : s.gatherCandidates.get(0)) + "」";
                    l3 = "还差 " + Math.max(0, s.gatherCount - countInInv(maid.getAvailableBackpackInv(), s.gatherCandidates))
                            + " 个；限时剩 " + left + " 秒";
                    l4 = "采够会自动传送回来继续做";
                }
                case CRAFT -> {
                    int total = s.plan == null ? 0 : s.plan.steps.size();
                    boolean melting = s.plan != null && s.stepIndex < s.plan.steps.size()
                            && s.plan.steps.get(s.stepIndex).kind == CraftPlanner.Step.Kind.SMELT;
                    if (melting) {
                        CraftPlanner.Step st = s.plan.steps.get(s.stepIndex);
                        l2 = "阶段：熔炼中（已收 " + s.meltCollected + "/" + st.times + "）";
                        l3 = "当前：" + name(st.output.m_41720_()) + "（熔炉烧制中，燃料与材料都从她背包出）";
                    } else {
                        l2 = "阶段：合成中（第 " + Math.min(s.stepIndex + 1, Math.max(1, total)) + " / " + Math.max(1, total) + " 步）";
                        l3 = s.plan != null && s.stepIndex < s.plan.steps.size()
                                ? "当前：" + name(s.plan.steps.get(s.stepIndex).output.m_41720_()) : "";
                    }
                }
                case DELIVER -> l2 = "阶段：收尾（传送回主人身边交付）";
                case STALL -> {
                    l2 = "阶段：等待补料";
                    l3 = s.plan == null ? "" : "还缺：" + fmt(s.plan.missingMerged());
                    l4 = "把材料放进她附近或你附近的箱子即可自动继续";
                }
                default -> l2 = "";
            }
            return new String[]{"1", l1, l2, l3, l4};
        } catch (Throwable t) {
            return new String[]{"0", "空闲：等她接单", "", "", ""};
        }
    }

    /* ==================== 挂载 ==================== */

    public static final class Hook {
        @SubscribeEvent
        public void onMaidTick(MaidTickEvent event) {
            try {
                tick(event.getMaid());
            } catch (Throwable ignored) {
            }
        }
    }

    /* ==================== 主入口 ==================== */

    private static void tick(EntityMaid maid) {
        if (maid == null || !(maid.m_9236_() instanceof ServerLevel level)) {
            return;
        }
        IMaidTask task = maid.getTask();
        if (task == null) {
            return;
        }
        ResourceLocation uid = task.getUid();
        State s = STATES.get(maid);
        boolean isHome = CRAFT_UID.equals(uid);
        // 玩家中途手动改任务（既不是 craft，也不在我们切的矿/木/攻击上）→ 交还控制权
        if (!isHome && (s == null || !(MINE_UID.equals(uid) || WOOD_UID.equals(uid)
                || ATTACK_UID.equals(uid) || s.combatByMe))) {
            if (s != null) {
                GatherHook.end(maid);
            }
            STATES.remove(maid);
            return;
        }
        if (s == null) {
            s = new State();
            STATES.put(maid, s);
        }
        if (!s.busy()) {
            return; // 空转：任务在 craft 但没接单（待命）
        }
        if (!com.maidsmart.config.MaidSmartConfig.CRAFT_ORDER_ENABLE.get()) {
            abort(maid, level, s, "委托合成被关掉了");
            return;
        }
        long now = level.m_46467_();
        // 战斗打断（全阶段统一，口径照 AutoMine：让位给 AutoCombatSwitch；安全 5 秒切回）
        boolean acsActive = com.maidsmart.combat.AutoCombatSwitch.isAutoCombatActive(maid);
        boolean danger = com.maidsmart.dialogue.PerceptionManager.dangerActive(maid) || acsActive;
        if (danger) {
            s.safeSince = -1;
            if (!acsActive && !s.combatByMe) {
                engageCombat(maid, level, s);
            }
            return;
        }
        if (s.combatByMe) {
            if (s.safeSince < 0) {
                s.safeSince = now;
                return;
            }
            if (now - s.safeSince >= COMBAT_SAFE_MS) {
                disengageCombat(maid, level, s);
            }
            return;
        }
        switch (s.phase) {
            case ANALYZE -> stepAnalyze(maid, level, s);
            case FETCH -> stepFetch(maid, level, s, now);
            case GATHER -> stepGather(maid, level, s, now);
            case CRAFT -> stepCraft(maid, level, s, now);
            case DELIVER -> stepDeliver(maid, level, s);
            case STALL -> stepStall(maid, level, s, now);
        }
    }

    /* ==================== ANALYZE：展开 + 对账 + 分流 ==================== */

    private static void stepAnalyze(EntityMaid maid, ServerLevel level, State s) {
        // 确保任务回到 craft（从采集回来时可能还在 mine/woodcut；切失败下轮重试）
        if (maid.getTask() == null || !CRAFT_UID.equals(maid.getTask().getUid())) {
            if (!switchTask(maid, CRAFT_UID)) {
                return;
            }
        }
        GatherHook.end(maid); // 保险（采集结束路径已清，双保险）
        Map<Item, Integer> stock = collectStock(maid, level);
        CraftPlanner.Plan plan = CraftPlanner.plan(level.m_7465_(), level.m_9598_(),
                s.order.target, s.order.count,
                com.maidsmart.config.MaidSmartConfig.CRAFT_ORDER_MAX_DEPTH.get(),
                stock, GatherHook::isGatherable);
        s.plan = plan;
        if (!plan.craftable) {
            stall(maid, level, s, "做不了「" + name(s.order.target) + "」：" + plan.failReason);
            return;
        }
        if (!plan.missing.isEmpty()) {
            List<CraftPlanner.Need> gatherables = new ArrayList<>();
            List<CraftPlanner.Need> hard = new ArrayList<>();
            for (CraftPlanner.Need n : plan.missingMerged()) {
                if (n.gatherable) {
                    gatherables.add(n);
                } else {
                    hard.add(n);
                }
            }
            boolean canGather = s.order.allowGather
                    && com.maidsmart.config.MaidSmartConfig.CRAFT_ORDER_ALLOW_GATHER.get();
            if (!hard.isEmpty() && (gatherables.isEmpty() || !canGather)) {
                stall(maid, level, s, "还缺：" + fmt(plan.missingMerged()) + "，给我补上就继续～");
                return;
            }
            if (!gatherables.isEmpty() && canGather) {
                if (!hard.isEmpty()) {
                    say(maid, s, "缺 " + fmt(hard) + "（这个要你补），另外 " + fmt(gatherables) + " 我去采一些～");
                }
                CraftPlanner.Need target = gatherables.get(0);
                beginGather(maid, level, s, target);
                return;
            }
            // 理论上到不了这里（上面已覆盖），保险
            stall(maid, level, s, "还缺：" + fmt(plan.missingMerged()) + "，给我补上就继续～");
            return;
        }
        // 缺口空 → 全部在库存里；把箱子里的那部分搬进背包
        buildFetchQueue(maid, level, s);
        if (s.queue.isEmpty()) {
            s.phase = Phase.CRAFT;
            s.stepIndex = 0;
            s.stepDone = 0;
            s.nextCraftTick = level.m_46467_();
            say(maid, s, "材料齐了，开工做「" + name(s.order.target) + "」～");
        } else {
            s.phase = Phase.FETCH;
            startNextFetch(maid, level, s);
        }
    }

    /** 搜库存：女仆背包 + 主人附近 + 她自身附近的箱子 → item 计数表（planner 对账用） */
    private static Map<Item, Integer> collectStock(EntityMaid maid, ServerLevel level) {
        Map<Item, Integer> stock = new HashMap<>();
        try {
            IItemHandler inv = maid.getAvailableBackpackInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack st = inv.getStackInSlot(i);
                if (!st.m_41619_()) {
                    stock.merge(st.m_41720_(), st.m_41613_(), Integer::sum);
                }
            }
        } catch (Throwable ignored) {
        }
        if (maid.m_269323_() instanceof ServerPlayer owner && owner.m_9236_() == level) {
            addContainers(level, owner.m_20183_(),
                    com.maidsmart.config.MaidSmartConfig.CRAFT_ORDER_SCAN_RADIUS.get(), stock);
        }
        addContainers(level, maid.m_20183_(),
                com.maidsmart.config.MaidSmartConfig.CRAFT_ORDER_SELF_RADIUS.get(), stock);
        return stock;
    }

    private static void addContainers(ServerLevel level, BlockPos center, int radius, Map<Item, Integer> stock) {
        for (BlockEntity be : blockEntitiesNear(level, center, Math.max(4, radius))) {
            if (be instanceof Container c) {
                for (int i = 0; i < c.m_6643_(); i++) {
                    ItemStack st = c.m_8020_(i);
                    if (!st.m_41619_()) {
                        stock.merge(st.m_41720_(), st.m_41613_(), Integer::sum);
                    }
                }
            }
        }
    }

    /** 生成取料队列：对每个需求「背包缺多少 → 找存得最多的箱子」 */
    private static void buildFetchQueue(EntityMaid maid, ServerLevel level, State s) {
        s.queue.clear();
        if (s.plan == null) {
            return;
        }
        IItemHandler inv = maid.getAvailableBackpackInv();
        List<BlockPos> centers = new ArrayList<>();
        if (maid.m_269323_() instanceof ServerPlayer owner && owner.m_9236_() == level) {
            centers.add(owner.m_20183_());
        }
        centers.add(maid.m_20183_());
        int radius = Math.max(com.maidsmart.config.MaidSmartConfig.CRAFT_ORDER_SCAN_RADIUS.get(),
                com.maidsmart.config.MaidSmartConfig.CRAFT_ORDER_SELF_RADIUS.get());
        Map<BlockPos, BlockEntity> boxes = new HashMap<>();
        for (BlockPos c : centers) {
            for (BlockEntity be : blockEntitiesNear(level, c, Math.max(4, radius))) {
                boxes.put(be.m_58899_(), be);
            }
        }
        for (CraftPlanner.Need n : s.plan.needs) {
            int have = countInInv(inv, n.candidates);
            int need = n.count - have;
            if (need <= 0) {
                continue;
            }
            BlockPos best = null;
            int bestN = 0;
            for (Map.Entry<BlockPos, BlockEntity> e : boxes.entrySet()) {
                if (e.getValue() instanceof Container c) {
                    int cn = countInContainer(c, n.candidates);
                    if (cn > bestN) {
                        bestN = cn;
                        best = e.getKey();
                    }
                }
            }
            if (best != null) {
                s.queue.add(new Fetch(best, n.candidates, Math.min(bestN, need)));
            }
        }
    }

    /* ==================== FETCH：走箱取料（照弹药补给口径） ==================== */

    private static void startNextFetch(EntityMaid maid, ServerLevel level, State s) {
        s.targetChest = null;
        s.chestOpened = false;
        s.openTicks = 0;
        while (!s.queue.isEmpty() && level.m_7702_(s.queue.get(0).pos) == null) {
            s.queue.remove(0); // 箱子被拆了 → 跳过
        }
        if (s.queue.isEmpty()) {
            s.phase = Phase.CRAFT;
            s.stepIndex = 0;
            s.stepDone = 0;
            s.nextCraftTick = level.m_46467_();
            return;
        }
        Fetch f = s.queue.get(0);
        s.targetChest = f.pos;
        double d = maid.m_20183_().m_123331_(f.pos);
        s.walkDeadline = level.m_46467_() + 200 + (long) (Math.sqrt(Math.max(0, d)) * 8);
        BehaviorUtils.m_22617_(maid, f.pos, 0.7f, 2);
    }

    private static void stepFetch(EntityMaid maid, ServerLevel level, State s, long now) {
        if (maid.m_20159_() || maid.isMaidInSittingPose()) {
            return; // 被骑/坐着 → 等她下来（不推进不失败）
        }
        if (s.targetChest == null) {
            startNextFetch(maid, level, s);
            return;
        }
        if (level.m_7702_(s.targetChest) == null) {
            s.queue.remove(0);
            startNextFetch(maid, level, s);
            return;
        }
        double dSq = maid.m_20183_().m_123331_(s.targetChest);
        if (dSq > REACH_SQ) {
            if (now > s.walkDeadline) {
                stall(maid, level, s, "装材料的箱子我走不过去，先停一下～");
                return;
            }
            if (maid.m_6274_().m_21952_(MemoryModuleType.f_26370_).isEmpty()) {
                BehaviorUtils.m_22617_(maid, s.targetChest, 0.7f, 2);
            }
            return;
        }
        if (!s.chestOpened) {
            level.m_46796_(1, s.targetChest, 1); // 开箱盖
            s.chestOpened = true;
            s.openTicks = OPEN_WAIT_TICKS;
            return;
        }
        if (s.openTicks > 0) {
            s.openTicks--;
            return;
        }
        Fetch f = s.queue.get(0);
        if (level.m_7702_(s.targetChest) instanceof Container c) {
            int got = takeItems(c, f.candidates, f.count, maid);
            if (got > 0) {
                maid.m_6674_(InteractionHand.MAIN_HAND); // 挥臂"拿东西"
            }
        }
        level.m_46796_(1, s.targetChest, 0); // 关箱盖
        s.queue.remove(0);
        startNextFetch(maid, level, s);
    }

    /** 从容器取 candidates 中的任意物品进她背包（塞不下的还回原槽，不吞东西） */
    private static int takeItems(Container c, List<Item> candidates, int max, EntityMaid maid) {
        int got = 0;
        for (int i = 0; i < c.m_6643_() && got < max; i++) {
            ItemStack st = c.m_8020_(i);
            if (st.m_41619_() || !candidates.contains(st.m_41720_())) {
                continue;
            }
            int move = Math.min(max - got, st.m_41613_());
            ItemStack taken = c.m_7407_(i, move);
            ItemStack remain = ItemHandlerHelper.insertItemStacked(maid.getAvailableBackpackInv(), taken, false);
            if (!remain.m_41619_()) {
                c.m_6836_(i, remain);
            }
            got += taken.m_41613_() - remain.m_41613_();
        }
        return got;
    }

    /* ==================== GATHER：传送式缺料采集（玩家规格） ==================== */

    private static void beginGather(EntityMaid maid, ServerLevel level, State s, CraftPlanner.Need need) {
        GatherHook.Kind kind = GatherHook.kindOf(need.item);
        if (kind == GatherHook.Kind.NONE) {
            stall(maid, level, s, "缺「" + name(need.item) + "×" + need.count + "」，我采不了，给我补上吧～");
            return;
        }
        BlockPos origin = maid.m_20183_();
        if (maid.m_269323_() instanceof ServerPlayer owner && owner.m_9236_() == level) {
            origin = owner.m_20183_();
        }
        BlockPos site = pickGatherSite(level, origin, kind, need.item);
        if (site == null) {
            stall(maid, level, s, "附近找不到可采的「" + name(need.item) + "」，等会儿再试～");
            return;
        }
        ResourceLocation taskUid = kind == GatherHook.Kind.WOOD ? WOOD_UID : MINE_UID;
        if (!switchTask(maid, taskUid)) {
            stall(maid, level, s, "采集任务切不过去，先停一下～");
            return;
        }
        s.gatherKind = kind;
        s.gatherCandidates = need.candidates;
        s.gatherCount = need.count;
        s.gatherSite = site;
        s.gatherBack = origin;
        s.gatherDeadline = level.m_46467_()
                + Math.max(30, com.maidsmart.config.MaidSmartConfig.CRAFT_ORDER_GATHER_TIMEOUT.get()) * 20L;
        GatherHook.begin(maid, kind);
        // 锚点重置：挖矿行为把锚点"首次定位"在采集点（不背家里的旧锚——照 AutoMine 口径）
        if (kind == GatherHook.Kind.MINE) {
            com.maidsmart.task.MaidMineBehavior.resetAnchor(maid.m_19879_());
        } else {
            com.maidsmart.task.MaidWoodBehavior.forget(maid.m_19879_());
        }
        teleport(maid, level, site);
        s.phase = Phase.GATHER;
        say(maid, s, "缺「" + name(need.item) + "×" + need.count + "」，我去采一些～");
        com.maidsmart.tool.PromaidLog.log("委托合成",
                com.maidsmart.tool.PromaidLog.nameOf(maid) + " 采集 " + kind
                        + " → " + site.m_123341_() + "," + site.m_123342_() + "," + site.m_123343_());
    }

    private static void stepGather(EntityMaid maid, ServerLevel level, State s, long now) {
        int have = countInInv(maid.getAvailableBackpackInv(), s.gatherCandidates);
        if (have >= s.gatherCount) {
            finishGather(maid, level, s, true);
            return;
        }
        if (now > s.gatherDeadline) {
            finishGather(maid, level, s, false);
            return;
        }
        if (s.gatherSite != null && maid.m_20183_().m_123331_(s.gatherSite) > SITE_LOST_DIST_SQ) {
            finishGather(maid, level, s, false); // 被死亡回城/意外传走 → 本次作废
        }
    }

    private static void finishGather(EntityMaid maid, ServerLevel level, State s, boolean ok) {
        GatherHook.end(maid);
        BlockPos back = null;
        if (maid.m_269323_() instanceof ServerPlayer owner && owner.m_9236_() == level) {
            back = owner.m_20183_();
        } else if (s.gatherBack != null) {
            back = s.gatherBack;
        }
        if (back != null) {
            teleport(maid, level, back);
        }
        switchTask(maid, CRAFT_UID); // 失败下轮 ANALYZE 里重试
        s.phase = Phase.ANALYZE;
        if (!ok) {
            say(maid, s, "没采够，先回来啦～");
        }
    }

    /**
     * 找采集点：以 origin 为心、{@link #GATHER_DISTANCES} 两档距离 × 8 方向采样，
     * 每点统计"地表附近的目标方块数"，取最多者。只选已加载区块（照 AutoMine 口径——
     * 不把女仆传进未加载区块、也不为选点强拉区块生成）。
     */
    private static BlockPos pickGatherSite(ServerLevel level, BlockPos origin, GatherHook.Kind kind, Item missing) {
        Set<Block> probe = kind == GatherHook.Kind.MINE ? GatherHook.probeBlocks(missing) : Set.of();
        List<int[]> dirs = new ArrayList<>();
        Collections.addAll(dirs, new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}});
        Collections.shuffle(dirs);
        BlockPos best = null;
        int bestN = 0;
        for (int[] d : dirs) {
            for (int dist : GATHER_DISTANCES) {
                int x = origin.m_123341_() + d[0] * dist;
                int z = origin.m_123343_() + d[1] * dist;
                if (!level.m_46749_(new BlockPos(x, origin.m_123342_(), z))) {
                    continue; // 未加载 → 跳过
                }
                BlockPos surface = surfaceAt(level, x, origin.m_123342_(), z);
                if (surface == null) {
                    continue;
                }
                int n = countTargets(level, surface, kind, probe);
                if (n > bestN) {
                    bestN = n;
                    best = surface;
                }
            }
        }
        return best; // null = 附近找不到
    }

    /** 统计地表点附近的目标方块数（水平 ±4、垂直 -4..+8） */
    private static int countTargets(ServerLevel level, BlockPos surface, GatherHook.Kind kind, Set<Block> probe) {
        int n = 0;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -4; dy <= 8; dy++) {
                    BlockState st = level.m_8055_(surface.m_7918_(dx, dy, dz));
                    if (kind == GatherHook.Kind.WOOD) {
                        if (GatherHook.isWoodBlock(st)) {
                            n++;
                            if (n > 24) {
                                return n; // 足够多了，不必数完
                            }
                        }
                    } else if (probe.contains(st.m_60734_())) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** 地表站位（照 AutoMine.surfaceAt：有界上下搜 + 危险格排除） */
    private static BlockPos surfaceAt(ServerLevel level, int x, int originY, int z) {
        int y = originY;
        int up = 0;
        while (up < 32 && (!level.m_8055_(new BlockPos(x, y, z)).m_60795_()
                || !level.m_8055_(new BlockPos(x, y + 1, z)).m_60795_())) {
            y++;
            up++;
        }
        int down = 0;
        while (down < 64 && level.m_8055_(new BlockPos(x, y - 1, z)).m_60795_()) {
            y--;
            down++;
        }
        if (down >= 64) {
            return null; // 悬空/虚空
        }
        if (com.maidsmart.tool.DangerBlocks.cellDangerous(level, x, y, z)
                || com.maidsmart.tool.DangerBlocks.cellDangerous(level, x, y - 1, z)) {
            return null;
        }
        return new BlockPos(x, y, z);
    }

    /* ==================== CRAFT：逐级合成（物品守恒） ==================== */

    private static void stepCraft(EntityMaid maid, ServerLevel level, State s, long now) {
        if (s.plan == null) {
            s.phase = Phase.ANALYZE;
            return;
        }
        if (s.stepIndex >= s.plan.steps.size()) {
            s.phase = Phase.DELIVER;
            return;
        }
        if (now < s.nextCraftTick) {
            return;
        }
        CraftPlanner.Step step = s.plan.steps.get(s.stepIndex);
        if (step.kind == CraftPlanner.Step.Kind.SMELT) {
            // V2-A：熔炼步骤——找熔炉放料加燃料，等烧好收取（内部自带节流）
            stepMelt(maid, level, s, now);
            return;
        }
        IItemHandler inv = maid.getAvailableBackpackInv();
        // 抽料（每格 1 个，原版合成台口径）
        List<ItemStack> used = new ArrayList<>();
        for (Ingredient ing : step.ingredients) {
            int slot = findSlot(inv, ing);
            if (slot < 0) {
                rollback(inv, used);
                stall(maid, level, s, "材料对不上了（可能被拿走），先停下～");
                return;
            }
            used.add(inv.extractItem(slot, 1, false));
        }
        ItemStack out = step.output.m_41777_();
        ItemStack remain = ItemHandlerHelper.insertItemStacked(inv, out, false);
        if (!remain.m_41619_()) {
            rollback(inv, used);
            stall(maid, level, s, "背包满了放不下成品，先停下～");
            return;
        }
        maid.m_6674_(InteractionHand.MAIN_HAND);
        s.stepDone++;
        if (s.stepDone >= step.times) {
            s.stepIndex++;
            s.stepDone = 0;
        }
        s.nextCraftTick = now + Math.max(2, com.maidsmart.config.MaidSmartConfig.CRAFT_ORDER_CRAFT_INTERVAL.get());
        if (s.stepIndex >= s.plan.steps.size()) {
            s.phase = Phase.DELIVER;
        }
    }

    /* ==================== V2-A：熔炼步骤（熔炉放料烧制） ==================== */

    /** 熔炉节拍（tick/轮） */
    private static final int MELT_STEP_TICKS = 10;

    /**
     * 熔炼步骤执行：找空闲熔炉/高炉 → 走到炉边 → 放输入 + 补燃料 → 轮询收取。
     * 【真实性】材料与燃料都从她背包出、真进炉子（你也看得到、随时能接手）；
     * 只使用"输入槽与输出槽都空"的炉子，不打扰你正在用的炉子。
     */
    private static void stepMelt(EntityMaid maid, ServerLevel level, State s, long now) {
        CraftPlanner.Step step = s.plan.steps.get(s.stepIndex);
        if (step.ingredients.isEmpty()) {
            stall(maid, level, s, "这条熔炼配方不对劲，先停下～");
            return;
        }
        // 炉子失效（被拆/被换）→ 重新找（已收取的计数保留——它们在她背包里）
        if (s.meltFurnace != null
                && !(level.m_7702_(s.meltFurnace) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity)) {
            s.meltFurnace = null;
        }
        if (s.meltFurnace == null) {
            s.meltFurnace = findFreeFurnace(level, maid.m_20183_());
            if (s.meltFurnace == null) {
                stall(maid, level, s, "附近没有空闲的熔炉/高炉，放一个吧～");
                return;
            }
            s.meltDeadline = now + Math.max(60,
                    com.maidsmart.config.MaidSmartConfig.CRAFT_ORDER_MELT_TIMEOUT.get()) * 20L;
            BehaviorUtils.m_22617_(maid, s.meltFurnace, 0.7f, 2);
            s.nextCraftTick = now + MELT_STEP_TICKS;
            return;
        }
        // 走位
        double dSq = maid.m_20183_().m_123331_(s.meltFurnace);
        if (dSq > REACH_SQ) {
            if (now > s.meltDeadline) {
                stall(maid, level, s, "熔炉我走不过去，先停下～");
                return;
            }
            if (maid.m_6274_().m_21952_(MemoryModuleType.f_26370_).isEmpty()) {
                BehaviorUtils.m_22617_(maid, s.meltFurnace, 0.7f, 2);
            }
            s.nextCraftTick = now + MELT_STEP_TICKS;
            return;
        }
        if (!(level.m_7702_(s.meltFurnace) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity f)) {
            s.meltFurnace = null;
            s.nextCraftTick = now + MELT_STEP_TICKS;
            return;
        }
        // 收取产出（每轮都收——输出槽满会停炉，及时取走）
        ItemStack out = f.m_8020_(2);
        if (!out.m_41619_()) {
            ItemStack taken = f.m_8016_(2);
            ItemStack remain = ItemHandlerHelper.insertItemStacked(maid.getAvailableBackpackInv(), taken, false);
            if (!remain.m_41619_()) {
                f.m_6836_(2, remain);
            }
            s.meltCollected += taken.m_41613_() - remain.m_41613_();
            maid.m_6674_(InteractionHand.MAIN_HAND);
        }
        // 烧够 → 推进
        if (s.meltCollected >= step.times) {
            s.meltFurnace = null;
            s.meltCollected = 0;
            s.stepIndex++;
            s.stepDone = 0;
            s.nextCraftTick = now + Math.max(2,
                    com.maidsmart.config.MaidSmartConfig.CRAFT_ORDER_CRAFT_INTERVAL.get());
            if (s.stepIndex >= s.plan.steps.size()) {
                s.phase = Phase.DELIVER;
            }
            return;
        }
        if (now > s.meltDeadline) {
            stall(maid, level, s, "烧炼等待超时，先停下（可调「熔炼限时」）～");
            return;
        }
        // 补输入：槽 0 空 → 从背包抽一个堆（保留 NBT；不够下一轮再补）
        if (f.m_8020_(0).m_41619_()) {
            int want = Math.min(step.times - s.meltCollected, 64);
            IItemHandler inv = maid.getAvailableBackpackInv();
            ItemStack input = ItemStack.f_41583_;
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack st = inv.getStackInSlot(i);
                if (!st.m_41619_() && step.ingredients.get(0).test(st)) {
                    input = inv.extractItem(i, Math.min(want, st.m_41613_()), false);
                    break;
                }
            }
            if (!input.m_41619_()) {
                f.m_6836_(0, input);
                maid.m_6674_(InteractionHand.MAIN_HAND);
            } else {
                stall(maid, level, s, "烧到一半材料不够了（缺「"
                        + name(step.ingredients.get(0).m_43908_()[0].m_41720_()) + "」），给我补点～");
                return;
            }
        }
        // 补燃料：槽 1 空 → 从背包抽 1 个燃料（优先煤/木炭/煤炭块；不把装备当柴烧）
        if (f.m_8020_(1).m_41619_()) {
            ItemStack fuel = extractFuel(maid.getAvailableBackpackInv());
            if (!fuel.m_41619_()) {
                f.m_6836_(1, fuel);
            } else {
                stall(maid, level, s, "没有燃料了（煤/木炭/任何可燃物都行），给我补点～");
                return;
            }
        }
        s.nextCraftTick = now + MELT_STEP_TICKS;
    }

    /** 找空闲熔炉/高炉（输入槽与输出槽都空；烟熏炉不收——它只烧食物）；找不到返回 null */
    private static BlockPos findFreeFurnace(ServerLevel level, BlockPos center) {
        int radius = Math.max(4, com.maidsmart.config.MaidSmartConfig.CRAFT_ORDER_MELT_RADIUS.get());
        for (BlockEntity be : blockEntitiesNear(level, center, radius)) {
            boolean isFurnace = be instanceof net.minecraft.world.level.block.entity.FurnaceBlockEntity
                    || be instanceof net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
            if (!isFurnace || !(be instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity f)) {
                continue;
            }
            if (f.m_8020_(0).m_41619_() && f.m_8020_(2).m_41619_()) {
                return be.m_58899_();
            }
        }
        return null;
    }

    /**
     * 从背包抽 1 个燃料：第一轮只在煤/木炭/煤炭块里挑（热量高、不占特殊物品）；
     * 没有才退而求其次选任意 isFuel 且无耐久的物品（跳过熔岩桶——烧完空桶会占住燃料槽）。
     */
    private static ItemStack extractFuel(IItemHandler inv) {
        int bestSlot = -1;
        int bestTicks = 0;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (st.m_41619_()) {
                continue;
            }
            String id = idOf(st.m_41720_());
            if (!"minecraft:coal".equals(id) && !"minecraft:charcoal".equals(id)
                    && !"minecraft:coal_block".equals(id)) {
                continue;
            }
            int t = burnTicks(st);
            if (t > bestTicks) {
                bestTicks = t;
                bestSlot = i;
            }
        }
        if (bestSlot < 0) {
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack st = inv.getStackInSlot(i);
                if (st.m_41619_() || st.m_41776_() > 0) {
                    continue;
                }
                String id = idOf(st.m_41720_());
                if ("minecraft:lava_bucket".equals(id)) {
                    continue;
                }
                int t = burnTicks(st);
                if (t > bestTicks) {
                    bestTicks = t;
                    bestSlot = i;
                }
            }
        }
        return bestSlot < 0 ? ItemStack.f_41583_ : inv.extractItem(bestSlot, 1, false);
    }

    private static int burnTicks(ItemStack st) {
        try {
            if (!net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.m_58399_(st)) {
                return 0;
            }
            Integer ticks = net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity
                    .m_58423_().get(st.m_41720_());
            return ticks == null ? 1 : ticks;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String idOf(Item item) {
        try {
            ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item);
            return id == null ? "" : id.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static int findSlot(IItemHandler inv, Ingredient ing) {
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (!st.m_41619_() && ing.test(st)) {
                return i;
            }
        }
        return -1;
    }

    private static void rollback(IItemHandler inv, List<ItemStack> used) {
        for (ItemStack st : used) {
            if (!st.m_41619_()) {
                ItemHandlerHelper.insertItemStacked(inv, st, false);
            }
        }
    }

    /* ==================== DELIVER：传送回主人 + 交付 ==================== */

    private static void stepDeliver(EntityMaid maid, ServerLevel level, State s) {
        boolean ownerHere = maid.m_269323_() instanceof ServerPlayer owner && owner.m_9236_() == level;
        if (ownerHere) {
            ServerPlayer owner = (ServerPlayer) maid.m_269323_();
            if (maid.m_20183_().m_123331_(owner.m_20183_()) > 16.0) {
                teleport(maid, level, owner.m_20183_());
            }
            // 交付：只交"本次做的增量"（扣除开工基线）——不吞她原有的存货
            IItemHandler inv = maid.getAvailableBackpackInv();
            IItemHandler oinv = new InvWrapper(owner.m_150109_());
            int total = countInInv(inv, List.of(s.order.target));
            int toGive = Math.max(0, Math.min(total - s.baseTargetCount, s.order.count));
            int moved = 0;
            for (int i = 0; i < inv.getSlots() && moved < toGive; i++) {
                ItemStack st = inv.getStackInSlot(i);
                if (st.m_41619_() || st.m_41720_() != s.order.target) {
                    continue;
                }
                int take = Math.min(toGive - moved, st.m_41613_());
                ItemStack extract = inv.extractItem(i, take, false);
                ItemStack remain = ItemHandlerHelper.insertItemStacked(oinv, extract, false);
                if (!remain.m_41619_()) {
                    ItemHandlerHelper.insertItemStacked(inv, remain, false); // 主人背包满 → 还她
                }
                moved += extract.m_41613_() - remain.m_41613_();
            }
            say(maid, s, "「" + name(s.order.target) + "」做好了，给你 " + moved + " 个～"
                    + (moved < s.order.count ? "（其余放不下，先留在我这儿）" : ""));
            com.maidsmart.tool.PromaidLog.log("委托合成",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 完成：" + name(s.order.target) + "×" + moved);
        } else {
            say(maid, s, "「" + name(s.order.target) + "」做好啦（你不在旁边，先放我这儿）～");
        }
        GatherHook.end(maid);
        if (!s.pendingOrders.isEmpty()) {
            advanceToNext(maid, s, "这张做完啦～"); // V2-C：自动接下一张
        } else {
            STATES.remove(maid); // 完成：清委托（任务保留 craft = 待命）
        }
    }

    /* ==================== STALL：挂起等补料（每秒自动重试） ==================== */

    private static void stepStall(EntityMaid maid, ServerLevel level, State s, long now) {
        if (now - s.nextCraftTick < 20) {
            return; // 每秒重试一次（nextCraftTick 复用为节流）
        }
        s.nextCraftTick = now;
        s.phase = Phase.ANALYZE; // 玩家补料后自动续做
    }

    private static void stall(EntityMaid maid, ServerLevel level, State s, String reason) {
        s.phase = Phase.STALL;
        if (!reason.equals(s.lastBubbleText)) {
            s.lastBubbleText = reason;
            s.lastBubble = 0; // 原因变化 → 允许立刻说
        }
        sayBubbleRare(maid, s, reason);
    }

    /* ==================== 战斗打断（照 AutoMine 口径） ==================== */

    private static void engageCombat(EntityMaid maid, ServerLevel level, State s) {
        IMaidTask attackTask = TaskManager.findTask(ATTACK_UID).orElse(null);
        if (attackTask == null) {
            return;
        }
        s.resumeTask = taskForPhase(s);
        try {
            com.maidsmart.combat.CombatTaskCompat.prepareSwitch(maid, attackTask);
        } catch (Throwable ignored) {
        }
        com.maidsmart.schedule.ScheduleSwitchGuard.runInternal(
                maid.m_20148_(), ATTACK_UID, () -> maid.setTask(attackTask));
        if (maid.getTask() != null && ATTACK_UID.equals(maid.getTask().getUid())) {
            s.combatByMe = true;
            s.safeSince = -1;
            com.maidsmart.tool.PromaidLog.log("委托合成",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 遇敌 → 切战斗（委托暂停）");
        }
    }

    private static void disengageCombat(EntityMaid maid, ServerLevel level, State s) {
        ResourceLocation back = s.resumeTask != null ? s.resumeTask : CRAFT_UID;
        if (switchTask(maid, back)) {
            s.combatByMe = false;
            s.safeSince = -1;
            com.maidsmart.tool.PromaidLog.log("委托合成",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 周边安全 → 委托继续");
        }
    }

    private static ResourceLocation taskForPhase(State s) {
        if (s.phase == Phase.GATHER) {
            return s.gatherKind == GatherHook.Kind.WOOD ? WOOD_UID : MINE_UID;
        }
        return CRAFT_UID;
    }

    /** 功能被关 → 收尾放弃（清状态；采集目标钩子一并清） */
    private static void abort(EntityMaid maid, ServerLevel level, State s, String reason) {
        try {
            GatherHook.end(maid);
            say(maid, s, reason + "，委托先取消～");
        } catch (Throwable ignored) {
        }
        STATES.remove(maid);
    }

    /* ==================== 工具 ==================== */

    /** 任务切换（ScheduleSwitchGuard 内部通道 + 读回校验——照 AutoMine 口径） */
    private static boolean switchTask(EntityMaid maid, ResourceLocation uid) {
        try {
            IMaidTask t = TaskManager.findTask(uid).orElse(null);
            if (t == null) {
                return false;
            }
            try {
                com.maidsmart.combat.CombatTaskCompat.prepareSwitch(maid, t);
            } catch (Throwable ignored) {
            }
            com.maidsmart.schedule.ScheduleSwitchGuard.runInternal(
                    maid.m_20148_(), uid, () -> maid.setTask(t));
            return maid.getTask() != null && uid.equals(maid.getTask().getUid());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 传送（MaidHeatEscape.teleportSelf 同款链路：清摔落/清速度/音效/属性包） */
    private static boolean teleport(EntityMaid maid, ServerLevel level, BlockPos dest) {
        try {
            maid.m_264318_(level, dest.m_123341_() + 0.5, dest.m_123342_(),
                    dest.m_123343_() + 0.5, Collections.emptySet(), maid.m_146908_(), maid.m_146909_());
            maid.f_19789_ = 0.0f;
            maid.m_20256_(net.minecraft.world.phys.Vec3.f_82478_);
            level.m_5594_(null, dest, net.minecraft.sounds.SoundEvents.f_11852_,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.0f);
            com.maidsmart.command.MaidResyncCommand.scheduleAttributeResend(maid);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 附近方块实体（区块枚举；照弹药补给口径，256 区块守卫） */
    private static List<BlockEntity> blockEntitiesNear(ServerLevel level, BlockPos center, int radius) {
        List<BlockEntity> out = new ArrayList<>();
        int[] box = {center.m_123341_() - radius, center.m_123342_() - 32, center.m_123343_() - radius,
                center.m_123341_() + radius, center.m_123342_() + 32, center.m_123343_() + radius};
        int cx0 = box[0] >> 4;
        int cx1 = (box[3] - 1) >> 4;
        int cz0 = box[2] >> 4;
        int cz1 = (box[5] - 1) >> 4;
        int guard = 0;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (++guard > 256) {
                    return out;
                }
                LevelChunk chunk = level.m_6325_(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (Map.Entry<BlockPos, BlockEntity> e : chunk.m_62954_().entrySet()) {
                    BlockPos p = e.getKey();
                    if (p.m_123341_() >= box[0] && p.m_123341_() < box[3]
                            && p.m_123342_() >= box[1] && p.m_123342_() < box[4]
                            && p.m_123343_() >= box[2] && p.m_123343_() < box[5]) {
                        out.add(e.getValue());
                    }
                }
            }
        }
        return out;
    }

    private static int countInInv(IItemHandler inv, List<Item> candidates) {
        int n = 0;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (!st.m_41619_() && candidates.contains(st.m_41720_())) {
                n += st.m_41613_();
            }
        }
        return n;
    }

    private static int countInContainer(Container c, List<Item> candidates) {
        int n = 0;
        for (int i = 0; i < c.m_6643_(); i++) {
            ItemStack st = c.m_8020_(i);
            if (!st.m_41619_() && candidates.contains(st.m_41720_())) {
                n += st.m_41613_();
            }
        }
        return n;
    }

    static String name(Item item) {
        try {
            return new ItemStack(item).m_41786_().getString();
        } catch (Throwable t) {
            return String.valueOf(item);
        }
    }

    private static String fmt(List<CraftPlanner.Need> list) {
        StringBuilder sb = new StringBuilder();
        for (CraftPlanner.Need n : list) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(name(n.item)).append("×").append(n.count);
        }
        return sb.length() == 0 ? "一些材料" : sb.toString();
    }

    /** 气泡（免语音——照弹药补给口径：委托/状态类气泡不朗读） */
    private static void say(EntityMaid maid, State s, String text) {
        try {
            com.maidsmart.voice.SystemTTSManager.beginNoVoice();
            try {
                maid.getChatBubbleManager().addTextChatBubble(text);
            } finally {
                com.maidsmart.voice.SystemTTSManager.endNoVoice();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 高频重复提示（30 秒节流） */
    private static void sayBubbleRare(EntityMaid maid, State s, String text) {
        long now = System.currentTimeMillis();
        if (now - s.lastBubble < 30000L) {
            return;
        }
        s.lastBubble = now;
        say(maid, s, text);
    }
}
