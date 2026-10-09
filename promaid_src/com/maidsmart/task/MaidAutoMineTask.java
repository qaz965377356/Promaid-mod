package com.maidsmart.task;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * maid_smart:auto_mine —— 自主挖矿（远征）任务。
 *
 * <p>【玩家原话】「女仆如果选择的自主挖矿，则按需自己出门去挖矿，挖一定时间后传送回家/
 * 或者回主人身边。挖矿过程中如果有怪接近则进行战斗，等近距离身边安全了就继续挖，
 * 如果没血了则回到主人身边或者传送回家，中止本次挖矿。等血回满了再继续。」
 * 追加：出征方向按矿脉密度偏置；到远征点后找矿范围加大；镐没耐久/没镐即收工回家。
 *
 * <p>【架构】本任务本身**不带挖矿行为**——它是远征调度的"家"。真正的循环由
 * {@link AutoMineManager}（MaidTickEvent 服务）驱动：
 * <ol>
 *   <li>RESTING（本任务在身，在家休整）：血量回到再出发阈值 + 背包有可用镐 → 出征；</li>
 *   <li>出征：按矿脉密度偏置选一个方向（8 方向扫描，偏向矿多的方向），传送到
 *       距离配置格的地表点，**切到 maid_smart:mine 任务**（挖矿行为原样工作，
 *       锚点首次定位 = 脚下 = 远征点；远征中找矿半径按倍率加大）；</li>
 *   <li>AWAY（mine 任务在身）：挖满远征时长 → 回城；近处有怪 → 切 TLM 原生攻击任务
 *       （战斗期间远征计时冻结），持续安全 5 秒 → 切回挖矿继续；血量过低 / 镐子没了
 *       或坏了 → 立即回城中止本次；</li>
 *   <li>回城：任务还原回本任务 + 传送到主人身边（可配置为出征原点），进入 RESTING。</li>
 * </ol>
 * 玩家中途手动改任务 = 交还控制权（远征状态丢弃，不再插手）。
 */
public class MaidAutoMineTask implements IMaidTask {
    public static final ResourceLocation UID = ResourceLocation.parse("maid_smart:auto_mine");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public ItemStack getIcon() {
        // 铁镐 = 远征挖矿（区别于普通挖矿任务的钻石镐图标）
        return new ItemStack(ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("minecraft:iron_pickaxe")));
    }

    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) {
        return null;
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        // 必须返回可变列表：TLM 的 MaidBrain.registerWorkGoals 会往里追加行为。
        // 本任务在身时 = RESTING 休整期（在家站着等回血/备镐），无任务行为；
        // 远征的挖矿/战斗由 AutoMineManager 临时切到 mine/attack 任务完成。
        return new ArrayList<>();
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createRideBrainTasks(EntityMaid maid) {
        return new ArrayList<>();
    }

    @Override
    public boolean workPointTask(EntityMaid maid) {
        return true;
    }

    @Override
    public String getMaidActionSummary() {
        return "resting between auto-mining expeditions";
    }
}
