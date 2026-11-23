#!/usr/bin/env python3
"""SoulMod test client: Prism instance "soulmod-test" (group "SoulMod Test"; Minecraft 26.2, Fabric, the
Soul jar from this checkout), started directly with Prism's libraries as the offline account SoulTester
in a headless gamescope on the desktop GPU, driven with xdotool. It only ever joins the local test
server 127.0.0.1:25590 (tools/testserver/s).

  c install            create/refresh the instance (builds the Soul + dev bridge jars, downloads Fabric API + FLK)
  c update             rebuild the Soul + dev bridge jars and replace them in the instance (client must be down)
  c up [--no-join] [--backend local] [--hot]
                       launch, join 127.0.0.1:25590, wait until in the world (--no-join: title screen;
                       --backend local: talk to the local dev SkyBackend with a pre-issued test bearer;
                       --hot: UI hot reload, see below)
  c down               quit the client
  c status             running? joined? test server port open?
  c shot NAME          F2 screenshot -> tools/testclient/shots/NAME.png
  c click X Y          left click at window pixels (1708 x 960)
  c rclick X Y         right click
  c mclick X Y         middle click
  c move X Y           move the mouse (hover tooltips)
  c cursor             print the pointer position (cursor warps, e.g. menu switches)
  c wheel X Y up|down [N]   mouse wheel over a point
  c key KEY...         xdotool key names (Escape, F3, e, shift+Tab, ...)
  c hold KEY SECS      hold a key down
  c type TEXT          type text into the focused field
  c run "/cmd"         a command as the player: open chat, type, Enter (no screen may be open)
  c log [N]            last N (default 40) lines of the client's latest.log

Hot reload (`c up --hot`, dev-only): the game runs on JetBrains Runtime 25 (downloaded once into
~/.cache/soulmod/jbr, sha512-checked) with -XX:+AllowEnhancedClassRedefinition and the agent
tools/testclient/hotswap/SoulHotswap.java. A background watcher compiles src/main on every save
(./gradlew compileKotlin compileJava -q) and the agent redefines the changed classes in the running
game, structural changes (new methods, fields, lambdas) included. Install the current build first
(c update), the agent only pushes classes compiled after launch. Logs: cache/hotswap/compile.log
(compiles), <game>/logs/hotswap.log (redefinitions). c down stops the watcher.

Wrap a whole session in the lock so agents take turns:
  tools/testclient/lock testclient bash -c 'tools/testclient/c up && ...; tools/testclient/c down'
"""
import hashlib
import json
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import time
import urllib.request
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
PRISM = Path.home() / ".local/share/PrismLauncher"   # the user's Prism: instance, libraries, assets, Java
LIBS, META = PRISM / "libraries", PRISM / "meta"
JAVA = PRISM / "java/java-runtime-epsilon/bin/java"
INSTANCE_ID = "soulmod-test"
GROUP = "SoulMod Test"
INSTANCE = PRISM / "instances" / INSTANCE_ID
GAME = INSTANCE / "minecraft"
ACCOUNT = "SoulTester"
# the offline UUID a server in offline mode derives for this name
ACCOUNT_UUID = uuid.UUID(bytes=hashlib.md5(b"OfflinePlayer:" + ACCOUNT.encode()).digest(), version=3)
TOKEN = "0"   # offline dummy session token; wrap.sh refuses any other
HOST, PORT = "127.0.0.1", 25590
SERVER = "%s:%d" % (HOST, PORT)
# unreachable on purpose: SoulTester has no Mojang session, so backend auth can never succeed
BACKEND = "http://127.0.0.1:9"
# `c up --backend local`: the dev SkyBackend's compose project; its php container issues the test bearer
SKYBACKEND = Path(os.environ.get("SOUL_SKYBACKEND_DIR", Path.home() / "projects/SkyBackend"))
TEST_TOKEN_TTL = 30 * 86400
TEST_TOKEN = HERE / "cache/backend_token.json"
# dev access is per backend: the dev stack may revoke it, and offline mode could never unlock again
DEV_ACCESS = GAME / "config/soul/dev_access.json"
DEV_ACCESS_OFFLINE = HERE / "cache/dev_access.offline.json"
DEV_ACCESS_LOCAL = HERE / "cache/dev_access.local.json"
SOUL_CONFIG = GAME / "config/soul/config.json"
# 2/3 of the user's 2560x1440 / GUI scale 3 / Soul scale 2 setup: same proportions in a smaller window, width even so GUI 2 divides it exactly (GUI 2, Soul 2*2/3)
SOUL_UI_SCALE = float(os.environ.get("SOUL_TEST_UI_SCALE", "1.3333"))   # override per launch, e.g. 1.7787 = a 1080p monitor at Soul 2
W, H = 1708, 960
SHOTS = HERE / "shots"
CACHE = HERE / "cache"
MC, LOADER = "26.2", "0.19.3"
MODS = (("fabric-api", "0.153.0+26.2"), ("fabric-language-kotlin", "1.13.12+kotlin.2.4.0"))
OPTIONS_BASE = PRISM / "instances/26.2 mod testing/minecraft/options.txt"
# `c up --hot`: JetBrains Runtime (GPLv2 + Classpath Exception, like OpenJDK) can redefine classes structurally
JBR_BUILD = "jbrsdk-25.0.5.1-linux-x64-b635.102"
JBR_SHA512 = ("d7e61f7057d4d464a5a8f85c4c830cd7b66e54bbef6080deb0d0b17d817cc0d6"
              "5b0c8b3cf7c89406ad8d2f1be83738497e1535a92c0eeb6f0d203723604b53f6")
