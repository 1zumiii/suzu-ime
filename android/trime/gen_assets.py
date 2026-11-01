#!/usr/bin/env python3
"""生成 APK 自带的 Rime 方案与默认词库包，由 build_apk.sh 调用。

来源：rime-suzu 的一份导出（固定提交）+ 本仓库 rime/lua（与 APK 同一提交）。输出到 app/src/main/assets：
  shared/            方案、Lua、默认配置，Trime 同步到共享目录。方案 ID 与主词典名都是 suzu
  rime/<词库目录>/    默认词库包，同步到用户目录；包的主词典与说明放在包目录里，由 App 启用时写到用户目录
  rime/opencc/       rime-suzu 的 OpenCC 配置（emoji 等）。shared/opencc 归 OpenCC 数据任务管，不往里放
  suzu_sources.txt   来源提交，状态页显示
  suzu_packs.tsv     可下载的词库包清单（名字、说明、许可证、提交、下载地址、sha256、大小）
另外把每个词库包打成内容确定的 zip 放进 --pack-out（同样的提交总是得到同样的 sha256），发布到下载地址。
shared/ 里由 Trime 仓库跟踪的主题文件（trime.yaml、tongwenfeng.trime.yaml）和 opencc/ 保留，其余每次重新生成。

用法：gen_assets.py --rime-suzu <目录> --assets <目录> --pack <包名> --pack-out <目录> --sources <说明行>...
"""
import argparse
import os
import re
import shutil
import hashlib
import subprocess
import sys
import zipfile

BASE = "rime_ice"          # rime-suzu 里的基座方案
SCHEMA = "suzu"
KEEP_IN_SHARED = {"trime.yaml", "tongwenfeng.trime.yaml", "opencc"}
# 不进 APK：其他平台、文档、安装与工具脚本、词库（另行打包）、个人数据
EXCLUDE_TOP = {
    ".git", ".github", ".gitignore", ".DS_Store", "others", "windows", "llm-mac", "llm-android", "tools",
    "docs", "install", "install.ps1", "install.sh", "packs", "extras", "AGENTS.md", "LICENSE", "recipe.yaml",
    "squirrel.yaml", "squirrel.custom.yaml", "weasel.yaml", "build", "opencc", "dict_pack.lock",
    f"{BASE}.dict.yaml", "recent_words.tsv",
}
EXCLUDE_SUFFIX = (".userdb", ".gram", ".gguf", "luac.out")
PACK_URL = "https://github.com/1zumiii/rime-suzu/releases/download/dict-packs/{file}"
LUNAR_OLD = "ReverseDb( 'lua/lunar.db' )"
LUNAR_NEW = "ReverseDb(rime_api.get_shared_data_dir() .. '/lua/lunar.db')"   # Lua 的 ReverseDb 只认用户目录下的相对路径


def fail(msg):
    sys.exit(f"gen_assets: {msg}")


