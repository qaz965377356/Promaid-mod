"""Generate compile_neo.txt (javac @argfile) for the NeoForge 1.21.1 port.

Classpath:
  - client-1.21.1-20240808.144430-slim.jar  (Minecraft, Mojang-mapped)
  - neoforge-21.1.250-universal.jar / -client.jar (NeoForge APIs)
  - TLM neoforge jar (touhoulittlemaid-1.5.3-neoforge+mc1.21.1.jar)
  - MixinExtras (find 0.x for 1.21), Mixin library (spongepowered mixin via FML boot? compile-time: use
    the 'mixin' classes inside neoforge client jar or MixinExtras jar)
  - guava/gson/netty/etc come from libraries dir (needed only if referenced; javac needs transitive types)
Scan source list from promaid_src_neo.
"""
import os, glob, zipfile

# --- repo root resolution (this file lives in tools/<group>/) ---
def _find_repo_root(start):
    d = os.path.dirname(os.path.abspath(start))
    while True:
        if os.path.isdir(os.path.join(d, ".git")) or os.path.isfile(os.path.join(d, ".git")):
            return d
        parent = os.path.dirname(d)
        if parent == d:
            return os.path.dirname(os.path.abspath(start))
        d = parent
REPO = _find_repo_root(__file__)

MOD = _find_repo_root(__file__)
SRC = os.path.join(MOD, 'promaid_src_neo')

def find(*cands):
    for c in cands:
        if os.path.exists(c):
            return c
    return None

# v1.3.0 mac 适配：库目录可用环境变量 PROMAID_LIB 覆盖（默认保持 Windows 原路径不变）。
# 路径统一用 os.path.join 的分段写法——正/反斜杠段在两个平台都能被 os.path.exists 解析。
LIB = os.environ.get('PROMAID_LIB', r'D:\.minecraft\libraries')
neo = find(os.path.join(LIB, 'net', 'neoforged', 'neoforge', '21.1.250', 'neoforge-21.1.250-client.jar'),
           os.path.join(LIB, 'net', 'neoforged', 'neoforge', '21.1.250', 'neoforge-21.1.250-universal.jar'))
neou = os.path.join(LIB, 'net', 'neoforged', 'neoforge', '21.1.250', 'neoforge-21.1.250-universal.jar')
mc = find(os.path.join(LIB, 'net', 'minecraft', 'client', '1.21.1-20240808.144430', 'client-1.21.1-20240808.144430-srg.jar'))
# TLM 所在 mods 目录：Windows 照旧从 versions 下找；其它平台可用 PROMAID_TLM_MODS 指定
_tlm_mods = os.environ.get('PROMAID_TLM_MODS',
                           os.path.join(os.environ.get('PROMAID_MC_ROOT', r'D:\.minecraft'),
                                        'versions', '1.21.1-NeoForge_21.1.250', 'mods'))
tlm = find(os.path.join(_tlm_mods, 'touhoulittlemaid-1.5.3-neoforge+mc1.21.1.jar'))
if tlm is None:
    for p in glob.glob(os.path.join(_tlm_mods, '*touhoulittlemaid*.jar')):
        tlm = p
        break

cp = [neo, neou, mc, tlm]
# 【顺序必须与运行期一致：NeoForge 补丁 jar 在前，未修补的 vanilla 在后】
# 依据（实测 + javap 对照三个 jar 的 class 标志）：
#   ServerPlayer$RespawnPosAngle
#     - client-1.21.1-…-srg.jar          → final class（【包私有】）
#     - neoforge-21.1.250-client.jar     → public final class（NeoForge 修补版）
#     - neoforge-21.1.250-universal.jar  → 无此类
# MaidBedBlockInteropMixin 要实现 IBlockExtension.getRespawnPosition，其返回类型正是
# ServerPlayer.RespawnPosAngle —— 用【包私有】类型做 public 方法的返回类型，按 JLS
# 是编译错误。旧版 cp=[neo, mc, neou, tlm] 把未修补的 mc 排在修补版前面，javac 取到
# 包私有那份，本该直接编译失败；能过是因为 classpath 上先命中了 neo(client) 里的
# public 版本。顺序写反等于"靠偶然命中"，换成 universal-only classpath 或调整 jar
# 顺序就会突然编译不过。这里改成与运行期一致的 [neo, neou, mc]，消除该隐患。
# NeoForge platform libs (event bus, FML loader, lwjgl, distmarker)
# v1.3.0 mac 适配：版本号改为 glob 探测（不同机器装出来的小版本号不同）
import glob as _g


def _first(*segs):
    hits = sorted(_g.glob(os.path.join(LIB, *segs)))
    return hits[0] if hits else None