JBR_HOME = Path.home() / ".cache/soulmod/jbr" / JBR_BUILD
HOTSWAP = CACHE / "hotswap"
CLASS_DIRS = (REPO / "build/classes/kotlin/main", REPO / "build/classes/java/main")
WATCHER_PID = HOTSWAP / "watcher.pid"

WRAP = """#!/usr/bin/env bash
# Launch wrapper of the SoulMod test instance (tools/testclient): always a headless gamescope,
# rendered on the GPU, no window on the desktop, no audio.
set -euo pipefail

# Offline identity guard: start only with exactly one `--username %s` and one `--accessToken %s`
# in argv, as `c up` passes them. Prism's UI is the wrapper of this instance too, but hands the
# account to the game over stdin, so a launch from Prism (with a real Microsoft account) is refused.
refuse() { echo "wrap.sh: refused: $1; this instance only starts as the offline %s via tools/testclient/c up" >&2; exit 64; }
user= token= nuser=0 ntoken=0 prev=
for a in "$@"; do
    case $prev in
        --username) user=$a ;;
        --accessToken) token=$a ;;
    esac
    case $a in
        --username) nuser=$((nuser + 1)) ;;
        --accessToken) ntoken=$((ntoken + 1)) ;;
        --username=* | --accessToken=*) refuse "inline $a" ;;
    esac
    # joptsimple accepts unambiguous abbreviations (--userna X)
    if [ ${#a} -gt 2 ] && { [[ --username == "$a"* && $a != --username ]] || [[ --accessToken == "$a"* && $a != --accessToken ]]; }; then
        refuse "abbreviated $a"
    fi
    prev=$a
done
[ "$nuser" -eq 1 ] && [ "$ntoken" -eq 1 ] || refuse "need exactly one --username and one --accessToken in argv"
[ "$user" = %s ] && [ "$token" = %s ] || refuse "not the offline test identity"

unset WAYLAND_DISPLAY
export ALSOFT_DRIVERS=null
export SOUL_BACKEND_URL=%s
# NVIDIA only: glvnd/Vulkan must never pick Mesa (llvmpipe under the headless Xwayland).
export __GLX_VENDOR_LIBRARY_NAME=nvidia
export __EGL_VENDOR_LIBRARY_FILENAMES=/usr/share/glvnd/egl_vendor.d/10_nvidia.json
export VK_DRIVER_FILES=/usr/share/vulkan/icd.d/nvidia_icd.json
exec gamescope --backend headless -W %d -H %d -- "$@"
""" % (ACCOUNT, TOKEN, ACCOUNT, ACCOUNT, TOKEN, BACKEND, W, H)


def refuse_hypixel(text):
    if re.search(r"hypixel\.(?:net|io)|mc\.hypixel", text, re.I) or re.match(r"/?(?:connect|server|transfer)\b", text.strip(), re.I):
        sys.exit("refused: the test client only talks to %s, never Hypixel or another server" % SERVER)


# ---------- install ----------

def write_cfg(path, values):
    path.write_text("\n".join(["[General]"] + ["%s=%s" % kv for kv in values.items()]) + "\n")


