package com.maidsmart.craft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

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
    private static final int MAID_ROWS_MAX = 4;

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
        super(Component.m_237113_("委托单"));
        this.offHand = offHand;
    }

    /* ==================== S2C 入口（由 PromaidClientSetup 转发） ==================== */

    public static void acceptState(boolean offHand, String[] lines) {
        try {
            Minecraft mc = Minecraft.m_91087_();
            if (mc != null && mc.f_91080_ instanceof CraftOrderScreen s && s.offHand == offHand) {
                s.stateLines = lines == null ? s.stateLines : lines;
            }
        } catch (Throwable ignored) {
        }
    }

    public static void acceptMaids(boolean offHand, List<String[]> rows) {
        try {
            Minecraft mc = Minecraft.m_91087_();
            if (mc != null && mc.f_91080_ instanceof CraftOrderScreen s && s.offHand == offHand) {
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
    protected void m_7856_() {
        int cx = this.f_96543_ / 2;
        int left = cx - 172;
        int top = 30;
        gridLeft = left;
        gridTop = top + 22;

        this.searchBox = new EditBox(this.f_96547_, left, top, COLS * CELL + 4, 18,
                Component.m_237113_("搜索物品（中英文均可）"));
        this.searchBox.m_94199_(48);
        this.searchBox.m_94144_(query);
        this.searchBox.m_94151_(s -> {
            query = s == null ? "" : s.trim();
            page = 0;
        });
        this.m_142416_(searchBox);

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
            addBtn(label, rx, my + i * 18, 160, 17, b -> {
                maidUuid = r[0];
                rebuild();
            });
        }
        int by = my + shown * 18 + 6;
        addBtn("§a下单", rx, by, 160, 20, b -> submit());
        addBtn("§e取消当前", rx, by + 22, 78, 18, b -> sendAction("cancel", "", count));
        addBtn("§c清空全部", rx + 82, by + 22, 78, 18, b -> sendAction("cancel_all", "", count));

        if (!requested) {
            requested = true;
            sendRequest("");
        }
    }

    private void addBtn(String label, int x, int y, int w, int h, Button.OnPress onPress) {
        this.m_142416_(Button.m_253074_(Component.m_237113_(label), onPress)
                .m_252987_(x, y, w, h).m_253136_());
    }

    /** 清空控件并重建（女仆名单/开关变化时） */
    private void rebuild() {
        try {
            this.m_169413_(); // clearWidgets
            this.m_7856_();
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 轮询 / 发送 ==================== */

    @Override
    public void m_86600_() { // tick
        super.m_86600_();
        if (++pollTimer >= POLL_TICKS) {
            pollTimer = 0;
            sendRequest(maidUuid);
        }
    }

    private void sendRequest(String uuid) {
        try {
            CraftOrderNetworking.CHANNEL.sendToServer(
                    new CraftOrderNetworking.RequestPacket(offHand, uuid));
        } catch (Throwable ignored) {
        }
    }

    private void sendAction(String op, String itemId, int cnt) {
        try {
            CraftOrderNetworking.CHANNEL.sendToServer(
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
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(selected);
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
    public boolean m_6375_(double mx, double my, int button) {
        int idx = gridIndexAt(mx, my);
        if (idx >= 0) {
            List<Item> items = pageItems();
            if (idx < items.size()) {
                selected = items.get(idx);
                return true; // 命中网格吃掉点击（照配置面板同款合同）
            }
        }
        return super.m_6375_(mx, my, button);
    }

    private void hint(String s) {
        hint = s;
        hintUntil = System.currentTimeMillis() + 3000;
    }

    /* ==================== 渲染 ==================== */

    @Override
    public void m_280039_(GuiGraphics g) {
        super.m_280039_(g);
        g.m_280509_(0, 0, this.f_96543_, this.f_96544_, 0xB0101018);
    }

    @Override
    public void m_88315_(GuiGraphics g, int mx, int my, float pt) {
        this.m_280039_(g);
        super.m_88315_(g, mx, my, pt);
        int cx = this.f_96543_ / 2;
        g.m_280137_(this.f_96547_, "§b委托单", cx, 10, C_TEXT);

        // 左：物品网格
        List<Item> items = pageItems();
        for (int i = 0; i < items.size(); i++) {
            int gx = gridLeft + (i % COLS) * CELL;
            int gyy = gridTop + (i / COLS) * CELL;
            g.m_280509_(gx, gyy, gx + CELL - 1, gyy + CELL - 1, 0x30101010); // 格子底
            g.m_280480_(new ItemStack(items.get(i)), gx + 1, gyy + 1);
            if (items.get(i) == selected) {
                g.m_280509_(gx, gyy, gx + CELL - 1, gyy + 1, C_SEL);
                g.m_280509_(gx, gyy + CELL - 2, gx + CELL - 1, gyy + CELL - 1, C_SEL);
                g.m_280509_(gx, gyy, gx + 1, gyy + CELL - 1, C_SEL);
                g.m_280509_(gx + CELL - 2, gyy, gx + CELL - 1, gyy + CELL - 1, C_SEL);
            }
        }
        int total = filtered().size();
        int pages = Math.max(1, (total + PER_PAGE - 1) / PER_PAGE);
        g.m_280137_(this.f_96547_, "§7第 " + (page + 1) + "/" + pages + " 页（共 " + total + " 种，翻页看更多）",
                gridLeft + COLS * CELL / 2, gridTop + ROWS * CELL + 24, C_DIM);

        // 悬停名
        int hover = gridIndexAt(mx, my);
        if (hover >= 0 && hover < items.size()) {
            g.m_280653_(this.f_96547_, new ItemStack(items.get(hover)).m_41786_(), mx + 8, my - 6, 0xFFFFFF);
        }

        // 右列文本
        int rx = gridLeft + COLS * CELL + 12;
        String selName = selected == null ? "（未选）" : new ItemStack(selected).m_41786_().getString();
        g.m_280653_(this.f_96547_, Component.m_237113_("§f选中：§b" + selName), rx, 32, C_TEXT);
        g.m_280653_(this.f_96547_, Component.m_237113_("§f数量：§e×" + count), rx, 48, C_TEXT);
        g.m_280653_(this.f_96547_, Component.m_237113_("§f女仆（点击选择）："), rx, top80(), C_TEXT);

        // 底部：委托状态
        int sy = gridTop + ROWS * CELL + 40;
        for (int i = 1; i < stateLines.length && i <= 4; i++) {
            String line = stateLines[i];
            if (line != null && !line.isEmpty()) {
                g.m_280653_(this.f_96547_, Component.m_237113_
                        ((i == 1 ? "§f" : "§7") + line), gridLeft, sy, i == 1 ? C_TEXT : C_DIM);
                sy += 11;
            }
        }

        if (hint != null && System.currentTimeMillis() < hintUntil) {
            g.m_280137_(this.f_96547_, "§e" + hint, cx, this.f_96544_ - 12, C_WARN);
        }
    }

    /** 女仆标题的 y（80 与布局常量对齐；单独方法避免魔法数散布） */
    private int top80() {
        return 30 + 80 - 2;
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
                        String name = new ItemStack(it).m_41786_().getString().toLowerCase();
                        if (name.contains(q)) {
                            out.add(it);
                            continue;
                        }
                        ResourceLocation id = ForgeRegistries.ITEMS.getKey(it);
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
                for (Item it : ForgeRegistries.ITEMS.getValues()) {
                    try {
                        if (!new ItemStack(it).m_41619_()) {
                            list.add(it);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                list.sort(Comparator.comparing(i -> new ItemStack(i).m_41786_().getString()));
            } catch (Throwable ignored) {
            }
            ALL_ITEMS = list;
        }
        return ALL_ITEMS;
    }

    @Override
    public boolean m_7043_() {
        return false;
    }
}