cp += [p for p in [
    _first('net', 'neoforged', 'bus', '*', 'bus-*.jar'),
    _first('net', 'neoforged', 'fancymodloader', 'loader', '*', 'loader-*.jar'),
    _first('net', 'neoforged', 'mergetool', '*', 'mergetool-*-api.jar'),  # Dist/distmarker stub
] if p]
# distmarker: inside neoforge client jar? verify; lwjgl from lwjgl dir
for sub in (('org', 'lwjgl', 'lwjgl'), ('org', 'lwjgl', 'lwjgl-glfw')):
    for p in sorted(_g.glob(os.path.join(LIB, *sub, '*', 'lwjgl-*.jar'))):
        cp.append(p)
# copy jars to ASCII-safe paths (javac argfile encoding chokes on CJK paths)
import shutil

libdir = os.path.join(MOD, 'libs_neo')
os.makedirs(libdir, exist_ok=True)
safe = []
for c in cp:
    if c is None:
        continue
    if all(ord(ch) < 128 for ch in c):
        safe.append(c)
    else:
        dst = os.path.join(libdir, os.path.basename(c).replace('[车万女仆] ', '').replace(' ', '_'))
        if not os.path.exists(dst):
            shutil.copy2(c, dst)
        safe.append(dst)
cp = safe
# minimal support libs (compile-time transitive types commonly referenced by signatures we touch)
# v1.3.0 mac 适配：全部改为 glob 探测（版本号随安装器下载的版本浮动）
common_patterns = [
    ('com', 'google', 'guava', 'guava', '*', 'guava-*.jar'),
    ('com', 'google', 'code', 'gson', 'gson', '*', 'gson-*.jar'),
    ('io', 'netty', 'netty-buffer', '*', 'netty-buffer-*.jar'),
    ('io', 'netty', 'netty-common', '*', 'netty-common-*.jar'),
    ('io', 'netty', 'netty-transport', '*', 'netty-transport-*.jar'),
    ('org', 'slf4j', 'slf4j-api', '*', 'slf4j-api-*.jar'),
    ('org', 'spongepowered', 'mixin', '*', 'mixin-*.jar'),
    ('io', 'github', 'llamalad7', 'mixinextras-*', '*', 'mixinextras-*.jar'),
    ('org', 'ow2', 'asm', 'asm', '*', 'asm-*.jar'),
    ('org', 'ow2', 'asm', 'asm-commons', '*', 'asm-commons-*.jar'),
    ('org', 'ow2', 'asm', 'asm-tree', '*', 'asm-tree-*.jar'),
    ('org', 'joml', 'joml', '*', 'joml-*.jar'),
    ('it', 'unimi', 'dsi', 'fastutil', '*', 'fastutil-*.jar'),
    ('org', 'apache', 'commons', 'commons-lang3', '*', 'commons-lang3-*.jar'),
    ('com', 'mojang', 'authlib', '*', 'authlib-*.jar'),
    ('com', 'mojang', 'brigadier', '*', 'brigadier-*.jar'),
    ('com', 'mojang', 'datafixerupper', '*', 'datafixerupper-*.jar'),
    ('com', 'mojang', 'javabridge', '*', 'javabridge-*.jar'),
    ('com', 'mojang', 'logging', '*', 'logging-*.jar'),
    ('org', 'apache', 'maven', 'maven-artifact', '*', 'maven-artifact-*.jar'),
    ('com', 'google', 'code', 'findbugs', 'jsr305', '*', 'jsr305-*.jar'),
    ('org', 'checkerframework', 'checker-qual', '*', 'checker-qual-*.jar'),
]
for seg in common_patterns:
    hits = sorted(_g.glob(os.path.join(LIB, *seg)))
    if hits:
        cp.append(hits[0])
    else:
        print('WARN: support lib not found:', '/'.join(seg))

# mixin / mixinextras availability check
mixinjar = find(os.path.join(LIB, r'org\spongepowered\mixin\mixin\0.8.5\mixin-0.8.5.jar'))
print('mixin jar:', mixinjar)

cp = [c for c in cp if c]
print('classpath entries:')
for c in cp:
    print('  ', c, os.path.getsize(c) if os.path.exists(c) else 'MISSING')

# source list
srcs = []
for dp, dn, fn in os.walk(SRC):
    for f in fn:
        if f.endswith('.java'):
            srcs.append(os.path.join(dp, f))
print('sources:', len(srcs))

out = ['-d', os.path.join(MOD, 'out_promaid_neo').replace('\\', '/'),
       '--release', '21',
       '-proc:none', '-nowarn', '-encoding', 'UTF-8',
       '-Xmaxerrs', '5000',
       '-classpath', (';' if os.name == 'nt' else ':').join(cp).replace('\\', '/')]
out += [s.replace('\\', '/') for s in srcs]
with open(os.path.join(MOD, 'compile_neo.txt'), 'w', encoding='ascii', errors='replace') as fp:
    fp.write(' '.join('"%s"' % s for s in out))
print('saved compile_neo.txt')