def download(url, dest, sha512):
    if dest.exists() and hashlib.sha512(dest.read_bytes()).hexdigest() == sha512:
        return
    req = urllib.request.Request(url, headers={"User-Agent": "soulmod-testclient"})
    data = urllib.request.urlopen(req, timeout=60).read()
    if hashlib.sha512(data).hexdigest() != sha512:
        sys.exit("checksum mismatch for %s" % url)
    dest.write_bytes(data)


def modrinth_jar(project, version):
    req = urllib.request.Request("https://api.modrinth.com/v2/project/%s/version" % project,
                                 headers={"User-Agent": "soulmod-testclient"})
    for v in json.load(urllib.request.urlopen(req, timeout=60)):
        if v["version_number"] == version:
            f = next(f for f in v["files"] if f["primary"]) if any(f["primary"] for f in v["files"]) else v["files"][0]
            CACHE.mkdir(parents=True, exist_ok=True)
            dest = CACHE / f["filename"]
            download(f["url"], dest, f["hashes"]["sha512"])
            return dest
    sys.exit("%s %s not on Modrinth" % (project, version))


def soul_jar(build=True):
    """The Soul jar plus the dev-only bridge jar (tools/devbridge), which only this instance gets."""
    if build:
        subprocess.run(["./gradlew", "build", "devbridgeJar", "-q"], cwd=REPO, check=True)
    ver = next(l.split("=", 1)[1].strip() for l in (REPO / "gradle.properties").read_text().splitlines()
               if l.startswith("mod_version="))
    jars = [REPO / ("build/libs/soul-%s.jar" % ver), REPO / ("build/devbridge/soul-devbridge-%s.jar" % ver)]
    for jar in jars:
        if not jar.exists():
            sys.exit("missing %s" % jar)
    return jars


def install_soul(jars):
    mods = GAME / "mods"
    mods.mkdir(parents=True, exist_ok=True)
    for old in mods.glob("soul-*.jar"):
        old.unlink()
    for jar in jars:
        shutil.copy(jar, mods / jar.name)
        print("soul jar:", jar.name)


def nbt_servers():
    """servers.dat (uncompressed NBT) with only the test server; Quick Play looks the entry up."""
    def s(name):
        b = name.encode()
        return struct.pack(">H", len(b)) + b

    entry = (b"\x08" + s("name") + s("SoulMod Test Server") + b"\x08" + s("ip") + s(SERVER)
             + b"\x01" + s("acceptTextures") + b"\x01" + b"\x00")
    return b"\x0a" + s("") + b"\x09" + s("servers") + b"\x0a" + struct.pack(">i", 1) + entry + b"\x00"


def set_options(values):
    opts = GAME / "options.txt"
    cur = dict(l.split(":", 1) for l in opts.read_text().splitlines() if ":" in l) if opts.exists() else {}
    cur.update(values)
    opts.write_text("".join("%s:%s\n" % kv for kv in cur.items()))


def add_group():
    path = PRISM / "instances/instgroups.json"
    data = json.loads(path.read_text())
    group = data.setdefault("groups", {}).setdefault(GROUP, {"hidden": False, "instances": []})
    if INSTANCE_ID in group["instances"]:
        return
    shutil.copy(path, path.with_name("instgroups.json.bak-soulmod-test-%d" % time.time()))
    group["instances"].append(INSTANCE_ID)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=2) + "\n")
    tmp.replace(path)


def maven_path(name):
    g, a, v, *rest = name.split("@")[0].split(":")
    return "%s/%s/%s/%s-%s%s.jar" % (g.replace(".", "/"), a, v, a, v, "-" + rest[0] if rest else "")


def linux_allowed(lib):
    rules = lib.get("rules")
    if not rules:
        return True
    ok = False
    for r in rules:
        if "os" not in r or r["os"].get("name") == "linux":
            ok = r["action"] == "allow"
    return ok


