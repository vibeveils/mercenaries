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

# -------------------------------------------------------------------------- MDL / VVD / VTX


@dataclass
class Bone:
    name: str
    parent: int
    world_pos: np.ndarray  # bind-pose position in model space


@dataclass
class MeshData:
    material: int                 # skin reference index
    positions: np.ndarray         # (n,3) source model space
    normals: np.ndarray           # (n,3)
    uvs: np.ndarray               # (n,2)
    bones: np.ndarray             # (n,) dominant bone
    indices: np.ndarray           # (m,) triangle list into this mesh


@dataclass
class Model:
    name: str
    bones: List[Bone]
    textures: List[str]
    cd_textures: List[str]
    skins: List[List[int]]
    meshes: List[MeshData] = field(default_factory=list)


def _cstr(buf: bytes, off: int) -> str:
    end = buf.index(b"\0", off)
    return buf[off:end].decode("utf-8", errors="replace")


def read_vvd(data: bytes) -> np.ndarray:
    """Returns structured array of LOD0 vertices after fixups."""
    ident, ver, chk, nlods = struct.unpack_from("<4sIii", data, 0)
    if ident != b"IDSV":
        raise ValueError("bad VVD")
    lod_verts = struct.unpack_from("<8i", data, 16)
    nfix, fix_start, vert_start, _tan_start = struct.unpack_from("<iiii", data, 48)
    dt = np.dtype([("w", "<f4", 3), ("b", "u1", 3), ("nb", "u1"), ("pos", "<f4", 3),
                   ("nrm", "<f4", 3), ("uv", "<f4", 2)])
    total = lod_verts[0]
    if nfix == 0:
        return np.frombuffer(data, dt, total, vert_start)
    allv = np.frombuffer(data, dt, (len(data) - vert_start) // 48, vert_start)
    chunks = []
    for i in range(nfix):
        lod, src, cnt = struct.unpack_from("<iii", data, fix_start + 12 * i)
        if lod >= 0:
            chunks.append(allv[src:src + cnt])
    return np.concatenate(chunks)


def read_mdl(vfs: VFS, path: str) -> Model:
    mdl = vfs.read(path)
    if mdl is None:
        raise FileNotFoundError(path)
    base = path[:-4]
    vvd = vfs.read(base + ".vvd")
    vtx = vfs.read(base + ".dx90.vtx") or vfs.read(base + ".dx80.vtx") or vfs.read(base + ".sw.vtx")
    if vvd is None or vtx is None:
        raise FileNotFoundError(f"{path}: missing .vvd/.vtx")
    ident, version = struct.unpack_from("<4si", mdl, 0)
    if ident != b"IDST":
        raise ValueError(f"{path}: bad MDL")
    name = mdl[12:76].split(b"\0")[0].decode(errors="replace")
    (numbones, boneindex) = struct.unpack_from("<ii", mdl, 156)
    numtex, texidx, numcd, cdidx, nskinref, nfam, skinidx, nbp, bpidx = struct.unpack_from("<9i", mdl, 204)

    bones = []
    for i in range(numbones):
        o = boneindex + i * 216
        nameoff, parent = struct.unpack_from("<ii", mdl, o)
        m = np.array(struct.unpack_from("<12f", mdl, o + 96), np.float64).reshape(3, 4)
        R, t = m[:, :3], m[:, 3]
        world = -R.T @ t  # inverse of poseToBone translation
        bones.append(Bone(_cstr(mdl, o + nameoff), parent, world))

    textures = []
    for i in range(numtex):
        o = texidx + i * 64
        textures.append(_cstr(mdl, o + struct.unpack_from("<i", mdl, o)[0]))
    cds = []
    for i in range(numcd):
        cds.append(_cstr(mdl, struct.unpack_from("<i", mdl, cdidx + 4 * i)[0]))
    skins = []
    for f in range(max(1, nfam)):
        skins.append(list(struct.unpack_from(f"<{nskinref}h", mdl, skinidx + f * nskinref * 2)) if nskinref else [])

    verts = read_vvd(vvd)
    model = Model(name, bones, textures, cds, skins)

    # VTX header (pack(1)): version i, vcache i, maxbps H, maxbpt H, maxbpv i, checksum i, nlods i, mrl i, nbp i, bpoff i
    v_nbp, v_bpoff = struct.unpack_from("<ii", vtx, 28)
    for bp in range(nbp):
        bo = bpidx + bp * 16
        _bpname, nmodels, _base, modelindex = struct.unpack_from("<iiii", mdl, bo)
        vbo = v_bpoff + bp * 8
        vnm, vmo = struct.unpack_from("<ii", vtx, vbo)
        if nmodels == 0:
            continue
        mi = 0  # default bodygroup
        mo = bo + modelindex + mi * 148
        nmeshes, meshindex, nverts, vertexindex = struct.unpack_from("<iiii", mdl, mo + 72)
        if nmeshes == 0 or nverts == 0:
            continue
        model_vstart = vertexindex // 48
        vmodel = vbo + vmo + mi * 8
        _nlods, lodoff = struct.unpack_from("<ii", vtx, vmodel)
        vlod = vmodel + lodoff  # LOD 0
        vnmesh, vmeshoff = struct.unpack_from("<ii", vtx, vlod)
        for me in range(nmeshes):
            mso = mo + meshindex + me * 116
            material, _modelidx, m_nverts, m_voff = struct.unpack_from("<iiii", mdl, mso)
            vmesh = vlod + vmeshoff + me * 9
            nsg, sgoff = struct.unpack_from("<ii", vtx, vmesh)
            out_idx: List[int] = []
            for sg in range(nsg):
                vsg = vmesh + sgoff + sg * 25
                sg_nv, sg_vo, sg_ni, sg_io, sg_ns, sg_so = struct.unpack_from("<iiiiii", vtx, vsg)
                sverts = np.frombuffer(vtx, np.dtype([("bw", "u1", 3), ("nb", "u1"), ("orig", "<u2"), ("bid", "i1", 3)]), sg_nv, vsg + sg_vo)
                sidx = np.frombuffer(vtx, "<u2", sg_ni, vsg + sg_io)
                orig = sverts["orig"].astype(np.int64)
                for st in range(sg_ns):
                    so = vsg + sg_so + st * 27
                    st_ni, st_io, st_nv, st_vo = struct.unpack_from("<iiii", vtx, so)
                    st_flags = vtx[so + 18]
                    seg = sidx[st_io:st_io + st_ni]
                    if st_flags & 2:  # tristrip
                        tris = []
                        for k in range(len(seg) - 2):
                            a, b, c = seg[k], seg[k + 1], seg[k + 2]
                            if a == b or b == c or a == c:
                                continue
                            tris += [a, c, b] if k % 2 else [a, b, c]
                        seg = np.array(tris, np.int64)
                    out_idx.extend(orig[np.asarray(seg, np.int64)].tolist())
            if not out_idx:
                continue
            gstart = model_vstart + m_voff
            mv = verts[gstart:gstart + m_nverts]
            w = mv["w"]
            dom = mv["b"][np.arange(len(mv)), np.argmax(w, axis=1)].astype(np.int32)
            model.meshes.append(MeshData(material, mv["pos"].astype(np.float64), mv["nrm"].astype(np.float64),
                                         mv["uv"].astype(np.float64), dom, np.array(out_idx, np.int32)))
    return model

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


def resolve_material(vfs: VFS, model: Model, tex_index: int) -> Optional[Material]:
    name = model.textures[tex_index].replace("\\", "/")
    dirs = model.cd_textures or [""]
    for d in dirs:
        d = d.replace("\\", "/").strip("/")
        p = f"materials/{d}/{name}.vmt" if d else f"materials/{name}.vmt"
        mat = load_vmt(vfs, p)
        if mat:
            return mat
    return None

# -------------------------------------------------------------------------- mesh conversion

PART_ORDER = ["head", "body", "right_arm", "left_arm", "right_leg", "left_leg"]


def bone_part(name: str) -> str:
    n = name.lower()
    side = "right" if re.search(r"(_r$|_r_|_r\d|right)", n) else ("left" if re.search(r"(_l$|_l_|_l\d|left)", n) else None)
    if any(k in n for k in ("head", "neck", "jaw", "eye", "brow", "lip", "tongue", "cheek", "hat")):
        return "head"
    if side and any(k in n for k in ("upperarm", "lowerarm", "forearm", "hand", "finger", "thumb", "index", "middle", "ring", "pinky", "wrist", "elbow")):
        return f"{side}_arm"
    if side and any(k in n for k in ("hip", "thigh", "knee", "calf", "foot", "toe", "upperleg", "lowerleg", "ankle")):
        return f"{side}_leg"
    return "body"


def find_bone(bones: List[Bone], *keys: str) -> Optional[Bone]:
    for k in keys:
        for b in bones:
            if b.name.lower() == k.lower():
                return b
    for k in keys:
        for b in bones:
            if k.lower() in b.name.lower():
                return b
    return None


def rot_between(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    a = a / (np.linalg.norm(a) + 1e-12)
    b = b / (np.linalg.norm(b) + 1e-12)
    v = np.cross(a, b)
    c = float(np.dot(a, b))
    if c < -0.9999:
        axis = np.cross(a, [1, 0, 0])
        if np.linalg.norm(axis) < 1e-6:
            axis = np.cross(a, [0, 1, 0])
        axis /= np.linalg.norm(axis)
        return 2 * np.outer(axis, axis) - np.eye(3)
    vx = np.array([[0, -v[2], v[1]], [v[2], 0, -v[0]], [-v[1], v[0], 0]])
    return np.eye(3) + vx + vx @ vx * (1.0 / (1.0 + c))


def src_to_entity(p: np.ndarray, scale: float) -> np.ndarray:
    """Source (x fwd, y left, z up) -> Minecraft entity-model space (x left, y down, z back), feet at y=1.5."""
    out = np.empty_like(p)
    out[..., 0] = p[..., 1] * scale
    out[..., 1] = 1.5 - p[..., 2] * scale
    out[..., 2] = -p[..., 0] * scale
    return out


def src_dir_to_entity(n: np.ndarray) -> np.ndarray:
    out = np.empty_like(n)
    out[..., 0], out[..., 1], out[..., 2] = n[..., 1], -n[..., 2], -n[..., 0]
    return out


def src_to_item(p: np.ndarray) -> np.ndarray:
    """Source -> item space: barrel (+x) points to -z, up is +y."""
    out = np.empty_like(p)
    out[..., 0], out[..., 1], out[..., 2] = -p[..., 1], p[..., 2], -p[..., 0]
    return out


def _r(a: np.ndarray, nd: int = 4) -> list:
    return np.round(a.astype(np.float64), nd).reshape(-1).tolist()


class ModelConverter:
    def __init__(self, vfs: VFS, tex: TextureCache, max_tex: int):
        self.vfs, self.tex = vfs, tex

    def _skins(self, model: Model) -> Tuple[List[List[Optional[str]]], List[str], Dict[int, Material]]:
        """Returns per-family texture ids per skin-ref slot, render mode per slot, and material per texture index."""
        mats: Dict[int, Optional[Material]] = {}
        fams: List[List[Optional[str]]] = []
        modes: List[str] = []
        nref = len(model.skins[0]) if model.skins else len(model.textures)
        for fam in (model.skins or [list(range(len(model.textures)))]):
            row = []
            for slot in range(nref):
                ti = fam[slot] if slot < len(fam) else slot
                if ti not in mats:
                    mats[ti] = resolve_material(self.vfs, model, ti) if ti < len(model.textures) else None
                m = mats[ti]
                tid = None
                if m is not None:
                    shader = m.shader.lower()
                    src = m.params.get("$iris") if shader in ("eyes", "eyerefract") else m.basetexture
                    if src:
                        tid = self.tex.export(src, opaque=not (m.translucent or m.alphatest or shader in ("eyes", "eyerefract")))
                row.append(tid)
            fams.append(row)
        for slot in range(nref):
            ti = model.skins[0][slot] if model.skins and slot < len(model.skins[0]) else slot
            m = mats.get(ti)
            mode = "cutout"
            if m is not None:
                if m.shader.lower() in ("eyes", "eyerefract"):
                    mode = "eye"
                elif m.translucent:
                    mode = "translucent"
            modes.append(mode)
        return fams, modes, {k: v for k, v in mats.items() if v}

    @staticmethod
    def _eye_uv(pos_e: np.ndarray) -> np.ndarray:
        """Planar projection for eyeball meshes (entity space, looking along -z)."""
        c = pos_e.mean(0)
        ext = max(1e-6, float(np.max(np.abs(pos_e[:, :2] - c[:2]))) * 2.0)
        uv = np.empty((len(pos_e), 2))
        uv[:, 0] = 0.5 + (pos_e[:, 0] - c[0]) / ext
        uv[:, 1] = 0.5 + (pos_e[:, 1] - c[1]) / ext
        return np.clip(uv, 0, 1)

    def player(self, path: str, scale: float) -> dict:
        model = read_mdl(self.vfs, path)
        fams, modes, _ = self._skins(model)
        bones = model.bones
        part_of_bone = [bone_part(b.name) for b in bones]

        pivots_src = {
            "head": find_bone(bones, "bip_neck", "neck", "bip_head"),
            "body": find_bone(bones, "bip_neck", "bip_spine_3", "spine"),
            "right_arm": find_bone(bones, "bip_upperArm_R", "upperarm_r"),
            "left_arm": find_bone(bones, "bip_upperArm_L", "upperarm_l"),
            "right_leg": find_bone(bones, "bip_hip_R", "thigh_r"),
            "left_leg": find_bone(bones, "bip_hip_L", "thigh_l"),
        }
        ends = {
            "right_arm": find_bone(bones, "bip_hand_R", "hand_r"),
            "left_arm": find_bone(bones, "bip_hand_L", "hand_l"),
            "right_leg": find_bone(bones, "bip_foot_R", "foot_r"),
            "left_leg": find_bone(bones, "bip_foot_L", "foot_l"),
        }
        pivots = {}
        straighten = {}
        for part, b in pivots_src.items():
            pv = src_to_entity(b.world_pos[None], scale)[0] if b else np.array([0.0, 0.0, 0.0])
            pivots[part] = pv
            e = ends.get(part)
            if b is not None and e is not None:
                d = src_to_entity(e.world_pos[None], scale)[0] - pv
                # hang straight down (+y in entity model space); legs keep slight natural offset
                straighten[part] = rot_between(d, np.array([0.0, 1.0, 0.0]))

        parts: Dict[str, List[dict]] = {p: [] for p in PART_ORDER}
        for mesh in model.meshes:
            pos_e = src_to_entity(mesh.positions, scale)
            nrm_e = src_dir_to_entity(mesh.normals)
            mp = np.array([part_of_bone[b] if 0 <= b < len(bones) else "body" for b in mesh.bones])
            mode = modes[mesh.material] if mesh.material < len(modes) else "cutout"
            uv = self._eye_uv(pos_e) if mode == "eye" else mesh.uvs
            tri = mesh.indices.reshape(-1, 3)
            tri_parts = mp[tri]
            # each triangle goes to the part owning >= 2 of its vertices, else its first vertex's part
            label = tri_parts[:, 0].copy()
            maj = tri_parts[:, 1] == tri_parts[:, 2]
            label[maj] = tri_parts[maj, 1]
            for part in PART_ORDER:
                sel = np.where(label == part)[0]
                if len(sel) == 0:
                    continue
                t = tri[sel]
                used, inv = np.unique(t.reshape(-1), return_inverse=True)
                pv = pivots[part]
                lp = pos_e[used] - pv
                ln = nrm_e[used]
                if part in straighten:
                    R = straighten[part]
                    lp = lp @ R.T
                    ln = ln @ R.T
                parts[part].append({
                    "mat": int(mesh.material),
                    "v": _r(lp), "n": _r(ln, 3), "uv": _r(uv[used], 5),
                    "i": inv.astype(np.int32).tolist(),
                })
        return {
            "format": 1, "kind": "player", "source": path,
            "skins": fams, "modes": modes,
            "parts": {p: {"pivot": _r(pivots[p] * 16.0, 3), "meshes": parts[p]} for p in PART_ORDER if parts[p]},
        }

    def static(self, path: str, size: float = 1.0, rotate: Tuple[float, float, float] = (0, 0, 0), center: bool = True) -> dict:
        model = read_mdl(self.vfs, path)
        fams, modes, _ = self._skins(model)
        allp = np.concatenate([m.positions for m in model.meshes]) if model.meshes else np.zeros((1, 3))
        pts = src_to_item(allp)
        R = euler(rotate)
        pts = pts @ R.T
        lo, hi = pts.min(0), pts.max(0)
        ext = float(max(hi - lo)) or 1.0
        s = size / ext
        mid = (lo + hi) / 2
        meshes = []
        for mesh in model.meshes:
            p = src_to_item(mesh.positions) @ R.T
            anchor = mid if center else np.array([mid[0], lo[1], mid[2]])
            p = (p - anchor) * s + (np.array([0.5, 0.5, 0.5]) if center else 0)
            n = src_to_item(mesh.normals) @ R.T
            meshes.append({"mat": int(mesh.material), "v": _r(p), "n": _r(n, 3), "uv": _r(mesh.uvs, 5),
                           "i": mesh.indices.astype(np.int32).tolist()})
        return {"format": 1, "kind": "static", "source": path, "skins": fams, "modes": modes,
                "native_size": round(ext, 3), "parts": {"root": {"pivot": [0, 0, 0], "meshes": meshes}}}


def euler(deg: Tuple[float, float, float]) -> np.ndarray:
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
    def __init__(self, vfs: VFS, pack: "PackWriter"):
        self.vfs, self.pack = vfs, pack
        self.scripts: Dict[str, str] = {}
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
                wave = body.str("wave")
                if not wave:
                    rw = body.node("rndwave")
                    waves = [v for _k, v in rw if isinstance(v, str)]
                    wave = waves[0] if waves else ""
                if wave:
                    self.scripts[name.lower()] = re.sub(r"^[\^)(\]<>*#@!?$&}{~]+", "", wave.replace("\\", "/"))

    @staticmethod
    def _find_encoder() -> Optional[str]:
        if shutil.which("ffmpeg"):
            return "ffmpeg"
        try:
            import soundfile  # noqa: F401
            return "soundfile"
        except ImportError:
            return None

    def wav_for(self, candidates: List[str]) -> Optional[str]:
        for c in candidates:
            if not c:
                continue
            if c.lower().endswith((".wav", ".mp3")):
                p = c if c.startswith("sound/") else f"sound/{c}"
                if self.vfs.exists(p):
                    return p
            w = self.scripts.get(c.lower())
            if w and self.vfs.exists(f"sound/{w}"):
                return f"sound/{w}"
        return None

    def add(self, event_key: str, candidates: List[str], volume: float = 1.0) -> bool:
        if self.encoder is None:
            return False
        src = self.wav_for(candidates)
        if src is None:
            return False
        data = self.vfs.read(src)
        ogg = self._to_ogg(data, Path(src).suffix)
        if ogg is None:
            return False
        rel = "tf2/" + event_key.replace(".", "_")
        self.pack.add(f"assets/{MOD_ID}/sounds/{rel}.ogg", ogg)
        self.events[event_key] = {"replace": True, "sounds": [{"name": f"{MOD_ID}:{rel}", "volume": volume}]}
        return True

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

def handheld_3d_model(tex_id: Optional[str]) -> dict:
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
    manifest = {"format": 1, "players": [], "weapons": [], "props": [], "icons": [], "sounds": []}

    orient = {}
    if args.orient and Path(args.orient).is_file():
        orient = json.loads(Path(args.orient).read_text())

    resolved = {}
    for wid, spec in WEAPONS.items():
        info = schema.find(spec["defindex"], spec["names"]) if schema.items else None
        resolved[wid] = info or {}
    if args.list_weapons:
        for wid, info in resolved.items():
            print(f"{wid:22s} model={info.get('model_player') or info.get('model_world') or '-'}  icon={info.get('image_inventory') or '-'}")
        return 0

    # ---- player models
    if not args.skip_players:
        print("Converting player models...")
        for cls in CLASSES:
            path = f"models/player/{CLASS_MODEL[cls]}.mdl"
            try:
                mesh = conv.player(path, args.player_scale)
                pack.add_json(f"assets/{MOD_ID}/merc_models/player/{cls}.json", mesh)
                manifest["players"].append(cls)
                print(f"  + {cls}")
            except Exception as ex:
                print(f"  ! {cls}: {ex}")
        for cls in CLASSES:
            if tex.export(f"hud/leaderboard_class_{cls}", opaque=False, dest_hint=f"gui/class/{cls}"):
                manifest["icons"].append(f"class/{cls}")

    # ---- weapons
    if not args.skip_weapons:
        print("Converting weapons...")
        for wid, info in resolved.items():
            mpath = info.get("model_player") or info.get("model_world")
            icon = info.get("image_inventory")
            icon_id = None
            if isinstance(icon, str) and icon:
                for cand in (f"{icon}_large", icon):
                    icon_id = tex.export(cand, opaque=False, dest_hint=f"item/tf2/{wid}")
                    if icon_id:
                        break
            if isinstance(mpath, str) and mpath and vfs.exists(mpath):
                try:
                    rot = tuple(orient.get(wid, {}).get("rotate", (0, 0, 0)))
                    mesh = conv.static(mpath, size=float(orient.get(wid, {}).get("size", 1.0)), rotate=rot)
                    pack.add_json(f"assets/{MOD_ID}/merc_models/weapon/{wid}.json", mesh)
                    manifest["weapons"].append(wid)
                    base = f"{MOD_ID}:item/tf2/{wid}_3d"
                    pack.add_json(f"assets/{MOD_ID}/models/item/tf2/{wid}_3d.json",
                                  handheld_3d_model(f"{MOD_ID}:item/tf2/{wid}" if icon_id else None), compact=False)
                    def special(skin: int) -> dict:
                        return {"type": "minecraft:special", "base": base,
                                "model": {"type": f"{MOD_ID}:merc_mesh", "mesh": f"weapon/{wid}", "skin": skin}}
                    # weapons carry custom_model_data "red"/"blu" (set by the mod) to pick the team skin
                    team_select = {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
                                   "cases": [{"when": "blu", "model": special(1)}], "fallback": special(0)}
                    gui_model = {"type": "minecraft:model", "model": f"{MOD_ID}:item/tf2/{wid}_icon"} if icon_id else team_select
                    item_def = {"model": {
                        "type": "minecraft:select", "property": "minecraft:display_context",
                        "cases": [{"when": "gui", "model": gui_model}],
                        "fallback": team_select,
                    }}
                    pack.add_json(f"assets/{MOD_ID}/items/{wid}.json", item_def, compact=False)
                    print(f"  + {wid}  ({mpath})")
                except Exception as ex:
                    print(f"  ! {wid}: {ex}")
            else:
                print(f"  - {wid}: no 3D model found (icon {'ok' if icon_id else 'missing'})")
            if icon_id:
                pack.add_json(f"assets/{MOD_ID}/models/item/tf2/{wid}_icon.json",
                              {"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD_ID}:item/tf2/{wid}"}})
                if wid not in manifest["weapons"]:
                    pack.add_json(f"assets/{MOD_ID}/items/{wid}.json",
                                  {"model": {"type": "minecraft:model", "model": f"{MOD_ID}:item/tf2/{wid}_icon"}})
                manifest["icons"].append(f"item/{wid}")

        print("Converting projectiles & buildings...")
        for pid, paths in PROPS.items():
            for p in paths:
                if vfs.exists(p):
                    try:
                        building = pid in ("sentry", "dispenser")
                        rot = tuple(orient.get(pid, {}).get("rotate", (0, 0, 0)))
                        mesh = conv.static(p, size=1.0, rotate=rot, center=not building)
                        pack.add_json(f"assets/{MOD_ID}/merc_models/prop/{pid}.json", mesh)
                        manifest["props"].append(pid)
                        print(f"  + {pid}")
                    except Exception as ex:
                        print(f"  ! {pid}: {ex}")
                    break

    # ---- sounds
    if not args.skip_sounds:
        sb = SoundBank(vfs, pack)
        if sb.encoder is None:
            print("Sounds skipped: install ffmpeg (on PATH) or `pip install soundfile` to convert sounds.")
        else:
            print(f"Converting sounds (encoder: {sb.encoder})...")
            ok = 0
            for wid, info in resolved.items():
                cands = []
                vis = info.get("visuals") if isinstance(info.get("visuals"), dict) else {}
                for key in ("sound_single_shot", "sound_melee_miss", "sound_burst"):
                    if isinstance(vis.get(key), str):
                        cands.append(vis[key])
                ic = info.get("item_class")
                if isinstance(ic, str):
                    script = vfs.read(f"scripts/{ic}.txt")
                    if script:
                        sd = parse_kv(decode_text(script)).node("WeaponData").node("SoundData")
                        for key in ("single_shot", "melee_miss", "burst"):
                            if sd.str(key):
                                cands.append(sd.str(key))
                if sb.add(f"weapon.{wid}.fire", cands):
                    ok += 1
                    manifest["sounds"].append(f"weapon.{wid}.fire")
            for key, cands in SHARED_SOUNDS.items():
                if sb.add(f"fx.{key}", cands):
                    ok += 1
                    manifest["sounds"].append(f"fx.{key}")
            if sb.events:
                pack.add_json(f"assets/{MOD_ID}/sounds.json", sb.events, compact=False)
            print(f"  {ok} sounds converted")

    pack.add_json(f"assets/{MOD_ID}/merc_models/manifest.json", manifest, compact=False)
    pack.add_json("pack.mcmeta", {"pack": {
        "description": "Mercenaries: TF2 assets extracted from your own copy. Personal use only - do not redistribute.",
        "min_format": PACK_MIN_FORMAT, "max_format": PACK_MAX_FORMAT}}, compact=False)
    pack.add("README.txt", (
        "Generated by extract_tf2_assets.py from a local Team Fortress 2 installation.\n"
        "Team Fortress 2 assets are (c) Valve Corporation. This pack is for personal use with the\n"
        "Mercenaries Minecraft mod and must not be redistributed.\n").encode())
    pack.write()
    print(f"\nWrote {out}  ({out.stat().st_size / 1e6:.1f} MB, {len(pack.files)} files)")
    print("Enable it in Minecraft: Options > Resource Packs (put it above any other pack).")
    return 0


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
    ap.add_argument("--list-weapons", action="store_true", help="print weapon -> TF2 model mapping and exit")
    return run(ap.parse_args(argv))


if __name__ == "__main__":
    sys.exit(main())
