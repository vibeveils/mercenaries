#!/usr/bin/env python3
"""
Mercenaries - TF2 asset extractor
=================================

Builds a *personal* Minecraft resource pack for the "Mercenaries" Fabric mod from
YOUR OWN legally installed copy of Team Fortress 2 (Steam).

The mod itself ships with no Valve content. This script reads the game's VPK
archives on your machine and converts:

  * player models (MDL/VVD/VTX)   -> mercenaries mesh JSON (split into head/body/arms/legs)
  * weapon / projectile / building models -> mercenaries mesh JSON
  * materials (VMT/VTF)           -> PNG textures
  * backpack icons + class icons  -> PNG item / GUI textures
  * weapon sounds (WAV)           -> mono OGG Vorbis (needs ffmpeg or the `soundfile` package)

Output is a resource-pack .zip (default: MercenariesTF2Assets.zip) that you enable in
Minecraft under Options > Resource Packs. Do not redistribute the generated pack:
it contains Valve's copyrighted assets and is for your own use only.

Requirements: Python 3.9+, numpy, Pillow.   pip install numpy pillow
Optional:     ffmpeg on PATH (or `pip install soundfile`) for sounds.

Usage:
  python extract_tf2_assets.py                       # auto-detect Steam install
  python extract_tf2_assets.py --tf2 "D:/SteamLibrary/steamapps/common/Team Fortress 2"
  python extract_tf2_assets.py --out ~/.minecraft/resourcepacks/MercenariesTF2Assets.zip
  python extract_tf2_assets.py --list-weapons        # show how weapons resolved, then exit

File format references: Valve Source SDK 2013 (public/studio.h, public/optimize.h,
public/vtf/vtf.h). No SDK code is copied here; formats were re-implemented from the
documented struct layouts.
"""
from __future__ import annotations

import argparse
import io
import json
import math
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

try:
    import numpy as np
    from PIL import Image
except ImportError:  # pragma: no cover
    sys.exit("This script needs numpy and Pillow:  pip install numpy pillow")

sys.path.insert(0, str(Path(__file__).resolve().parent))
import studiomdl  # noqa: E402  (sibling module)

MOD_ID = "mercenaries"
PACK_MIN_FORMAT = 84          # Minecraft 26.1+
PACK_MAX_FORMAT = 9999

CLASSES = ["scout", "soldier", "pyro", "demoman", "heavy", "engineer", "medic", "sniper", "spy"]
# TF2's internal file names for each class model
CLASS_MODEL = {
    "scout": "scout", "soldier": "soldier", "pyro": "pyro", "demoman": "demo",
    "heavy": "heavy", "engineer": "engineer", "medic": "medic", "sniper": "sniper", "spy": "spy",
}

# ---------------------------------------------------------------------------
# Weapon table. Keys MUST match the ids registered by the Java mod (Weapons.java).
#   defindex : items_game.txt item id (tried first)
#   names    : items_game "name" fallbacks (case-insensitive)
# ---------------------------------------------------------------------------
WEAPONS: Dict[str, dict] = {
    # Scout
    "scattergun":          {"defindex": 13,  "names": ["TF_WEAPON_SCATTERGUN"]},
    "force_a_nature":      {"defindex": 45,  "names": ["The Force-a-Nature"]},
    "pistol":              {"defindex": 23,  "names": ["TF_WEAPON_PISTOL_SCOUT", "TF_WEAPON_PISTOL"]},
    "bonk":                {"defindex": 46,  "names": ["Bonk! Atomic Punch"]},
    "bat":                 {"defindex": 0,   "names": ["TF_WEAPON_BAT"]},
    "sandman":             {"defindex": 44,  "names": ["The Sandman"]},
    # Soldier
    "rocket_launcher":     {"defindex": 18,  "names": ["TF_WEAPON_ROCKETLAUNCHER"]},
    "direct_hit":          {"defindex": 127, "names": ["The Direct Hit"]},
    "black_box":           {"defindex": 228, "names": ["The Black Box"]},
    "shotgun":             {"defindex": 10,  "names": ["TF_WEAPON_SHOTGUN_SOLDIER", "TF_WEAPON_SHOTGUN_PRIMARY"]},
    "heavy_shotgun":       {"defindex": 11,  "names": ["TF_WEAPON_SHOTGUN_HWG", "TF_WEAPON_SHOTGUN_PRIMARY"]},
    "engineer_shotgun":    {"defindex": 9,   "names": ["TF_WEAPON_SHOTGUN_PRIMARY"]},
    "engineer_pistol":     {"defindex": 22,  "names": ["TF_WEAPON_PISTOL"]},
    "gunboats":            {"defindex": 133, "names": ["The Gunboats"]},
    "shovel":              {"defindex": 6,   "names": ["TF_WEAPON_SHOVEL"]},
    "equalizer":           {"defindex": 128, "names": ["The Equalizer"]},
    # Pyro
    "flamethrower":        {"defindex": 21,  "names": ["TF_WEAPON_FLAMETHROWER"]},
    "backburner":          {"defindex": 40,  "names": ["The Backburner"]},
    "flare_gun":           {"defindex": 39,  "names": ["The Flare Gun"]},
    "fire_axe":            {"defindex": 2,   "names": ["TF_WEAPON_FIREAXE"]},
    "axtinguisher":        {"defindex": 38,  "names": ["The Axtinguisher"]},
    # Demoman
    "grenade_launcher":    {"defindex": 19,  "names": ["TF_WEAPON_GRENADELAUNCHER"]},
    "loch_n_load":         {"defindex": 308, "names": ["The Loch-n-Load"]},
    "stickybomb_launcher": {"defindex": 20,  "names": ["TF_WEAPON_PIPEBOMBLAUNCHER"]},
    "scottish_resistance": {"defindex": 130, "names": ["The Scottish Resistance"]},
    "bottle":              {"defindex": 1,   "names": ["TF_WEAPON_BOTTLE"]},
    "eyelander":           {"defindex": 132, "names": ["The Eyelander"]},
    # Heavy
    "minigun":             {"defindex": 15,  "names": ["TF_WEAPON_MINIGUN"]},
    "natascha":            {"defindex": 41,  "names": ["Natascha"]},
    "tomislav":            {"defindex": 424, "names": ["Tomislav"]},
    "sandvich":            {"defindex": 42,  "names": ["The Sandvich"]},
    "fists":               {"defindex": 5,   "names": ["TF_WEAPON_FISTS"]},
    "gru":                 {"defindex": 239, "names": ["Gloves of Running Urgently"]},
    # Engineer
    "frontier_justice":    {"defindex": 141, "names": ["The Frontier Justice"]},
    "wrench":              {"defindex": 7,   "names": ["TF_WEAPON_WRENCH"]},
    "gunslinger":          {"defindex": 142, "names": ["The Gunslinger"]},
    "construction_pda":    {"defindex": 25,  "names": ["TF_WEAPON_PDA_ENGINEER_BUILD"]},
    # Medic
    "syringe_gun":         {"defindex": 17,  "names": ["TF_WEAPON_SYRINGEGUN_MEDIC"]},
    "blutsauger":          {"defindex": 36,  "names": ["The Blutsauger"]},
    "medigun":             {"defindex": 29,  "names": ["TF_WEAPON_MEDIGUN"]},
    "kritzkrieg":          {"defindex": 35,  "names": ["The Kritzkrieg"]},
    "bonesaw":             {"defindex": 8,   "names": ["TF_WEAPON_BONESAW"]},
    "ubersaw":             {"defindex": 37,  "names": ["The Ubersaw"]},
    # Sniper
    "sniper_rifle":        {"defindex": 14,  "names": ["TF_WEAPON_SNIPERRIFLE"]},
    "huntsman":            {"defindex": 56,  "names": ["The Huntsman"]},
    "smg":                 {"defindex": 16,  "names": ["TF_WEAPON_SMG"]},
    "jarate":              {"defindex": 58,  "names": ["Jarate"]},
    "kukri":               {"defindex": 3,   "names": ["TF_WEAPON_CLUB"]},
    "tribalmans_shiv":     {"defindex": 171, "names": ["The Tribalman's Shiv"]},
    # Spy
    "revolver":            {"defindex": 24,  "names": ["TF_WEAPON_REVOLVER"]},
    "ambassador":          {"defindex": 61,  "names": ["The Ambassador"]},
    "sapper":              {"defindex": 735, "names": ["TF_WEAPON_BUILDER_SPY", "Upgradeable TF_WEAPON_SAPPER"]},
    "knife":               {"defindex": 4,   "names": ["TF_WEAPON_KNIFE"]},
    "big_earner":          {"defindex": 461, "names": ["The Big Earner"]},
}

