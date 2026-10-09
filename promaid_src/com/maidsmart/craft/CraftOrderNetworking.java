package com.maidsmart.craft;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 【委托合成】网络层（委托合成 v1 新增）。
 *
 * <p>包一览（**数据读写全在服务端**，客户端 Screen 只管显示与收集操作——
 * 与巡逻航图/压缩盒同一条铁律）：
 * <ul>
 *   <li>{@link OpenCraftOrderPacket}（S2C，0）：打开委托界面（右键委托单）；</li>
 *   <li>{@link StatePacket}（S2C，1）：当前选中女仆的委托快照（5 行文本，服务端算好）；</li>
 *   <li>{@link MaidListPacket}（S2C，2）：自己的女仆名单（uuid + 名字），界面打开时推一版；</li>
 *   <li>{@link ActionPacket}（C2S，3）：下单 / 取消；</li>
 *   <li>{@link RequestPacket}（C2S，4）：轮询刷新（空 maidUuid = 只要女仆名单）。</li>
 * </ul>
 *
 * <p>【安全】所有 C2S 都校验"玩家手上那一格就是委托单"（照巡逻航图口径）；
 * 目标女仆必须是**这位玩家自己的**（{@link #findOwnMaid}）；下单数量在
 * {@code CraftOrderManager.submit} 里再钳一次。
 */
public final class CraftOrderNetworking {

    private static final String PROTOCOL = "1";
    /** 1.20.1（Forge）通道：与 PatrolNetworking / ScheduleNetworking 同款 SimpleChannel */
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("maid_smart", "craft_order"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    /** 维度扫描用的巨箱（只取已加载区块里的实体——照巡逻航图口径） */
    private static final AABB WHOLE_DIMENSION =
            new AABB(-3.0E7, -2048.0, -3.0E7, 3.0E7, 2048.0, 3.0E7);

    private CraftOrderNetworking() {
    }

    /** 由 {@code ProMaidMod} 构造期调用 */
    public static void register() {
        CHANNEL.registerMessage(0, OpenCraftOrderPacket.class,
                OpenCraftOrderPacket::encode, OpenCraftOrderPacket::decode, OpenCraftOrderPacket::handle);
        CHANNEL.registerMessage(1, StatePacket.class,
                StatePacket::encode, StatePacket::decode, StatePacket::handle);
        CHANNEL.registerMessage(2, MaidListPacket.class,
                MaidListPacket::encode, MaidListPacket::decode, MaidListPacket::handle);
        CHANNEL.registerMessage(3, ActionPacket.class,
                ActionPacket::encode, ActionPacket::decode, ActionPacket::handle);
        CHANNEL.registerMessage(4, RequestPacket.class,
                RequestPacket::encode, RequestPacket::decode, RequestPacket::handle);
    }

    /** 服务端：打开委托界面（右键委托单）——顺带推一版女仆名单（界面列表要用） */
    public static void openFor(ServerPlayer player, InteractionHand hand) {
        try {
            boolean offHand = hand == InteractionHand.OFF_HAND;
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new OpenCraftOrderPacket(true, offHand));
            sendMaidList(player, offHand);
        } catch (Throwable ignored) {
        }
    }

    /* ==================== S2C 发送 ==================== */

    static void sendMaidList(ServerPlayer player, boolean offHand) {
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new MaidListPacket(offHand, collectOwnMaids(player)));
        } catch (Throwable ignored) {
        }
    }

    static void sendState(ServerPlayer player, boolean offHand, String[] lines) {
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new StatePacket(offHand, lines));
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
            player.m_213846_(Component.m_237113_(msg));
        } catch (Throwable ignored) {
        }
    }

    /** 玩家手上那一格是不是委托单（照巡逻航图"只对持有者生效"口径） */
    private static boolean holdsChart(ServerPlayer player, boolean offHand) {
        try {
            ItemStack st = player.m_21120_(offHand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
            return !st.m_41619_() && st.m_41720_() == com.maidsmart.ProMaidMod.CRAFT_ORDER.get();
        } catch (Throwable t) {
            return false;
        }
    }

    private static Item findItem(String id) {
        try {
            return ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(id));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按 uuid 找这位玩家的自家女仆（全维度、活着的；照巡逻航图口径） */
    private static EntityMaid findOwnMaid(ServerPlayer player, String uuid) {
        try {
            UUID id = UUID.fromString(uuid);
            for (EntityMaid m : ownMaids(player)) {
                if (id.equals(m.m_20148_())) {
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
            List<EntityMaid> maids = player.m_9236_().m_6443_(EntityMaid.class, WHOLE_DIMENSION,
                    m -> m.m_6084_() && m.m_269323_() == player);
            maids.sort(java.util.Comparator
                    .comparingDouble((EntityMaid m) -> m.m_20280_(player))
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
                        task = m.getTask().getUid().m_135815_(); // getPath（同 FishingChairService 实证）
                    }
                } catch (Throwable ignored) {
                }
                out.add(new String[]{m.m_20148_().toString(), com.maidsmart.tool.PromaidLog.nameOf(m), task});
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /* ==================== 包定义 ==================== */

    /** S2C：打开（open=true）或刷新委托界面。 */
    public static class OpenCraftOrderPacket {

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

        public static void handle(OpenCraftOrderPacket p, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().setPacketHandled(true);
            if (ctx.get().getDirection() != net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT) {
                return;
            }
            ctx.get().enqueueWork(() -> com.maidsmart.client.PromaidClientSetup.openCraftOrderScreen(p));
        }
    }

    /** S2C：委托状态快照（5 行文本——hasOrder 标志 + 4 行显示文本）。 */
    public static class StatePacket {

        public final boolean offHand;
        public final String[] lines;

        public StatePacket(boolean offHand, String[] lines) {
            this.offHand = offHand;
            this.lines = lines == null ? new String[]{"0", "", "", "", ""} : lines;
        }

        public static void encode(StatePacket p, FriendlyByteBuf buf) {
            buf.writeBoolean(p.offHand);
            buf.m_130130_(p.lines.length);
            for (String s : p.lines) {
                buf.m_130072_(s == null ? "" : s, 512);
            }
        }

        public static StatePacket decode(FriendlyByteBuf buf) {
            boolean offHand = buf.readBoolean();
            int n = buf.m_130242_();
            String[] lines = new String[Math.max(0, Math.min(n, 8))];
            for (int i = 0; i < lines.length; i++) {
                lines[i] = buf.m_130136_(512);
            }
            return new StatePacket(offHand, lines);
        }

        public static void handle(StatePacket p, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().setPacketHandled(true);
            if (ctx.get().getDirection() != net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT) {
                return;
            }
            ctx.get().enqueueWork(() -> com.maidsmart.client.PromaidClientSetup.acceptCraftOrderState(p));
        }
    }

    /** S2C：自己的女仆名单（每行 {uuid, 名字, 当前任务 path}）。 */
    public static class MaidListPacket {

        public final boolean offHand;
        public final List<String[]> rows;

        public MaidListPacket(boolean offHand, List<String[]> rows) {
            this.offHand = offHand;
            this.rows = rows == null ? new ArrayList<>() : rows;
        }

        public static void encode(MaidListPacket p, FriendlyByteBuf buf) {
            buf.writeBoolean(p.offHand);
            buf.m_130130_(p.rows.size());
            for (String[] r : p.rows) {
                buf.m_130072_(r.length > 0 && r[0] != null ? r[0] : "", 64);
                buf.m_130072_(r.length > 1 && r[1] != null ? r[1] : "", 64);
                buf.m_130072_(r.length > 2 && r[2] != null ? r[2] : "", 64);
            }
        }

        public static MaidListPacket decode(FriendlyByteBuf buf) {
            boolean offHand = buf.readBoolean();
            int n = buf.m_130242_();
            List<String[]> rows = new ArrayList<>();
            for (int i = 0; i < n && i < 256; i++) {
                rows.add(new String[]{buf.m_130136_(64), buf.m_130136_(64), buf.m_130136_(64)});
            }
            return new MaidListPacket(offHand, rows);
        }

        public static void handle(MaidListPacket p, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().setPacketHandled(true);
            if (ctx.get().getDirection() != net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT) {
                return;
            }
            ctx.get().enqueueWork(() -> com.maidsmart.client.PromaidClientSetup.acceptCraftOrderMaids(p));
        }
    }

    /** C2S：下单（submit）/ 取消（cancel）。 */
    public static class ActionPacket {

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
            buf.m_130072_(p.op, 16);
            buf.m_130072_(p.itemId, 96);
            buf.m_130130_(p.count);
            buf.m_130072_(p.maidUuid, 64);
            buf.writeBoolean(p.allowGather);
        }

        public static ActionPacket decode(FriendlyByteBuf buf) {
            return new ActionPacket(buf.readBoolean(), buf.m_130136_(16), buf.m_130136_(96),
                    buf.m_130242_(), buf.m_130136_(64), buf.readBoolean());
        }

        public static void handle(ActionPacket p, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().setPacketHandled(true);
            if (ctx.get().getDirection() != net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER) {
                return;
            }
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) {
                return;
            }
            ctx.get().enqueueWork(() -> onAction(sender, p));
        }
    }

    /** C2S：轮询刷新（maidUuid 空 = 只要女仆名单）。 */
    public static class RequestPacket {

        public final boolean offHand;
        public final String maidUuid;

        public RequestPacket(boolean offHand, String maidUuid) {
            this.offHand = offHand;
            this.maidUuid = maidUuid == null ? "" : maidUuid;
        }

        public static void encode(RequestPacket p, FriendlyByteBuf buf) {
            buf.writeBoolean(p.offHand);
            buf.m_130072_(p.maidUuid, 64);
        }

        public static RequestPacket decode(FriendlyByteBuf buf) {
            return new RequestPacket(buf.readBoolean(), buf.m_130136_(64));
        }

        public static void handle(RequestPacket p, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().setPacketHandled(true);
            if (ctx.get().getDirection() != net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER) {
                return;
            }
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) {
                return;
            }
            ctx.get().enqueueWork(() -> onRequest(sender, p));
        }
    }
}