def launch_files():
    """Classpath and asset index from Prism's meta for the same components as mmc-pack.json.
    Prism itself cannot launch an offline account without a Microsoft account that owns Minecraft in
    the same launcher, so the game is started directly; missing libraries are fetched (sha1-checked)."""
    comps = (("org.lwjgl3", "3.4.1"), ("net.minecraft", MC), ("net.fabricmc.intermediary", MC),
             ("net.fabricmc.fabric-loader", LOADER))
    cp, asset_index = {}, None
    for uid, ver in comps:
        meta = json.loads((META / uid / ("%s.json" % ver)).read_text())
        asset_index = meta.get("assetIndex", {}).get("id", asset_index)
        libs = [l for l in meta.get("libraries", []) if linux_allowed(l)]
        if "mainJar" in meta:
            libs.append(meta["mainJar"])
        for lib in libs:
            rel = maven_path(lib["name"])
            path = LIBS / rel
            if not path.exists():
                art = lib.get("downloads", {}).get("artifact", {})
                url = art.get("url") or lib.get("url", "https://libraries.minecraft.net/").rstrip("/") + "/" + rel
                req = urllib.request.Request(url, headers={"User-Agent": "soulmod-testclient"})
                data = urllib.request.urlopen(req, timeout=60).read()
                if art.get("sha1") and hashlib.sha1(data).hexdigest() != art["sha1"]:
                    sys.exit("checksum mismatch for %s" % url)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
            parts = lib["name"].split(":")
            key = (parts[0], parts[1], parts[3] if len(parts) > 3 else "")
            cp.pop(key, None)   # a later component wins (Fabric's ASM over Minecraft's)
            cp[key] = str(path)
    if not (PRISM / ("assets/indexes/%s.json" % asset_index)).exists():
        sys.exit("assets for %s missing: launch any %s instance in Prism once" % (MC, MC))
    return list(cp.values()), asset_index


def cmd_install(_):
    if java_pid():
        sys.exit("client is running: c down first")
    jars = soul_jar()
    GAME.mkdir(parents=True, exist_ok=True)
    pack = {"formatVersion": 1, "components": [
        {"uid": "org.lwjgl3", "version": "3.4.1", "dependencyOnly": True},
        {"uid": "net.minecraft", "version": MC, "important": True},
        {"uid": "net.fabricmc.intermediary", "version": MC, "dependencyOnly": True},
        {"uid": "net.fabricmc.fabric-loader", "version": LOADER}]}
    (INSTANCE / "mmc-pack.json").write_text(json.dumps(pack, indent=4))
    (INSTANCE / "wrap.sh").write_text(WRAP)
    os.chmod(INSTANCE / "wrap.sh", 0o755)
    # Prism runs the game through wrap.sh as well, whose identity guard refuses any launch from Prism's UI
    write_cfg(INSTANCE / "instance.cfg", {
        "ConfigVersion": "1.3", "InstanceType": "OneSix", "name": "SoulMod Test",
        "iconKey": "default", "AutomaticJava": "true",
        "OverrideJavaLocation": "true", "JavaPath": str(JAVA),
        "OverrideMemory": "true", "MinMemAlloc": "1024", "MaxMemAlloc": "4096",
        "OverrideJavaArgs": "true", "JvmArgs": "-Dsoul.backendUrl=%s -Dsoul.testclient=true" % BACKEND,
        "OverrideCommands": "true", "WrapperCommand": str(INSTANCE / "wrap.sh"),
        "OverrideWindow": "true", "LaunchMaximized": "false",
        "MinecraftWinWidth": str(W), "MinecraftWinHeight": str(H),
    })
    mods = GAME / "mods"
    mods.mkdir(exist_ok=True)
    keep = set()
    for project, version in MODS:
        src = modrinth_jar(project, version)
        shutil.copy(src, mods / src.name)
        keep.add(src.name)
        print("mod:", src.name)
    for old in mods.glob("*.jar"):
        if old.name not in keep and not old.name.startswith("soul-"):
            old.unlink()
    install_soul(jars)
    opts = GAME / "options.txt"
    if not opts.exists() and OPTIONS_BASE.exists():
        shutil.copy(OPTIONS_BASE, opts)   # options.txt without `version` is read as an old format and dropped
    elif not opts.exists():
        opts.write_text("version:4903\n")
    sounds = {"soundCategory_%s" % c: "0.0" for c in (
        "master", "music", "record", "weather", "block", "hostile", "neutral", "player", "ambient", "voice", "ui")}
    set_options({**sounds, "guiScale": "2", "fullscreen": "false", "narrator": "0", "narratorHotkey": "false",
                 "pauseOnLostFocus": "false", "onboardAccessibility": "false", "tutorialStep": "none",
                 "skipMultiplayerWarning": "true", "joinedFirstServer": "true", "musicToast": '"never"',
                 "chatVisibility": "0", "chatOpacity": "1.0", "renderDistance": "8", "maxFps": "60",
                 "lang": "en_us", "key_key.screenshot": "key.keyboard.f2", "key_key.command": "key.keyboard.slash",
                 # typed text that misses the chat box must not open screens
                 "key_key.socialInteractions": "key.keyboard.unknown",
                 "key_key.advancements": "key.keyboard.unknown"})
    (GAME / "servers.dat").write_bytes(nbt_servers())
    add_group()
    launch_files()
    print("instance ready:", INSTANCE)


