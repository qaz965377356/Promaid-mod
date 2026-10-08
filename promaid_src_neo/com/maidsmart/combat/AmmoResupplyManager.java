package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 弹药自动补给——用枪的女仆没弹时，去**主人附近**的箱子取材料，按原版合成配方做出
 * **对得上这把枪口径**的弹药放进自己背包。
 *
 * <p>【需求（玩家原话）】「女仆支持在枪械没有子弹时，自动去箱子中获取 铜+火药，
 * 制作合适的子弹自己使用」。四条硬性要求，逐条落在哪：
 * <ul>
 *   <li>【不在战斗中】触发与进行全程都要求 {@link #inCombat} 为假（原版目标字段 +
 *       ATTACK_TARGET 记忆双口径）；一旦开打/被骑乘/坐下/枪离手，**悄悄放弃**
 *       （{@link #abortQuietly}——不说话不报错，材料留在她背包里，打完下次接着用）；</li>
 *   <li>【玩家附近具备条件】材料只在**主人身边** {@code combat.ammoCraftRadius}（默认 16 格）
 *       内的箱子/桶/潜影箱里找（{@link #containersNear}）；主人太远（>48 格）或不在场
 *       干脆不触发——她不会为了弹药满世界跑；</li>
 *   <li>【失败要说出来】每条失败通路都给一句气泡（没配方 / 缺哪些材料 / 箱子走不到 /
 *       背包放不下），见各 fail 调用点；</li>
 *   <li>【不频繁触发】缺弹状态要**站稳 2 秒**才发起（防换弹瞬间抖动）；无论成败，
 *       一次尝试后冷却 {@code combat.ammoCraftCooldown}（默认 60 秒）——失败原因一分钟
 *       最多说一次；中途因开打放弃只给 5 秒短冷却（打完还要接着补）。</li>
 *   <li>【只在用枪时触发】主手那把是枪（{@link GunCompat#isGun}）且
 *       {@code !canFeed && !canReload}——枪在背包里不算"使用中"，不触发。</li>
 * </ul>
 *
 * <p>【"合适的子弹"怎么判：探针法，零新增枪械 mod 内部 API】TACZ 的口径在 NBT、
 * 卓越前线每枪认一类弹——与其再深挖两家的内部接口，不如让**枪械 mod 自己验收**：
 * <ol>
 *   <li>枚举原版合成台配方，粗筛"产物像弹药"的（注册名 path 含 ammo：tacz:ammo、
 *       tacz:ammo_box、superbwarfare 的 *_ammo 全覆盖）；</li>
 *   <li>对每个候选，把 1 个**产物样本**插进她背包 → 问 {@link GunCompat#canFeed}
 *       （这把枪现在打得响吗）→ 再原样取回样本。样本插进又取出，不消耗任何材料；
 *       canFeed 翻真 = 口径对上了（TACZ 弹药箱配方同样被这一步验收）；</li>
 *   <li>真正的合成在材料齐了之后：逐个 ingredient 从她背包抽 1 个 → 产出
 *       {@code getResultItem} 的拷贝插回背包。产物带着配方自带 NBT（TACZ 的 AmmoId
 *       就在里面），开火链路自己会去吃。</li>
 * </ol>
 * 反射不可用 / 没装枪械 mod 时 canFeed 恒假 → 探针全否 → 气泡说"没有配方"，绝不误合成。
 *
 * <p>【链路】挂 TLM 的 {@code MaidTickEvent}（两侧都发，{@link #tick} 第一行挡客户端），
 * 与 MaidFreeFlightHandler 同一挂载方式。状态机：RESOLVE（查配方）→ GOTO/TAKE
 * （逐箱取料，走过去 + 开箱盖动画 + 挥臂，范式照搬 BuildContainerFetchBehavior）→
 * CRAFT（合成入包）。全程不开箱子 UI、不碰末影箱（它不是 Container，天然翻不到）。
 *
 * <p>【为什么是 tick 服务而不是 brain 行为】触发条件与任务无关（idle/在家/跟随都可能
 * 手持空枪），而本模组加不进 TLM 自带任务的 brain——事件服务是本模组既有的跨任务
 * 挂载方式（飞行控制器/牵引绳/感知都是）。走位用 WALK_TARGET 记忆（TLM 的移动 sink
 * 每 tick 消费它），跟随任务每拍也写 WALK_TARGET 会打架——打不赢就超时，超时就说
 * "走不过去"，诚实收场。
 */
public final class AmmoResupplyManager {
    private AmmoResupplyManager() {
    }

    /* ---------------- 节奏常量 ---------------- */
    /** 检测节流（tick）：缺弹判定本身有反射调用，不必每 tick 跑 */
    private static final long DETECT_INTERVAL = 10;
    /** 缺弹状态需持续稳定（tick）才发起——40 = 2 秒，防换弹瞬间抖动误触发 */
    private static final int STABLE_TICKS = 40;
    /** 够得着箱子的距离平方（3 格，参照 BuildContainerFetchBehavior） */
    private static final double REACH_SQ = 9.0;
    /** 开箱盖动画到取物的等待（tick） */
    private static final int OPEN_WAIT_TICKS = 8;
    /** 主人与女仆的最大同行距离（格）：更远就不触发——她不为弹药长途跋涉 */
    private static final double OWNER_MAX_DIST_SQ = 48.0 * 48.0;

    /* ---------------- 状态 ---------------- */
    private enum Phase { GOTO, TAKE, CRAFT }

    /** 一种合成材料：判据（Ingredient，认标签）+ 代表物品（找箱子/展示名用）+ 每组个数 */
    private static final class Mat {
        final Ingredient ing;
        final Item rep;
        final int perBatch;

        Mat(Ingredient ing, Item rep, int perBatch) {
            this.ing = ing;
            this.rep = rep;
            this.perBatch = perBatch;
        }
    }

    /** 一次取料：去哪个容器、取哪种材料、取几个 */
    private static final class Fetch {
        final BlockPos pos;
        final Mat mat;
        final int count;

        Fetch(BlockPos pos, Mat mat, int count) {
            this.pos = pos;
            this.mat = mat;
            this.count = count;
        }
    }

    /** 一次补给尝试的进行时状态（maid → attempt；女仆释放即随 WeakHashMap 消失） */
    private static final class Attempt {
        Phase phase = Phase.GOTO;
        /** 计划用的配方（探针验收过的那个） */
        CraftingRecipe recipe;
        /** 材料单（顺序无关，按代表物品合并） */
        List<Mat> mats = new ArrayList<>();
        /** 计划合成多少组 */
        int batches;
        /** 待取的箱子队列 */
        List<Fetch> queue = new ArrayList<>();
        /** 当前目标箱 */
        BlockPos targetChest;
        /** 开箱动画剩余 tick */
        int openTicks;
        /** 本趟走箱的截止 gameTime */
        long walkDeadline;
    }

    private static final Map<EntityMaid, Attempt> ACTIVE =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** maid → 缺弹状态最早被看到的 gameTime（站稳 STABLE_TICKS 才发起） */
    private static final Map<EntityMaid, Long> STABLE_SINCE =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** maid → 冷却截止 gameTime（无论成败，一次尝试后都要等） */
    private static final Map<EntityMaid, Long> COOLDOWN =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** maid → 下次允许检测的 gameTime（检测本身有反射开销，节流） */
    private static final Map<EntityMaid, long[]> NEXT_DETECT =
            Collections.synchronizedMap(new WeakHashMap<>());

    /* ---------------- 挂载 ---------------- */

    /** MaidTickEvent 挂载（两侧都发，tick() 用 ServerLevel 判侧）——ProMaidMod 显式注册 */
    public static final class Hook {
        @SubscribeEvent
        public void onMaidTick(MaidTickEvent event) {
            try {
                tick(event.getMaid());
            } catch (Throwable ignored) {
            }
        }
    }

    /* ---------------- 主入口 ---------------- */

    private static void tick(EntityMaid maid) {
        if (maid == null || !(maid.level() instanceof ServerLevel level)) {
            return; // 客户端 / 非服务端关卡
        }
        Attempt a = ACTIVE.get(maid);
        if (a != null) {
            tickAttempt(maid, level, a);
            return;
        }
        long now = level.getGameTime();
        long[] next = NEXT_DETECT.get(maid);
        if (next != null && now < next[0]) {
            return;
        }
        NEXT_DETECT.put(maid, new long[]{now + DETECT_INTERVAL});
        detect(maid, level, now);
    }

    /* ---------------- 触发检测 ---------------- */

    private static void detect(EntityMaid maid, ServerLevel level, long now) {
        if (!com.maidsmart.config.MaidSmartConfig.AMMO_AUTO_CRAFT.get()) {
            return;
        }
        if (!GunCompat.anyGunModLoaded()) {
            return;
        }
        if (maid.isPassenger() || maid.isMaidInSittingPose()) {
            return;
        }
        // 【不在战斗中】开打 = 清稳定窗（怪一走重新等 2 秒）
        if (inCombat(maid)) {
            STABLE_SINCE.remove(maid);
            return;
        }
        // 【不频繁触发】冷却中不打扰
        Long cd = COOLDOWN.get(maid);
        if (cd != null && now < cd) {
            return;
        }
        // 主人在场、同维度、不算太远
        if (!(maid.getOwner() instanceof ServerPlayer owner)) {
            return;
        }
        if (owner.level() != level) {
            return;
        }
        if (maid.blockPosition().distSqr(owner.blockPosition()) > OWNER_MAX_DIST_SQ) {
            return; // 主人太远：默默不管，也不说话（免得人不在也吵）
        }
        // 【只在用枪时】主手是枪，且打不响也换不上弹
        ItemStack gun = maid.getMainHandItem();
        if (gun.isEmpty() || !GunCompat.isGun(gun)) {
            STABLE_SINCE.remove(maid);
            return;
        }
        if (GunCompat.canFeed(maid, gun) || GunCompat.canReload(maid, gun)) {
            STABLE_SINCE.remove(maid);
            return;
        }
        // 站稳 STABLE_TICKS 才发起（防换弹/搬弹药的十几 tick 抖动）
        Long since = STABLE_SINCE.get(maid);
        if (since == null) {
            STABLE_SINCE.put(maid, now);
            return;
        }
        if (now - since < STABLE_TICKS) {
            return;
        }
        STABLE_SINCE.remove(maid);
        Attempt a = new Attempt();
        ACTIVE.put(maid, a);
        stepResolve(maid, level, a);
    }

    /** 是否在战斗：原版目标字段 + ATTACK_TARGET 记忆双口径（两处都包住，异常按"不在战斗"算） */
    private static boolean inCombat(EntityMaid maid) {
        try {
            if (maid.getTarget() != null && maid.getTarget().isAlive()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            java.util.Optional<net.minecraft.world.entity.LivingEntity> t =
                    maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET);
            if (t.isPresent() && t.get() != null && t.get().isAlive()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /* ---------------- 阶段一：查配方（探针法） ---------------- */

    private static void stepResolve(EntityMaid maid, ServerLevel level, Attempt a) {
        CraftingRecipe found = null;
        try {
            for (RecipeHolder<CraftingRecipe> rh : level.getRecipeManager()
                    .getAllRecipesFor(RecipeType.CRAFTING)) {
                CraftingRecipe r = rh.value();
                ItemStack out;
                try {
                    out = r.getResultItem(level.registryAccess());
                } catch (Throwable t) {
                    continue; // 坏配方跳过，不拦别人
                }
                if (out.isEmpty() || !ammoLike(out)) {
                    continue;
                }
                if (!accepts(maid, maid.getMainHandItem(), out)) {
                    continue; // 口径对不上（枪械 mod 自己验收）
                }
                found = r;
                break;
            }
        } catch (Throwable t) {
            found = null;
        }
        if (found == null) {
            fail(maid, level, a, "这把枪的子弹我没有合成配方，做不出来，先省着点用哦～");
            return;
        }
        a.recipe = found;
        collectMats(a);
        if (a.mats.isEmpty()) {
            // 退化配方（无任何材料的合成不存在于正常数据包）——绝不无中生有
            fail(maid, level, a, "这把枪的子弹我没有合成配方，做不出来，先省着点用哦～");
            return;
        }
        planFetch(maid, level, a);
    }

    /** 产物像不像弹药：注册名 path 含 ammo（tacz:ammo / tacz:ammo_box / *_ammo 全覆盖） */
    private static boolean ammoLike(ItemStack out) {
        try {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(out.getItem());
            return key != null && key.getPath().contains("ammo");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 探针：把 1 个候选产物插进她背包 → 问 GunCompat「打得响**或**换得上弹吗」→ 取回。
     * 样本是凭空造的、插进又取出，全程不消耗任何东西；背包塞不下样本时保守判否。
     *
     * <p>【为什么必须是 canFeed || canReload 的或门，与模式门禁同口径】弹匣供弹的枪
     * （TACZ/SBW 大多数枪）canFeed 只看"弹匣/弹膛里这一拍有没有弹"——插进背包的样本
     * 不会让它翻真；扫背包的是另一半：TACZ 的 canReload（useInventoryAmmo=false 的枪
     * 走它扫背包/弹药箱）、SBW 的 shouldStartReloading（弹匣枪的备弹计数）。而
     * 不吃弹匣的枪（背包弹/能量）正路本来就是 canFeed（它算的就是背包/能量池）。
     * 所以探针必须用与 MaidFlightKit.hasAmmoForWeapon 同一个或门，四种供弹形态全覆盖：
     * TACZ 弹匣枪 ✓（canReload）／TACZ 背包弹枪 ✓（canFeed）／SBW 弹匣枪 ✓（canReload）／
     * SBW 背包弹与能量枪 ✓（canFeed）。
     */
    private static boolean accepts(EntityMaid maid, ItemStack gun, ItemStack candidateOut) {
        try {
            IItemHandler inv = maid.getAvailableBackpackInv();
            ItemStack probe = candidateOut.copy();
            probe.setCount(1);
            ItemStack remain = ItemHandlerHelper.insertItemStacked(inv, probe, false);
            if (!remain.isEmpty()) {
                return false;
            }
            boolean ok;
            try {
                ok = GunCompat.canFeed(maid, gun) || GunCompat.canReload(maid, gun);
            } finally {
                // 取回样本（找同类堆叠抽 1 个；取不回就取不回——1 发弹药的成本，不卡流程）
                for (int i = 0; i < inv.getSlots(); i++) {
                    ItemStack s = inv.getStackInSlot(i);
                    if (!s.isEmpty() && ItemHandlerHelper.canItemStacksStack(s, candidateOut)) {
                        inv.extractItem(i, 1, false);
                        break;
                    }
                }
            }
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 配方 → 材料单（同代表物品合并计数；标签配方用 Ingredient 判据，不写死物品） */
    private static void collectMats(Attempt a) {
        Map<Item, Mat> byRep = new LinkedHashMap<>();
        for (Ingredient ing : a.recipe.getIngredients()) {
            if (ing == null || ing.getItems().length == 0) {
                continue; // 有序配方的空槽（见 SmartCraftTool 同款注释）
            }
            ItemStack repStack = ing.getItems()[0];
            if (repStack.isEmpty()) {
                continue;
            }
            Item rep = repStack.getItem();
            Mat m = byRep.get(rep);
            if (m == null) {
                m = new Mat(ing, rep, 1);
                byRep.put(rep, m);
            } else {
                m.perBatch++;
            }
        }
        a.mats = new ArrayList<>(byRep.values());
    }

    /* ---------------- 阶段二：计划取料 ---------------- */

    private static void planFetch(EntityMaid maid, ServerLevel level, Attempt a) {
        if (!(maid.getOwner() instanceof ServerPlayer owner) || owner.level() != level) {
            abortQuietly(maid, level);
            return;
        }
        IItemHandler inv = maid.getAvailableBackpackInv();
        int cap = Math.max(1, com.maidsmart.config.MaidSmartConfig.AMMO_CRAFT_MAX_CRAFT.get());
        // 对每种材料：背包缺多少 → 主人附近哪个箱子存得最多（一个箱子解决就认它）
        int radius = Math.max(4, com.maidsmart.config.MaidSmartConfig.AMMO_CRAFT_RADIUS.get());
        List<BlockEntity> containers = containersNear(level, owner.blockPosition(), radius);
        for (Mat m : a.mats) {
            int need = m.perBatch * cap;
            int have = countInInv(inv, m);
            if (have >= need) {
                continue;
            }
            BlockEntity best = null;
            int bestN = 0;
            for (BlockEntity be : containers) {
                if (!(be instanceof Container c)) {
                    continue;
                }
                int n = countInContainer(c, m);
                if (n > bestN) {
                    bestN = n;
                    best = be;
                }
            }
            if (best != null) {
                a.queue.add(new Fetch(best.getBlockPos(), m, Math.min(bestN, need - have)));
            }
        }
        // 预估"取完之后"能做几组：箱子被搬空/背包装不下都能兜住（合成前还会再算一次）
        int batches = cap;
        for (Mat m : a.mats) {
            int have = countInInv(inv, m);
            for (Fetch f : a.queue) {
                if (f.mat == m) {
                    have += f.count;
                }
            }
            batches = Math.min(batches, have / m.perBatch);
        }
        if (batches < 1) {
            fail(maid, level, a, "想做子弹，但缺材料：" + missingNames(inv, a.mats) + "，附近的箱子里也没有～");
            return;
        }
        a.batches = batches;
        startNextFetch(maid, level, a);
    }

    /** 缺料清单（名字 × 个数）：气泡文案用 */
    private static String missingNames(IItemHandler inv, List<Mat> mats) {
        StringBuilder sb = new StringBuilder();
        for (Mat m : mats) {
            int lack = m.perBatch - countInInv(inv, m);
            if (lack <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(new ItemStack(m.rep).getHoverName().getString()).append("×").append(lack);
        }
        return sb.length() == 0 ? "一些材料" : sb.toString();
    }

    private static void startNextFetch(EntityMaid maid, ServerLevel level, Attempt a) {
        a.targetChest = null;
        while (!a.queue.isEmpty() && level.getBlockEntity(a.queue.get(0).pos) == null) {
            a.queue.remove(0); // 箱子被拆了 → 跳过（短了多少合成前会再算）
        }
        if (a.queue.isEmpty()) {
            a.phase = Phase.CRAFT;
            stepCraft(maid, level, a);
            return;
        }
        Fetch f = a.queue.get(0);
        a.targetChest = f.pos;
        a.phase = Phase.GOTO;
        double d = maid.blockPosition().distSqr(f.pos);
        // 距离越远给的时间越多：基础 10 秒 + 每 8 格 1 秒（跟随任务抢 WALK_TARGET 打不赢时诚实超时）
        a.walkDeadline = level.getGameTime() + 200 + (long) (Math.sqrt(Math.max(0, d)) * 8);
        BehaviorUtils.setWalkAndLookTargetMemories(maid, f.pos, 0.7f, 2);
    }

    /* ---------------- 阶段三：走箱 / 取料 ---------------- */

    private static void tickAttempt(EntityMaid maid, ServerLevel level, Attempt a) {
        // 【不在战斗中】贯穿全程：开打 / 被骑乘 / 坐下 / 枪离手 → 悄悄放弃（不说话，材料留在背包）
        if (inCombat(maid) || maid.isPassenger() || maid.isMaidInSittingPose()
                || !GunCompat.isGun(maid.getMainHandItem())) {
            abortQuietly(maid, level);
            return;
        }
        if (a.phase == Phase.GOTO) {
            tickGoto(maid, level, a);
        } else if (a.phase == Phase.TAKE) {
            tickTake(maid, level, a);
        } else {
            stepCraft(maid, level, a);
        }
    }

    private static void tickGoto(EntityMaid maid, ServerLevel level, Attempt a) {
        if (a.targetChest == null || !(level.getBlockEntity(a.targetChest) instanceof Container)) {
            a.queue.remove(0);
            startNextFetch(maid, level, a);
            return;
        }
        double dSq = maid.blockPosition().distSqr(a.targetChest);
        if (dSq > REACH_SQ) {
            if (level.getGameTime() > a.walkDeadline) {
                fail(maid, level, a, "装材料的箱子太远，我走不过去，子弹先补不成了～");
                return;
            }
            // 还在路上：走位目标丢了就补一次（TLM 的移动 sink 每 tick 会消费它）
            if (maid.getBrain().getMemory(MemoryModuleType.WALK_TARGET).isEmpty()) {
                BehaviorUtils.setWalkAndLookTargetMemories(maid, a.targetChest, 0.7f, 2);
            }
            return;
        }
        // 到位：开箱盖，等几 tick 再取（"走过去 → 开箱 → 取 → 关箱"，同 BuildContainerFetchBehavior）
        level.blockEvent(a.targetChest, level.getBlockState(a.targetChest).getBlock(), 1, 1);
        a.openTicks = OPEN_WAIT_TICKS;
        a.phase = Phase.TAKE;
    }

    private static void tickTake(EntityMaid maid, ServerLevel level, Attempt a) {
        if (a.openTicks > 0) {
            a.openTicks--;
            return;
        }
        Fetch f = a.queue.get(0);
        if (level.getBlockEntity(a.targetChest) instanceof Container c) {
            int got = takeItem(c, f.mat, f.count, maid);
            if (got > 0) {
                maid.swing(net.minecraft.world.InteractionHand.MAIN_HAND); // 挥臂"拿东西"
            }
        }
        level.blockEvent(a.targetChest, level.getBlockState(a.targetChest).getBlock(), 1, 0); // 关箱盖
        a.queue.remove(0);
        startNextFetch(maid, level, a);
    }

    /** 从容器取材料进她背包（塞不下的还回原槽，不吞东西）——Item 版 take，同 BuildContainerSource 口径 */
    private static int takeItem(Container c, Mat m, int max, EntityMaid maid) {
        int got = 0;
        for (int i = 0; i < c.getContainerSize() && got < max; i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty() || !m.ing.test(s)) {
                continue;
            }
            int move = Math.min(max - got, s.getCount());
            ItemStack taken = c.removeItem(i, move);
            ItemStack remain = ItemHandlerHelper.insertItemStacked(maid.getAvailableBackpackInv(), taken, false);
            if (!remain.isEmpty()) {
                c.setItem(i, remain); // 背包满了 → 还回原槽
            }
            got += taken.getCount() - remain.getCount();
        }
        return got;
    }

    /* ---------------- 阶段四：合成 ---------------- */

    private static void stepCraft(EntityMaid maid, ServerLevel level, Attempt a) {
        IItemHandler inv = maid.getAvailableBackpackInv();
        // 取料途中箱子可能被搬空 → 按她背包**实际**持有量重算组数
        int batches = a.batches;
        for (Mat m : a.mats) {
            batches = Math.min(batches, countInInv(inv, m) / m.perBatch);
        }
        if (batches < 1) {
            fail(maid, level, a, "材料不够了，子弹没做成……主人附近再放点材料吧");
            return;
        }
        int made = 0;
        for (int b = 0; b < batches; b++) {
            ItemStack sample = recipeResult(level, a.recipe);
            if (sample.isEmpty() || !canInsert(inv, sample)) {
                break; // 背包放不下产物 → 停在这里，材料一个没动
            }
            List<ItemStack> taken = new ArrayList<>();
            boolean ok = true;
            for (Ingredient ing : a.recipe.getIngredients()) {
                if (ing == null || ing.getItems().length == 0) {
                    continue;
                }
                int slot = findSlot(inv, ing);
                if (slot < 0) {
                    ok = false;
                    break;
                }
                taken.add(inv.extractItem(slot, 1, false));
            }
            if (!ok) {
                // 抽取中断（背包被人动了）：已抽的还回去，物品守恒
                for (ItemStack s : taken) {
                    ItemHandlerHelper.insertItemStacked(inv, s, false);
                }
                break;
            }
            ItemStack out = recipeResult(level, a.recipe).copy();
            ItemStack remain = ItemHandlerHelper.insertItemStacked(inv, out, false);
            if (!remain.isEmpty()) {
                // 理论到不了（上面模拟过放得下）；最后一道保险：逐槽硬塞
                for (int i = 0; i < inv.getSlots() && !remain.isEmpty(); i++) {
                    remain = inv.insertItem(i, remain, false);
                }
                if (!remain.isEmpty()) {
                    com.maidsmart.tool.PromaidLog.log("弹药补给",
                            com.maidsmart.tool.PromaidLog.nameOf(maid) + " 产物放不下丢失 "
                                    + remain.getCount() + " 个（不应发生，现场请反馈）");
                }
            }
            made++;
        }
        if (made < 1) {
            fail(maid, level, a, "背包塞满了，子弹做不出来，先给我腾点地方吧～");
            return;
        }
        // 收尾：合成后用与探针同一个或门问一次枪械 mod（弹匣枪要看"换得上"，单看 canFeed 会误报）
        boolean feed;
        try {
            feed = GunCompat.canFeed(maid, maid.getMainHandItem()) || GunCompat.canReload(maid, maid.getMainHandItem());
        } catch (Throwable t) {
            feed = true; // 兼容层异常时不冤枉她
        }
        com.maidsmart.tool.PromaidLog.log("弹药补给",
                com.maidsmart.tool.PromaidLog.nameOf(maid) + " 合成了 " + made + " 组弹药"
                        + "（配方 " + a.recipe + "，补给后打得响=" + feed + "）");
        say(maid, "子弹补给完成，做了 " + made + " 组，这下能打响啦～");
        finish(maid, level);
    }

    private static ItemStack recipeResult(ServerLevel level, CraftingRecipe r) {
        try {
            return r.getResultItem(level.registryAccess());
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    /** 模拟插入：产物整堆放不放得下（不改动任何槽位） */
    private static boolean canInsert(IItemHandler inv, ItemStack stack) {
        int want = stack.getCount();
        for (int i = 0; i < inv.getSlots() && want > 0; i++) {
            ItemStack remain = inv.insertItem(i, stack.copy(), true);
            want -= stack.getCount() - remain.getCount();
        }
        return want <= 0;
    }

    private static int findSlot(IItemHandler inv, Ingredient ing) {
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.isEmpty() && ing.test(s)) {
                return i;
            }
        }
        return -1;
    }

    private static int countInInv(IItemHandler inv, Mat m) {
        int n = 0;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.isEmpty() && m.ing.test(s)) {
                n += s.getCount();
            }
        }
        return n;
    }

    private static int countInContainer(Container c, Mat m) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && m.ing.test(s)) {
                n += s.getCount();
            }
        }
        return n;
    }

    /** 主人附近的方块实体（按区块枚举，同 BuildContainerSource.blockEntitiesIn 的口径） */
    private static List<BlockEntity> containersNear(ServerLevel level, BlockPos center, int radius) {
        List<BlockEntity> out = new ArrayList<>();
        int[] box = {center.getX() - radius, center.getY() - radius, center.getZ() - radius,
                center.getX() + radius, center.getY() + radius, center.getZ() + radius};
        int cx0 = box[0] >> 4;
        int cx1 = (box[3] - 1) >> 4;
        int cz0 = box[2] >> 4;
        int cz1 = (box[5] - 1) >> 4;
        int guard = 0;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (++guard > 256) {
                    return out; // 64 格半径封顶，不把一帧搭进去
                }
                net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunk(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                    BlockPos p = e.getKey();
                    if (p.getX() >= box[0] && p.getX() < box[3]
                            && p.getY() >= box[1] && p.getY() < box[4]
                            && p.getZ() >= box[2] && p.getZ() < box[5]) {
                        out.add(e.getValue());
                    }
                }
            }
        }
        return out;
    }

    /* ---------------- 收尾 ---------------- */

    /** 失败：气泡说明缺失条件 + 完整冷却 */
    private static void fail(EntityMaid maid, ServerLevel level, Attempt a, String reason) {
        ACTIVE.remove(maid);
        cooldown(maid, level);
        say(maid, reason);
        com.maidsmart.tool.PromaidLog.log("弹药补给",
                com.maidsmart.tool.PromaidLog.nameOf(maid) + " 失败：" + reason);
    }

    /** 成功收尾：完整冷却（子弹补上了，短期不会再触发） */
    private static void finish(EntityMaid maid, ServerLevel level) {
        ACTIVE.remove(maid);
        cooldown(maid, level);
    }

    /** 中途放弃（开打/枪离手等）：不出声，短冷却 5 秒——打完这波还能接着补 */
    private static void abortQuietly(EntityMaid maid, ServerLevel level) {
        ACTIVE.remove(maid);
        COOLDOWN.put(maid, level.getGameTime() + 100L);
    }

    private static void cooldown(EntityMaid maid, ServerLevel level) {
        int sec = Math.max(5, com.maidsmart.config.MaidSmartConfig.AMMO_CRAFT_COOLDOWN.get());
        COOLDOWN.put(maid, level.getGameTime() + sec * 20L);
    }

    private static void say(EntityMaid maid, String text) {
        try {
            maid.getChatBubbleManager().addTextChatBubble(text);
        } catch (Throwable ignored) {
        }
    }
}
