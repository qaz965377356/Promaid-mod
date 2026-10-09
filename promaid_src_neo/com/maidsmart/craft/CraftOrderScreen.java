package com.maidsmart.craft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 【委托合成】委托界面（委托合成 v1）。
 *
 * <p>布局：左侧「全物品搜索网格」（中英文搜索 / 翻页 / 点选，与拾取名单子页同款交互），
 * 右侧「数量 + 缺料自采开关 + 女仆列表 + 下单/取消」，底部「进行中委托状态」。
 *
 * <p>【服务端铁律】本类只做显示与收集操作；下单/取消/刷新都发服务端
 * （{@link CraftOrderNetworking}），委托真身只在服务端读写（与巡逻航图/压缩盒同口径）。
 */
public class CraftOrderScreen extends Screen {

    private static final int C_TEXT = 0xFFE8E8E8;
    private static final int C_DIM = 0xFF9A9A9A;
    private static final int C_WARN = 0xFFFFD24A;
    private static final int C_SEL = 0xFF7FE0FF;

    private static final int COLS = 9;
    private static final int ROWS = 4;
    private static final int CELL = 18;
    private static final int PER_PAGE = COLS * ROWS;
    private static final int POLL_TICKS = 20;
    private static final int MAID_ROWS_MAX = 5;

    /** 全物品缓存（注册表冻结后不变；名字排序） */
    private static List<Item> ALL_ITEMS;

    private final boolean offHand;
    private Item selected;
    private int count = 1;
    private String maidUuid = "";
    private boolean allowGather = true;
    private String[] stateLines = {"0", "空闲：等她接单", "", "", ""};
    private List<String[]> maidRows = new ArrayList<>();

    private EditBox searchBox;
    private String query = "";
    private String filteredQuery = "\u0000";
    private List<Item> filteredCache = new ArrayList<>();
    private int page = 0;
    private int gridLeft;
    private int gridTop;
    private int pollTimer;
    private boolean requested;
    private String hint;
    private long hintUntil;

    public CraftOrderScreen(boolean offHand) {
        super(Component.literal("委托单"));
        this.offHand = offHand;
    }

    /* ==================== S2C 入口（由 PromaidClientSetup 转发） ==================== */

