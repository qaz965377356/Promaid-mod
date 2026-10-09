package com.maidsmart.craft;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * GatherHook —— 委托合成的「缺料采集」钩子（委托合成 v1 新增；玩家规格：传送式采集）。
 *
 * <p>【玩家原话（规格）】「一些简单场景的缺失材料，应该女仆要支持传送过去进行采集。」
 *
 * <p>【职责】两件事：
 * <ol>
 *   <li><b>可采集判定</b>（给 CraftPlanner 的谓词）：缺失材料属不属于「简单可采场景」——
 *       原木类（原版 logs 标签，含所有木头/菌柄）与常见矿/石类（{@link #MINE_ITEM_BLOCKS} 表）；</li>
 *   <li><b>采集目标钩子</b>（给 MaidMineBehavior 的注入点）：委托采集中，把「目标方块」以
 *       最高价值（{@value #TARGET_VALUE}）并入挖矿候选——挖矿行为本身一行不动，
 *       只在这一处读本类的 {@link #valueFor}（照 {@code AutoMineManager.effectiveSearchRadius}
 *       的既有注入范式）。伐木不需要钩子：砍任何树都算数。</li>
 * </ol>
 *
 * <p>【为什么用注册名字符串而不是原版常量】两树镜像（SRG / Mojmap）下原版 {@code Items.xxx}
 * 字段名不同；本模组统一用「注册名字符串 → 运行时解析」的写法（见 {@code SmartCraftTool}
 * 的物品取法），两树代码一字不差。热路径（挖矿扫描逐格）只查解析好的 {@code Map<Block,Integer>}，
 * 不做字符串转换；字符串解析在首次使用时惰性完成（照 {@code MaidMineBehavior.ensureCustomOres} 口径）。
 */
public final class GatherHook {
    private GatherHook() {
    }

    /** 采集类型 */
    public enum Kind {
        WOOD, MINE, NONE
    }

    /** 命中目标方块的临时价值（远超矿表最高分 500，保证优先挖它） */
    public static final int TARGET_VALUE = 9999;

    /** 原版原木标签（含所有木头/菌柄/去皮变体；与伐木任务同一套 tag 口径） */
    private static final TagKey<Block> LOGS_TAG = BlockTags.create(ResourceLocation.parse("minecraft:logs"));

    /** 原木类物品（注册名 path 集合；方块 id 与物品 id 同名，找点/钩子都用同一份） */
    private static final Set<String> WOOD_IDS = Set.of(
            "oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log", "dark_oak_log",
            "mangrove_log", "cherry_log", "pale_oak_log", "crimson_stem", "warped_stem", "bamboo_block",
            "stripped_oak_log", "stripped_spruce_log", "stripped_birch_log", "stripped_jungle_log",
            "stripped_acacia_log", "stripped_dark_oak_log", "stripped_mangrove_log", "stripped_cherry_log",
            "stripped_crimson_stem", "stripped_warped_stem");

    /** 矿/石类：物品 path → 目标方块 path 列表（v1 硬编码原版常见；找点与钩子共用） */
    private static final Map<String, List<String>> MINE_ITEM_BLOCKS = new HashMap<>();

    static {
        put("raw_iron", "iron_ore", "deepslate_iron_ore");
        put("raw_copper", "copper_ore", "deepslate_copper_ore");
        put("raw_gold", "gold_ore", "deepslate_gold_ore", "nether_gold_ore");
        put("coal", "coal_ore", "deepslate_coal_ore");
        put("diamond", "diamond_ore", "deepslate_diamond_ore");
        put("redstone", "redstone_ore", "deepslate_redstone_ore");
        put("lapis_lazuli", "lapis_ore", "deepslate_lapis_ore");
        put("emerald", "emerald_ore", "deepslate_emerald_ore");
        put("quartz", "nether_quartz_ore");
        // 石类（"简单场景"最常见的一类：搭路、熔炉、石质工具）
        put("cobblestone", "stone", "cobblestone", "deepslate");
        put("stone", "stone", "deepslate");
        put("cobbled_deepslate", "deepslate", "cobbled_deepslate");
        put("deepslate", "deepslate");
        put("sand", "sand");
        put("dirt", "dirt");
    }

    private static void put(String item, String... blocks) {
        MINE_ITEM_BLOCKS.put(item, List.of(blocks));
    }

    /* ---------------- 惰性解析缓存 ---------------- */

    private static volatile Map<Item, Kind> KIND_BY_ITEM;
    private static volatile Set<Block> WOOD_BLOCK_SET;
    private static volatile Map<Block, Integer> MINE_BLOCK_VALUE;

    private static void ensure() {
        if (KIND_BY_ITEM != null) {
            return;
        }
        synchronized (GatherHook.class) {
            if (KIND_BY_ITEM != null) {
                return;
            }
            Map<Item, Kind> kind = new HashMap<>();
            Set<Block> wood = new HashSet<>();
            Map<Block, Integer> mine = new HashMap<>();
            for (String id : WOOD_IDS) {
                Item it = item("minecraft:" + id);
                if (it != null) {
                    kind.put(it, Kind.WOOD);
                }
                Block b = block("minecraft:" + id);
                if (b != null) {
                    wood.add(b);
                }
            }
            for (Map.Entry<String, List<String>> e : MINE_ITEM_BLOCKS.entrySet()) {
                Item it = item("minecraft:" + e.getKey());
                if (it != null) {
                    kind.put(it, Kind.MINE);
                }
                for (String bid : e.getValue()) {
                    Block b = block("minecraft:" + bid);
                    if (b != null) {
                        mine.put(b, TARGET_VALUE);
                    }
                }
            }
            MINE_BLOCK_VALUE = mine;
            WOOD_BLOCK_SET = wood;
            KIND_BY_ITEM = kind;
        }
    }

    private static Item item(String id) {
        try {
            return ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(id));
        } catch (Throwable t) {
            return null;
        }
    }

    private static Block block(String id) {
        try {
            return ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse(id));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String pathOf(Item item) {
        try {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            return id == null ? "" : id.m_135815_(); // m_135815_ = getPath（javap 实证，同 FishingChairService）
        } catch (Throwable t) {
            return "";
        }
    }

    /* ---------------- 对外：判定 / 目标 / 钩子 ---------------- */

    /** 收集端谓词：该物品是否属于「简单可采场景」（给 CraftPlanner 用） */
    public static boolean isGatherable(Item item) {
        return kindOf(item) != Kind.NONE;
    }

    public static Kind kindOf(Item item) {
        if (item == null) {
            return Kind.NONE;
        }
        ensure();
        return KIND_BY_ITEM.getOrDefault(item, Kind.NONE);
    }

    /** 采集中（该女仆当前的目标类型；null = 不在采集） */
    public static Kind activeKind(EntityMaid maid) {
        return ACTIVE.get(maid);
    }

    /** 找点用：该物品的目标方块集合（原木返回空集并提示用 tag——见 {@link #isWoodBlock}） */
    public static Set<Block> probeBlocks(Item item) {
        ensure();
        Kind k = kindOf(item);
        if (k == Kind.WOOD) {
            return Set.of();
        }
        if (k == Kind.MINE) {
            Set<Block> out = new HashSet<>();
            List<String> ids = MINE_ITEM_BLOCKS.get(pathOf(item));
            if (ids != null) {
                for (String bid : ids) {
                    Block b = block("minecraft:" + bid);
                    if (b != null) {
                        out.add(b);
                    }
                }
            }
            return out;
        }
        return Set.of();
    }

    /** 找点用：原木判定（tag，运行时 O(1)） */
    public static boolean isWoodBlock(BlockState state) {
        try {
            return state.m_204336_(LOGS_TAG);
        } catch (Throwable t) {
            return false;
        }
    }

    /* ---------------- 活跃目标表（Manager 写、MineBehavior 读） ---------------- */

    /** 委托采集中：该女仆当前要采的目标类型 */
    private static final Map<EntityMaid, Kind> ACTIVE =
            Collections.synchronizedMap(new WeakHashMap<>());

    public static void begin(EntityMaid maid, Kind kind) {
        if (maid != null && kind != null && kind != Kind.NONE) {
            ACTIVE.put(maid, kind);
        }
    }

    public static void end(EntityMaid maid) {
        ACTIVE.remove(maid);
    }

    /**
     * 挖矿价值钩子（MaidMineBehavior.fullScanOres 读）：
     * 委托采集中且该方块是目标类 → 返回 {@link #TARGET_VALUE}（优先挖它）；否则 null（走原矿表）。
     * 【性能】逐格调用——只做一次 Map 查 + 最多一次 tag 检查，无字符串操作。
     */
    public static Integer valueFor(EntityMaid maid, BlockState state) {
        Kind k = ACTIVE.get(maid);
        if (k == null) {
            return null;
        }
        try {
            if (k == Kind.WOOD) {
                return state.m_204336_(LOGS_TAG) ? TARGET_VALUE : null;
            }
            if (k == Kind.MINE) {
                Map<Block, Integer> m = MINE_BLOCK_VALUE;
                if (m == null) {
                    ensure();
                    m = MINE_BLOCK_VALUE;
                }
                return m == null ? null : m.get(state.m_60734_());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 目标方块名（气泡文案用；只在报告时调，字符串转换不心疼） */
    public static String displayName(Item item) {
        try {
            return new net.minecraft.world.item.ItemStack(item).m_41786_().getString();
        } catch (Throwable t) {
            return pathOf(item);
        }
    }

    /** 备用：原木类方块集合（未用 tag 的兜底路径） */
    static Set<Block> woodBlockSet() {
        ensure();
        return WOOD_BLOCK_SET == null ? Set.of() : WOOD_BLOCK_SET;
    }

    /** 目标方块集合快照（诊断用） */
    public static List<String> debugTargets() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Item, Kind> e : KIND_BY_ITEM.entrySet()) {
            out.add(pathOf(e.getKey()) + "=" + e.getValue());
        }
        return out;
    }
}
