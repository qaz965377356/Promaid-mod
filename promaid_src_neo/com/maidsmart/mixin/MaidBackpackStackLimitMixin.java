package com.maidsmart.mixin;

import com.github.tartaricacid.touhoulittlemaid.inventory.handler.MaidBackpackHandler;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * 女仆背包堆叠上限（v1.3.x）：每个格子可堆叠更多，配置项 {@code misc.maidInvStackLimit}
 * （默认 64 = 原版行为不变，范围 64~127）。
 *
 * <p>【玩家原话】「我希望女仆背包的物品堆叠能大一点」。
 *
 * <p>【为什么上限是 127】1.20.1 的原版 ItemStack 数量在**网络同步与 NBT 存档里都是
 * byte**（压缩盒 javap 实证：{@code FriendlyByteBuf.writeItem} 用 writeByte、NBT 的
 * Count 也是 byte）——女仆背包打开时同步给界面、下线时写进存档，走的都是这两条路；
 * 超过 127 的堆会被截断（114514 存成 82），等于下线丢物品。所以本功能只做"简单实现"：
 * 上限提到 127（signed byte 上限）为止，再大的数量级需要整套绕开原版序列化（压缩盒
 * 那种自定义存储），不在本功能范围。
 *
 * <p>【落点】javap 实证：{@code EntityMaid.maidInv = new MaidBackpackHandler(36, maid)}——
 * TLM 自己的 ItemStackHandler 子类（36 槽，RangedWrapper 按背包等级截到 6/12/24/36）。
 * mixin 它一处即全覆盖：女仆自己捡物/取物（insertItem/extractItem）、玩家 GUI 搬运
 * （MaidMainContainer$BackpackSlot 是 SlotItemHandler，容量判定走 handler 的
 * getSlotLimit/getStackLimit）、本模组自己的取弹药/取材料代码。hideInv/taskInv 不是
 * 这个类，不受影响；玩家背包与箱子更不沾边。
 *
 * <p>【不可堆叠物品保持原样】上限 1 的物品（工具/附魔书/药水）绝不放大——压缩盒
 * 实测六百一十八的教训：原版任何"插进背包/手里"的代码都按 getMaxStackSize() 只收
 * 1 个、把剩余作为返回值退回，而 TLM 几十处调用点不看返回值 = 凭空蒸发。16 上限的
 * （雪球/末影珍珠）可以提到 127（byte 装得下，序列化安全）。
 *
 * <p>【实现细节】不直接调 {@code stack.getMaxStackSize()}（SRG 名两树不同），改用
 * {@code super.getStackLimit(slot, stack)}（super 实现内部就是它）——两树 mixin 代码
 * 完全一致。这两个方法在 MaidBackpackHandler 里不存在，mixin 以"新增方法"合入，
 * 成为对 ItemStackHandler 父类方法的覆写；Forge items 类不混淆，无需 remap。
 */
@Mixin(value = MaidBackpackHandler.class, remap = false)
public abstract class MaidBackpackStackLimitMixin {

    /** 配置上限，夹紧到 64~127（127 = 1.20.1 物品数量 byte 序列化硬上限） */
    @Unique
    private static int maidSmart$stackLimit() {
        return Math.max(64, Math.min(127,
                com.maidsmart.config.MaidSmartConfig.MAID_INV_STACK_LIMIT.get()));
    }

    /**
     * 单堆上限：物品自带值之上的提升。不可堆叠（≤1）保持原样——见类注释六百一十八条目。
     * （本树官方映射名 getMaxStackSize；1.20.1 侧是 SRG m_41741_，javap 实证两者同为
     * "getItem().getMaxStackSize()" 这一层语义。）
     */
    protected int getStackLimit(int slot, ItemStack stack) {
        int own = stack.getMaxStackSize();
        if (own <= 1) {
            return own;
        }
        return Math.max(own, maidSmart$stackLimit());
    }

    /** 格子容量：同步提上限（GUI 的 SlotItemHandler 容量判定走这里） */
    public int getSlotLimit(int slot) {
        return maidSmart$stackLimit();
    }
}
