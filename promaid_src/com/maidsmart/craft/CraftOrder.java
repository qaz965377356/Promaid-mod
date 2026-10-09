package com.maidsmart.craft;

import net.minecraft.world.item.Item;

import java.util.UUID;

/**
 * CraftOrder —— 一条「委托合成」记录（委托合成 v1 新增）。
 *
 * <p>由界面下单时创建（目标物品 + 数量 + 是否允许自行采集），随 craft 任务写入
 * 调度器（CraftOrderManager）。v1 为内存态（服务器重启后委托不保留，界面重下即可）；
 * 持久化留后续版本（照 BuildArchive 范式）。
 *
 * <p>【物品守恒】本类只是数据：不持有任何物品引用，执行全程由调度器按
 * CraftPlanner 的计划从背包 / 箱子真实取料、真实消耗。
 */
public final class CraftOrder {
    /** 发起委托的玩家（交付与气泡都找他） */
    public final UUID owner;
    /** 目标物品（服务端 Item 引用；网络传输用注册名在包层转换） */
    public final Item target;
    /** 目标数量（已由下单侧钳进合法区间） */
    public final int count;
    /** 缺料时是否允许自行采集（v1 记录、报告用；采集执行在后续版本生效） */
    public final boolean allowGather;
    /** 下单时的 gameTime（回执 / 日志用） */
    public final long createdAtGameTime;

    public CraftOrder(UUID owner, Item target, int count, boolean allowGather, long createdAtGameTime) {
        this.owner = owner;
        this.target = target;
        this.count = count;
        this.allowGather = allowGather;
        this.createdAtGameTime = createdAtGameTime;
    }
}