def cmd_update(_):
    if java_pid():
        sys.exit("client is running: c down first")
    if not GAME.exists():
        sys.exit("no instance: c install first")
    install_soul(soul_jar())


# ---------- runtime ----------

def java_pid():
    out = subprocess.run(["pgrep", "-f", "instances/%s" % INSTANCE_ID], capture_output=True, text=True).stdout.split()
    for pid in map(int, out):
        try:
            if Path("/proc/%d/comm" % pid).read_text().strip() == "java":
                return pid
        except OSError:
            pass
    return None


def display():
    pid = java_pid()
    if not pid:
        sys.exit("client not running (c up)")
    env = Path("/proc/%d/environ" % pid).read_bytes().split(b"\0")
    return next(e.split(b"=", 1)[1].decode() for e in env if e.startswith(b"DISPLAY="))


def xdo(*args):
    subprocess.run(["xdotool", *args], env=dict(os.environ, DISPLAY=display()), check=True)


def server_up():
    try:
        with socket.create_connection((HOST, PORT), timeout=1):
            return True
    except OSError:
        return False


def log_text(since=0.0):
    log = GAME / "logs/latest.log"
    try:
        if log.stat().st_mtime < since:
            return None
        return log.read_text(errors="replace")
    except OSError:
        return None


def joined(text):
    i = text.find("Connecting to %s, %d" % (HOST, PORT))
    return i >= 0 and ("joined the game" in text[i:] or "[CHAT]" in text[i:])


# ---------- local backend ----------

def compose(*args):
    if not (SKYBACKEND / "compose.yaml").exists():
        sys.exit("no SkyBackend checkout at %s (set SOUL_SKYBACKEND_DIR)" % SKYBACKEND)
    r = subprocess.run(["docker", "compose", "--project-directory", str(SKYBACKEND), *args], capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("docker compose %s failed (is the dev stack up? `docker compose up -d` in %s):\n%s"
                 % (" ".join(args[:2]), SKYBACKEND, (r.stderr or r.stdout).strip()))
    return r.stdout.strip()


def local_backend_url():
    port = compose("port", "nginx", "80").splitlines()[0].rsplit(":", 1)[1]
    return "http://127.0.0.1:%s" % port


def token_valid(url, token):
    req = urllib.request.Request(url + "/dev/access", headers={"Authorization": token, "X-Intent": "testclient-token-check"})
    try:
        return urllib.request.urlopen(req, timeout=5).status == 200
    except urllib.error.HTTPError as e:
        if e.code == 401:
            return False
        sys.exit("dev backend %s answered HTTP %d to the token check" % (url, e.code))
    except OSError as e:
        sys.exit("dev backend %s unreachable: %s" % (url, e))


def test_token(url):
    """Reuses the cached bearer while the dev stack accepts it, so a fresh one is minted only when
    needed (the backend keeps at most 5 live tokens per player)."""
    try:
        cached = json.loads(TEST_TOKEN.read_text())
        if cached["url"] == url and token_valid(url, cached["token"]):
            return cached["token"]
    except (OSError, ValueError, KeyError):
        pass
    token = compose("exec", "-T", "php", "php", "bin/console", "app:issue-token", ACCOUNT_UUID.hex, ACCOUNT,
                    "--ttl", str(TEST_TOKEN_TTL), "--raw").splitlines()[-1].strip()
    if not token_valid(url, token):
        sys.exit("the freshly issued test bearer is rejected by %s" % url)
    TEST_TOKEN.parent.mkdir(parents=True, exist_ok=True)
    TEST_TOKEN.write_text(json.dumps({"url": url, "token": token}))
    print("issued a test bearer for %s on the dev stack (valid %d days)" % (ACCOUNT, TEST_TOKEN_TTL // 86400))
    return token


def set_soul_scale():
    """Pins Soul's own UI scale to SOUL_UI_SCALE so HUD sizes match a typical player's. Only an existing
    config is patched: the mod writes the versioned file itself on the first launch."""
    try:
        conf = json.loads(SOUL_CONFIG.read_text())
    except (OSError, ValueError):
        return
    if conf.get("general", {}).get("uiScale") != SOUL_UI_SCALE:
        conf.setdefault("general", {})["uiScale"] = SOUL_UI_SCALE
        SOUL_CONFIG.write_text(json.dumps(conf, indent=2))


def swap_dev_access(local):
    """Keeps config/soul/dev_access.json per backend mode (client must be down): the dev stack can
    revoke developer access, and offline mode has no backend to unlock against again."""
    stashed = DEV_ACCESS_OFFLINE.exists()
    if local == stashed:
        return
    leave, enter = (DEV_ACCESS_OFFLINE, DEV_ACCESS_LOCAL) if local else (DEV_ACCESS_LOCAL, DEV_ACCESS_OFFLINE)
    leave.parent.mkdir(parents=True, exist_ok=True)
    leave.write_text(DEV_ACCESS.read_text() if DEV_ACCESS.exists() else "")
    if enter.exists() and enter.read_text():
        DEV_ACCESS.parent.mkdir(parents=True, exist_ok=True)
        DEV_ACCESS.write_text(enter.read_text())
    else:
        DEV_ACCESS.unlink(missing_ok=True)
    if not local:
        DEV_ACCESS_OFFLINE.unlink()


def running_backend():
    pid = java_pid()
    if not pid:
        return None
    args = Path("/proc/%d/cmdline" % pid).read_bytes().split(b"\0")
    url = next((a.split(b"=", 1)[1].decode() for a in args if a.startswith(b"-Dsoul.backendUrl=")), BACKEND)
    return None if url == BACKEND else url


# ---------- hot reload (c up --hot) ----------

def jbr_java():
    """JetBrains Runtime 25 in ~/.cache/soulmod/jbr (outside the repo), fetched once and sha512-checked."""
    java = JBR_HOME / "bin/java"
    if not java.exists():
        url = "https://cache-redirector.jetbrains.com/intellij-jbr/%s.tar.gz" % JBR_BUILD
        print("downloading", url)
        data = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "soulmod-testclient"}),
                                      timeout=600).read()
        if hashlib.sha512(data).hexdigest() != JBR_SHA512:
            sys.exit("checksum mismatch for %s" % url)
        JBR_HOME.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["tar", "xz", "-C", str(JBR_HOME.parent)], input=data, check=True)
    return java