# Mod weapon id -> class (used to decide which animation sets each class needs)
WEAPON_CLASS = {
    **{w: "scout" for w in ("scattergun", "force_a_nature", "pistol", "bonk", "bat", "sandman")},
    **{w: "soldier" for w in ("rocket_launcher", "direct_hit", "black_box", "shotgun", "gunboats", "shovel", "equalizer")},
    **{w: "pyro" for w in ("flamethrower", "backburner", "flare_gun", "fire_axe", "axtinguisher")},
    **{w: "demoman" for w in ("grenade_launcher", "loch_n_load", "stickybomb_launcher", "scottish_resistance", "bottle", "eyelander")},
    **{w: "heavy" for w in ("minigun", "natascha", "tomislav", "sandvich", "heavy_shotgun", "fists", "gru")},
    **{w: "engineer" for w in ("engineer_shotgun", "frontier_justice", "engineer_pistol", "wrench", "gunslinger", "construction_pda")},
    **{w: "medic" for w in ("syringe_gun", "blutsauger", "medigun", "kritzkrieg", "bonesaw", "ubersaw")},
    **{w: "sniper" for w in ("sniper_rifle", "huntsman", "smg", "jarate", "kukri", "tribalmans_shiv")},
    **{w: "spy" for w in ("revolver", "ambassador", "sapper", "knife", "big_earner")},
}
MELEE_WEAPONS = {"bat", "sandman", "shovel", "equalizer", "fire_axe", "axtinguisher", "bottle", "eyelander", "fists",
                 "gru", "wrench", "gunslinger", "bonesaw", "ubersaw", "kukri", "tribalmans_shiv", "knife", "big_earner"}
WEAPON_SLOT_FALLBACK = {w: "melee" for w in MELEE_WEAPONS}
WEAPON_SLOT_FALLBACK.update({"construction_pda": "pda", "sapper": "building", "pistol": "secondary", "engineer_pistol": "secondary",
                             "medigun": "secondary", "kritzkrieg": "secondary", "smg": "secondary", "jarate": "secondary",
                             "bonk": "secondary", "sandvich": "secondary", "stickybomb_launcher": "secondary",
                             "scottish_resistance": "secondary", "flare_gun": "secondary", "shotgun": "secondary",
                             "heavy_shotgun": "secondary", "gunboats": "secondary"})

# Per-weapon sound events -> TF2 SoundData / visuals keys
WEAPON_SOUND_KEYS = {
    "fire": ["single_shot", "burst", "melee_miss"],
    "reload": ["reload"],
    "hit": ["melee_hit"],
    "hit_world": ["melee_hit_world"],
    "deploy": ["deploy"],
    "empty": ["empty"],
}
# Class voice events -> vo/<class>_<prefix>*.mp3
VOICE_EVENTS = {
    "pain": ["painsharp", "painsevere"],
    "death": ["paincriticaldeath"],
    "medic": ["medic0"],
    "battlecry": ["battlecry"],
}

# Non-weapon models used by projectiles and buildings. id -> candidate model paths
PROPS: Dict[str, List[str]] = {
    "rocket":     ["models/weapons/w_models/w_rocket.mdl"],
    "pipe":       ["models/weapons/w_models/w_grenade_grenadelauncher.mdl"],
    "sticky":     ["models/weapons/w_models/w_stickybomb.mdl"],
    "flare":      ["models/weapons/w_models/w_flaregun_shell.mdl"],
    "arrow":      ["models/weapons/w_models/w_arrow.mdl"],
    "jar":        ["models/weapons/c_models/urinejar.mdl", "models/weapons/w_models/w_jar.mdl"],
    "syringe":    ["models/weapons/w_models/w_syringe_proj.mdl", "models/weapons/w_models/w_syringe.mdl"],
    "sentry":     ["models/buildables/sentry1.mdl"],
    "dispenser":  ["models/buildables/dispenser_light.mdl", "models/buildables/dispenser.mdl"],
}

# Shared sound events: mod key -> candidate soundscript names / raw wav paths (relative to sound/)
SHARED_SOUNDS: Dict[str, List[str]] = {
    "explode":          ["BaseExplosionEffect.Sound", "weapons/explode1.wav", "weapons/explode3.wav"],
    "sticky_detonate":  ["Weapon_StickyBombLauncher.ModeSwitch", "weapons/stickybomblauncher_det.wav"],
    "minigun_spin":     ["Weapon_Minigun.Spin", "weapons/minigun_spin.wav"],
    "minigun_windup":   ["Weapon_Minigun.WindUp", "weapons/minigun_wind_up.wav"],
    "flame_loop":       ["Weapon_FlameThrower.FireLoop", "weapons/flame_thrower_loop.wav"],
    "airblast":         ["Weapon_FlameThrower.AirBurstAttack", "weapons/flame_thrower_airblast.wav"],
    "medigun_heal":     ["WeaponMedigun.Healing", "weapons/medigun_heal.wav"],
    "uber":             ["WeaponMedigun.Charged", "player/invulnerable_on.wav"],
    "crit":             ["TFPlayer.CritHit", "player/crit_hit.wav"],
    "backstab":         ["Player.Backstab", "player/spy_shield_break.wav"],
    "headshot":         ["Player.ResistanceHeavy", "player/headshot1.wav"],
    "cloak":            ["Player.Spy_Cloak", "player/spy_cloak.wav"],
    "uncloak":          ["Player.Spy_UnCloak", "player/spy_uncloak.wav"],
    "melee_hit":        ["Weapon_Bat.HitFlesh", "weapons/bat_hit.wav"],
    "melee_world":      ["Weapon_Bat.HitWorld", "weapons/cbar_hit1.wav"],
    "sentry_shoot":     ["Building_Sentrygun.Fire", "weapons/sentry_shoot.wav"],
    "sentry_scan":      ["Building_Sentrygun.Idle", "weapons/sentry_scan.wav"],
    "sentry_spot":      ["Building_Sentrygun.Alert", "weapons/sentry_spot.wav"],
    "build":            ["Engineer.BuildingPlaced", "weapons/building_place.wav", "weapons/wrench_hit_build_success1.wav"],
    "reload":           ["Weapon_Shotgun.Reload", "weapons/shotgun_reload.wav"],
    "sniper_charge":    ["Weapon_SniperRifle.Zoom", "weapons/sniper_rifle_zoom.wav"],
    "jarate_break":     ["Jar.Explode", "weapons/jar_explode.wav"],
    "eat":              ["SandwichEat", "vo/sandwicheat09.mp3"],
    "drink":            ["Weapon_Bonk.Drink", "player/pl_scout_dodge_can_drink.wav"],
}

# -------------------------------------------------------------------------- KeyValues

_KV_TOKEN = re.compile(r'"((?:[^"\\]|\\.)*)"|(\{)|(\})|//[^\n]*|(\[[^\]]*\])|([^\s"{}]+)', re.S)


class KV:
    """Ordered multi-map KeyValues node. Keys are case-insensitive for lookup."""

    __slots__ = ("items",)

    def __init__(self):
        self.items: List[Tuple[str, object]] = []

    def get(self, key: str, default=None):
        kl = key.lower()
        for k, v in self.items:
            if k.lower() == kl:
                return v
        return default

    def get_all(self, key: str) -> list:
        kl = key.lower()
        return [v for k, v in self.items if k.lower() == kl]

    def node(self, key: str) -> "KV":
        v = self.get(key)
        return v if isinstance(v, KV) else KV()

    def str(self, key: str, default: str = "") -> str:
        v = self.get(key)
        return v if isinstance(v, str) else default

    def __iter__(self):
        return iter(self.items)

    def __repr__(self):
        return f"KV({len(self.items)} items)"


def parse_kv(text: str) -> KV:
    root = KV()
    stack = [root]
    pending_key: Optional[str] = None
    for m in _KV_TOKEN.finditer(text):
        quoted, lb, rb, cond, bare = m.groups()
        if cond is not None:
            continue  # platform conditional like [$WIN32] - ignore
        if lb:
            node = KV()
            stack[-1].items.append((pending_key or "", node))
            stack.append(node)
            pending_key = None
        elif rb:
            if len(stack) > 1:
                stack.pop()
            pending_key = None
        else:
            tok = quoted if quoted is not None else bare
            if tok is None:
                continue  # comment
            if quoted is not None:
                tok = tok.replace('\\"', '"').replace("\\n", "\n").replace("\\\\", "\\")
            if pending_key is None:
                if tok.startswith("#") and quoted is None:   # #base / #include directives
                    pending_key = None
                    continue
                pending_key = tok
            else:
                stack[-1].items.append((pending_key, tok))
                pending_key = None
    return root


