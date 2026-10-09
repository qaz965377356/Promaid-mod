package com.maidsmart.craft;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 【委托合成】委托单物品（委托合成 v1 新增）。
 *
 * <p>右键打开「委托界面」：界面上选目标物品与数量，交给附近的女仆去完成——
 * 她会按配方树取料 / 合成指定数量，完成后传送到你身边交付（见
 * {@code CraftOrderManager} 与《委托合成_可行性设计.md》）。
 *
 * <p>光效沿用管理道具惯例（{@code isFoil} 恒 true，与手册/排班表/巡逻航图同款）。
 */
public class CraftOrderItem extends Item {

    public CraftOrderItem(Properties props) {
        super(props);
    }

    /** 常驻附魔光效——与蓝图手册/排班表/巡逻航图同款 */
    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide() && player instanceof ServerPlayer sp) {
            // 服务端发 S2C 开屏（照 PatrolChartItem → PatrolNetworking.openFor 的口径）
            CraftOrderNetworking.openFor(sp, hand);
        }
        return new InteractionResultHolder<>(
                level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.CONSUME,
                stack);
    }
}