def hotswap_agent():
    """Builds tools/testclient/hotswap/SoulHotswap.java into cache/hotswap/soul-hotswap.jar when it changed."""
    src, jar = HERE / "hotswap/SoulHotswap.java", HOTSWAP / "soul-hotswap.jar"
    if jar.exists() and jar.stat().st_mtime >= src.stat().st_mtime:
        return jar
    classes = HOTSWAP / "classes"
    shutil.rmtree(classes, ignore_errors=True)
    subprocess.run([str(JBR_HOME / "bin/javac"), "--release", "25", "-d", str(classes), str(src)], check=True)
    manifest = HOTSWAP / "MANIFEST.MF"
    manifest.write_text("Premain-Class: SoulHotswap\nCan-Redefine-Classes: true\n")
    subprocess.run([str(JBR_HOME / "bin/jar"), "cfm", str(jar), str(manifest), "-C", str(classes), "."], check=True)
    return jar


def source_mtimes():
    out = {}
    for root, _, files in os.walk(REPO / "src/main"):
        for f in files:
            if f.endswith((".kt", ".java")):
                p = os.path.join(root, f)
                try:
                    out[p] = os.stat(p).st_mtime_ns
                except OSError:
                    pass
    return out


def cmd_watch(_):
    """Background loop of `c up --hot`: compiles src/main after every save. The game-side agent picks up the classes."""
    log = open(HOTSWAP / "compile.log", "a", buffering=1)
    seen = source_mtimes()
    started = False
    while True:
        time.sleep(0.2)
        alive = java_pid() is not None
        if started and not alive:
            return   # the game is gone (c down, crash)
        started |= alive
        now = source_mtimes()
        if now == seen:
            continue
        changed = sorted(p for p in now if now[p] != seen.get(p))
        seen = now
        t0 = time.time()
        r = subprocess.run(["./gradlew", "compileKotlin", "compileJava", "-q"], cwd=REPO, capture_output=True, text=True)
        log.write("%d change %s; compile %s in %.1f s\n" % (t0 * 1000, " ".join(os.path.relpath(p, REPO) for p in changed),
                                                        "ok" if r.returncode == 0 else "FAILED", time.time() - t0))
        if r.returncode:
            log.write(r.stdout + r.stderr)