def decode_text(data: bytes) -> str:
    if data.startswith(b"\xff\xfe") or data.startswith(b"\xfe\xff"):
        return data.decode("utf-16")
    if data.startswith(b"\xef\xbb\xbf"):
        data = data[3:]
    return data.decode("utf-8", errors="replace")

# -------------------------------------------------------------------------- VPK / VFS


class VPK:
    """Reader for Valve PacK (v1 and v2) directory files."""

    def __init__(self, dir_path: Path):
        self.dir_path = Path(dir_path)
        self.base = str(self.dir_path)[: -len("_dir.vpk")]
        self.entries: Dict[str, tuple] = {}
        with open(self.dir_path, "rb") as f:
            sig, ver, tree_size = struct.unpack("<III", f.read(12))
            if sig != 0x55AA1234:
                raise ValueError(f"{dir_path}: not a VPK")
            header = 12
            if ver == 2:
                f.read(16)
                header = 28
            elif ver != 1:
                raise ValueError(f"{dir_path}: unsupported VPK version {ver}")
            tree = f.read(tree_size)
        self.data_offset = header + tree_size
        self._parse_tree(tree)

    def _parse_tree(self, tree: bytes):
        pos = 0

        def rstr() -> str:
            nonlocal pos
            end = tree.index(b"\0", pos)
            s = tree[pos:end].decode("utf-8", errors="replace")
            pos = end + 1
            return s

        while True:
            ext = rstr()
            if not ext:
                break
            while True:
                path = rstr()
                if not path:
                    break
                while True:
                    name = rstr()
                    if not name:
                        break
                    crc, preload, arch, off, length, term = struct.unpack_from("<IHHIIH", tree, pos)
                    pos += 18
                    pre = tree[pos:pos + preload]
                    pos += preload
                    full = name if ext == " " else f"{name}.{ext}"
                    if path.strip():
                        full = f"{path}/{full}"
                    self.entries[full.lower()] = (pre, arch, off, length)

    def read(self, path: str) -> Optional[bytes]:
        e = self.entries.get(path.lower().replace("\\", "/"))
        if e is None:
            return None
        pre, arch, off, length = e
        if length == 0:
            return pre
        if arch == 0x7FFF:
            fname, off = self.dir_path, off + self.data_offset
        else:
            fname = f"{self.base}_{arch:03d}.vpk"
        with open(fname, "rb") as f:
            f.seek(off)
            return pre + f.read(length)

    def names(self) -> Iterable[str]:
        return self.entries.keys()


class VFS:
    """Search path over loose game folders and VPKs (tf first, then hl2)."""

    def __init__(self, game_root: Path):
        self.sources: List[object] = []
        for sub in ("tf", "hl2"):
            d = game_root / sub
            if not d.is_dir():
                continue
            self.sources.append(d)  # loose files
            for vpk in sorted(d.glob("*_dir.vpk")):
                try:
                    self.sources.append(VPK(vpk))
                except Exception as ex:  # pragma: no cover
                    print(f"  ! skipping {vpk.name}: {ex}")

    def read(self, path: str) -> Optional[bytes]:
        path = path.replace("\\", "/").lstrip("/")
        for s in self.sources:
            if isinstance(s, Path):
                p = s / path
                if p.is_file():
                    return p.read_bytes()
                # case-insensitive fallback for Linux installs
                continue
            data = s.read(path)
            if data is not None:
                return data
        return None

    def list(self, prefix: str) -> List[str]:
        """All file paths (lower-case) starting with ``prefix`` across VPKs and loose folders."""
        prefix = prefix.lower().replace("\\", "/")
        out = set()
        for s in self.sources:
            if isinstance(s, Path):
                base = s / prefix.rsplit("/", 1)[0] if "/" in prefix else s
                if base.is_dir():
                    for f in base.iterdir():
                        rel = f.relative_to(s).as_posix().lower()
                        if rel.startswith(prefix):
                            out.add(rel)
            else:
                out.update(n for n in s.entries if n.startswith(prefix))
        return sorted(out)

    def exists(self, path: str) -> bool:
        path = path.replace("\\", "/").lstrip("/").lower()
        for s in self.sources:
            if isinstance(s, Path):
                if (s / path).is_file():
                    return True
            elif path in s.entries:
                return True
        return False

# -------------------------------------------------------------------------- VTF -> RGBA

FMT = {
    "RGBA8888": 0, "ABGR8888": 1, "RGB888": 2, "BGR888": 3, "RGB565": 4, "I8": 5, "IA88": 6,
    "A8": 8, "RGB888_BLUESCREEN": 9, "BGR888_BLUESCREEN": 10, "ARGB8888": 11, "BGRA8888": 12,
    "DXT1": 13, "DXT3": 14, "DXT5": 15, "BGRX8888": 16, "BGR565": 17, "BGRX5551": 18,
    "BGRA4444": 19, "DXT1_ONEBITALPHA": 20, "BGRA5551": 21, "UV88": 22,
}
_BPP = {0: 4, 1: 4, 2: 3, 3: 3, 4: 2, 5: 1, 6: 2, 8: 1, 9: 3, 10: 3, 11: 4, 12: 4, 16: 4, 17: 2,
        18: 2, 19: 2, 21: 2, 22: 2}
_DXT = {13: 8, 20: 8, 14: 16, 15: 16}