    public static void acceptState(boolean offHand, String[] lines) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.screen instanceof CraftOrderScreen s && s.offHand == offHand) {
                s.stateLines = lines == null ? s.stateLines : lines;
            }
        } catch (Throwable ignored) {
        }
    }

    public static void acceptMaids(boolean offHand, List<String[]> rows) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.screen instanceof CraftOrderScreen s && s.offHand == offHand) {
                s.maidRows = rows == null ? new ArrayList<>() : rows;
                if (s.maidUuid.isEmpty() && !s.maidRows.isEmpty()) {
                    s.maidUuid = s.maidRows.get(0)[0];
                }
                s.rebuild();
            }
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 布局 ==================== */

    @Override
    protected void init() {
        int cx = this.width / 2;
        int left = cx - 172;
        int top = 30;
        gridLeft = left;
        gridTop = top + 22;

        this.searchBox = new EditBox(this.font, left, top, COLS * CELL + 4, 18,
                Component.literal("搜索物品（中英文均可）"));
        this.searchBox.setMaxLength(48);
        this.searchBox.setValue(query);
        this.searchBox.setResponder(s -> {
            query = s == null ? "" : s.trim();
            page = 0;
        });
        this.addRenderableWidget(searchBox);

        int gy = gridTop + ROWS * CELL + 4;
        addBtn("§7◀", left, gy, 20, 18, b -> {
            if (page > 0) {
                page--;
            }
        });
        addBtn("§7▶", left + COLS * CELL - 16, gy, 20, 18, b -> {
            if ((page + 1) * PER_PAGE < filtered().size()) {
                page++;
            }
        });

        int rx = left + COLS * CELL + 12;
        addBtn("§6-10", rx, top + 34, 36, 18, b -> count = Math.max(1, count - 10));
        addBtn("§6-1", rx + 38, top + 34, 32, 18, b -> count = Math.max(1, count - 1));
        addBtn("§6+1", rx + 72, top + 34, 32, 18, b -> count = Math.min(4096, count + 1));
        addBtn("§6+10", rx + 106, top + 34, 36, 18, b -> count = Math.min(4096, count + 10));
        addBtn("§b一组", rx, top + 54, 40, 18, b -> count = 64);
        addBtn(allowGather ? "§a缺料自采：开" : "§7缺料自采：关", rx + 44, top + 54, 96, 18,
                b -> {
                    allowGather = !allowGather;
                    rebuild();
                });

        int my = top + 80;
        int shown = Math.min(MAID_ROWS_MAX, maidRows.size());
        for (int i = 0; i < shown; i++) {
            String[] r = maidRows.get(i);
            String label = (r[0].equals(maidUuid) ? "§b▶ " : "§7") + r[1];
            addBtn(label, rx, my + i * 19, 160, 17, b -> {
                maidUuid = r[0];
                rebuild();
            });
        }
        int by = my + shown * 19 + 6;
        addBtn("§a下单", rx, by, 78, 20, b -> submit());
        addBtn("§c取消委托", rx + 82, by, 78, 20, b -> sendAction("cancel", "", count));

        if (!requested) {
            requested = true;
            sendRequest("");
        }
    }

    private void addBtn(String label, int x, int y, int w, int h, Button.OnPress onPress) {
        this.addRenderableWidget(Button.builder(Component.literal(label), onPress)
                .bounds(x, y, w, h).build());
    }

    /** 清空控件并重建（女仆名单/开关变化时） */
    private void rebuild() {
        try {
            this.clearWidgets();
            this.init();
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 轮询 / 发送 ==================== */

    @Override
    public void tick() {
        super.tick();
        if (++pollTimer >= POLL_TICKS) {
            pollTimer = 0;
            sendRequest(maidUuid);
        }
    }

    private void sendRequest(String uuid) {
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new CraftOrderNetworking.RequestPacket(offHand, uuid));
        } catch (Throwable ignored) {
        }
    }

    private void sendAction(String op, String itemId, int cnt) {
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new CraftOrderNetworking.ActionPacket(offHand, op, itemId, cnt, maidUuid, allowGather));
        } catch (Throwable ignored) {
        }
    }

    private void submit() {
        if (selected == null) {
            hint("先在上面选一个物品");
            return;
        }
        if (maidUuid.isEmpty()) {
            hint("先选一位女仆");
            return;
        }
        try {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(selected);
            if (id != null) {
                sendAction("submit", id.toString(), count);
                hint("已发送委托：×" + count);
            }
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 输入 ==================== */

    private int gridIndexAt(double mx, double my) {
        if (mx < gridLeft || my < gridTop) {
            return -1;
        }
        int col = (int) ((mx - gridLeft) / CELL);
        int row = (int) ((my - gridTop) / CELL);
        if (col < 0 || col >= COLS || row < 0 || row >= ROWS) {
            return -1;
        }
        return row * COLS + col;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int idx = gridIndexAt(mx, my);
        if (idx >= 0) {
            List<Item> items = pageItems();
            if (idx < items.size()) {
                selected = items.get(idx);
                return true; // 命中网格吃掉点击（照配置面板同款合同）
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private void hint(String s) {
        hint = s;
        hintUntil = System.currentTimeMillis() + 3000;
    }

    /* ==================== 渲染 ==================== */

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, this.width, this.height, 0xB0101018);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        int cx = this.width / 2;
        g.drawCenteredString(this.font, "§b委托单", cx, 10, C_TEXT);

        // 左：物品网格
        List<Item> items = pageItems();
        for (int i = 0; i < items.size(); i++) {
            int gx = gridLeft + (i % COLS) * CELL;
            int gyy = gridTop + (i / COLS) * CELL;
            g.fill(gx, gyy, gx + CELL - 1, gyy + CELL - 1, 0x30101010); // 格子底
            g.renderItem(new ItemStack(items.get(i)), gx + 1, gyy + 1);
            if (items.get(i) == selected) {
                g.fill(gx, gyy, gx + CELL - 1, gyy + 1, C_SEL);
                g.fill(gx, gyy + CELL - 2, gx + CELL - 1, gyy + CELL - 1, C_SEL);
                g.fill(gx, gyy, gx + 1, gyy + CELL - 1, C_SEL);
                g.fill(gx + CELL - 2, gyy, gx + CELL - 1, gyy + CELL - 1, C_SEL);
            }
        }
        int total = filtered().size();
        int pages = Math.max(1, (total + PER_PAGE - 1) / PER_PAGE);
        g.drawCenteredString(this.font, "§7第 " + (page + 1) + "/" + pages + " 页（共 " + total + " 种，翻页看更多）",
                gridLeft + COLS * CELL / 2, gridTop + ROWS * CELL + 24, C_DIM);

        // 悬停名
        int hover = gridIndexAt(mx, my);
        if (hover >= 0 && hover < items.size()) {
            g.drawString(this.font, new ItemStack(items.get(hover)).getHoverName(), mx + 8, my - 6, 0xFFFFFF);
        }

        // 右列文本
        int rx = gridLeft + COLS * CELL + 12;
        String selName = selected == null ? "（未选）" : new ItemStack(selected).getHoverName().getString();
        g.drawString(this.font, Component.literal("§f选中：§b" + selName), rx, 32, C_TEXT);
        g.drawString(this.font, Component.literal("§f数量：§e×" + count), rx, 48, C_TEXT);
        g.drawString(this.font, Component.literal("§f女仆（点击选择）："), rx, 108, C_TEXT);

        // 底部：委托状态
        int sy = gridTop + ROWS * CELL + 40;
        for (int i = 1; i < stateLines.length && i <= 4; i++) {
            String line = stateLines[i];
            if (line != null && !line.isEmpty()) {
                g.drawString(this.font, Component.literal((i == 1 ? "§f" : "§7") + line),
                        gridLeft, sy, i == 1 ? C_TEXT : C_DIM);
                sy += 11;
            }
        }

        if (hint != null && System.currentTimeMillis() < hintUntil) {
            g.drawCenteredString(this.font, "§e" + hint, cx, this.height - 12, C_WARN);
        }
    }

    /* ==================== 数据 ==================== */

    private List<Item> filtered() {
        if (!query.equals(filteredQuery)) {
            filteredQuery = query;
            List<Item> all = allItems();
            if (query.isEmpty()) {
                filteredCache = all;
            } else {
                String q = query.toLowerCase();
                List<Item> out = new ArrayList<>();
                for (Item it : all) {
                    try {
                        String name = new ItemStack(it).getHoverName().getString().toLowerCase();
                        if (name.contains(q)) {
                            out.add(it);
                            continue;
                        }
                        ResourceLocation id = BuiltInRegistries.ITEM.getKey(it);
                        if (id != null && id.toString().toLowerCase().contains(q)) {
                            out.add(it);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                filteredCache = out;
            }
        }
        return filteredCache;
    }

    private List<Item> pageItems() {
        List<Item> f = filtered();
        int from = Math.min(page * PER_PAGE, f.size());
        int to = Math.min(from + PER_PAGE, f.size());
        return f.subList(from, to);
    }

    /** 全物品缓存（过滤空气；按显示名排序——中文本地化名字自然排在最常用物附近） */
    private static List<Item> allItems() {
        if (ALL_ITEMS == null) {
            List<Item> list = new ArrayList<>();
            try {
                BuiltInRegistries.ITEM.stream().forEach(it -> {
                    try {
                        if (!new ItemStack(it).isEmpty()) {
                            list.add(it);
                        }
                    } catch (Throwable ignored) {
                    }
                });
                list.sort(Comparator.comparing(i -> new ItemStack(i).getHoverName().getString()));
            } catch (Throwable ignored) {
            }
            ALL_ITEMS = list;
        }
        return ALL_ITEMS;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