def stop_watcher():
    try:
        os.killpg(int(WATCHER_PID.read_text()), 15)
    except (OSError, ValueError):
        pass
    WATCHER_PID.unlink(missing_ok=True)


def start_watcher():
    stop_watcher()
    p = subprocess.Popen([sys.executable, str(HERE / "client.py"), "_watch"], stdin=subprocess.DEVNULL,
                         stdout=subprocess.DEVNULL, stderr=open(HOTSWAP / "compile.log", "a"), start_new_session=True)
    WATCHER_PID.write_text(str(p.pid))


def launch(join, local, hot=False):
    if join and not server_up():
        sys.exit("test server not reachable on %s: start it with tools/testserver/s up" % SERVER)
    if not GAME.exists():
        sys.exit("no instance: c install first")
    backend, extra = BACKEND, []
    if local:
        backend = local_backend_url()
        if re.search(r"soulreturns\.com", backend, re.I):
            sys.exit("refused: the test bearer only ever goes to the local dev stack")
        extra = ["-Dsoul.testBearerToken=%s" % test_token(backend)]
    swap_dev_access(local)
    set_soul_scale()
    cp, asset_index = launch_files()
    t0 = time.time()
    java, hot_args = JAVA, []
    if hot:
        java = jbr_java()
        HOTSWAP.mkdir(parents=True, exist_ok=True)
        hot_args = ["-XX:+AllowEnhancedClassRedefinition",
                    "-javaagent:%s=%s" % (hotswap_agent(), ":".join(map(str, CLASS_DIRS)))]
        start_watcher()
    # authlib's Mojang hosts go to the dead port; Realms still contacts Mojang with the dummy token "0"
    hosts = ["-Dminecraft.api.%s.host=%s" % (h, BACKEND) for h in ("auth", "account", "session", "services", "profiles")]
    args = [str(INSTANCE / "wrap.sh"), str(java), "-Xms1024m", "-Xmx4096m", *hot_args, "-Dsoul.backendUrl=%s" % backend,
            "-Dsoul.testclient=true", *extra, *hosts,
            "-cp", ":".join(cp), "net.fabricmc.loader.impl.launch.knot.KnotClient",
            "--username", ACCOUNT, "--uuid", ACCOUNT_UUID.hex, "--accessToken", TOKEN, "--userType", "legacy",
            "--version", MC, "--versionType", "release", "--gameDir", str(GAME),
            "--assetsDir", str(PRISM / "assets"), "--assetIndex", asset_index,
            "--width", str(W), "--height", str(H)]
    if join:
        args += ["--quickPlayMultiplayer", SERVER]
    (GAME / "logs").mkdir(exist_ok=True)
    with open(GAME / "logs/stdout.log", "wb") as out:
        subprocess.Popen(args, cwd=GAME, stdout=out, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                         start_new_session=True)
    while time.time() - t0 < 240:
        if time.time() - t0 > 15 and not java_pid():
            sys.exit("game exited during startup:\n%s" % "\n".join(
                (GAME / "logs/stdout.log").read_text(errors="replace").splitlines()[-25:]))
        time.sleep(0.5)
        text = log_text(t0)
        if text is None:
            continue
        if join:
            if joined(text):
                time.sleep(1)
                return
            if re.search(r"Couldn't connect|Disconnected|Connection refused|lost connection", text[text.find("Connecting to"):] if "Connecting to" in text else ""):
                sys.exit("client could not join %s:\n%s" % (SERVER, "\n".join(text.splitlines()[-15:])))
        elif "Sound engine started" in text or "Created: " in text and "minecraft:textures/atlas" in text:
            time.sleep(4)   # title screen fades in
            return
    sys.exit("client did not %s within 240 s (see %s)" % ("join" if join else "start", GAME / "logs/latest.log"))


def stop():
    pid = java_pid()
    if pid:
        subprocess.run(["kill", str(pid)])
        while java_pid():
            time.sleep(0.2)
    stop_watcher()
    swap_dev_access(False)