def _img_size(fmt: int, w: int, h: int) -> int:
    if fmt in _DXT:
        return max(1, (w + 3) // 4) * max(1, (h + 3) // 4) * _DXT[fmt]
    if fmt in _BPP:
        return w * h * _BPP[fmt]
    raise ValueError(f"unsupported VTF image format {fmt}")


def _565(c: np.ndarray) -> np.ndarray:
    c = c.astype(np.uint32)
    r = ((c >> 11) & 31) * 255 // 31
    g = ((c >> 5) & 63) * 255 // 63
    b = (c & 31) * 255 // 31
    return np.stack([r, g, b], -1).astype(np.int32)


def _dxt_colors(blocks: np.ndarray, force4: bool) -> Tuple[np.ndarray, np.ndarray]:
    """blocks: (N, 8) uint8 color blocks -> palette (N,4,4) RGBA, indices (N,16)."""
    c0 = blocks[:, 0].astype(np.uint16) | (blocks[:, 1].astype(np.uint16) << 8)
    c1 = blocks[:, 2].astype(np.uint16) | (blocks[:, 3].astype(np.uint16) << 8)
    p0, p1 = _565(c0), _565(c1)
    four = (c0 > c1) | force4
    p2 = np.where(four[:, None], (2 * p0 + p1) // 3, (p0 + p1) // 2)
    p3 = np.where(four[:, None], (p0 + 2 * p1) // 3, 0)
    alpha = np.full((len(blocks), 4), 255, np.int32)
    alpha[:, 3] = np.where(four, 255, 0)
    pal = np.stack([p0, p1, p2, p3], 1)
    pal = np.concatenate([pal, alpha[:, :, None]], 2)
    bits = blocks[:, 4:8].astype(np.uint32)
    word = bits[:, 0] | (bits[:, 1] << 8) | (bits[:, 2] << 16) | (bits[:, 3] << 24)
    idx = np.stack([(word >> (2 * i)) & 3 for i in range(16)], 1)
    return pal, idx


def _assemble(px: np.ndarray, w: int, h: int) -> np.ndarray:
    """px: (N,16,4) per-block pixels -> (h,w,4)."""
    bw, bh = max(1, (w + 3) // 4), max(1, (h + 3) // 4)
    img = px.reshape(bh, bw, 4, 4, 4).transpose(0, 2, 1, 3, 4).reshape(bh * 4, bw * 4, 4)
    return img[:h, :w]


def decode_dxt(data: bytes, w: int, h: int, fmt: int) -> np.ndarray:
    bs = _DXT[fmt]
    n = max(1, (w + 3) // 4) * max(1, (h + 3) // 4)
    raw = np.frombuffer(data[: n * bs], np.uint8).reshape(n, bs)
    if fmt in (13, 20):
        pal, idx = _dxt_colors(raw, force4=False)
        px = np.take_along_axis(pal, idx[:, :, None].repeat(4, 2), 1)
        return _assemble(px.astype(np.uint8), w, h)
    pal, idx = _dxt_colors(raw[:, 8:16], force4=True)
    px = np.take_along_axis(pal, idx[:, :, None].repeat(4, 2), 1).astype(np.int32)
    if fmt == 14:  # DXT3: explicit 4-bit alpha
        a = raw[:, :8].astype(np.int32)
        nib = np.stack([(a[:, i // 2] >> (4 * (i % 2))) & 15 for i in range(16)], 1)
        px[:, :, 3] = nib * 17
    else:  # DXT5: interpolated alpha
        a0 = raw[:, 0].astype(np.int32)
        a1 = raw[:, 1].astype(np.int32)
        ab = raw[:, 2:8].astype(np.uint64)
        word = np.zeros(n, np.uint64)
        for i in range(6):
            word |= ab[:, i] << np.uint64(8 * i)
        aidx = np.stack([((word >> np.uint64(3 * i)) & np.uint64(7)).astype(np.int32) for i in range(16)], 1)
        tab = np.zeros((n, 8), np.int32)
        tab[:, 0], tab[:, 1] = a0, a1
        gt = a0 > a1
        for i in range(1, 7):
            tab[:, i + 1] = np.where(gt, ((7 - i) * a0 + i * a1) // 7, 0)
        for i in range(1, 5):
            tab[:, i + 1] = np.where(gt, tab[:, i + 1], ((5 - i) * a0 + i * a1) // 5)
        tab[:, 6] = np.where(gt, tab[:, 6], 0)
        tab[:, 7] = np.where(gt, tab[:, 7], 255)
        px[:, :, 3] = np.take_along_axis(tab, aidx, 1)
    return _assemble(px.astype(np.uint8), w, h)


def decode_raw(data: bytes, w: int, h: int, fmt: int) -> np.ndarray:
    bpp = _BPP[fmt]
    a = np.frombuffer(data[: w * h * bpp], np.uint8).reshape(h, w, bpp).astype(np.int32)
    out = np.full((h, w, 4), 255, np.int32)
    if fmt == 0:   out[:] = a
    elif fmt == 1: out[..., 0], out[..., 1], out[..., 2], out[..., 3] = a[..., 3], a[..., 2], a[..., 1], a[..., 0]
    elif fmt in (2, 9): out[..., :3] = a
    elif fmt in (3, 10): out[..., :3] = a[..., ::-1]
    elif fmt == 11: out[..., 0], out[..., 1], out[..., 2], out[..., 3] = a[..., 1], a[..., 2], a[..., 3], a[..., 0]
    elif fmt == 12: out[..., :3], out[..., 3] = a[..., 2::-1], a[..., 3]
    elif fmt == 16: out[..., :3] = a[..., 2::-1]
    elif fmt == 5: out[..., :3] = a[..., :1]
    elif fmt == 6: out[..., :3], out[..., 3] = a[..., :1], a[..., 1]
    elif fmt == 8: out[..., :3], out[..., 3] = 0, a[..., 0]
    elif fmt == 22: out[..., 0], out[..., 1], out[..., 2] = a[..., 0], a[..., 1], 0
    else:
        v = a[..., 0] | (a[..., 1] << 8)
        if fmt == 4:
            out[..., 0], out[..., 1], out[..., 2] = (v & 31) * 255 // 31, ((v >> 5) & 63) * 255 // 63, ((v >> 11) & 31) * 255 // 31
        elif fmt == 17:
            out[..., 0], out[..., 1], out[..., 2] = ((v >> 11) & 31) * 255 // 31, ((v >> 5) & 63) * 255 // 63, (v & 31) * 255 // 31
        elif fmt in (18, 21):
            out[..., 0], out[..., 1], out[..., 2] = ((v >> 10) & 31) * 255 // 31, ((v >> 5) & 31) * 255 // 31, (v & 31) * 255 // 31
            if fmt == 21:
                out[..., 3] = ((v >> 15) & 1) * 255
        elif fmt == 19:
            out[..., 0], out[..., 1], out[..., 2], out[..., 3] = ((v >> 8) & 15) * 17, ((v >> 4) & 15) * 17, (v & 15) * 17, ((v >> 12) & 15) * 17
    return out.astype(np.uint8)


def decode_vtf(data: bytes) -> Image.Image:
    if data[:4] != b"VTF\0":
        raise ValueError("not a VTF")
    major, minor, header_size = struct.unpack_from("<III", data, 4)
    w, h, flags, frames, _first = struct.unpack_from("<HHIHH", data, 16)
    hi_fmt, mips, lo_fmt, lo_w, lo_h = struct.unpack_from("<iBiBB", data, 52)
    depth = struct.unpack_from("<H", data, 63)[0] if minor >= 2 else 1
    depth = max(1, depth)
    faces = 1
    if flags & 0x4000:  # environment map
        faces = 6 if (minor >= 5 or _first != 0xFFFF) else 7
    hi_off = None
    if minor >= 3:
        nres = struct.unpack_from("<I", data, 68)[0]
        for i in range(nres):
            tag, rflags, roff = struct.unpack_from("<3sBI", data, 80 + 8 * i)
            if tag == b"\x30\x00\x00":
                hi_off = roff
    if hi_off is None:
        lo = 0 if lo_fmt < 0 else _img_size(lo_fmt, lo_w, lo_h)
        hi_off = header_size + lo
    per_mip = lambda m: _img_size(hi_fmt, max(1, w >> m), max(1, h >> m)) * frames * faces * depth
    off = hi_off + sum(per_mip(m) for m in range(1, max(1, mips)))
    blob = data[off: off + _img_size(hi_fmt, w, h)]
    if hi_fmt in _DXT:
        arr = decode_dxt(blob, w, h, hi_fmt)
    else:
        arr = decode_raw(blob, w, h, hi_fmt)
    return Image.fromarray(arr, "RGBA")

# -------------------------------------------------------------------------- VMT


@dataclass
class Material:
    name: str
    shader: str
    params: Dict[str, str]

    @property
    def basetexture(self) -> Optional[str]:
        return self.params.get("$basetexture")

    @property
    def translucent(self) -> bool:
        return self.params.get("$translucent", "0") not in ("0", "")

    @property
    def alphatest(self) -> bool:
        return self.params.get("$alphatest", "0") not in ("0", "")


def load_vmt(vfs: VFS, path: str, depth: int = 0) -> Optional[Material]:
    data = vfs.read(path)
    if data is None or depth > 4:
        return None
    kv = parse_kv(decode_text(data))
    if not kv.items:
        return None
    shader, body = kv.items[0]
    if not isinstance(body, KV):
        return None
    params = {k.lower(): v for k, v in body if isinstance(v, str)}
    if shader.lower() == "patch":
        inc = params.get("include", "")
        base = load_vmt(vfs, inc, depth + 1) if inc else None
        if base is None:
            return None
        merged = dict(base.params)
        for blk in ("insert", "replace"):
            for k, v in body.node(blk):
                if isinstance(v, str):
                    merged[k.lower()] = v
        return Material(path, base.shader, merged)
    return Material(path, shader, params)

# -------------------------------------------------------------------------- texture export


class TextureCache:
    def __init__(self, vfs: VFS, pack: "PackWriter", max_size: int):
        self.vfs, self.pack, self.max_size = vfs, pack, max_size
        self.done: Dict[str, Optional[str]] = {}

    def export(self, vtf_path: str, opaque: bool, dest_hint: Optional[str] = None) -> Optional[str]:
        """Exports materials/<vtf_path>.vtf to the pack; returns a Minecraft texture id or None."""
        key = (vtf_path.lower().replace("\\", "/"), opaque, dest_hint)
        if key in self.done:
            return self.done[key]
        data = self.vfs.read(f"materials/{key[0]}.vtf")
        result = None
        if data is not None:
            try:
                img = decode_vtf(data)
                if opaque:
                    img.putalpha(255)
                if max(img.size) > self.max_size:
                    s = self.max_size / max(img.size)
                    img = img.resize((max(1, int(img.width * s)), max(1, int(img.height * s))), Image.LANCZOS)
                rel = dest_hint or ("tf2/" + sanitize(key[0]))
                buf = io.BytesIO()
                img.save(buf, "PNG", optimize=True)
                self.pack.add(f"assets/{MOD_ID}/textures/{rel}.png", buf.getvalue())
                result = f"{MOD_ID}:textures/{rel}.png"
            except Exception as ex:
                print(f"  ! texture {vtf_path}: {ex}")
        self.done[key] = result
        return result


def sanitize(p: str) -> str:
    p = p.lower().replace("\\", "/")
    return re.sub(r"[^a-z0-9_./-]", "_", p)


def resolve_material(vfs: VFS, model, tex_index: int) -> Optional[Material]:
    name = model.textures[tex_index].replace("\\", "/")
    for d in (model.cd_textures or [""]):
        d = d.replace("\\", "/").strip("/")
        mat = load_vmt(vfs, f"materials/{d}/{name}.vmt" if d else f"materials/{name}.vmt")
        if mat:
            return mat
    return None

# -------------------------------------------------------------------------- mesh conversion

# Player animation states and the TF2 sequence names tried for each ({s} = anim slot)
ANIM_STATES = {
    "stand": ["stand_{s}"],
    "run": ["run_{s}"],
    "crouch": ["crouch_{s}"],
    "crouch_walk": ["crouch_walk_{s}", "crouchwalk_{s}"],
    "air": ["airwalk_{s}", "a_jumpfloat_{s}", "jump_float_{s}"],
    "jump": ["jump_start_{s}", "a_jumpstart_{s}"],
    "swim": ["swim_{s}"],
}
BLEND_TARGETS = {"move_x": 1.0, "move_y": 0.0, "body_pitch": 0.0, "body_yaw": 0.0, "move_scale": 1.0}
MAX_FRAMES = 60


def _r(a, nd: int = 4) -> list:
    return np.round(np.asarray(a, np.float64), nd).reshape(-1).tolist()


def skeleton_basis(bones) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Character left / up / forward axes in model space, measured from the skeleton itself.

    TF2 models are not all authored facing the same axis, so instead of assuming one we read it
    off the bones: left = right hip -> left hip (or shoulders), up = feet -> head.
    """
    def wp(*names):
        for n in names:
            for b in bones:
                if b.name.lower() == n.lower():
                    return b.world_pos
        return None
    hl, hr = wp("bip_hip_L", "bip_thigh_L"), wp("bip_hip_R", "bip_thigh_R")
    sl, sr = wp("bip_upperArm_L", "bip_collar_L"), wp("bip_upperArm_R", "bip_collar_R")
    head = wp("bip_head", "bip_neck")
    fl, fr = wp("bip_foot_L"), wp("bip_foot_R")
    left = None
    if hl is not None and hr is not None:
        left = hl - hr
    if sl is not None and sr is not None:
        left = (sl - sr) if left is None else left + (sl - sr)
    if left is None or np.linalg.norm(left) < 1e-6:
        left = np.array([0.0, 1.0, 0.0])
    feet = (fl + fr) / 2 if fl is not None and fr is not None else np.zeros(3)
    up = (head - feet) if head is not None else np.array([0.0, 0.0, 1.0])
    up = up / (np.linalg.norm(up) + 1e-12)
    left = left - up * np.dot(left, up)
    left = left / (np.linalg.norm(left) + 1e-12)
    fwd = np.cross(left, up)
    return left, up, fwd


def to_entity_matrix(left, up, fwd, scale: float, floor: float) -> np.ndarray:
    """4x4 from Source model space to Minecraft entity-model space (x = left, y = down, z = back, feet at y = 1.5)."""
    m = np.eye(4)
    m[0, :3] = left * scale
    m[1, :3] = -up * scale
    m[2, :3] = -fwd * scale
    m[1, 3] = 1.5 + floor * scale
    return m


class ModelConverter:
    def __init__(self, vfs: VFS, tex: TextureCache, max_tex: int):
        self.vfs, self.tex = vfs, tex
        self.read = vfs.read

    def _skins(self, model):
        mats: Dict[int, Optional[Material]] = {}
        fams, modes = [], []
        nref = len(model.skins[0]) if model.skins and model.skins[0] else len(model.textures)
        for fam in (model.skins if model.skins and model.skins[0] else [list(range(len(model.textures)))]):
            row = []
            for slot in range(nref):
                ti = fam[slot] if slot < len(fam) else slot
                if ti not in mats:
                    mats[ti] = resolve_material(self.vfs, model, ti) if ti < len(model.textures) else None
                m = mats[ti]
                tid = None
                if m is not None:
                    shader = m.shader.lower()
                    eye = shader in ("eyes", "eyerefract")
                    src = m.params.get("$iris") if eye else m.basetexture
                    if src:
                        tid = self.tex.export(src, opaque=not (m.translucent or m.alphatest or eye))
                row.append(tid)
            fams.append(row)
        first = model.skins[0] if model.skins and model.skins[0] else list(range(nref))
        for slot in range(nref):
            m = mats.get(first[slot] if slot < len(first) else slot)
            mode = "cutout"
            if m is not None:
                if m.shader.lower() in ("eyes", "eyerefract"):
                    mode = "eye"
                elif m.translucent:
                    mode = "translucent"
            modes.append(mode)
        return fams, modes

    @staticmethod
    def _eye_uv(pos: np.ndarray, fwd: np.ndarray, left: np.ndarray, up: np.ndarray) -> np.ndarray:
        c = pos.mean(0)
        u = (pos - c) @ left
        v = (pos - c) @ up
        ext = max(1e-6, float(max(np.abs(u).max(), np.abs(v).max())) * 2)
        return np.clip(np.stack([0.5 - u / ext, 0.5 - v / ext], 1), 0, 1)

    def _mesh_json(self, mesh, modes, basis=None, with_weights=True) -> dict:
        uv = mesh.uvs
        if basis is not None and mesh.material < len(modes) and modes[mesh.material] == "eye":
            left, up, fwd = basis
            uv = self._eye_uv(mesh.positions, fwd, left, up)
        out = {"mat": int(mesh.material), "v": _r(mesh.positions), "n": _r(mesh.normals, 3), "uv": _r(uv, 5),
               "i": mesh.indices.astype(np.int32).tolist()}
        if with_weights:
            out["bi"] = mesh.bones.astype(np.int32).reshape(-1).tolist()
            out["bw"] = _r(mesh.weights, 3)
        return out

    @staticmethod
    def _bones_json(bones) -> list:
        return [{"name": b.name, "parent": int(b.parent), "p2b": _r(b.p2b, 6)} for b in bones]

    # ------------------------------------------------------------------ players

    def player(self, path: str, scale: float, slots: List[str], report: List[str]) -> dict:
        files = studiomdl.load_with_includes(self.read, path)
        main = files[0]
        fams, modes = self._skins(main)
        left, up, fwd = skeleton_basis(main.bones)
        allp = np.concatenate([m.positions for m in main.meshes])
        floor = -float((allp @ up).min())
        to_entity = to_entity_matrix(left, up, fwd, scale, floor)

        seq_index: Dict[str, Tuple[object, object]] = {}
        for f in files:
            for sq in f.seqs:
                seq_index.setdefault(sq.label.lower(), (f, sq))
        report.append(f"{path}: {len(main.bones)} bones, {sum(len(m.indices) for m in main.meshes) // 3} tris, "
                      f"{len(seq_index)} sequences from {len(files)} file(s): " + ", ".join(sorted(seq_index)))

        clips = {}
        main_names = [b.name.lower() for b in main.bones]
        default_pos = np.array([b.pos for b in main.bones])
        default_quat = np.array([b.quat for b in main.bones])
        for slot in slots:
            for state, patterns in ANIM_STATES.items():
                key = f"{state}_{slot.lower()}"
                hit = None
                for pat in patterns:
                    hit = seq_index.get(pat.format(s=slot).lower())
                    if hit:
                        break
                if hit is None:
                    continue
                f, sq = hit
                ai = studiomdl.pick_blend(f, sq, BLEND_TARGETS)
                if not (0 <= ai < len(f.anims)):
                    continue
                a = f.anims[ai]
                if a.flags & studiomdl.STUDIO_DELTA or a.numframes <= 0:
                    continue
                remap = [main_names.index(b.name.lower()) if b.name.lower() in main_names else -1 for b in f.bones]
                n = min(a.numframes, MAX_FRAMES)
                step = a.numframes / n
                frames = []
                for k in range(n):
                    pos, quat = studiomdl.decode_frame(f, a, min(a.numframes - 1, int(round(k * step))))
                    fp, fq = default_pos.copy(), default_quat.copy()
                    for fi, mi in enumerate(remap):
                        if mi >= 0:
                            fp[mi], fq[mi] = pos[fi], quat[fi]
                    frames.append(np.concatenate([fp, fq], 1))  # (bones, 7)
                arr = np.stack(frames)  # (frames, bones, 7)
                varies = np.where(np.abs(arr - arr[0]).max(axis=(0, 2)) > 1e-4)[0]
                const = {int(i): _r(arr[0, i], 4) for i in range(arr.shape[1]) if i not in set(varies.tolist())}
                clips[key] = {
                    "fps": round(a.fps / step, 3), "frames": n, "loop": bool(sq.looping or state in ("stand", "run", "crouch", "crouch_walk", "air", "swim")),
                    "const": const, "anim": varies.astype(int).tolist(), "data": _r(arr[:, varies, :], 4),
                }
        report.append(f"  exported clips: {', '.join(sorted(clips)) or 'NONE'}")

        meshes = [self._mesh_json(m, modes, (left, up, fwd)) for m in main.meshes]
        head = main.bone_index("bip_head")
        spine = main.bone_index("bip_spine_3")
        return {"format": 2, "kind": "skinned", "source": path, "skins": fams, "modes": modes,
                "to_entity": _r(to_entity, 6), "bones": self._bones_json(main.bones),
                "head_bone": head, "spine_bone": spine, "meshes": meshes, "clips": clips}

    # ------------------------------------------------------------------ weapons

    def weapon(self, path: str, melee: bool, rotate=(0, 0, 0), size: float = 1.0) -> dict:
        m = studiomdl.load(self.read, path, True)
        fams, modes = self._skins(m)
        # vertices in the frame of the bone the hand holds (weapon_bone), so every weapon shares one convention
        wb = m.bone_index("weapon_bone")
        frame = m.bones[wb].p2b if wb >= 0 else np.eye(4)[:3]
        R, t = frame[:, :3], frame[:, 3]
        allp = np.concatenate([x.positions for x in m.meshes]) if m.meshes else np.zeros((1, 3))
        local = allp @ R.T + t
        # Heuristic axes: longest extent = barrel/blade (pointing away from the grip at the origin),
        # second-longest = up (the side with more geometry above the hand).
        ext = local.max(0) - local.min(0)
        order = list(np.argsort(-ext))
        a_len, a_up = order[0], order[1]
        sign_len = 1.0 if local[:, a_len].max() >= -local[:, a_len].min() else -1.0
        sign_up = 1.0 if local[:, a_up].max() >= -local[:, a_up].min() else -1.0
        L = np.zeros(3); L[a_len] = sign_len
        U = np.zeros(3); U[a_up] = sign_up
        side = np.cross(U, L) if not melee else np.cross(L, U)
        if melee:   # blade points up
            basis = np.stack([side, L, -U])
        else:       # barrel points forward (-z), up is +y
            basis = np.stack([side, U, -L])
        basis = euler(rotate) @ basis
        pts = local @ basis.T
        lo, hi = pts.min(0), pts.max(0)
        s = size / (float(max(hi - lo)) or 1.0)
        grip = np.array([0.5, 0.35, 0.55]) if not melee else np.array([0.5, 0.15, 0.5])
        item_meshes = []
        for x in m.meshes:
            p = ((x.positions @ R.T + t) @ basis.T) * s + grip
            n = (x.normals @ R.T) @ basis.T
            item_meshes.append({"mat": int(x.material), "v": _r(p), "n": _r(n, 3), "uv": _r(x.uvs, 5),
                                "i": x.indices.astype(np.int32).tolist()})
        attach = {"bones": self._bones_json(m.bones), "meshes": [self._mesh_json(x, modes) for x in m.meshes]}
        return {"format": 2, "kind": "weapon", "source": path, "skins": fams, "modes": modes,
                "parts": {"root": {"pivot": [0, 0, 0], "meshes": item_meshes}}, "attach": attach}

    def static(self, path: str, size: float = 1.0, rotate=(0, 0, 0), center: bool = True) -> dict:
        m = studiomdl.load(self.read, path, True)
        fams, modes = self._skins(m)
        allp = np.concatenate([x.positions for x in m.meshes]) if m.meshes else np.zeros((1, 3))
        R = euler(rotate)
        conv = lambda p: (np.stack([-p[..., 1], p[..., 2], -p[..., 0]], -1)) @ R.T
        pts = conv(allp)
        lo, hi = pts.min(0), pts.max(0)
        s = size / (float(max(hi - lo)) or 1.0)
        mid = (lo + hi) / 2
        anchor = mid if center else np.array([mid[0], lo[1], mid[2]])
        meshes = []
        for x in m.meshes:
            p = (conv(x.positions) - anchor) * s + (np.array([0.5, 0.5, 0.5]) if center else 0)
            meshes.append({"mat": int(x.material), "v": _r(p), "n": _r(conv(x.normals), 3), "uv": _r(x.uvs, 5),
                           "i": x.indices.astype(np.int32).tolist()})
        return {"format": 2, "kind": "static", "source": path, "skins": fams, "modes": modes,
                "parts": {"root": {"pivot": [0, 0, 0], "meshes": meshes}}}

    def sequences(self, path: str) -> List[str]:
        try:
            return sorted({sq.label for f in studiomdl.load_with_includes(self.read, path) for sq in f.seqs})
        except Exception as ex:
            return [f"<error: {ex}>"]


def euler(deg) -> np.ndarray:
    x, y, z = (math.radians(a) for a in deg)
    Rx = np.array([[1, 0, 0], [0, math.cos(x), -math.sin(x)], [0, math.sin(x), math.cos(x)]])
    Ry = np.array([[math.cos(y), 0, math.sin(y)], [0, 1, 0], [-math.sin(y), 0, math.cos(y)]])
    Rz = np.array([[math.cos(z), -math.sin(z), 0], [math.sin(z), math.cos(z), 0], [0, 0, 1]])
    return Rz @ Ry @ Rx


# -------------------------------------------------------------------------- items_game


class ItemSchema:
    def __init__(self, vfs: VFS):
        data = vfs.read("scripts/items/items_game.txt")
        self.items: Dict[str, KV] = {}
        self.prefabs: Dict[str, KV] = {}
        if data is None:
            print("  ! scripts/items/items_game.txt not found; using fallback model paths only")
            return
        root = parse_kv(decode_text(data)).node("items_game")
        for k, v in root.node("prefabs"):
            if isinstance(v, KV):
                self.prefabs[k.lower()] = v
        for k, v in root.node("items"):
            if isinstance(v, KV):
                self.items[k] = v

    def _resolve(self, kv: KV, out: Dict[str, object], depth=0):
        if depth > 8:
            return
        for pf in kv.str("prefab").split():
            p = self.prefabs.get(pf.lower())
            if p is not None:
                self._resolve(p, out, depth + 1)
        for k, v in kv:
            if k.lower() == "prefab":
                continue
            if isinstance(v, KV):
                sub = out.get(k.lower())
                merged = sub if isinstance(sub, dict) else {}
                inner: Dict[str, object] = {}
                self._resolve(v, inner, depth + 1)
                merged.update(inner)
                out[k.lower()] = merged
            else:
                out[k.lower()] = v

    def find(self, defindex: int, names: List[str]) -> Optional[Dict[str, object]]:
        cands = []
        if str(defindex) in self.items:
            cands.append(self.items[str(defindex)])
        lower = [n.lower() for n in names]
        for kv in self.items.values():
            if kv.str("name").lower() in lower:
                cands.append(kv)
        for kv in cands:
            res: Dict[str, object] = {}
            self._resolve(kv, res)
            if res.get("model_player") or res.get("model_world") or res.get("image_inventory"):
                return res
        return None

# -------------------------------------------------------------------------- sounds


class SoundBank:
    """Resolves TF2 soundscript names / raw paths to files and converts them to mono OGG."""

    def __init__(self, vfs: VFS, pack: "PackWriter"):
        self.vfs, self.pack = vfs, pack
        self.scripts: Dict[str, List[str]] = {}
        self.encoder = self._find_encoder()
        self.events: Dict[str, dict] = {}
        man = vfs.read("scripts/game_sounds_manifest.txt")
        files = []
        if man:
            for _k, v in parse_kv(decode_text(man)).node("game_sounds_manifest"):
                if isinstance(v, str) and v.endswith(".txt"):
                    files.append(v)
        for f in files:
            d = vfs.read(f)
            if not d:
                continue
            for name, body in parse_kv(decode_text(d)):
                if not isinstance(body, KV):
                    continue
                waves = [body.str("wave")] if body.str("wave") else [v for _k, v in body.node("rndwave") if isinstance(v, str)]
                waves = [re.sub(r"^[\^)(\]<>*#@!?$&}{~]+", "", w.replace("\\", "/")) for w in waves if w]
                if waves:
                    self.scripts[name.lower()] = waves

    @staticmethod
    def _find_encoder() -> Optional[str]:
        if shutil.which("ffmpeg"):
            return "ffmpeg"
        try:
            import soundfile  # noqa: F401
            return "soundfile"
        except ImportError:
            return None

    def files_for(self, candidates: List[str], limit: int = 1) -> List[str]:
        """First candidate that resolves, expanded to up to ``limit`` random-wave variants."""
        for c in candidates:
            if not c:
                continue
            if c.lower().endswith((".wav", ".mp3")):
                p = c if c.startswith("sound/") else f"sound/{c}"
                if self.vfs.exists(p):
                    return [p]
                continue
            found = [f"sound/{w}" for w in self.scripts.get(c.lower(), []) if self.vfs.exists(f"sound/{w}")]
            if found:
                return found[:limit]
        return []

    def add_files(self, event_key: str, files: List[str], volume: float = 1.0) -> bool:
        if self.encoder is None or not files:
            return False
        sounds = []
        for k, src in enumerate(files):
            ogg = self._to_ogg(self.vfs.read(src), Path(src).suffix)
            if ogg is None:
                continue
            rel = "tf2/" + event_key.replace(".", "_") + (f"_{k}" if len(files) > 1 else "")
            self.pack.add(f"assets/{MOD_ID}/sounds/{rel}.ogg", ogg)
            sounds.append({"name": f"{MOD_ID}:{rel}", "volume": volume})
        if sounds:
            self.events[event_key] = {"replace": True, "sounds": sounds}
        return bool(sounds)

    def add(self, event_key: str, candidates: List[str], volume: float = 1.0, variants: int = 1) -> bool:
        return self.add_files(event_key, self.files_for(candidates, variants), volume)

    def _to_ogg(self, data: bytes, suffix: str) -> Optional[bytes]:
        with tempfile.TemporaryDirectory() as td:
            src = Path(td) / f"in{suffix}"
            dst = Path(td) / "out.ogg"
            src.write_bytes(data)
            try:
                if self.encoder == "ffmpeg":
                    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(src), "-ac", "1", "-c:a", "libvorbis", "-q:a", "5", str(dst)],
                                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
                else:
                    import soundfile as sf
                    audio, rate = sf.read(str(src), always_2d=True)
                    sf.write(str(dst), audio.mean(axis=1), rate, format="OGG", subtype="VORBIS")
                return dst.read_bytes()
            except Exception as ex:
                print(f"  ! sound conversion failed ({src.name}): {ex}")
                return None

# -------------------------------------------------------------------------- pack writer


class PackWriter:
    def __init__(self, out: Path):
        self.out = out
        self.files: Dict[str, bytes] = {}

    def add(self, path: str, data: bytes):
        self.files[path] = data

    def add_json(self, path: str, obj, compact=True):
        self.add(path, json.dumps(obj, separators=(",", ":") if compact else None, indent=None if compact else 2).encode())

    def write(self):
        self.out.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(self.out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
            for p in sorted(self.files):
                z.writestr(p, self.files[p])

# -------------------------------------------------------------------------- item model json

def handheld_3d_model(tex_id: Optional[str], melee: bool = False) -> dict:
    """Base model for special-rendered 3D weapons: only display transforms + particle."""
    m = {
        "textures": {"particle": tex_id or f"{MOD_ID}:item/placeholder"},
        "gui_light": "front",
        "display": {
            "gui":                    {"rotation": [20, -135, 0], "translation": [0, 0, 0], "scale": [0.9, 0.9, 0.9]},
            "ground":                 {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
            "fixed":                  {"rotation": [0, 90, 0], "translation": [0, 0, 0], "scale": [0.9, 0.9, 0.9]},
            "thirdperson_righthand":  {"rotation": [0, 0, 0], "translation": [0, 2.5, -1.5], "scale": [0.85, 0.85, 0.85]},
            "thirdperson_lefthand":   {"rotation": [0, 0, 0], "translation": [0, 2.5, -1.5], "scale": [0.85, 0.85, 0.85]},
            "firstperson_righthand":  {"rotation": [0, 5, 0], "translation": [1.5, 1.5, -2.5], "scale": [0.95, 0.95, 0.95]},
            "firstperson_lefthand":   {"rotation": [0, -5, 0], "translation": [1.5, 1.5, -2.5], "scale": [0.95, 0.95, 0.95]},
            "head":                   {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
        },
    }
    return m

# -------------------------------------------------------------------------- main


def steam_libraries() -> List[Path]:
    home = Path.home()
    roots = [
        Path("C:/Program Files (x86)/Steam"), Path("C:/Program Files/Steam"),
        home / ".steam/steam", home / ".local/share/Steam", home / ".var/app/com.valvesoftware.Steam/.local/share/Steam",
        home / "Library/Application Support/Steam",
    ]
    libs = []
    for r in roots:
        vdf = r / "steamapps/libraryfolders.vdf"
        if r.is_dir():
            libs.append(r)
        if vdf.is_file():
            try:
                kv = parse_kv(vdf.read_text(errors="replace")).node("libraryfolders")
                for _k, v in kv:
                    if isinstance(v, KV) and v.str("path"):
                        libs.append(Path(v.str("path")))
            except Exception:
                pass
    return libs


def find_tf2() -> Optional[Path]:
    for lib in steam_libraries():
        p = lib / "steamapps/common/Team Fortress 2"
        if (p / "tf/gameinfo.txt").is_file():
            return p
    return None


def run(args) -> int:
    root = Path(args.tf2) if args.tf2 else find_tf2()
    if root is None or not (root / "tf").is_dir():
        print("Could not find Team Fortress 2. Pass --tf2 \"<path to Team Fortress 2 folder>\".")
        return 2
    if not (root / "tf/gameinfo.txt").is_file():
        print(f"{root} does not look like a TF2 install (tf/gameinfo.txt missing).")
        return 2
    print(f"TF2 install: {root}")
    vfs = VFS(root)
    print(f"  mounted {sum(1 for s in vfs.sources if isinstance(s, VPK))} VPK archives")

    out = Path(args.out).expanduser()
    pack = PackWriter(out)
    tex = TextureCache(vfs, pack, args.max_texture)
    conv = ModelConverter(vfs, tex, args.max_texture)
    schema = ItemSchema(vfs)
    manifest = {"format": 2, "players": [], "weapons": {}, "props": [], "gui": [], "sounds": []}
    report: List[str] = []

    orient = {}
    if args.orient and Path(args.orient).is_file():
        orient = json.loads(Path(args.orient).read_text())

    resolved = {}
    for wid, spec in WEAPONS.items():
        info = schema.find(spec["defindex"], spec["names"]) if schema.items else None
        resolved[wid] = info or {}
    if args.list_weapons:
        for wid, info in resolved.items():
            print(f"{wid:22s} slot={anim_slot(wid, info):9s} model={info.get('model_player') or info.get('model_world') or '-'}"
                  f"  icon={info.get('image_inventory') or '-'}")
        return 0

    # which animation sets each class needs (TF2 animates by the weapon's slot/anim_slot)
    class_slots: Dict[str, List[str]] = {c: ["PRIMARY", "SECONDARY", "MELEE"] for c in CLASSES}
    for wid, info in resolved.items():
        cls = WEAPON_CLASS.get(wid)
        if cls:
            s = anim_slot(wid, info)
            if s not in class_slots[cls]:
                class_slots[cls].append(s)

    # ---- player models
    if not args.skip_players:
        print("Converting player models (with animations)...")
        for cls in CLASSES:
            path = f"models/player/{CLASS_MODEL[cls]}.mdl"
            try:
                mesh = conv.player(path, args.player_scale, class_slots[cls], report)
                pack.add_json(f"assets/{MOD_ID}/merc_models/player/{cls}.json", mesh)
                manifest["players"].append(cls)
                print(f"  + {cls}: {len(mesh['clips'])} animations")
            except Exception as ex:
                print(f"  ! {cls}: {ex}")
                report.append(f"{path}: FAILED {ex}")
            arms = f"models/weapons/c_models/c_{CLASS_MODEL[cls]}_arms.mdl"
            if vfs.exists(arms):
                report.append(f"{arms} sequences: " + ", ".join(conv.sequences(arms)))

    # ---- weapons
    if not args.skip_weapons:
        print("Converting weapons...")
        for wid, info in resolved.items():
            mpath = info.get("model_player") or info.get("model_world")
            icon = info.get("image_inventory")
            slot = anim_slot(wid, info)
            icon_id = None
            if isinstance(icon, str) and icon:
                for cand in (f"{icon}_large", icon):
                    icon_id = tex.export(cand, opaque=False, dest_hint=f"item/tf2/{wid}")
                    if icon_id:
                        break
            has_mesh = False
            if isinstance(mpath, str) and mpath and vfs.exists(mpath):
                try:
                    o = orient.get(wid, {})
                    mesh = conv.weapon(mpath, wid in MELEE_WEAPONS, tuple(o.get("rotate", (0, 0, 0))), float(o.get("size", 1.0)))
                    mesh["anim_slot"] = slot
                    pack.add_json(f"assets/{MOD_ID}/merc_models/weapon/{wid}.json", mesh)
                    has_mesh = True
                    print(f"  + {wid:22s} [{slot}] {mpath}")
                except Exception as ex:
                    print(f"  ! {wid}: {ex}")
                    report.append(f"weapon {wid}: FAILED {mpath}: {ex}")
            else:
                print(f"  - {wid}: no 3D model found (icon {'ok' if icon_id else 'missing'})")
                report.append(f"weapon {wid}: no model (items_game entry {'found' if info else 'NOT found'})")
            manifest["weapons"][wid] = {"anim_slot": slot, "mesh": has_mesh, "icon": bool(icon_id)}
            if icon_id:
                pack.add_json(f"assets/{MOD_ID}/models/item/tf2/{wid}_icon.json",
                              {"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD_ID}:item/tf2/{wid}"}})
            pack.add_json(f"assets/{MOD_ID}/items/{wid}.json", item_definition(wid, has_mesh, bool(icon_id)), compact=False)
            if has_mesh:
                pack.add_json(f"assets/{MOD_ID}/models/item/tf2/{wid}_3d.json",
                              handheld_3d_model(f"{MOD_ID}:item/tf2/{wid}" if icon_id else None, wid in MELEE_WEAPONS), compact=False)

        print("Converting projectiles & buildings...")
        for pid, paths in PROPS.items():
            for p in paths:
                if vfs.exists(p):
                    try:
                        building = pid in ("sentry", "dispenser")
                        mesh = conv.static(p, size=1.0, rotate=tuple(orient.get(pid, {}).get("rotate", (0, 0, 0))), center=not building)
                        pack.add_json(f"assets/{MOD_ID}/merc_models/prop/{pid}.json", mesh)
                        manifest["props"].append(pid)
                        print(f"  + {pid}")
                    except Exception as ex:
                        print(f"  ! {pid}: {ex}")
                    break

    # ---- HUD / menu art
    if not args.skip_gui:
        print("Extracting HUD and menu art...")
        for key, cands in gui_textures().items():
            got = None
            for c in cands:
                got = tex.export(c, opaque=False, dest_hint=f"gui/tf2/{key}")
                if got:
                    break
            if got:
                manifest["gui"].append(key)
            report.append(f"gui {key}: {'ok' if got else 'missing (tried ' + ', '.join(cands) + ')'}")
        print(f"  {len(manifest['gui'])} images")

    # ---- sounds
    if not args.skip_sounds:
        sb = SoundBank(vfs, pack)
        if sb.encoder is None:
            print("Sounds skipped: install ffmpeg (on PATH) or `pip install soundfile` to convert sounds.")
        else:
            print(f"Converting sounds (encoder: {sb.encoder})...")
            for wid, info in resolved.items():
                cands: Dict[str, List[str]] = {k: [] for k in WEAPON_SOUND_KEYS}
                vis = info.get("visuals") if isinstance(info.get("visuals"), dict) else {}
                for ev, keys in WEAPON_SOUND_KEYS.items():
                    for k in keys:
                        if isinstance(vis.get(f"sound_{k}"), str):
                            cands[ev].append(vis[f"sound_{k}"])
                ic = info.get("item_class")
                if isinstance(ic, str):
                    script = vfs.read(f"scripts/{ic}.txt")
                    if script:
                        sd = parse_kv(decode_text(script)).node("WeaponData").node("SoundData")
                        for ev, keys in WEAPON_SOUND_KEYS.items():
                            cands[ev] += [sd.str(k) for k in keys if sd.str(k)]
                for ev, c in cands.items():
                    if c and sb.add(f"weapon.{wid}.{ev}", c, variants=3):
                        manifest["sounds"].append(f"weapon.{wid}.{ev}")
            for key, c in SHARED_SOUNDS.items():
                if sb.add(f"fx.{key}", c, variants=3):
                    manifest["sounds"].append(f"fx.{key}")
            for cls in CLASSES:
                for ev, prefixes in VOICE_EVENTS.items():
                    files = []
                    for pre in prefixes:
                        files += [f for f in vfs.list(f"sound/vo/{cls}_{pre}") if f.endswith((".mp3", ".wav"))]
                    if sb.add_files(f"class.{cls}.{ev}", files[:6], 0.9):
                        manifest["sounds"].append(f"class.{cls}.{ev}")
            if sb.events:
                pack.add_json(f"assets/{MOD_ID}/sounds.json", sb.events, compact=False)
            print(f"  {len(sb.events)} sound events converted")
            report.append(f"sounds: {len(sb.events)} events: " + ", ".join(sorted(sb.events)))

    pack.add_json(f"assets/{MOD_ID}/merc_models/manifest.json", manifest, compact=False)
    pack.add_json("pack.mcmeta", {"pack": {
        "description": "Mercenaries: TF2 assets extracted from your own copy. Personal use only - do not redistribute.",
        "min_format": PACK_MIN_FORMAT, "max_format": PACK_MAX_FORMAT}}, compact=False)
    pack.add("README.txt", (
        "Generated by extract_tf2_assets.py from a local Team Fortress 2 installation.\n"
        "Team Fortress 2 assets are (c) Valve Corporation. This pack is for personal use with the\n"
        "Mercenaries Minecraft mod and must not be redistributed.\n").encode())
    pack.write()
    rep = out.with_name(out.stem + "_report.txt")
    rep.write_text("\n".join(report), encoding="utf-8")
    print(f"\nWrote {out}  ({out.stat().st_size / 1e6:.1f} MB, {len(pack.files)} files)")
    print(f"Report (no Valve content, safe to share): {rep}")
    print("Enable it in Minecraft: Options > Resource Packs (put it above any other pack).")
    return 0


def anim_slot(wid: str, info: dict) -> str:
    """TF2's own animation slot for a weapon (items_game anim_slot, else item_slot)."""
    s = info.get("anim_slot") or info.get("item_slot") or WEAPON_SLOT_FALLBACK.get(wid, "primary")
    s = str(s).upper()
    return {"PDA2": "PDA", "BUILDING": "BUILDING"}.get(s, s)


def item_definition(wid: str, has_mesh: bool, has_icon: bool) -> dict:
    flat = {"type": "minecraft:model", "model": f"{MOD_ID}:item/tf2/{wid}_icon" if has_icon else f"{MOD_ID}:item/{wid}"}
    if not has_mesh:
        return {"model": flat}
    base = f"{MOD_ID}:item/tf2/{wid}_3d"

    def special(skin: int) -> dict:
        return {"type": "minecraft:special", "base": base,
                "model": {"type": f"{MOD_ID}:merc_mesh", "mesh": f"weapon/{wid}", "skin": skin}}
    team = {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
            "cases": [{"when": "blu", "model": special(1)}], "fallback": special(0)}
    return {"model": {
        "type": "minecraft:select", "property": "minecraft:display_context",
        "cases": [
            {"when": "gui", "model": flat},
            # third person: the mod draws the weapon on the TF2 skeleton's hand bone instead
            {"when": ["thirdperson_righthand", "thirdperson_lefthand"], "model": {"type": "minecraft:empty"}},
        ],
        "fallback": team,
    }}


def gui_textures() -> Dict[str, List[str]]:
    t = {
        "health_bg": ["hud/health_bg"], "health_color": ["hud/health_color"],
        "health_over": ["hud/health_over_bg"], "health_dead": ["hud/health_dead"],
        "ammo_red": ["hud/ammo_red_bg"], "ammo_blu": ["hud/ammo_blue_bg"],
        "misc_red": ["hud/misc_ammo_area_red"], "misc_blu": ["hud/misc_ammo_area_blue"],
        "crosshair": ["vgui/crosshairs/crosshair3", "vgui/crosshairs/default", "vgui/crosshairs/crosshair1"],
    }
    for cls in CLASSES:
        t[f"class_{cls}_red"] = [f"vgui/class_sel_sm_{cls}_red", f"hud/class_{cls}red"]
        t[f"class_{cls}_blu"] = [f"vgui/class_sel_sm_{cls}_blu", f"hud/class_{cls}blue"]
        t[f"portrait_{cls}_red"] = [f"hud/class_{cls}red", f"vgui/class_sel_sm_{cls}_red"]
        t[f"portrait_{cls}_blu"] = [f"hud/class_{cls}blue", f"vgui/class_sel_sm_{cls}_blu"]
        t[f"icon_{cls}"] = [f"hud/leaderboard_class_{cls}"]
    return t


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tf2", help="path to the 'Team Fortress 2' install folder (auto-detected from Steam)")
    ap.add_argument("--out", default="MercenariesTF2Assets.zip", help="output resource pack zip")
    ap.add_argument("--max-texture", type=int, default=1024, help="downscale textures larger than this (px)")
    ap.add_argument("--player-scale", type=float, default=0.0235, help="Source units -> model units for player meshes")
    ap.add_argument("--orient", help="JSON file with per-weapon {'rotate':[x,y,z],'size':s} overrides")
    ap.add_argument("--skip-players", action="store_true")
    ap.add_argument("--skip-weapons", action="store_true")
    ap.add_argument("--skip-sounds", action="store_true")
    ap.add_argument("--skip-gui", action="store_true")
    ap.add_argument("--list-weapons", action="store_true", help="print weapon -> TF2 model mapping and exit")
    return run(ap.parse_args(argv))


if __name__ == "__main__":
    sys.exit(main())
