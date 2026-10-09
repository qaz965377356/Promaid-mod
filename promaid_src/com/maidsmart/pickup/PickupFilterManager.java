package com.maidsmart.pickup;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidPickupEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * 女仆拾取过滤——不拾取名单 + 拾取即销毁名单（misc.pickupBlacklist / misc.pickupDestroy，
 * 面板「杂项 → 女仆拾取名单」管理）。
 *
 * <p>【玩家原话】「我另外还希望女仆虽然会自动拾取，但是会自动不拾取，或者销毁一些特定的
 * 物品。物品支持选择配置。」
 *
 * <p>【注入点：TLM 自带 API 事件，零 mixin】javap 实证
 * {@code com.github.tartaricacid.touhoulittlemaid.api.event.MaidPickupEvent}——
 * {@code ItemResultPre} 在女仆拾取一个 {@code ItemEntity} 前发出（带 {@code isSimulate}），
 * {@code setCanPickup(false)} 即可拦下，正是本功能要的口子。
 *
 * <p>【两份名单的语义】
 * <ul>
 *   <li>不拾取：模拟/真实两态都 {@code setCanPickup(false)}——她看见也无视，物品留在地上；</li>
 *   <li>拾取即销毁：模拟态放行（让她照常锁定目标走过去），真实态把 {@code ItemEntity}
 *       {@code discard} 掉（凭空消失，不进背包也不留地上）+ 拒拾。适合挖矿垃圾
 *       （圆石/泥土之类）防淹背包。</li>
 * </ul>
 * 两份名单同时命中以销毁优先。名单外的物品完全不管（TLM 原行为一字不改）。
 *
 * <p>【匹配口径】注册 id 精确（{@code minecraft:cobblestone}）；省略命名空间的条目按
 * {@code minecraft:} 前缀补齐后再比；支持 {@code tacz:*} 这类命名空间通配。
 */
public final class PickupFilterManager {
    private PickupFilterManager() {
    }

    /** 挂载：ProMaidMod 显式注册（与 AmmoResupplyManager.Hook 同款） */
    public static final class Hook {
        @SubscribeEvent
        public void onPickup(MaidPickupEvent.ItemResultPre event) {
            try {
                filter(event);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void filter(MaidPickupEvent.ItemResultPre event) {
        ItemStack stack = event.getEntityItem().m_32055_(); // getItem（javap 实证：ItemEntity 唯一返回 ItemStack 的实例方法）
        String id = idOf(stack);
        if (id == null) {
            return;
        }
        if (inList(com.maidsmart.config.MaidSmartConfig.MISC_PICKUP_DESTROY.get(), id)) {
            if (event.isSimulate()) {
                event.setCanPickup(true); // 模拟：照常锁定目标（走到跟前真拾取阶段销毁）
            } else {
                event.getEntityItem().m_146870_(); // discard：销毁（不进背包也不留地上）
                event.setCanPickup(false);
            }
            return;
        }
        if (inList(com.maidsmart.config.MaidSmartConfig.MISC_PICKUP_BLACKLIST.get(), id)) {
            event.setCanPickup(false); // 不拾取：留在地上
        }
    }

    private static String idOf(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return null;
        }
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
        return key == null ? null : key.toString();
    }

    /** 名单匹配：精确 id / 省略 minecraft: 前缀 / "命名空间:*" 通配 */
    static boolean inList(List<? extends String> list, String id) {
        for (String e : list) {
            if (e == null || e.isEmpty()) {
                continue;
            }
            if (e.equals(id)) {
                return true;
            }
            if (!e.contains(":") && ("minecraft:" + e).equals(id)) {
                return true;
            }
            if (e.endsWith(":*") && id.startsWith(e.substring(0, e.length() - 1))) {
                return true;
            }
        }
        return false;
    }
}