def cmd_up(args):
    join, local, hot, rest = True, False, False, list(args)
    while rest:
        a = rest.pop(0)
        if a == "--no-join":
            join = False
        elif a == "--hot":
            hot = True
        elif a == "--backend" and rest and rest[0] in ("local", "off"):
            local = rest.pop(0) == "local"
        else:
            refuse_hypixel(a)
            sys.exit("unknown argument %s (the server is always %s; --backend local|off)" % (a, SERVER))
    if java_pid():
        if join and not joined(log_text() or ""):
            sys.exit("client is running but not joined to %s: c down first" % SERVER)
        if (running_backend() is not None) != local:
            sys.exit("client is running with backend %s: c down first" % (running_backend() or "off"))
        print("client already running")
        return
    launch(join, local, hot)
    print("client up" + (", joined %s" % SERVER if join else " (title screen)") +
          (", backend %s" % running_backend() if local else "") + (", hot reload on" if hot else ""))


def cmd_status(_):
    pid = java_pid()
    text = log_text() or ""
    print("client:", "running (pid %d)" % pid if pid else "down")
    if pid:
        print("joined:", "yes" if joined(text) else "no")
        print("backend:", running_backend() or "off (dead port)")
    print("test server %s:" % SERVER, "up" if server_up() else "down")
    watcher = WATCHER_PID.read_text() if WATCHER_PID.exists() else None
    if watcher and not Path("/proc/%s" % watcher).exists():
        watcher = None
    print("hot reload:", "on (watcher pid %s)" % watcher if watcher else "off")


def cmd_shot(args):
    if not args:
        sys.exit("usage: c shot NAME")
    d = GAME / "screenshots"
    d.mkdir(exist_ok=True)
    before = set(d.iterdir())
    xdo("key", "F2")
    for _ in range(50):
        time.sleep(0.1)
        new = [p for p in set(d.iterdir()) - before if p.suffix == ".png"]
        if new:
            for _ in range(100):   # written completely: the PNG end chunk is there
                with open(new[0], "rb") as f:
                    f.seek(max(0, new[0].stat().st_size - 8))
                    if f.read() == b"IEND\xaeB`\x82":
                        break
                time.sleep(0.2)
            SHOTS.mkdir(parents=True, exist_ok=True)
            out = SHOTS / ("%s.png" % args[0])
            shutil.move(str(new[0]), out)
            print(out)
            return
    sys.exit("no screenshot")


def click(button, x, y):
    xdo("mousemove", x, y)
    time.sleep(0.15)
    xdo("mousedown", button)
    time.sleep(0.08)
    xdo("mouseup", button)


def cmd_log(args):
    n = int(args[0]) if args else 40
    text = log_text() or ""
    print("\n".join(text.splitlines()[-n:]))


def main():
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help", "help"):
        sys.exit(__doc__)
    c, args = sys.argv[1], sys.argv[2:]
    t0 = time.time()
    if c == "install":
        cmd_install(args)
    elif c == "update":
        cmd_update(args)
    elif c == "up":
        cmd_up(args)
    elif c == "down":
        stop()
    elif c == "status":
        cmd_status(args)
    elif c == "shot":
        cmd_shot(args)
    elif c == "click":
        click("1", args[0], args[1])
    elif c == "rclick":
        click("3", args[0], args[1])
    elif c == "mclick":
        click("2", args[0], args[1])
    elif c == "move":
        xdo("mousemove", args[0], args[1])
    elif c == "cursor":
        xdo("getmouselocation")
    elif c == "wheel":
        xdo("mousemove", args[0], args[1])
        time.sleep(1.0)   # gamescope delivers the pointer move late: the first wheel step is lost otherwise
        for _ in range(int(args[3]) if len(args) > 3 else 1):
            xdo("click", "4" if args[2] == "up" else "5")
            time.sleep(0.25)
    elif c == "key":
        xdo("key", *args)
    elif c == "hold":
        xdo("keydown", args[0])
        time.sleep(float(args[1]))
        xdo("keyup", args[0])
    elif c == "type":
        text = " ".join(args)
        refuse_hypixel(text)
        xdo("type", "--delay", "30", text)
    elif c == "run":
        text = " ".join(args).removeprefix("/")
        refuse_hypixel(text)
        xdo("key", "slash")
        time.sleep(0.3)
        xdo("type", "--delay", "15", text)
        xdo("key", "Return")
        time.sleep(0.5)
    elif c == "_watch":
        cmd_watch(args)
    elif c == "log":
        cmd_log(args)
    else:
        sys.exit(__doc__)
    if c in ("install", "update", "up", "down", "shot"):
        print("(%s: %.1f s)" % (c, time.time() - t0))


if __name__ == "__main__":
    main()
