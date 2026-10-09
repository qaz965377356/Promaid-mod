package com.maidsmart.craft;

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
 * maid_smart:craft —— 委托合成任务（委托合成 v1 新增）。
 *
 * <p>【玩家需求】「使女仆能帮忙制作玩家所选的某个物品或者道具或者方块……完成指定数量后
 * 传送到主人旁边。如果箱子中只有部分材料，但是缺失的材料是可采集的，女仆可以主动到地点
 * 去采集后，返回合成。」＋「简单场景的缺失材料，支持传送过去进行采集。」
 *
 * <p>【架构】本任务本身**不带行为**——它是委托调度的"家"。真正的循环由
 * {@link CraftOrderManager}（MaidTickEvent 服务）驱动：
 * <ol>
 *   <li>ANALYZE：配方树展开 + 库存对账（女仆背包 + 主人附近 + 自身附近箱子）；</li>
 *   <li>FETCH：把箱子里的材料搬进她背包（走箱开盖，照弹药补给口径）；</li>
 *   <li>GATHER：缺料且可采 → 找点 → 传送 → 切挖矿/伐木任务采集 → 采够传送回（会临时
 *       切到 maid_smart:mine / maid_smart:woodcut，采集完切回本任务）；</li>
 *   <li>CRAFT：按计划的步骤表逐级合成（节拍见配置，物品守恒）；</li>
 *   <li>DELIVER：传送回主人身边交付成品。</li>
 * </ol>
 * 玩家中途手动改任务 = 交还控制权（委托作废）；近敌自动切战斗、安全后切回。
 */
public class MaidCraftOrderTask implements IMaidTask {
    public static final ResourceLocation UID = ResourceLocation.parse("maid_smart:craft");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public ItemStack getIcon() {
        // 工作台 = 委托合成（区别于挖矿/伐木等采集任务）
        return new ItemStack(ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("minecraft:crafting_table")));
    }

    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) {
        return null;
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        // 必须返回可变列表：TLM 的 MaidBrain.registerWorkGoals 会往里追加行为。
        // 本任务在身时：合成/取料/交付都由 CraftOrderManager 按 tick 驱动，无任务行为；
        // 采集阶段临时切到 mine/woodcut 任务完成。
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
        return "crafting items for the owner (craft order)";
    }
}
