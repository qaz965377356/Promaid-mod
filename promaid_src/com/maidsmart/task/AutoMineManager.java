package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 自主挖矿（远征）调度器——{@link MaidAutoMineTask} 的驱动循环。
 *
 * <p>【玩家原话（规格）】「按需自己出门去挖矿，挖一定时间后传送回家/或者回主人身边。
 * 挖矿过程中如果有怪接近则进行战斗，等近距离身边安全了就继续挖，如果没血了则回到
 * 主人身边或者传送回家，中止本次挖矿。等血回满了再继续。」追加三条：
 * ① 出征方向按矿脉密度偏置（8 方向扫描，偏向矿多的方向）；② 到远征点后找矿范围
 * 远一点（{@link #effectiveSearchRadius} 按倍率放大，MaidMineBehavior 的找矿读它）；
 * ③ 镐子没耐久了/没镐了 → 立即收工回家（TLM 的按需换镐会先烧尽背包备镐，全没了
 * 才轮到本判断）。
 *
 * <p>【状态机】RESTING（auto_mine 任务在身，在家休整）→ 出征（传送 + 切 mine 任务）
 * → AWAY（mine 任务在身，挖矿；近敌切 attack 任务、计时冻结；挖满/低血/没镐 → 回城）
 * → 回城（切回 auto_mine + 传送到主人身边/出征原点）→ RESTING……血回满 + 有镐 →
 * 自动再出发。玩家中途手动改任务 = 交还控制权（状态丢弃）。
 *
 * <p>【全部走已实证的既有机制】任务切换 = {@code ScheduleSwitchGuard.runInternal +
 * maid.setTask}（AutoCombatSwitch 同款，含读回校验与失败重试）；战斗武器 =
 * {@code CombatTaskCompat.prepareSwitch}；近敌判定 = {@code PerceptionManager.dangerActive}
 * （12 格内存活敌对生物，感知系统同口径）+ AutoCombatSwitch 活动标记让位（它先接管
 * 就不重复切）；传送 = MaidHeatEscape.teleportSelf 同款链路（清摔落/清速度/音效/属性包）；
 * 回血 = TLM 膳食系统（背包有食物自动吃，本类只看血量阈值）。
 */
public final class AutoMineManager {
    private AutoMineManager() {
    }

    private static final ResourceLocation AUTO_MINE_UID = ResourceLocation.parse("maid_smart:auto_mine");
    private static final ResourceLocation MINE_UID = ResourceLocation.parse("maid_smart:mine");
    /** TLM 原生攻击任务（javap 实证 TaskAttack 的 uid 常量：touhou_little_maid + attack） */
    private static final ResourceLocation ATTACK_UID = new ResourceLocation("touhou_little_maid", "attack");
    /** 连续安全多少 ms 后把战斗切回挖矿 */
    private static final long COMBAT_SAFE_MS = 5000L;
    /** 离远征点过远（死亡回城/意外传送）→ 视为本次远征已中断 */
    private static final double SITE_LOST_DIST_SQ = 128.0 * 128.0;

    private enum Phase { RESTING, AWAY }

    private static final class State {
        Phase phase = Phase.RESTING;
        /** AWAY 已挖 tick 数（战斗期间冻结——"打完继续挖"不吃时长） */
        long awayTicks = 0;
        /** 出征原点（回城 fallback；主人不在/跨维时回这里） */
        BlockPos origin;
        /** 远征点（离点过远 = 意外中断检测用） */
        BlockPos site;
        /** 战斗是我切出去的（还原到 mine 任务） */
        boolean combatByMe = false;
        /** 连续安全的起点（gameTime；-1 = 不安全中） */
        long safeSince = -1;
        /** 气泡节流 */
        long lastBubble = -100000L;
    }

    private static final Map<EntityMaid, State> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    /* ---------------- 给 MaidMineBehavior 的远征加成 ---------------- */

    /** 是否远征挖矿中（AWAY 且 mine 任务在身）——找矿半径加成的开关 */
    public static boolean isExpeditionMining(EntityMaid maid) {
        State s = STATES.get(maid);
        return s != null && s.phase == Phase.AWAY;
    }

    /** 生效找矿半径：远征时按 autoMine.scanBoost 放大（封顶 64，防扫描预算爆炸） */
    public static int effectiveSearchRadius(EntityMaid maid) {
        int base = com.maidsmart.config.MaidSmartConfig.MINE_SEARCH_RADIUS.get();
        if (!isExpeditionMining(maid)) {
            return base;
        }
        double boost = Math.max(1.0, com.maidsmart.config.MaidSmartConfig.AUTO_MINE_SCAN_BOOST.get());
        return (int) Math.min(64, Math.round(base * boost));
    }

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
            return;
        }
        IMaidTask task = maid.getTask();
        if (task == null) {
            return;
        }
        ResourceLocation uid = task.getUid();
        State s = STATES.get(maid);
        boolean isAutoMine = AUTO_MINE_UID.equals(uid);
        // 玩家中途手动改任务（既不是 auto_mine，也不在我们远征的 mine/attack 上）→ 交还控制权
        if (!isAutoMine && (s == null || s.phase != Phase.AWAY
                || !(MINE_UID.equals(uid) || ATTACK_UID.equals(uid) || s.combatByMe))) {
            STATES.remove(maid);
            return;
        }
        if (s == null) {
            s = new State();
            STATES.put(maid, s);
        }
        if (!com.maidsmart.config.MaidSmartConfig.AUTO_MINE_ENABLED.get()) {
            if (s.phase == Phase.AWAY) {
                recall(maid, level, s, "远征功能被关掉了");
            } else {
                STATES.remove(maid);
            }
            return;
        }
        if (s.phase == Phase.AWAY) {
            tickAway(maid, level, s, uid);
        } else {
            tickResting(maid, level, s);
        }
    }

    /* ---------------- RESTING：在家休整，等血/等镐 ---------------- */

    private static void tickResting(EntityMaid maid, ServerLevel level, State s) {
        long now = level.m_46467_();
        if (now % 20 != 0) {
            return; // 出发条件每秒查一次
        }
        if (maid.m_20159_() || maid.isMaidInSittingPose()) {
            return;
        }
        double hpPct = hpPercent(maid);
        if (hpPct < com.maidsmart.config.MaidSmartConfig.AUTO_MINE_HP_RESUME.get()) {
            bubbleRare(maid, s, "血量还没回满，休息一下再出发～");
            return;
        }
        if (!hasPickaxe(maid)) {
            bubbleRare(maid, s, "没有能用的镐子，带一把给我吧～");
            return;
        }
        depart(maid, level, s);
    }

    /* ---------------- 出征：矿脉密度偏置选点 + 传送 + 切挖矿任务 ---------------- */

    private static void depart(EntityMaid maid, ServerLevel level, State s) {
        BlockPos origin;
        if (maid.m_269323_() instanceof ServerPlayer owner && owner.m_9236_() == level) {
            origin = owner.m_20183_();
        } else {
            origin = maid.m_20183_();
        }
        s.origin = origin;
        int distance = Math.max(16, com.maidsmart.config.MaidSmartConfig.AUTO_MINE_DISTANCE.get());
        BlockPos site = pickSite(level, origin, distance);
        if (site == null) {
            bubbleRare(maid, s, "附近找不到合适的出发位置，等会儿再试～");
            return;
        }
        if (!teleport(maid, level, site)) {
            return; // 传送失败下轮重试
        }
        IMaidTask mineTask = TaskManager.findTask(MINE_UID).orElse(null);
        if (mineTask == null) {
            recall(maid, level, s, "挖矿任务缺失");
            return;
        }
        // 锚点重置：让挖矿行为把锚点"首次定位"在远征点（脚下），不背家里的旧锚
        MaidMineBehavior.resetAnchor(maid.m_19879_());
        try {
            com.maidsmart.combat.CombatTaskCompat.prepareSwitch(maid, mineTask);
        } catch (Throwable ignored) {
        }
        com.maidsmart.schedule.ScheduleSwitchGuard.runInternal(
                maid.m_20148_(), MINE_UID, () -> maid.setTask(mineTask));
        boolean ok = maid.getTask() != null && MINE_UID.equals(maid.getTask().getUid());
        if (!ok) {
            recall(maid, level, s, "挖矿任务切换失败");
            return;
        }
        s.phase = Phase.AWAY;
        s.awayTicks = 0;
        s.site = site;
        s.combatByMe = false;
        s.safeSince = -1;
        say(maid, s, "出门挖矿啦，挖 "
                + com.maidsmart.config.MaidSmartConfig.AUTO_MINE_TRIP_MINUTES.get() + " 分钟就回来～");
        com.maidsmart.tool.PromaidLog.log("自主挖矿",
                com.maidsmart.tool.PromaidLog.nameOf(maid) + " 出征 → " + site.m_123341_() + ","
                        + site.m_123342_() + "," + site.m_123343_());
    }

    /** 8 方向 × 出征距离采样：每方向数"地表点下方 24 格、横向 ±2"的白名单矿数，
     *  偏向矿多的方向（并列随机）。
     *  【未加载区块不传送（玩家规格）】候选方向先过 hasChunkAt——未加载直接跳过该方向，
     *  绝不把女仆传进未加载区块（也不会为了选点去强拉区块生成）；8 个方向全没加载 =
     *  本轮不出征（原路气泡提示，等区块加载了再试）。 */
    private static BlockPos pickSite(ServerLevel level, BlockPos origin, int distance) {
        List<int[]> dirs = new ArrayList<>();
        Collections.addAll(dirs, new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}});
        Collections.shuffle(dirs);
        List<BlockPos> ties = new ArrayList<>();
        int best = -1;
        for (int[] d : dirs) {
            int x = origin.m_123341_() + d[0] * distance;
            int z = origin.m_123343_() + d[1] * distance;
            if (!level.m_46749_(new BlockPos(x, origin.m_123342_(), z))) {
                continue; // 区块未加载 → 不传送过去（不为此强拉区块生成）
            }
            BlockPos surface = surfaceAt(level, x, origin.m_123342_(), z);
            if (surface == null) {
                continue;
            }
            int ore = countOresBelow(level, surface, 24);
            if (ore > best) {
                best = ore;
                ties.clear();
                ties.add(surface);
            } else if (ore == best) {
                ties.add(surface);
            }
        }
        if (ties.isEmpty()) {
            return null;
        }
        return ties.get((int) (level.m_46467_() % ties.size()));
    }

    /** 地表点：从原点 Y 附近找一个"脚下实心且不危险、头两格空"的站位（有界上下搜）。 */
    private static BlockPos surfaceAt(ServerLevel level, int x, int originY, int z) {
        int y = originY;
        // 若起点埋在实心里 → 先向上找到头上有空气的位置（最多升 32）
        int up = 0;
        while (up < 32 && (!level.m_8055_(new BlockPos(x, y, z)).m_60795_()
                || !level.m_8055_(new BlockPos(x, y + 1, z)).m_60795_())) {
            y++;
            up++;
        }
        // 向下找到"脚下实心"（最多 64 格）
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
            return null; // 脚下危险（岩浆/火等）
        }
        return new BlockPos(x, y, z);
    }

    private static int countOresBelow(ServerLevel level, BlockPos surface, int depth) {
        int n = 0;
        for (int dy = 1; dy <= depth; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (MaidMineBehavior.isWhitelistOre(
                            level.m_8055_(new BlockPos(surface.m_123341_() + dx,
                                    surface.m_123342_() - dy, surface.m_123343_() + dz)))) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /* ---------------- AWAY：挖矿中（计时 / 战斗打断 / 低血 / 没镐） ---------------- */

    private static void tickAway(EntityMaid maid, ServerLevel level, State s, ResourceLocation uid) {
        // 死亡回城/被意外传送走：离远征点过远 → 本次作废，回家休整
        if (s.site != null && maid.m_20183_().m_123331_(s.site) > SITE_LOST_DIST_SQ) {
            recall(maid, level, s, "远征被打断了");
            return;
        }
        boolean acsActive = com.maidsmart.combat.AutoCombatSwitch.isAutoCombatActive(maid);
        boolean danger = com.maidsmart.dialogue.PerceptionManager.dangerActive(maid) || acsActive;
        if (danger) {
            s.safeSince = -1;
            // 别的系统（AutoCombatSwitch）已接管战斗 → 让位，等它还原回 mine
            if (!acsActive && !s.combatByMe) {
                engageCombat(maid, level, s);
            }
            return; // 战斗期间远征计时冻结
        }
        if (s.combatByMe) {
            // 我切出去的战斗：连续安全 5 秒 → 切回挖矿（失败下轮重试，不推进计时）
            if (s.safeSince < 0) {
                s.safeSince = level.m_46467_();
                return;
            }
            if (level.m_46467_() - s.safeSince >= COMBAT_SAFE_MS) {
                disengageCombat(maid, level, s);
            }
            return;
        }
        // 任务不是挖矿（被外部改走）→ 交还控制权
        if (!MINE_UID.equals(uid)) {
            STATES.remove(maid);
            return;
        }
        s.awayTicks++;
        long tripTicks = Math.max(1, com.maidsmart.config.MaidSmartConfig.AUTO_MINE_TRIP_MINUTES.get())
                * 60L * 20L;
        if (s.awayTicks >= tripTicks) {
            recall(maid, level, s, "挖够了");
            return;
        }
        double hpPct = hpPercent(maid);
        if (hpPct < com.maidsmart.config.MaidSmartConfig.AUTO_MINE_HP_ABORT.get()) {
            recall(maid, level, s, "血量过低，先回家");
            return;
        }
        if (!hasPickaxe(maid)) {
            recall(maid, level, s, "镐子没了或全坏了");
            return;
        }
    }

    /* ---------------- 战斗接入（TLM 原生攻击任务，AutoCombatSwitch 让位） ---------------- */

    private static void engageCombat(EntityMaid maid, ServerLevel level, State s) {
        IMaidTask attackTask = TaskManager.findTask(ATTACK_UID).orElse(null);
        if (attackTask == null) {
            return; // 找不到攻击任务就不硬切（继续挖/继续计时——危险判定下一轮还会来）
        }
        try {
            com.maidsmart.combat.CombatTaskCompat.prepareSwitch(maid, attackTask);
        } catch (Throwable ignored) {
        }
        com.maidsmart.schedule.ScheduleSwitchGuard.runInternal(
                maid.m_20148_(), ATTACK_UID, () -> maid.setTask(attackTask));
        boolean ok = maid.getTask() != null && ATTACK_UID.equals(maid.getTask().getUid());
        if (ok) {
            s.combatByMe = true;
            s.safeSince = -1;
            com.maidsmart.tool.PromaidLog.log("自主挖矿",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 远征中遇敌 → 切战斗");
        }
    }

    private static void disengageCombat(EntityMaid maid, ServerLevel level, State s) {
        IMaidTask mineTask = TaskManager.findTask(MINE_UID).orElse(null);
        if (mineTask == null) {
            return;
        }
        try {
            com.maidsmart.combat.CombatTaskCompat.prepareSwitch(maid, mineTask);
        } catch (Throwable ignored) {
        }
        com.maidsmart.schedule.ScheduleSwitchGuard.runInternal(
                maid.m_20148_(), MINE_UID, () -> maid.setTask(mineTask));
        boolean ok = maid.getTask() != null && MINE_UID.equals(maid.getTask().getUid());
        if (ok) {
            s.combatByMe = false;
            s.safeSince = -1;
            com.maidsmart.tool.PromaidLog.log("自主挖矿",
                    com.maidsmart.tool.PromaidLog.nameOf(maid) + " 周边安全 → 切回挖矿");
        }
    }

    /* ---------------- 回城 ---------------- */

    private static void recall(EntityMaid maid, ServerLevel level, State s, String reason) {
        // 任务先还原回 auto_mine（若当前在 mine/attack 上）
        IMaidTask task = maid.getTask();
        if (task != null && !AUTO_MINE_UID.equals(task.getUid())) {
            IMaidTask autoTask = TaskManager.findTask(AUTO_MINE_UID).orElse(null);
            if (autoTask != null) {
                try {
                    com.maidsmart.combat.CombatTaskCompat.prepareSwitch(maid, autoTask);
                } catch (Throwable ignored) {
                }
                com.maidsmart.schedule.ScheduleSwitchGuard.runInternal(
                        maid.m_20148_(), AUTO_MINE_UID, () -> maid.setTask(autoTask));
            }
        }
        // 回城目标：默认主人身边（主人不在/跨维 → 出征原点）；可配置为只回原点
        BlockPos dest = null;
        boolean wantOwner = com.maidsmart.config.MaidSmartConfig.AUTO_MINE_RETURN_OWNER.get();
        if (wantOwner && maid.m_269323_() instanceof ServerPlayer owner && owner.m_9236_() == level) {
            dest = owner.m_20183_();
        } else if (s.origin != null) {
            dest = s.origin;
        } else if (ownerPos(maid, level) != null) {
            dest = ownerPos(maid, level);
        }
        if (dest != null) {
            teleport(maid, level, dest);
        }
        MaidMineBehavior.resetAnchor(maid.m_19879_());
        s.phase = Phase.RESTING;
        s.awayTicks = 0;
        s.combatByMe = false;
        s.safeSince = -1;
        say(maid, s, reason + "，我回来啦～血回满就再出发");
        com.maidsmart.tool.PromaidLog.log("自主挖矿",
                com.maidsmart.tool.PromaidLog.nameOf(maid) + " 回城（" + reason + "）");
    }

    /* ---------------- 工具 ---------------- */

    private static BlockPos ownerPos(EntityMaid maid, ServerLevel level) {
        if (maid.m_269323_() instanceof ServerPlayer owner && owner.m_9236_() == level) {
            return owner.m_20183_();
        }
        return null;
    }

    private static double hpPercent(EntityMaid maid) {
        try {
            return maid.m_21223_() / Math.max(1.0f, maid.m_21233_()) * 100.0;
        } catch (Throwable t) {
            return 100.0;
        }
    }

    /** 是否有可用镐（主手或背包：镐类且未损坏——TLM 按需换镐会先烧备镐，全没了才轮到本判断） */
    private static boolean hasPickaxe(EntityMaid maid) {
        if (isUsablePick(maid.m_21205_())) {
            return true;
        }
        try {
            IItemHandler inv = maid.getAvailableBackpackInv();
            for (int i = 0; i < inv.getSlots(); i++) {
                if (isUsablePick(inv.getStackInSlot(i))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isUsablePick(ItemStack stack) {
        if (stack.m_41619_() || !(stack.m_41720_() instanceof PickaxeItem)) {
            return false;
        }
        int max = stack.m_41776_();
        return max <= 0 || stack.m_41773_() < max;
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

    /** 气泡（免语音 + 30 秒节流——远征状态的重复提示不刷屏） */
    private static void bubbleRare(EntityMaid maid, State s, String text) {
        long now = System.currentTimeMillis();
        if (now - s.lastBubble < 30000L) {
            return;
        }
        s.lastBubble = now;
        say(maid, s, text);
    }

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
}