def sub_once(pattern, repl, text, what, flags=re.M):
    text, n = re.subn(pattern, repl, text, count=1, flags=flags)
    if n != 1:
        fail(f"没有找到 {what}")
    return text


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def write(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def clean_shared(shared):
    os.makedirs(shared, exist_ok=True)
    for name in os.listdir(shared):
        if name in KEEP_IN_SHARED:
            continue
        p = os.path.join(shared, name)
        shutil.rmtree(p) if os.path.isdir(p) and not os.path.islink(p) else os.remove(p)


def copy_config(src, shared):
    for name in sorted(os.listdir(src)):
        if name in EXCLUDE_TOP or name.startswith("README") or name.startswith("cn_dicts") or name.endswith(EXCLUDE_SUFFIX):
            continue
        s, d = os.path.join(src, name), os.path.join(shared, name)
        shutil.copytree(s, d) if os.path.isdir(s) else shutil.copy2(s, d)


def make_suzu(src, shared):
    # 方案：改 ID，主翻译器与部首反查都用 suzu 词典
    text = read(os.path.join(shared, f"{BASE}.schema.yaml"))
    text = sub_once(rf"^(\s*schema_id:\s*){BASE}\b", rf"\g<1>{SCHEMA}", text, "schema_id")
    text, n = re.subn(rf"^(\s*dictionary:\s*){BASE}\b", rf"\g<1>{SCHEMA}", text, flags=re.M)
    if n != 2:
        fail(f"方案里 dictionary: {BASE} 应有 2 处（主翻译器与部首反查），实际 {n} 处")
    write(os.path.join(shared, f"{SCHEMA}.schema.yaml"), text)
    os.remove(os.path.join(shared, f"{BASE}.schema.yaml"))

    # 补丁：手机专属的 overlay 追加到对应 custom 末尾；词库包的 lexicon_dir 由 App 写在用户目录的 suzu_pack.yaml
    for ov in sorted(os.listdir(os.path.join(src, "llm-android", "trime"))):
        if not ov.endswith(".overlay.yaml"):
            continue
        tgt = os.path.join(shared, ov[: -len(".overlay.yaml")] + ".custom.yaml")
        if not os.path.exists(tgt):
            fail(f"{ov} 没有对应的 custom 文件")
        text = read(tgt)
        write(tgt, text.rstrip("\n") + "\n" + read(os.path.join(src, "llm-android", "trime", ov)))
    custom = read(os.path.join(shared, f"{BASE}.custom.yaml"))
    custom = custom.rstrip("\n") + "\n  # 当前词库包（App 写在用户目录），没有这个文件时忽略\n  __patch: suzu_pack:/patch?\n"
    write(os.path.join(shared, f"{SCHEMA}.custom.yaml"), custom)
    os.remove(os.path.join(shared, f"{BASE}.custom.yaml"))

    # 主题补丁（键盘布局、高度）
    for name in sorted(os.listdir(os.path.join(src, "llm-android", "trime"))):
        if name.endswith(".custom.yaml"):
            shutil.copy2(os.path.join(src, "llm-android", "trime", name), os.path.join(shared, name))

    # 方案列表只有 suzu；手机上不记忆 ascii_punct
    for name in ("default.yaml", "default.custom.yaml"):
        p = os.path.join(shared, name)
        text = read(p)
        text, n = re.subn(rf"^(\s*-\s*schema:\s*){BASE}\b", rf"\g<1>{SCHEMA}", text, flags=re.M)
        if n != 1:
            fail(f"{name} 的 schema_list 里应有 1 处 {BASE}，实际 {n} 处")
        if name == "default.custom.yaml":
            text = sub_once(r"^    - ascii_punct\n", "", text, "save_options 里的 ascii_punct")
        write(p, text)

    p = os.path.join(shared, "lua", "lunar.lua")
    text = read(p)
    if LUNAR_OLD not in text:
        fail("lunar.lua 里没有找到 ReverseDb( 'lua/lunar.db' )")
    write(p, text.replace(LUNAR_OLD, LUNAR_NEW))


def overlay_lua(repo, shared):
    src = os.path.join(repo, "rime", "lua")
    for name in sorted(os.listdir(src)):
        if name.endswith(".lua"):
            shutil.copy2(os.path.join(src, name), os.path.join(shared, "lua", name))


def build_pack(src, pack, dest):
    """装一个包到 dest：cn_dicts_<包>/（含 pack.main.yaml、pack.lock）及 cn_dicts_<包>_cell/ 等"""
    tmp = dest + ".tmp"
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(tmp)
    into = f"cn_dicts_{pack}"
    subprocess.run([sys.executable, "-I", os.path.join(src, "tools", "dict_pack.py"), "install", pack,
                    "--rime-dir", tmp, "--into", into, "--schema", SCHEMA], check=True, stdout=subprocess.DEVNULL)
    os.makedirs(dest, exist_ok=True)
    for name in sorted(os.listdir(tmp)):
        if name.startswith(into):
            shutil.move(os.path.join(tmp, name), os.path.join(dest, name))
    # 主词典与锁文件随包放在包目录里，App 启用这个包时复制到用户目录
    shutil.move(os.path.join(tmp, f"{SCHEMA}.dict.yaml"), os.path.join(dest, into, "pack.main.yaml"))
    shutil.move(os.path.join(tmp, "dict_pack.lock"), os.path.join(dest, into, "pack.lock"))
    shutil.rmtree(tmp)


def zip_dir(root, out):
    """内容确定的 zip：条目排序、时间戳与权限固定"""
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for dirpath, dirnames, files in os.walk(root):
            dirnames.sort()
            for f in sorted(files):
                p = os.path.join(dirpath, f)
                info = zipfile.ZipInfo(os.path.relpath(p, root).replace(os.sep, "/"), (1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o644 << 16
                with open(p, "rb") as fh:
                    z.writestr(info, fh.read(), compresslevel=9)


def packs_table(src):
    with open(os.path.join(src, "packs", "packs.tsv"), encoding="utf-8") as f:
        rows = [l.rstrip("\n").split("\t") for l in f if l.strip() and not l.startswith("#")]
    head = rows[0]
    return [dict(zip(head, r)) for r in rows[1:]]


def make_packs(src, assets, default, pack_out):
    rime = os.path.join(assets, "rime")
    shutil.rmtree(rime, ignore_errors=True)
    build_pack(src, default, rime)
    shutil.copytree(os.path.join(src, "opencc"), os.path.join(rime, "opencc"))
    os.makedirs(pack_out, exist_ok=True)
    lines = []
    for row in packs_table(src):
        name = row["name"]
        work = os.path.join(pack_out, f".{name}")
        shutil.rmtree(work, ignore_errors=True)
        build_pack(src, name, work)
        file = f"{name}-{row['commit'][:12]}.zip"
        zip_dir(work, os.path.join(pack_out, file))
        shutil.rmtree(work)
        with open(os.path.join(pack_out, file), "rb") as fh:
            data = fh.read()
        title = re.split("[：（]", row.get("note", name))[0]
        lines.append("\t".join([name, title, row.get("license", ""), row["commit"], PACK_URL.format(file=file),
                                hashlib.sha256(data).hexdigest(), str(len(data))]))
    write(os.path.join(assets, "suzu_packs.tsv"), "name\ttitle\tlicense\tcommit\turl\tsha256\tsize\n" + "".join(l + "\n" for l in lines))
    return len(lines)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--rime-suzu", required=True)
    ap.add_argument("--assets", required=True)
    ap.add_argument("--pack", default="frost")
    ap.add_argument("--pack-out", required=True)
    ap.add_argument("--sources", nargs="*", default=[])
    a = ap.parse_args()
    repo = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    shared = os.path.join(a.assets, "shared")
    clean_shared(shared)
    copy_config(a.rime_suzu, shared)
    overlay_lua(repo, shared)
    make_suzu(a.rime_suzu, shared)
    n_packs = make_packs(a.rime_suzu, a.assets, a.pack, a.pack_out)
    write(os.path.join(a.assets, "suzu_sources.txt"), "".join(s + "\n" for s in a.sources + [f"词库包 {a.pack}"]))
    n = sum(len(fs) for _, _, fs in os.walk(shared))
    print(f"已生成：shared {n} 个文件，默认词库包 {a.pack}，可下载的包 {n_packs} 个（{a.pack_out}）")


if __name__ == "__main__":
    main()
