#!/usr/bin/env python3
"""Self-test for extract_tf2_assets.py using synthetic (non-Valve) files.

Builds a fake "Team Fortress 2" folder containing a VPK v2 archive with a tiny
procedurally generated model, material, texture, item schema and sound, then runs
the extractor end-to-end and checks the generated resource pack.
Also cross-checks the DXT decoder against Pillow's independent DDS decoder.

Run:  python test_extractor.py
"""
import io
import json
import math
import re
import struct
import sys
import tempfile
import wave
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
import extract_tf2_assets as ex  # noqa: E402

# ----------------------------------------------------------------- DXT encoders (tiny, for tests)


def enc565(r, g, b):
    return ((r * 31 // 255) << 11) | ((g * 63 // 255) << 5) | (b * 31 // 255)


def make_dxt1(w, h, seed=1):
    rng = np.random.default_rng(seed)
    out = bytearray()
    for _ in range(((w + 3) // 4) * ((h + 3) // 4)):
        c0 = int(rng.integers(0x8000, 0xFFFF))
        c1 = int(rng.integers(0, 0x7FFF))
        out += struct.pack("<HHI", c0, c1, int(rng.integers(0, 2**32 - 1)))
    return bytes(out)


def make_dxt5(w, h, seed=2):
    rng = np.random.default_rng(seed)
    out = bytearray()
    for _ in range(((w + 3) // 4) * ((h + 3) // 4)):
        a0, a1 = sorted(rng.integers(0, 255, 2).tolist(), reverse=bool(rng.integers(0, 2)))
        out += bytes([a0, a1]) + rng.integers(0, 255, 6, dtype=np.uint8).tobytes()
        c0 = int(rng.integers(0x8000, 0xFFFF)); c1 = int(rng.integers(0, 0x7FFF))
        out += struct.pack("<HHI", c0, c1, int(rng.integers(0, 2**32 - 1)))
    return bytes(out)


def dds_wrap(data, w, h, fourcc):
    hdr = struct.pack("<4sIIIIIII44sIIII20sIIII", b"DDS ", 124, 0x1007 | 0x80000, h, w, len(data), 0, 1,
                      b"\0" * 44, 32, 4, 0, 0, b"\0" * 20, 0x1000, 0, 0, 0)
    # patch fourcc into pixel format (offset 84)
    hdr = bytearray(hdr)
    hdr[84:88] = fourcc
    return bytes(hdr) + data


def check_dxt_against_pillow():
    for fmt, maker, cc in ((13, make_dxt1, b"DXT1"), (15, make_dxt5, b"DXT5")):
        w, h = 32, 16
        raw = maker(w, h)
        mine = ex.decode_dxt(raw, w, h, fmt).astype(int)
        ref = np.array(Image.open(io.BytesIO(dds_wrap(raw, w, h, cc))).convert("RGBA")).astype(int)
        diff = np.abs(mine - ref).max()
        assert diff <= 8, f"{cc} mismatch vs Pillow: max diff {diff}"
        print(f"  DXT {cc.decode()} decode matches Pillow (max channel diff {diff})")

# ----------------------------------------------------------------- VTF writer


def make_vtf(w, h, fmt, mip_datas, minor=4):
    """mip_datas: list largest->smallest."""
    header_size = 80
    hdr = bytearray(header_size)
    struct.pack_into("<4sIII", hdr, 0, b"VTF\0", 7, minor, header_size)
    struct.pack_into("<HHIHH", hdr, 16, w, h, 0, 1, 0)
    struct.pack_into("<iBiBB", hdr, 52, fmt, len(mip_datas), -1, 0, 0)
    struct.pack_into("<H", hdr, 63, 1)
    body = b"".join(reversed(mip_datas))
    return bytes(hdr) + body


def make_vtf_v73(w, h, fmt, data):
    hdr = bytearray(96)
    struct.pack_into("<4sIII", hdr, 0, b"VTF\0", 7, 3, 96)
    struct.pack_into("<HHIHH", hdr, 16, w, h, 0, 1, 0)
    struct.pack_into("<iBiBB", hdr, 52, fmt, 1, -1, 0, 0)
    struct.pack_into("<H", hdr, 63, 1)
    struct.pack_into("<I", hdr, 68, 1)
    struct.pack_into("<3sBI", hdr, 80, b"\x30\x00\x00", 0, 96)
    return bytes(hdr) + data

# ----------------------------------------------------------------- MDL/VVD/VTX writer


def build_model(bone_defs, tris_per_bone, material_name, cd_dir):
    """bone_defs: list of (name, parent, world_pos). Returns (mdl, vvd, vtx)."""
    nb = len(bone_defs)
    verts = []
    tris = []
    for bi, (_n, _p, pos) in enumerate(bone_defs):
        for t in range(tris_per_bone):
            base = len(verts)
            for k in range(3):
                off = np.array(pos) + np.array([t * 0.5, k * 1.0, (k == 2) * 1.0])
                verts.append((bi, off, (k * 0.5, t * 0.1)))
            tris += [base, base + 1, base + 2]
    nv = len(verts)

    # VVD
    vvd = bytearray(64)
    struct.pack_into("<4sIii", vvd, 0, b"IDSV", 4, 0, 1)
    struct.pack_into("<8i", vvd, 16, nv, 0, 0, 0, 0, 0, 0, 0)
    struct.pack_into("<iiii", vvd, 48, 0, 64, 64, 0)
    for bi, p, uv in verts:
        vvd += struct.pack("<3f3bB3f3f2f", 1.0, 0, 0, bi, 0, 0, 1, *p, 0, 0, 1, *uv)

    # VTX: header(36) bodypart(8) model(8) lod(12) mesh(9) stripgroup(25) verts(9*n) idx(2*m) strip(27)
    vtx = bytearray()
    vtx += struct.pack("<iiHHiiiiii", 7, 24, 53, 9, 3, 0, 1, 0, 1, 36)
    bp_off = len(vtx); vtx += struct.pack("<ii", 1, 8)
    m_off = len(vtx); vtx += struct.pack("<ii", 1, 8)
    lod_off = len(vtx); vtx += struct.pack("<iif", 1, 12, 0.0)
    mesh_off = len(vtx); vtx += struct.pack("<iiB", 1, 9, 0)
    sg_off = len(vtx)
    vert_rel = 25
    idx_rel = vert_rel + 9 * nv
    strip_rel = idx_rel + 2 * len(tris)
    vtx += struct.pack("<iiiiiiB", nv, vert_rel, len(tris), idx_rel, 1, strip_rel, 0)
    for i in range(nv):
        vtx += struct.pack("<3BBH3b", 0, 0, 0, 1, i, 0, 0, 0)
    for t in tris:
        vtx += struct.pack("<H", t)
    vtx += struct.pack("<iiiihBii", len(tris), 0, nv, 0, nb, 1, 0, 0)

    # MDL
    strings = bytearray()
    str_pos = {}

    def sref(s):
        if s not in str_pos:
            str_pos[s] = len(strings); strings.extend(s.encode() + b"\0")
        return str_pos[s]

    mdl = bytearray(408)
    struct.pack_into("<4si", mdl, 0, b"IDST", 48)
    mdl[12:12 + 9] = b"test.mdl\0"
    bone_index = len(mdl)
    bone_name_fix = []
    for i, (name, parent, pos) in enumerate(bone_defs):
        b = bytearray(216)
        struct.pack_into("<ii", b, 0, 0, parent)
        struct.pack_into("<3f", b, 32, *pos) if parent < 0 else struct.pack_into("<3f", b, 32, *(np.array(pos) - np.array(bone_defs[parent][2])))
        struct.pack_into("<4f", b, 44, 0, 0, 0, 1)
        struct.pack_into("<3f", b, 72, 1 / 32.0, 1 / 32.0, 1 / 32.0)
        struct.pack_into("<3f", b, 84, 1 / 4096.0, 1 / 4096.0, 1 / 4096.0)
        # poseToBone = [I | -pos]
        m = [1, 0, 0, -pos[0], 0, 1, 0, -pos[1], 0, 0, 1, -pos[2]]
        struct.pack_into("<12f", b, 96, *m)
        bone_name_fix.append((len(mdl), name))
        mdl += b
    tex_index = len(mdl)
    tex_fix = len(mdl); mdl += bytearray(64)
    cd_index = len(mdl); cd_fix = len(mdl); mdl += bytearray(4)
    skin_index = len(mdl); mdl += struct.pack("<hh", 0, 1)  # 2 families x 1 ref
    bp_index = len(mdl); bp_fix = len(mdl); mdl += bytearray(16)
    model_index = len(mdl); mdl += bytearray(148)
    mesh_index = len(mdl); mdl += bytearray(116)
    str_base = len(mdl)

    for off, name in bone_name_fix:
        struct.pack_into("<i", mdl, off, str_base + sref(name) - off)
    struct.pack_into("<i", mdl, tex_fix, str_base + sref(material_name) - tex_fix)
    struct.pack_into("<i", mdl, cd_fix, str_base + sref(cd_dir))
    struct.pack_into("<iiii", mdl, bp_fix, 0, 1, 0, model_index - bp_fix)
    struct.pack_into("<iiii", mdl, model_index + 72, 1, mesh_index - model_index, nv, 0)
    struct.pack_into("<iiii", mdl, mesh_index, 0, 0, nv, 0)
    struct.pack_into("<ii", mdl, 156, nb, bone_index)
    # two textures entries? keep one texture but two skin families reference tex 0 and tex 1
    struct.pack_into("<9i", mdl, 204, 2, tex_index, 1, cd_index, 1, 2, skin_index, 1, bp_index)
    mdl += strings
    # second texture entry (blue): append and patch numtextures=2 -> need contiguous array; rebuild quickly
    return bytes(mdl), bytes(vvd), bytes(vtx)


def build_model_two_tex(bones, name_red, name_blue, cd):
    mdl, vvd, vtx = build_model(bones, 2, name_red, cd)
    mdl = bytearray(mdl)
    # append a 2nd texture struct + its name, and relocate texture array to the end
    numtex, texidx = struct.unpack_from("<ii", mdl, 204)
    first = bytes(mdl[texidx:texidx + 64])
    first_name_abs = texidx + struct.unpack_from("<i", first, 0)[0]
    new_idx = len(mdl)
    mdl += bytearray(128)
    mdl[new_idx:new_idx + 64] = first
    struct.pack_into("<i", mdl, new_idx, first_name_abs - new_idx)
    name_abs = len(mdl); mdl += name_blue.encode() + b"\0"
    struct.pack_into("<i", mdl, new_idx + 64, name_abs - (new_idx + 64))
    struct.pack_into("<ii", mdl, 204, 2, new_idx)
    return bytes(mdl), vvd, vtx

# ----------------------------------------------------------------- animation writer (test data only)


def rle(values):
    """Encode int16 values as one (valid, total) run, the layout the reader expects."""
    out = struct.pack("<BB", len(values), len(values))
    for v in values:
        out += struct.pack("<h", v)
    return out


def add_animation(mdl: bytes, nbones: int) -> bytes:
    """Appends one animdesc + one sequence 'run_PRIMARY' (5 frames) to a synthetic MDL."""
    mdl = bytearray(mdl)
    desc_off = len(mdl)
    mdl += bytearray(100)
    anim_off = len(mdl)
    # bone 0: raw rot (identity) + raw pos (1,2,3)
    q48 = struct.pack("<HHH", 32768, 32768, 16384)
    v48 = np.array([1.0, 2.0, 3.0], "<f2").tobytes()
    rec0 = struct.pack("<BBh", 0, 0x02 | 0x01, 0) + q48 + v48
    # bone 3: ANIMROT with yaw channel RLE: [0, 512, 1024] then repeats last value
    vp = struct.pack("<3h", 0, 0, 6)  # offsets relative to the valueptr struct; x,y unused
    rec3 = struct.pack("<BBh", 3, 0x08, 0) + vp + struct.pack("<BB", 3, 5) + struct.pack("<3h", 0, 512, 1024)
    rec0 = bytearray(rec0)
    struct.pack_into("<h", rec0, 2, len(rec0))
    mdl += rec0 + rec3
    name_off = len(mdl)
    mdl += b"a_run\0"
    struct.pack_into("<iifii", mdl, desc_off, -desc_off, name_off - desc_off, 30.0, 1, 5)
    struct.pack_into("<ii", mdl, desc_off + 52, 0, anim_off - desc_off)
    seq_off = len(mdl)
    mdl += bytearray(212)
    blend_off = len(mdl)
    mdl += struct.pack("<h", 0)
    label_off = len(mdl)
    mdl += b"run_PRIMARY\0"
    struct.pack_into("<iiii", mdl, seq_off, -seq_off, label_off - seq_off, 0, 1)
    struct.pack_into("<ii", mdl, seq_off + 56, 1, blend_off - seq_off)
    struct.pack_into("<2i", mdl, seq_off + 68, 1, 1)
    struct.pack_into("<iiii", mdl, 180, 1, desc_off, 1, seq_off)
    return bytes(mdl)


# ----------------------------------------------------------------- VPK writer


def write_vpk(path_dir: Path, files: dict):
    by_ext = {}
    for p, data in files.items():
        d, _, fn = p.rpartition("/")
        name, _, ext = fn.rpartition(".")
        by_ext.setdefault(ext, {}).setdefault(d or " ", []).append((name, data))
    tree = bytearray()
    blob = bytearray()
    for ext, dirs in by_ext.items():
        tree += ext.encode() + b"\0"
        for d, items in dirs.items():
            tree += d.encode() + b"\0"
            for name, data in items:
                tree += name.encode() + b"\0"
                pre = data[:4]
                rest = data[4:]
                tree += struct.pack("<IHHIIH", 0, len(pre), 0x7FFF, len(blob), len(rest), 0xFFFF) + pre
                blob += rest
            tree += b"\0"
        tree += b"\0"
    tree += b"\0"
    hdr = struct.pack("<IIIIIII", 0x55AA1234, 2, len(tree), len(blob), 0, 0, 0)
    path_dir.write_bytes(hdr + tree + blob)


def wav_bytes():
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(22050)
        t = np.arange(2205)
        s = (np.sin(t * 0.2) * 8000).astype("<i2")
        w.writeframes(np.stack([s, s], 1).tobytes())
    return buf.getvalue()


def main():
    print("DXT decoder cross-check:")
    check_dxt_against_pillow()

    print("VTF formats:")
    bgra = np.zeros((8, 8, 4), np.uint8); bgra[..., 0] = 10; bgra[..., 1] = 20; bgra[..., 2] = 30; bgra[..., 3] = 40
    img = ex.decode_vtf(make_vtf(8, 8, 12, [bgra.tobytes(), b"\0" * 64, b"\0" * 16, b"\0" * 4]))
    assert img.getpixel((3, 3)) == (30, 20, 10, 40), img.getpixel((3, 3))
    img = ex.decode_vtf(make_vtf_v73(8, 8, 3, bytes([1, 2, 3]) * 64))
    assert img.getpixel((0, 0)) == (3, 2, 1, 255)
    print("  BGRA8888 w/ mips, BGR888 v7.3 resources OK")

    with tempfile.TemporaryDirectory() as td:
        root = Path(td) / "Team Fortress 2"
        (root / "tf").mkdir(parents=True)
        (root / "tf/gameinfo.txt").write_text('"GameInfo" { game "Synthetic" }')
        bones = [("bip_pelvis", -1, (0, 0, 40)), ("bip_neck", 0, (0, 0, 64)), ("bip_head", 1, (0, 0, 70)),
                 ("bip_upperArm_R", 0, (0, -8, 60)), ("bip_hand_R", 3, (0, -30, 60)),
                 ("bip_upperArm_L", 0, (0, 8, 60)), ("bip_hand_L", 5, (0, 30, 60)),
                 ("bip_hip_R", 0, (0, -4, 38)), ("bip_foot_R", 7, (0, -4, 2)),
                 ("bip_hip_L", 0, (0, 4, 38)), ("bip_foot_L", 9, (0, 4, 2))]
        mdl, vvd, vtx = build_model_two_tex(bones, "scout_red", "scout_blue", "models/player/scout/")
        files = {
            "models/player/scout.mdl": mdl, "models/player/scout.vvd": vvd, "models/player/scout.dx90.vtx": vtx,
            "models/weapons/c_models/c_test/c_test.mdl": mdl, "models/weapons/c_models/c_test/c_test.vvd": vvd,
            "models/weapons/c_models/c_test/c_test.dx90.vtx": vtx,
            "materials/models/player/scout/scout_red.vmt": b'"VertexLitGeneric"\n{\n "$basetexture" "models/player/scout/scout_red" // c\n}',
            "materials/models/player/scout/scout_blue.vmt": b'"patch" { "include" "materials/models/player/scout/scout_red.vmt" "replace" { "$basetexture" "models/player/scout/scout_blue" } }',
            "materials/models/player/scout/scout_red.vtf": make_vtf(32, 16, 13, [make_dxt1(32, 16)]),
            "materials/models/player/scout/scout_blue.vtf": make_vtf(32, 16, 15, [make_dxt5(32, 16)]),
            "materials/backpack/weapons/c_models/c_test_large.vtf": make_vtf(8, 8, 12, [bgra.tobytes()]),
            "materials/hud/leaderboard_class_scout.vtf": make_vtf(8, 8, 12, [bgra.tobytes()]),
            "scripts/items/items_game.txt": b'''"items_game" {
  "prefabs" { "weapon_scattergun" { "item_class" "tf_weapon_scattergun" "model_player" "models/weapons/c_models/c_test/c_test.mdl" [$WIN32] } }
  "items" { "13" { "name" "TF_WEAPON_SCATTERGUN" "prefab" "weapon_scattergun" "image_inventory" "backpack/weapons/c_models/c_test" } }
}''',
            "scripts/tf_weapon_scattergun.txt": b'WeaponData { SoundData { "single_shot" "Weapon_Scatter_Gun.Single" } }',
            "scripts/game_sounds_manifest.txt": b'"game_sounds_manifest" { "precache_file" "scripts/game_sounds_weapons.txt" }',
            "scripts/game_sounds_weapons.txt": b'"Weapon_Scatter_Gun.Single" { "channel" "CHAN_WEAPON" "wave" ")weapons/scatter_gun_shoot.wav" }',
            "sound/weapons/scatter_gun_shoot.wav": wav_bytes(),
            "sound/vo/scout_painsharp01.mp3": wav_bytes(),
            "sound/vo/scout_painsevere01.mp3": wav_bytes(),
        }
        write_vpk(root / "tf/tf2_misc_dir.vpk", files)

        vfs = ex.VFS(root)
        assert vfs.read("models/player/scout.mdl") == mdl, "VPK round-trip failed"
        m = ex.studiomdl.load(vfs.read, "models/player/scout.mdl")
        assert len(m.bones) == len(bones) and m.bones[3].name == "bip_upperArm_R"
        assert np.allclose(m.bones[4].world_pos, (0, -30, 60))
        assert m.meshes[0].weights.shape[1] == 3 and np.allclose(m.meshes[0].weights.sum(1), 1)
        assert len(m.meshes) == 1 and len(m.meshes[0].indices) == len(bones) * 2 * 3
        print(f"  MDL parsed: {len(m.bones)} bones, {len(m.meshes[0].indices)//3} tris, skins={m.skins}")

        # ---- animation decoding (raw + RLE tracks)
        anim_mdl = add_animation(mdl, len(bones))
        files["models/player/scout.mdl"] = anim_mdl
        write_vpk(root / "tf/tf2_misc_dir.vpk", files)
        vfs = ex.VFS(root)
        am = ex.studiomdl.load(vfs.read, "models/player/scout.mdl")
        assert [s.label for s in am.seqs] == ["run_PRIMARY"], [s.label for s in am.seqs]
        pos, quat = ex.studiomdl.decode_frame(am, am.anims[0], 3)
        assert np.allclose(pos[0], (1.0, 2.0, 3.0), atol=1e-2), pos[0]
        assert np.allclose(quat[0], (0, 0, 0, 1), atol=1e-3), quat[0]
        # RLE yaw on bone 3: frames 0..4 = 0, 512, 1024, 1024(repeat), ... scaled by rotscale 1/4096
        _, q2 = ex.studiomdl.decode_frame(am, am.anims[0], 2)
        yaw = 2 * math.atan2(q2[3][2], q2[3][3])
        assert abs(yaw - 1024 / 4096.0) < 1e-3, yaw
        _, q3 = ex.studiomdl.decode_frame(am, am.anims[0], 4)
        assert abs(2 * math.atan2(q3[3][2], q3[3][3]) - 1024 / 4096.0) < 1e-3
        print("  animation decode (raw pos/rot, RLE euler) OK")

        # orientation from skeleton: synthetic model faces +X with left = +Y
        left, up, fwd = ex.skeleton_basis(am.bones)
        assert np.allclose(left, (0, 1, 0)) and np.allclose(up, (0, 0, 1)) and np.allclose(fwd, (1, 0, 0)), (left, up, fwd)
        # rotate the whole skeleton 90 degrees about Z: detection must follow
        rz = np.array([[0, -1, 0, 0], [1, 0, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]], float)
        for bn in am.bones:
            bw = rz @ bn.bind_world
            bn.p2b = np.linalg.inv(bw)[:3]
        left2, _, fwd2 = ex.skeleton_basis(am.bones)
        assert np.allclose(fwd2, (0, 1, 0)) and np.allclose(left2, (-1, 0, 0)), (left2, fwd2)
        print("  skeleton-based orientation OK (also for a model rotated 90 degrees)")

        out = Path(td) / "pack.zip"
        rc = ex.main(["--tf2", str(root), "--out", str(out)])
        assert rc == 0
        z = zipfile.ZipFile(out)
        names = set(z.namelist())
        need = ["pack.mcmeta", "assets/mercenaries/merc_models/player/scout.json",
                "assets/mercenaries/merc_models/weapon/scattergun.json", "assets/mercenaries/items/scattergun.json",
                "assets/mercenaries/textures/item/tf2/scattergun.png", "assets/mercenaries/merc_models/manifest.json"]
        for n in need:
            assert n in names, f"missing {n}"
        player = json.loads(z.read("assets/mercenaries/merc_models/player/scout.json"))
        assert player["kind"] == "skinned" and "run_primary" in player["clips"], player.get("clips", {}).keys()
        assert player["skins"][0][0] != player["skins"][1][0], "red/blue skins should differ"
        clip = player["clips"]["run_primary"]
        assert clip["frames"] == 5 and 3 in clip["anim"], clip["anim"]
        assert len(clip["data"]) == clip["frames"] * len(clip["anim"]) * 7
        mesh0 = player["meshes"][0]
        assert len(mesh0["bi"]) == len(mesh0["v"]) and len(mesh0["bw"]) == len(mesh0["v"])
        # to_entity maps the synthetic forward (+X) to entity -z, up (+Z) to entity -y, feet to y=1.5
        M = np.array(player["to_entity"]).reshape(4, 4)
        assert M[2, 0] < 0 and M[1, 2] < 0 and abs(M[1, 3] - (1.5 + 0 * M[0, 1])) < 0.2, M
        weapon = json.loads(z.read("assets/mercenaries/merc_models/weapon/scattergun.json"))
        assert weapon["kind"] == "weapon" and weapon["attach"]["bones"] and weapon["anim_slot"] == "PRIMARY", weapon.get("anim_slot")
        item = json.loads(z.read("assets/mercenaries/items/scattergun.json"))
        assert item["model"]["cases"][1]["model"]["type"] == "minecraft:empty"
        for tid in player["skins"][0] + player["skins"][1]:
            assert "assets/" + tid.replace(":", "/", 1) in names, tid
        if "assets/mercenaries/sounds.json" in names:
            sj = json.loads(z.read("assets/mercenaries/sounds.json"))
            assert "weapon.scattergun.fire" in sj, sorted(sj)
            assert "class.scout.pain" in sj, sorted(sj)
            assert len(sj["class.scout.pain"]["sounds"]) == 2
            print("  sound conversion OK (weapon + class voice variants)")
        rep = out.with_name(out.stem + "_report.txt").read_text()
        assert "run_PRIMARY" in rep or "run_primary" in rep
        print(f"  pack OK: {len(names)} files")

    # every weapon id registered by the Java mod must have an extractor entry
    java = (Path(__file__).resolve().parents[1] / "src/main/java/dev/mercenaries/weapon/Weapons.java").read_text()
    java_ids = set(re.findall(r'WeaponDef\.of\("(\w+)"', java))
    missing = java_ids - set(ex.WEAPONS)
    assert not missing, f"weapons missing from extractor table: {sorted(missing)}"
    assert java_ids <= set(ex.WEAPON_CLASS), sorted(java_ids - set(ex.WEAPON_CLASS))
    print(f"  all {len(java_ids)} mod weapons are mapped in the extractor")
    print("ALL TESTS PASSED")


if __name__ == "__main__":
    main()
