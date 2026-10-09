package com.maidsmart.combat;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 弹药自动补给——用枪的女仆没弹时，去**主人附近**的箱子取材料，按配方做出
 * **对得上这把枪口径**的弹药放进自己背包；TACZ 配方还会**走到主人附近的枪械工作台旁**合成。
 *
 * <p>【需求（玩家原话）】「女仆支持在枪械没有子弹时，自动去箱子中获取 铜+火药，
 * 制作合适的子弹自己使用」。四条硬性要求，逐条落在哪：
 * <ul>
 *   <li>【不在战斗中】触发与进行全程都要求 {@link #inCombat} 为假（原版目标字段 +
 *       ATTACK_TARGET 记忆双口径）；一旦开打/被骑乘/坐下/枪离手，**悄悄放弃**
 *       （{@link #abortQuietly}——不说话不报错，材料留在她背包里，打完下次接着用）；</li>
 *   <li>【玩家附近具备条件】材料只在**主人身边** {@code combat.ammoCraftRadius}（默认 16 格）
 *       内的箱子/桶/潜影箱里找；工作台也只在主人附近找；主人太远（>48 格）或不在场
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
 * 对每个候选配方，把 1 个**产物样本**插进她背包 → 问 {@link GunCompat#canFeed}
 * {@code || canReload}（打得响或换得上弹吗，与模式门禁同一个或门）→ 再原样取回样本。
 * 样本插进又取出，不消耗任何材料；或门翻真 = 口径对上了。
 *
 * <p>================ v1.3.x【配方从哪来：统一扫全量配方，TACZ 工作台优先】 ================
 *
 * <p>【玩家实测反馈】「没子弹后会触发逻辑，但是始终会报没有子弹的制作蓝图，无法制作，
 * 但是实际上子弹是在家里的一个子弹制作台上制作的。」
 *
 * <p>【旧版错在哪】旧版只枚举 {@code RecipeType.CRAFTING}（原版合成台）。而
 * **TACZ 的弹药不是原版合成台配方**——javap 实证（tacz-1.20.1-1.1.8-hotfix2）：
 * {@code com.tacz.guns.crafting.GunSmithTableRecipe implements net.minecraft.world.item.crafting.Recipe}
 * ——它就注册在**原版 RecipeManager 里、挂 TACZ 自家的 RecipeType**，材料是
 * {@code getInputs()}（每个元素 = 原版 Ingredient + 个数 {@code getCount()}）、
 * 产出是 {@code getOutput()}（弹药自带 AmmoId NBT）。旧版扫错了类型，所以永远
 * "没有配方"。
 *
 * <p>【现在的口径】一次枚举 {@code RecipeManager.getRecipes()}（全部类型的全部配方），
 * 两趟扫描：
 * <ol>
 *   <li>**TACZ 工作台配方优先**（装了 TACZ 才扫，反射句柄惰性解析、不在场即跳过）：
 *       材料**带个数**（getCount），产出带口径 NBT；</li>
 *   <li>退回原版合成台配方（每格材料 1 个，旧口径不变）。</li>
 * </ol>
 * 探针验收对两类配方一视同仁；反射不可用（没装 TACZ）时第一趟整体跳过，绝不误判。
 *
 * <p>【走到工作台旁合成】TACZ 配方命中后，若主人附近有枪械工作台
 * （{@code GunSmithTableBlockEntity}，A/B/C 三档都算），她会**走过去、在台边合成**
 * （对齐"子弹是在家里的制作台上做的"的直觉）；附近没有工作台就原地合成，材料流程不变。
 * 工作台的**档位不做校验**（档位是 TACZ 工作台界面的进度门，这里只复用它的位置语义）。
 *
 * <p>【链路】挂 TLM 的 {@code MaidTickEvent}（两侧都发，{@link #tick} 第一行挡客户端），
 * 与 MaidFreeFlightHandler 同一挂载方式。状态机：RESOLVE（查配方）→ GOTO/TAKE
 * （逐箱取料 / 走到工作台，范式照搬 BuildContainerFetchBehavior）→ CRAFT（合成入包）。
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

    /* ---------------- TACZ 工作台配方（javap 实证：tacz-1.20.1-1.1.8-hotfix2） ----------------
     *
     * GunSmithTableRecipe implements net.minecraft.world.item.crafting.Recipe<Inventory>——
     * 配方本体注册在原版 RecipeManager（自家 RecipeType），所以从 RecipeManager 全量枚举
     * 就能拿到，不需要碰 TACZ 的数据管理器。材料/产出的读取全部走它的公开方法：
     *   getInputs()  → java.util.List<GunSmithTableIngredient>
     *                  （getIngredient() → 原版 Ingredient；getCount() → int 个数）
     *   getOutput()  → net.minecraft.world.item.ItemStack（弹药自带 AmmoId NBT）
     * 工作台方块实体：com.tacz.guns.block.entity.GunSmithTableBlockEntity（A/B/C 三档共用）。
     * TACZ 是可选兼容 mod（不在编译类路径），照 GunCompat 的老规矩走惰性反射；
     * 不在场（api.length == 0）时整段跳过，原版合成台路径照旧，绝不因此误判"没配方"。
     */
    /** [0]=GunSmithTableRecipe 类，[1]=getInputs()，[2]=getOutput()，
     *  [3]=GunSmithTableIngredient.getIngredient()，[4]=getCount()，
     *  [5]=GunSmithTableBlockEntity 类；空数组 = TACZ 不在场/解析失败 */
    private static volatile Object[] TACZ_TABLE_API;
    private static final Object TACZ_TABLE_LOCK = new Object();

    private static Object[] taczTableApi() {
        Object[] api = TACZ_TABLE_API;
        if (api != null) {
            return api;
        }
        synchronized (TACZ_TABLE_LOCK) {
            if (TACZ_TABLE_API == null) {
                try {
                    Class<?> recipeCls = Class.forName("com.tacz.guns.crafting.GunSmithTableRecipe");
                    Class<?> ingCls = Class.forName("com.tacz.guns.crafting.GunSmithTableIngredient");
                    Class<?> beCls = Class.forName("com.tacz.guns.block.entity.GunSmithTableBlockEntity");
                    TACZ_TABLE_API = new Object[]{
                            recipeCls,
                            recipeCls.getMethod("getInputs"),
                            recipeCls.getMethod("getOutput"),
                            ingCls.getMethod("getIngredient"),
                            ingCls.getMethod("getCount"),
                            beCls};
                } catch (Throwable ignored) {
                    TACZ_TABLE_API = new Object[0];
                }
            }
            return TACZ_TABLE_API;
        }
    }

    /* ---------------- 节奏常量 ---------------- */
    /** 检测节流（tick）：缺弹判定本身有反射调用，不必每 tick 跑 */
    private static final long DETECT_INTERVAL = 10;
    /** 缺弹状态需持续稳定（tick）才发起——40 = 2 秒，防换弹瞬间抖动误触发 */
    private static final int STABLE_TICKS = 40;
    /** 够得着箱子/工作台的距离平方（3 格，参照 BuildContainerFetchBehavior） */
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
        /** 每组个数（TACZ 配方材料自带个数；原版配方每格 1 个；合并同类材料时会自增） */
        int perBatch;

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
        /** 材料单（TACZ 配方带个数；原版配方每格 1 个） */
        List<Mat> mats = new ArrayList<>();
        /** 产出模板（弹药自带口径 NBT；每组合成用它 copy 一份） */
        ItemStack outputTemplate;
        /** 配方来源描述（日志用） */
        String recipeDesc = "";
        /** TACZ 配方 = true：合成阶段优先走到工作台旁 */
        boolean atTable;
        /** 计划合成多少组 */
        int batches;
        /** 待取的箱子队列 */
        List<Fetch> queue = new ArrayList<>();
        /** 当前目标（箱子或工作台） */
        BlockPos targetChest;
        /** 当前目标是工作台（到位后直接合成，不走开箱取料） */
        boolean targetIsTable;
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
        if (maid == null || !(maid.m_9236_() instanceof ServerLevel level)) {
            return; // 客户端 / 非服务端关卡
        }
        Attempt a = ACTIVE.get(maid);
        if (a != null) {
            tickAttempt(maid, level, a);
            return;
        }
        long now = level.m_46467_();
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
        if (maid.m_20159_() || maid.isMaidInSittingPose()) {
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
        if (!(maid.m_269323_() instanceof ServerPlayer owner)) {
            return;
        }
        if (owner.m_9236_() != level) {
            return;
        }
        if (maid.m_20183_().m_123331_(owner.m_20183_()) > OWNER_MAX_DIST_SQ) {
            return; // 主人太远：默默不管，也不说话（免得人不在也吵）
        }
        // 【只在用枪时】主手是枪，且打不响也换不上弹
        ItemStack gun = maid.m_21205_();
        if (gun.m_41619_() || !GunCompat.isGun(gun)) {
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
            if (maid.m_5448_() != null && maid.m_5448_().m_6084_()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            java.util.Optional<net.minecraft.world.entity.LivingEntity> t =
                    maid.m_6274_().m_21952_(MemoryModuleType.f_26372_);
            if (t.isPresent() && t.get() != null && t.get().m_6084_()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /* ---------------- 阶段一：查配方（统一全量扫描，TACZ 工作台优先） ---------------- */

    private static void stepResolve(EntityMaid maid, ServerLevel level, Attempt a) {
        Collection<Recipe<?>> all;
        try {
            all = level.m_7465_().m_44051_(); // getRecipes()：全部类型的全部配方
        } catch (Throwable t) {
            all = Collections.emptyList();
        }
        // 第一趟：TACZ 枪械工作台配方（装了才扫；没 TACZ 时 api 为空数组直接跳过）
        Object[] tacz = taczTableApi();
        if (tacz != null && tacz.length > 0) {
            for (Recipe<?> r : all) {
                try {
                    if (!((Class<?>) tacz[0]).isInstance(r)) {
                        continue;
                    }
                    ItemStack out = (ItemStack) ((java.lang.reflect.Method) tacz[2]).invoke(r);
                    if (out.m_41619_() || !ammoLike(out)) {
                        continue;
                    }
                    if (!accepts(maid, maid.m_21205_(), out)) {
                        continue; // 口径对不上（枪械 mod 自己验收）
                    }
                    List<Mat> mats = taczMats(((java.lang.reflect.Method) tacz[1]).invoke(r), tacz);
                    if (mats.isEmpty()) {
                        continue;
                    }
                    a.mats = mats;
                    a.outputTemplate = out.m_41777_();
                    a.recipeDesc = "TACZ工作台:" + r.m_6423_();
                    a.atTable = true;
                    planFetch(maid, level, a);
                    return;
                } catch (Throwable t) {
                    continue; // 单个配方坏了不拦别人
                }
            }
        }
        // 第二趟：原版合成台配方（卓越前线等；每格材料 1 个）
        for (Recipe<?> r : all) {
            if (!(r instanceof CraftingRecipe cr)) {
                continue;
            }
            try {
                ItemStack out = cr.m_8043_(level.m_9598_());
                if (out.m_41619_() || !ammoLike(out)) {
                    continue;
                }
                if (!accepts(maid, maid.m_21205_(), out)) {
                    continue;
                }
                List<Mat> mats = vanillaMats(cr);
                if (mats.isEmpty()) {
                    continue; // 退化配方（无任何材料）——绝不无中生有
                }
                a.mats = mats;
                a.outputTemplate = out.m_41777_();
                a.recipeDesc = "原版合成:" + r.m_6423_();
                a.atTable = false;
                planFetch(maid, level, a);
                return;
            } catch (Throwable t) {
                continue;
            }
        }
        fail(maid, level, a, "这把枪的子弹我没有合成配方，做不出来，先省着点用哦～");
    }

    /** 产物像不像弹药：注册名 path 含 ammo（tacz:ammo / tacz:ammo_box / *_ammo 全覆盖） */
    private static boolean ammoLike(ItemStack out) {
        try {
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(out.m_41720_());
            return key != null && key.m_135815_().contains("ammo");
        } catch (Throwable t) {
            return false;
        }
    }

    /** TACZ 配方材料单：getInputs() 的每个元素 = 原版 Ingredient + 个数 */
    private static List<Mat> taczMats(Object inputs, Object[] api) {
        List<Mat> mats = new ArrayList<>();
        if (!(inputs instanceof List<?> list)) {
            return mats;
        }
        Map<Item, Mat> byRep = new LinkedHashMap<>();
        for (Object o : list) {
            try {
                Ingredient ing = (Ingredient) ((java.lang.reflect.Method) api[3]).invoke(o);
                int count = (Integer) ((java.lang.reflect.Method) api[4]).invoke(o);
                if (ing == null || ing.m_43908_().length == 0 || count <= 0) {
                    continue;
                }
                ItemStack rep = ing.m_43908_()[0];
                if (rep.m_41619_()) {
                    continue;
                }
                Mat m = byRep.get(rep.m_41720_());
                if (m == null) {
                    m = new Mat(ing, rep.m_41720_(), count);
                    byRep.put(rep.m_41720_(), m);
                } else {
                    m.perBatch += count;
                }
            } catch (Throwable ignored) {
            }
        }
        mats.addAll(byRep.values());
        return mats;
    }

    /** 原版配方材料单：每个非空 ingredient 格 1 个，同代表物品合并计数 */
    private static List<Mat> vanillaMats(CraftingRecipe cr) {
        Map<Item, Mat> byRep = new LinkedHashMap<>();
        for (Ingredient ing : cr.m_7527_()) {
            if (ing == null || ing.m_43908_().length == 0) {
                continue; // 有序配方的空槽（见 SmartCraftTool 同款注释）
            }
            ItemStack rep = ing.m_43908_()[0];
            if (rep.m_41619_()) {
                continue;
            }
            Mat m = byRep.get(rep.m_41720_());
            if (m == null) {
                m = new Mat(ing, rep.m_41720_(), 1);
                byRep.put(rep.m_41720_(), m);
            } else {
                m.perBatch++;
            }
        }
        return new ArrayList<>(byRep.values());
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
            ItemStack probe = candidateOut.m_41777_();
            probe.m_41774_(1);
            ItemStack remain = ItemHandlerHelper.insertItemStacked(inv, probe, false);
            if (!remain.m_41619_()) {
                return false;
            }
            boolean ok;
            try {
                ok = GunCompat.canFeed(maid, gun) || GunCompat.canReload(maid, gun);
            } finally {
                // 取回样本（找同类堆叠抽 1 个；取不回就取不回——1 发弹药的成本，不卡流程）
                for (int i = 0; i < inv.getSlots(); i++) {
                    ItemStack s = inv.getStackInSlot(i);
                    if (!s.m_41619_() && ItemHandlerHelper.canItemStacksStack(s, candidateOut)) {
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

    /* ---------------- 阶段二：计划取料 / 工作台 ---------------- */

    private static void planFetch(EntityMaid maid, ServerLevel level, Attempt a) {
        if (!(maid.m_269323_() instanceof ServerPlayer owner) || owner.m_9236_() != level) {
            abortQuietly(maid, level);
            return;
        }
        IItemHandler inv = maid.getAvailableBackpackInv();
        int cap = Math.max(1, com.maidsmart.config.MaidSmartConfig.AMMO_CRAFT_MAX_CRAFT.get());
        // 对每种材料：背包缺多少 → 主人附近哪个箱子存得最多（一个箱子解决就认它）
        int radius = Math.max(4, com.maidsmart.config.MaidSmartConfig.AMMO_CRAFT_RADIUS.get());
        List<BlockEntity> blockEntities = blockEntitiesNear(level, owner.m_20183_(), radius);
        for (Mat m : a.mats) {
            int need = m.perBatch * cap;
            int have = countInInv(inv, m);
            if (have >= need) {
                continue;
            }
            BlockEntity best = null;
            int bestN = 0;
            for (BlockEntity be : blockEntities) {
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
                a.queue.add(new Fetch(best.m_58899_(), m, Math.min(bestN, need - have)));
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
        // TACZ 配方：主人附近有枪械工作台就走到台边合成（对齐"子弹是在家里的制作台上做的"）
        if (a.atTable) {
            BlockPos table = findSmithTable(level, owner.m_20183_(), radius);
            if (table != null) {
                a.targetChest = table;
                a.targetIsTable = true;
                a.phase = Phase.GOTO;
                double d = maid.m_20183_().m_123331_(table);
                a.walkDeadline = level.m_46467_() + 200 + (long) (Math.sqrt(Math.max(0, d)) * 8);
                BehaviorUtils.m_22617_(maid, table, 0.7f, 2);
                return;
            }
            // 附近没有工作台 → 原地合成（材料流程不变，见 startNextFetch 的空队分支）
        }
        startNextFetch(maid, level, a);
    }

    /** 主人附近最近的枪械工作台方块实体（A/B/C 三档都认）；没有返回 null */
    private static BlockPos findSmithTable(ServerLevel level, BlockPos center, int radius) {
        Object[] api = taczTableApi();
        if (api == null || api.length == 0) {
            return null;
        }
        Class<?> beCls = (Class<?>) api[5];
        for (BlockEntity be : blockEntitiesNear(level, center, radius)) {
            try {
                if (beCls.isInstance(be)) {
                    return be.m_58899_();
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
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
            sb.append(new ItemStack(m.rep).m_41786_().getString()).append("×").append(lack);
        }
        return sb.length() == 0 ? "一些材料" : sb.toString();
    }

    private static void startNextFetch(EntityMaid maid, ServerLevel level, Attempt a) {
        a.targetChest = null;
        a.targetIsTable = false;
        while (!a.queue.isEmpty() && level.m_7702_(a.queue.get(0).pos) == null) {
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
        double d = maid.m_20183_().m_123331_(f.pos);
        // 距离越远给的时间越多：基础 10 秒 + 每 8 格 1 秒（跟随任务抢 WALK_TARGET 打不赢时诚实超时）
        a.walkDeadline = level.m_46467_() + 200 + (long) (Math.sqrt(Math.max(0, d)) * 8);
        BehaviorUtils.m_22617_(maid, f.pos, 0.7f, 2);
    }

    /* ---------------- 阶段三：走箱 / 走台 / 取料 ---------------- */

    private static void tickAttempt(EntityMaid maid, ServerLevel level, Attempt a) {
        // 【不在战斗中】贯穿全程：开打 / 被骑乘 / 坐下 / 枪离手 → 悄悄放弃（不说话，材料留在背包）
        if (inCombat(maid) || maid.m_20159_() || maid.isMaidInSittingPose()
                || !GunCompat.isGun(maid.m_21205_())) {
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
        if (a.targetChest == null || level.m_7702_(a.targetChest) == null) {
            if (a.targetIsTable) {
                // 工作台没了 → 原地合成（材料都在身上，不再找下一个台）
                a.targetIsTable = false;
                a.phase = Phase.CRAFT;
                return;
            }
            a.queue.remove(0);
            startNextFetch(maid, level, a);
            return;
        }
        double dSq = maid.m_20183_().m_123331_(a.targetChest);
        if (dSq > REACH_SQ) {
            if (level.m_46467_() > a.walkDeadline) {
                fail(maid, level, a, a.targetIsTable
                        ? "材料都拿到了，但我走不到制作台，先在原地做啦～"
                        : "装材料的箱子太远，我走不过去，子弹先补不成了～");
                return;
            }
            // 还在路上：走位目标丢了就补一次（TLM 的移动 sink 每 tick 会消费它）
            if (maid.m_6274_().m_21952_(MemoryModuleType.f_26370_).isEmpty()) {
                BehaviorUtils.m_22617_(maid, a.targetChest, 0.7f, 2);
            }
            return;
        }
        if (a.targetIsTable) {
            // 到台：挥一下手（"在制作台前操作"），直接合成——工作台没有箱盖动画
            maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND);
            a.phase = Phase.CRAFT;
            return;
        }
        // 到箱：开箱盖，等几 tick 再取（"走过去 → 开箱 → 取 → 关箱"，同 BuildContainerFetchBehavior）
        level.m_46796_(1, a.targetChest, 1);
        a.openTicks = OPEN_WAIT_TICKS;
        a.phase = Phase.TAKE;
    }

    private static void tickTake(EntityMaid maid, ServerLevel level, Attempt a) {
        if (a.openTicks > 0) {
            a.openTicks--;
            return;
        }
        Fetch f = a.queue.get(0);
        if (level.m_7702_(a.targetChest) instanceof Container c) {
            int got = takeItem(c, f.mat, f.count, maid);
            if (got > 0) {
                maid.m_6674_(net.minecraft.world.InteractionHand.MAIN_HAND); // 挥臂"拿东西"
            }
        }
        level.m_46796_(1, a.targetChest, 0); // 关箱盖
        a.queue.remove(0);
        startNextFetch(maid, level, a);
    }

    /** 从容器取材料进她背包（塞不下的还回原槽，不吞东西）——Item 版 take，同 BuildContainerSource 口径 */
    private static int takeItem(Container c, Mat m, int max, EntityMaid maid) {
        int got = 0;
        for (int i = 0; i < c.m_6643_() && got < max; i++) {
            ItemStack s = c.m_8020_(i);
            if (s.m_41619_() || !m.ing.test(s)) {
                continue;
            }
            int move = Math.min(max - got, s.m_41613_());
            ItemStack taken = c.m_7407_(i, move);
            ItemStack remain = ItemHandlerHelper.insertItemStacked(maid.getAvailableBackpackInv(), taken, false);
            if (!remain.m_41619_()) {
                c.m_6836_(i, remain); // 背包满了 → 还回原槽
            }
            got += taken.m_41613_() - remain.m_41613_();
        }
        return got;
    }

    /* ---------------- 阶段四：合成 ---------------- */

    private static void stepCraft(EntityMaid maid, ServerLevel level, Attempt a) {
        if (a.outputTemplate == null || a.outputTemplate.m_41619_()) {
            fail(maid, level, a, "这把枪的子弹我没有合成配方，做不出来，先省着点用哦～");
            return;
        }
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
            // 先确认产物放得下（模拟插入，不消耗任何东西）
            if (!canInsert(inv, a.outputTemplate)) {
                break; // 背包放不下产物 → 停在这里，材料一个没动
            }
            // 按材料单抽料（每种 perBatch 个；中途不够就全数还回，物品守恒）
            List<ItemStack> taken = new ArrayList<>();
            boolean ok = true;
            for (Mat m : a.mats) {
                for (int k = 0; k < m.perBatch && ok; k++) {
                    int slot = findSlot(inv, m.ing);
                    if (slot < 0) {
                        ok = false;
                        break;
                    }
                    taken.add(inv.extractItem(slot, 1, false));
                }
                if (!ok) {
                    break;
                }
            }
            if (!ok) {
                for (ItemStack s : taken) {
                    ItemHandlerHelper.insertItemStacked(inv, s, false);
                }
                break;
            }
            ItemStack out = a.outputTemplate.m_41777_();
            ItemStack remain = ItemHandlerHelper.insertItemStacked(inv, out, false);
            if (!remain.m_41619_()) {
                // 理论到不了（上面模拟过放得下）；最后一道保险：逐槽硬塞
                for (int i = 0; i < inv.getSlots() && !remain.m_41619_(); i++) {
                    remain = inv.insertItem(i, remain, false);
                }
                if (!remain.m_41619_()) {
                    com.maidsmart.tool.PromaidLog.log("弹药补给",
                            com.maidsmart.tool.PromaidLog.nameOf(maid) + " 产物放不下丢失 "
                                    + remain.m_41613_() + " 个（不应发生，现场请反馈）");
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
            feed = GunCompat.canFeed(maid, maid.m_21205_()) || GunCompat.canReload(maid, maid.m_21205_());
        } catch (Throwable t) {
            feed = true; // 兼容层异常时不冤枉她
        }
        com.maidsmart.tool.PromaidLog.log("弹药补给",
                com.maidsmart.tool.PromaidLog.nameOf(maid) + " 合成了 " + made + " 组弹药"
                        + "（配方 " + a.recipeDesc + "，补给后打得响=" + feed + "）");
        say(maid, "子弹补给完成，做了 " + made + " 组，这下能打响啦～");
        finish(maid, level);
    }

    /** 模拟插入：产物整堆放不放得下（不改动任何槽位） */
    private static boolean canInsert(IItemHandler inv, ItemStack stack) {
        int want = stack.m_41613_();
        for (int i = 0; i < inv.getSlots() && want > 0; i++) {
            ItemStack remain = inv.insertItem(i, stack.m_41777_(), true);
            want -= stack.m_41613_() - remain.m_41613_();
        }
        return want <= 0;
    }

    private static int findSlot(IItemHandler inv, Ingredient ing) {
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.m_41619_() && ing.test(s)) {
                return i;
            }
        }
        return -1;
    }

    private static int countInInv(IItemHandler inv, Mat m) {
        int n = 0;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (!s.m_41619_() && m.ing.test(s)) {
                n += s.m_41613_();
            }
        }
        return n;
    }

    private static int countInContainer(Container c, Mat m) {
        int n = 0;
        for (int i = 0; i < c.m_6643_(); i++) {
            ItemStack s = c.m_8020_(i);
            if (!s.m_41619_() && m.ing.test(s)) {
                n += s.m_41613_();
            }
        }
        return n;
    }

    /** 中心附近的方块实体（按区块枚举，同 BuildContainerSource.blockEntitiesIn 的口径） */
    private static List<BlockEntity> blockEntitiesNear(ServerLevel level, BlockPos center, int radius) {
        List<BlockEntity> out = new ArrayList<>();
        int[] box = {center.m_123341_() - radius, center.m_123342_() - radius, center.m_123343_() - radius,
                center.m_123341_() + radius, center.m_123342_() + radius, center.m_123343_() + radius};
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
                net.minecraft.world.level.chunk.LevelChunk chunk = level.m_6325_(cx, cz);
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
        COOLDOWN.put(maid, level.m_46467_() + 100L);
    }

    private static void cooldown(EntityMaid maid, ServerLevel level) {
        int sec = Math.max(5, com.maidsmart.config.MaidSmartConfig.AMMO_CRAFT_COOLDOWN.get());
        COOLDOWN.put(maid, level.m_46467_() + sec * 20L);
    }

    private static void say(EntityMaid maid, String text) {
        try {
            maid.getChatBubbleManager().addTextChatBubble(text);
        } catch (Throwable ignored) {
        }
    }
}
