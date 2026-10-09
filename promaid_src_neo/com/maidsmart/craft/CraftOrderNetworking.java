package com.maidsmart.craft;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 【委托合成】网络层（委托合成 v1 新增）。
 *
 * <p>包一览（**数据读写全在服务端**，客户端 Screen 只管显示与收集操作——
 * 与巡逻航图/压缩盒同一条铁律）：
 * <ul>
 *   <li>{@link OpenCraftOrderPacket}（S2C）：打开委托界面（右键委托单）；</li>
 *   <li>{@link StatePacket}（S2C）：当前选中女仆的委托快照（5 行文本，服务端算好）；</li>
 *   <li>{@link MaidListPacket}（S2C）：自己的女仆名单（uuid + 名字 + 任务），界面打开时推一版；</li>
 *   <li>{@link ActionPacket}（C2S）：下单 / 取消；</li>
 *   <li>{@link RequestPacket}（C2S）：轮询刷新（空 maidUuid = 只要女仆名单）。</li>
 * </ul>
 *
 * <p>【安全】所有 C2S 都校验"玩家手上那一格就是委托单"（照巡逻航图口径）；
 * 目标女仆必须是**这位玩家自己的**（{@link #findOwnMaid}）；下单数量在
 * {@code CraftOrderManager.submit} 里再钳一次。
 */
@EventBusSubscriber(modid = "promaid", bus = EventBusSubscriber.Bus.MOD)
public final class CraftOrderNetworking {

    /** 维度扫描用的巨箱（只取已加载区块里的实体——照巡逻航图口径） */
    private static final AABB WHOLE_DIMENSION =
            new AABB(-3.0E7, -2048.0, -3.0E7, 3.0E7, 2048.0, 3.0E7);

    private CraftOrderNetworking() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToClient(OpenCraftOrderPacket.TYPE,
                StreamCodec.ofMember(OpenCraftOrderPacket::encode, OpenCraftOrderPacket::decode),
                OpenCraftOrderPacket::handle);
        r.playToClient(StatePacket.TYPE,
                StreamCodec.ofMember(StatePacket::encode, StatePacket::decode),
                StatePacket::handle);
        r.playToClient(MaidListPacket.TYPE,
                StreamCodec.ofMember(MaidListPacket::encode, MaidListPacket::decode),
                MaidListPacket::handle);
        r.playToServer(ActionPacket.TYPE,
                StreamCodec.ofMember(ActionPacket::encode, ActionPacket::decode),
                ActionPacket::handle);
        r.playToServer(RequestPacket.TYPE,
                StreamCodec.ofMember(RequestPacket::encode, RequestPacket::decode),
                RequestPacket::handle);
    }

    /** 服务端：打开委托界面（右键委托单）——顺带推一版女仆名单（界面列表要用） */
    public static void openFor(ServerPlayer player, InteractionHand hand) {
        try {
            boolean offHand = hand == InteractionHand.OFF_HAND;
            PacketDistributor.sendToPlayer(player, new OpenCraftOrderPacket(true, offHand));
            sendMaidList(player, offHand);
        } catch (Throwable ignored) {
        }
    }

    /* ==================== S2C 发送 ==================== */

    static void sendMaidList(ServerPlayer player, boolean offHand) {
        try {
            PacketDistributor.sendToPlayer(player, new MaidListPacket(offHand, collectOwnMaids(player)));
        } catch (Throwable ignored) {
        }
    }

    static void sendState(ServerPlayer player, boolean offHand, String[] lines) {
        try {
            PacketDistributor.sendToPlayer(player, new StatePacket(offHand, lines));
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 服务端处理 ==================== */

    private static void onRequest(ServerPlayer player, RequestPacket p) {
        try {
            if (!holdsChart(player, p.offHand)) {
                return;
            }
            if (p.maidUuid == null || p.maidUuid.isEmpty()) {
                sendMaidList(player, p.offHand);
                return;
            }
            EntityMaid maid = findOwnMaid(player, p.maidUuid);
            if (maid != null) {
                sendState(player, p.offHand, CraftOrderManager.snapshot(maid));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void onAction(ServerPlayer player, ActionPacket p) {
        try {
            if (!holdsChart(player, p.offHand)) {
                return;
            }
            EntityMaid maid = findOwnMaid(player, p.maidUuid);
            if (maid == null) {
                reply(player, "§c找不到这位女仆（她必须是你的、且已加载）");
                return;
            }
            if ("cancel".equals(p.op)) {
                CraftOrderManager.cancel(maid);
                sendState(player, p.offHand, CraftOrderManager.snapshot(maid));
                return;
            }
            if ("submit".equals(p.op)) {
                Item item = findItem(p.itemId);
                if (item == null) {
                    reply(player, "§c未知物品 id：" + p.itemId);
                    return;
                }
                String err = CraftOrderManager.submit(player, maid, item, p.count, p.allowGather);
                if (!err.isEmpty()) {
                    reply(player, "§c" + err);
                } else {
                    reply(player, "§a已下单：「" + CraftOrderManager.name(item) + "」×" + p.count);
                }
                sendState(player, p.offHand, CraftOrderManager.snapshot(maid));
                return;
            }
        } catch (Throwable ignored) {
        }
    }

    private static void reply(ServerPlayer player, String msg) {
        try {
            player.displayClientMessage(Component.literal(msg), false);
        } catch (Throwable ignored) {
        }
    }

    /** 玩家手上那一格是不是委托单（照巡逻航图"只对持有者生效"口径） */
    private static boolean holdsChart(ServerPlayer player, boolean offHand) {
        try {
            ItemStack st = player.getItemInHand(offHand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
            return !st.isEmpty() && st.getItem() == com.maidsmart.ProMaidMod.CRAFT_ORDER.get();
        } catch (Throwable t) {
            return false;
        }
    }

    private static Item findItem(String id) {
        try {
            return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按 uuid 找这位玩家的自家女仆（全维度、活着的；照巡逻航图口径） */
    private static EntityMaid findOwnMaid(ServerPlayer player, String uuid) {
        try {
            UUID id = UUID.fromString(uuid);
            for (EntityMaid m : ownMaids(player)) {
                if (id.equals(m.getUUID())) {
                    return m;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 自己的、活着的女仆（按离玩家近→远排） */
    private static List<EntityMaid> ownMaids(ServerPlayer player) {
        try {
            List<EntityMaid> maids = player.level().getEntitiesOfClass(EntityMaid.class, WHOLE_DIMENSION,
                    m -> m.isAlive() && m.getOwner() == player);
            maids.sort(java.util.Comparator
                    .comparingDouble((EntityMaid m) -> m.distanceToSqr(player))
                    .thenComparing(m -> com.maidsmart.tool.PromaidLog.nameOf(m)));
            return maids;
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    private static List<String[]> collectOwnMaids(ServerPlayer player) {
        List<String[]> out = new ArrayList<>();
        try {
            for (EntityMaid m : ownMaids(player)) {
                if (out.size() >= 256) {
                    break;
                }
                String task = "";
                try {
                    if (m.getTask() != null && m.getTask().getUid() != null) {
                        task = m.getTask().getUid().getPath();
                    }
                } catch (Throwable ignored) {
                }
                out.add(new String[]{m.getUUID().toString(), com.maidsmart.tool.PromaidLog.nameOf(m), task});
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /* ==================== 包定义 ==================== */

    /** S2C：打开（open=true）或刷新委托界面。 */
    public static class OpenCraftOrderPacket implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<OpenCraftOrderPacket> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath("maid_smart", "craft_order_open"));

        public final boolean open;
        public final boolean offHand;

        public OpenCraftOrderPacket(boolean open, boolean offHand) {
            this.open = open;
            this.offHand = offHand;
        }

        public static void encode(OpenCraftOrderPacket p, FriendlyByteBuf buf) {
            buf.writeBoolean(p.open);
            buf.writeBoolean(p.offHand);
        }

        public static OpenCraftOrderPacket decode(FriendlyByteBuf buf) {
            return new OpenCraftOrderPacket(buf.readBoolean(), buf.readBoolean());
        }

        public static void handle(OpenCraftOrderPacket p, IPayloadContext ctx) {
            ctx.enqueueWork(() -> com.maidsmart.client.PromaidClientSetup.openCraftOrderScreen(p));
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C：委托状态快照（5 行文本——hasOrder 标志 + 4 行显示文本）。 */
    public static class StatePacket implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<StatePacket> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath("maid_smart", "craft_order_state"));

        public final boolean offHand;
        public final String[] lines;

        public StatePacket(boolean offHand, String[] lines) {
            this.offHand = offHand;
            this.lines = lines == null ? new String[]{"0", "", "", "", ""} : lines;
        }

        public static void encode(StatePacket p, FriendlyByteBuf buf) {
            buf.writeBoolean(p.offHand);
            buf.writeVarInt(p.lines.length);
            for (String s : p.lines) {
                buf.writeUtf(s == null ? "" : s, 512);
            }
        }

        public static StatePacket decode(FriendlyByteBuf buf) {
            boolean offHand = buf.readBoolean();
            int n = buf.readVarInt();
            String[] lines = new String[Math.max(0, Math.min(n, 8))];
            for (int i = 0; i < lines.length; i++) {
                lines[i] = buf.readUtf(512);
            }
            return new StatePacket(offHand, lines);
        }

        public static void handle(StatePacket p, IPayloadContext ctx) {
            ctx.enqueueWork(() -> com.maidsmart.client.PromaidClientSetup.acceptCraftOrderState(p));
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C：自己的女仆名单（每行 {uuid, 名字, 当前任务 path}）。 */
    public static class MaidListPacket implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<MaidListPacket> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath("maid_smart", "craft_order_maids"));

        public final boolean offHand;
        public final List<String[]> rows;

        public MaidListPacket(boolean offHand, List<String[]> rows) {
            this.offHand = offHand;
            this.rows = rows == null ? new ArrayList<>() : rows;
        }

        public static void encode(MaidListPacket p, FriendlyByteBuf buf) {
            buf.writeBoolean(p.offHand);
            buf.writeVarInt(p.rows.size());
            for (String[] r : p.rows) {
                buf.writeUtf(r.length > 0 && r[0] != null ? r[0] : "", 64);
                buf.writeUtf(r.length > 1 && r[1] != null ? r[1] : "", 64);
                buf.writeUtf(r.length > 2 && r[2] != null ? r[2] : "", 64);
            }
        }

        public static MaidListPacket decode(FriendlyByteBuf buf) {
            boolean offHand = buf.readBoolean();
            int n = buf.readVarInt();
            List<String[]> rows = new ArrayList<>();
            for (int i = 0; i < n && i < 256; i++) {
                rows.add(new String[]{buf.readUtf(64), buf.readUtf(64), buf.readUtf(64)});
            }
            return new MaidListPacket(offHand, rows);
        }

        public static void handle(MaidListPacket p, IPayloadContext ctx) {
            ctx.enqueueWork(() -> com.maidsmart.client.PromaidClientSetup.acceptCraftOrderMaids(p));
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S：下单（submit）/ 取消（cancel）。 */
    public static class ActionPacket implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<ActionPacket> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath("maid_smart", "craft_order_action"));

        public final boolean offHand;
        public final String op;
        public final String itemId;
        public final int count;
        public final String maidUuid;
        public final boolean allowGather;

        public ActionPacket(boolean offHand, String op, String itemId, int count,
                            String maidUuid, boolean allowGather) {
            this.offHand = offHand;
            this.op = op == null ? "" : op;
            this.itemId = itemId == null ? "" : itemId;
            this.count = count;
            this.maidUuid = maidUuid == null ? "" : maidUuid;
            this.allowGather = allowGather;
        }

        public static void encode(ActionPacket p, FriendlyByteBuf buf) {
            buf.writeBoolean(p.offHand);
            buf.writeUtf(p.op, 16);
            buf.writeUtf(p.itemId, 96);
            buf.writeVarInt(p.count);
            buf.writeUtf(p.maidUuid, 64);
            buf.writeBoolean(p.allowGather);
        }

        public static ActionPacket decode(FriendlyByteBuf buf) {
            return new ActionPacket(buf.readBoolean(), buf.readUtf(16), buf.readUtf(96),
                    buf.readVarInt(), buf.readUtf(64), buf.readBoolean());
        }

        public static void handle(ActionPacket p, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer player) {
                    onAction(player, p);
                }
            });
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S：轮询刷新（maidUuid 空 = 只要女仆名单）。 */
    public static class RequestPacket implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<RequestPacket> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath("maid_smart", "craft_order_request"));

        public final boolean offHand;
        public final String maidUuid;

        public RequestPacket(boolean offHand, String maidUuid) {
            this.offHand = offHand;
            this.maidUuid = maidUuid == null ? "" : maidUuid;
        }

        public static void encode(RequestPacket p, FriendlyByteBuf buf) {
            buf.writeBoolean(p.offHand);
            buf.writeUtf(p.maidUuid, 64);
        }

        public static RequestPacket decode(FriendlyByteBuf buf) {
            return new RequestPacket(buf.readBoolean(), buf.readUtf(64));
        }

        public static void handle(RequestPacket p, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (ctx.player() instanceof ServerPlayer player) {
                    onRequest(player, p);
                }
            });
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
