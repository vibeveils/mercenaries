"""
Reader for Source engine studio models (MDL v44-49 + VVD + VTX + ANI), written from the
struct layouts documented in the Source SDK 2013 headers (public/studio.h, optimize.h,
compressed_vector.h). Supports skinned meshes (3 weights/vertex), skeletons, sequences,
pose parameters, $includemodel files, animation sections and external .ani animblocks.

No Valve code is included; this is an independent implementation of the file format.
"""
from __future__ import annotations

import math
import struct
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Tuple

import numpy as np

# animation flags (mstudioanim_t.flags)
ANIM_RAWPOS, ANIM_RAWROT, ANIM_ANIMPOS, ANIM_ANIMROT, ANIM_DELTA, ANIM_RAWROT2 = 0x01, 0x02, 0x04, 0x08, 0x10, 0x20
STUDIO_DELTA = 0x0004  # animdesc / seqdesc flag
STUDIO_LOOPING = 0x0001

Reader = Callable[[str], Optional[bytes]]


def _cstr(buf: bytes, off: int) -> str:
    if off <= 0 or off >= len(buf):
        return ""
    end = buf.find(b"\0", off)
    return buf[off:end if end >= 0 else len(buf)].decode("utf-8", errors="replace")


@dataclass
class Bone:
    name: str
    parent: int
    pos: np.ndarray        # default local position
    quat: np.ndarray       # default local rotation (x, y, z, w)
    rot: np.ndarray        # default local rotation as RadianEuler
    posscale: np.ndarray
    rotscale: np.ndarray
    p2b: np.ndarray        # 3x4 poseToBone (inverse bind matrix)
    flags: int

    @property
    def bind_world(self) -> np.ndarray:
        m = np.eye(4)
        m[:3, :] = self.p2b
        return np.linalg.inv(m)

    @property
    def world_pos(self) -> np.ndarray:
        return self.bind_world[:3, 3]


@dataclass
class Mesh:
    material: int
    positions: np.ndarray  # (n,3)
    normals: np.ndarray    # (n,3)
    uvs: np.ndarray        # (n,2)
    weights: np.ndarray    # (n,3) float, zero-padded
    bones: np.ndarray      # (n,3) int, model bone indices
    indices: np.ndarray    # (m,) triangle list

    @property
    def dominant(self) -> np.ndarray:
        return self.bones[np.arange(len(self.bones)), np.argmax(self.weights, axis=1)]


@dataclass
class PoseParam:
    name: str
    start: float
    end: float


@dataclass
class SeqDesc:
    label: str
    flags: int
    groupsize: Tuple[int, int]
    paramindex: Tuple[int, int]
    paramstart: Tuple[float, float]
    paramend: Tuple[float, float]
    blends: List[int]  # animdesc indices, groupsize[0]*groupsize[1]

    @property
    def looping(self) -> bool:
        return bool(self.flags & STUDIO_LOOPING)


@dataclass
class AnimDesc:
    offset: int
    name: str
    fps: float
    flags: int
    numframes: int
    animblock: int
    animindex: int
    sectionindex: int
    sectionframes: int


@dataclass
class Mdl:
    path: str
    data: bytes
    version: int
    bones: List[Bone]
    textures: List[str]
    cd_textures: List[str]
    skins: List[List[int]]
    meshes: List[Mesh] = field(default_factory=list)
    anims: List[AnimDesc] = field(default_factory=list)
    seqs: List[SeqDesc] = field(default_factory=list)
    poseparams: List[PoseParam] = field(default_factory=list)
    includes: List[str] = field(default_factory=list)
    animblocks: List[Tuple[int, int]] = field(default_factory=list)
    animblock_name: str = ""
    ani: Optional[bytes] = None

    def bone_index(self, name: str) -> int:
        nl = name.lower()
        for i, b in enumerate(self.bones):
            if b.name.lower() == nl:
                return i
        return -1


# ----------------------------------------------------------------------------- header parsing

def parse_header(path: str, mdl: bytes) -> Mdl:
    ident, version = struct.unpack_from("<4si", mdl, 0)
    if ident != b"IDST":
        raise ValueError(f"{path}: bad MDL")
    numbones, boneindex = struct.unpack_from("<ii", mdl, 156)
    numlocalanim, localanimindex, numlocalseq, localseqindex = struct.unpack_from("<iiii", mdl, 180)
    numtex, texidx, numcd, cdidx, nskinref, nfam, skinidx = struct.unpack_from("<7i", mdl, 204)
    numposeparam, poseparamindex = struct.unpack_from("<ii", mdl, 300)
    numincludes, includeindex = struct.unpack_from("<ii", mdl, 336)
    animblockname_off, numanimblocks, animblockindex = struct.unpack_from("<iii", mdl, 348)

    bones = []
    for i in range(numbones):
        o = boneindex + i * 216
        nameoff, parent = struct.unpack_from("<ii", mdl, o)
        pos = np.array(struct.unpack_from("<3f", mdl, o + 32), np.float64)
        quat = np.array(struct.unpack_from("<4f", mdl, o + 44), np.float64)
        rot = np.array(struct.unpack_from("<3f", mdl, o + 60), np.float64)
        posscale = np.array(struct.unpack_from("<3f", mdl, o + 72), np.float64)
        rotscale = np.array(struct.unpack_from("<3f", mdl, o + 84), np.float64)
        p2b = np.array(struct.unpack_from("<12f", mdl, o + 96), np.float64).reshape(3, 4)
        flags = struct.unpack_from("<i", mdl, o + 160)[0]
        bones.append(Bone(_cstr(mdl, o + nameoff), parent, pos, quat, rot, posscale, rotscale, p2b, flags))

    textures = []
    for i in range(numtex):
        o = texidx + i * 64
        textures.append(_cstr(mdl, o + struct.unpack_from("<i", mdl, o)[0]))
    cds = [_cstr(mdl, struct.unpack_from("<i", mdl, cdidx + 4 * i)[0]) for i in range(numcd)]
    skins = []
    for f in range(max(1, nfam)):
        skins.append(list(struct.unpack_from(f"<{nskinref}h", mdl, skinidx + f * nskinref * 2)) if nskinref else [])

    m = Mdl(path, mdl, version, bones, textures, cds, skins)

    for i in range(numlocalanim):
        o = localanimindex + i * 100
        _base, nameoff, fps, flags, numframes = struct.unpack_from("<iifii", mdl, o)
        animblock, animindex = struct.unpack_from("<ii", mdl, o + 52)
        sectionindex, sectionframes = struct.unpack_from("<ii", mdl, o + 80)
        m.anims.append(AnimDesc(o, _cstr(mdl, o + nameoff), fps, flags, numframes, animblock, animindex, sectionindex, sectionframes))

    for i in range(numlocalseq):
        o = localseqindex + i * 212
        _base, labeloff, _act, flags = struct.unpack_from("<iiii", mdl, o)
        numblends, animindexindex = struct.unpack_from("<ii", mdl, o + 56)
        gs = struct.unpack_from("<2i", mdl, o + 68)
        pi = struct.unpack_from("<2i", mdl, o + 76)
        ps = struct.unpack_from("<2f", mdl, o + 84)
        pe = struct.unpack_from("<2f", mdl, o + 92)
        n = max(1, gs[0]) * max(1, gs[1])
        blends = list(struct.unpack_from(f"<{n}h", mdl, o + animindexindex))
        m.seqs.append(SeqDesc(_cstr(mdl, o + labeloff), flags, gs, pi, ps, pe, blends))

    for i in range(numposeparam):
        o = poseparamindex + i * 20
        nameoff, _flags, start, end, _loop = struct.unpack_from("<iifff", mdl, o)
        m.poseparams.append(PoseParam(_cstr(mdl, o + nameoff), start, end))

    for i in range(numincludes):
        o = includeindex + i * 8
        _label, nameoff = struct.unpack_from("<ii", mdl, o)
        name = _cstr(mdl, o + nameoff)
        if name:
            m.includes.append(name)

    if numanimblocks > 0 and 0 < animblockindex < len(mdl):
        m.animblock_name = _cstr(mdl, animblockname_off)
        for i in range(numanimblocks):
            m.animblocks.append(struct.unpack_from("<ii", mdl, animblockindex + i * 8))
    return m


# ----------------------------------------------------------------------------- meshes

def read_vvd(data: bytes) -> np.ndarray:
    ident, _ver, _chk, _nlods = struct.unpack_from("<4sIii", data, 0)
    if ident != b"IDSV":
        raise ValueError("bad VVD")
    lod_verts = struct.unpack_from("<8i", data, 16)
    nfix, fix_start, vert_start, _tan = struct.unpack_from("<iiii", data, 48)
    dt = np.dtype([("w", "<f4", 3), ("b", "u1", 3), ("nb", "u1"), ("pos", "<f4", 3),
                   ("nrm", "<f4", 3), ("uv", "<f4", 2)])
    if nfix == 0:
        return np.frombuffer(data, dt, lod_verts[0], vert_start)
    allv = np.frombuffer(data, dt, (len(data) - vert_start) // 48, vert_start)
    chunks = []
    for i in range(nfix):
        lod, src, cnt = struct.unpack_from("<iii", data, fix_start + 12 * i)
        if lod >= 0:
            chunks.append(allv[src:src + cnt])
    return np.concatenate(chunks)


def read_meshes(m: Mdl, vvd: bytes, vtx: bytes):
    mdl = m.data
    nbp, bpidx = struct.unpack_from("<ii", mdl, 232)
    verts = read_vvd(vvd)
    v_bpoff = struct.unpack_from("<i", vtx, 32)[0]
    sv_dt = np.dtype([("bw", "u1", 3), ("nb", "u1"), ("orig", "<u2"), ("bid", "i1", 3)])
    for bp in range(nbp):
        bo = bpidx + bp * 16
        _n, nmodels, _base, modelindex = struct.unpack_from("<iiii", mdl, bo)
        if nmodels == 0:
            continue
        vbo = v_bpoff + bp * 8
        _vnm, vmo = struct.unpack_from("<ii", vtx, vbo)
        mo = bo + modelindex  # bodygroup 0
        nmeshes, meshindex, nverts, vertexindex = struct.unpack_from("<iiii", mdl, mo + 72)
        if nmeshes == 0 or nverts == 0:
            continue
        model_vstart = vertexindex // 48
        vmodel = vbo + vmo
        _nl, lodoff = struct.unpack_from("<ii", vtx, vmodel)
        vlod = vmodel + lodoff
        _vn, vmeshoff = struct.unpack_from("<ii", vtx, vlod)
        for me in range(nmeshes):
            mso = mo + meshindex + me * 116
            material, _mi, m_nverts, m_voff = struct.unpack_from("<iiii", mdl, mso)
            vmesh = vlod + vmeshoff + me * 9
            nsg, sgoff = struct.unpack_from("<ii", vtx, vmesh)
            out: List[int] = []
            for sg in range(nsg):
                vsg = vmesh + sgoff + sg * 25
                sg_nv, sg_vo, sg_ni, sg_io, sg_ns, sg_so = struct.unpack_from("<iiiiii", vtx, vsg)
                orig = np.frombuffer(vtx, sv_dt, sg_nv, vsg + sg_vo)["orig"].astype(np.int64)
                sidx = np.frombuffer(vtx, "<u2", sg_ni, vsg + sg_io)
                for st in range(sg_ns):
                    so = vsg + sg_so + st * 27
                    st_ni, st_io = struct.unpack_from("<ii", vtx, so)
                    seg = sidx[st_io:st_io + st_ni]
                    if vtx[so + 18] & 2:  # tristrip
                        tris = []
                        for k in range(len(seg) - 2):
                            a, b, c = seg[k], seg[k + 1], seg[k + 2]
                            if a != b and b != c and a != c:
                                tris += [a, c, b] if k % 2 else [a, b, c]
                        seg = np.array(tris, np.int64)
                    out.extend(orig[np.asarray(seg, np.int64)].tolist())
            if not out:
                continue
            g = model_vstart + m_voff
            mv = verts[g:g + m_nverts]
            nb = mv["nb"].astype(np.int64)
            w = mv["w"].astype(np.float64).copy()
            b = mv["b"].astype(np.int64).copy()
            for k in range(3):
                w[nb <= k, k] = 0
                b[nb <= k, k] = 0
            s = w.sum(1, keepdims=True)
            s[s == 0] = 1
            w /= s
            m.meshes.append(Mesh(material, mv["pos"].astype(np.float64), mv["nrm"].astype(np.float64),
                                 mv["uv"].astype(np.float64), w, b, np.array(out, np.int32)))


def load(read: Reader, path: str, with_meshes: bool = True) -> Mdl:
    data = read(path)
    if data is None:
        raise FileNotFoundError(path)
    m = parse_header(path, data)
    if with_meshes:
        base = path[:-4]
        vvd = read(base + ".vvd")
        vtx = read(base + ".dx90.vtx") or read(base + ".dx80.vtx") or read(base + ".sw.vtx")
        if vvd is None or vtx is None:
            raise FileNotFoundError(f"{path}: missing .vvd/.vtx")
        read_meshes(m, vvd, vtx)
    if m.animblocks and m.animblock_name:
        m.ani = read(m.animblock_name) or read(path[:-4] + ".ani")
    return m


def load_with_includes(read: Reader, path: str) -> List[Mdl]:
    """Main model (with meshes) followed by every $includemodel file (animations only)."""
    main = load(read, path, True)
    out, seen, queue = [main], {path.lower()}, list(main.includes)
    while queue:
        inc = queue.pop(0).replace("\\", "/")
        cands = [inc] if inc.lower().startswith("models/") else [inc, "models/" + inc]
        for c in cands:
            if c.lower() in seen:
                break
            try:
                m = load(read, c, False)
            except Exception:
                continue
            seen.add(c.lower())
            out.append(m)
            queue.extend(m.includes)
            break
    return out


# ----------------------------------------------------------------------------- animation decoding

def euler_to_quat(e: np.ndarray) -> np.ndarray:
    """RadianEuler (x=roll, y=pitch, z=yaw) -> quaternion (x, y, z, w), Source convention."""
    sr, cr = math.sin(e[0] * 0.5), math.cos(e[0] * 0.5)
    sp, cp = math.sin(e[1] * 0.5), math.cos(e[1] * 0.5)
    sy, cy = math.sin(e[2] * 0.5), math.cos(e[2] * 0.5)
    return np.array([sr * cp * cy - cr * sp * sy,
                     cr * sp * cy + sr * cp * sy,
                     cr * cp * sy - sr * sp * cy,
                     cr * cp * cy + sr * sp * sy])


def quat48(buf: bytes, off: int) -> np.ndarray:
    x, y, zw = struct.unpack_from("<HHH", buf, off)
    q = np.array([(x - 32768) / 32768.0, (y - 32768) / 32768.0, ((zw & 0x7FFF) - 16384) / 16384.0, 0.0])
    q[3] = math.sqrt(max(0.0, 1 - q[0] ** 2 - q[1] ** 2 - q[2] ** 2)) * (-1 if zw & 0x8000 else 1)
    return q


def quat64(buf: bytes, off: int) -> np.ndarray:
    v = struct.unpack_from("<Q", buf, off)[0]
    x, y, z = v & 0x1FFFFF, (v >> 21) & 0x1FFFFF, (v >> 42) & 0x1FFFFF
    q = np.array([(x - 1048576) / 1048576.5, (y - 1048576) / 1048576.5, (z - 1048576) / 1048576.5, 0.0])
    q[3] = math.sqrt(max(0.0, 1 - q[0] ** 2 - q[1] ** 2 - q[2] ** 2)) * (-1 if v >> 63 else 1)
    return q


def vec48(buf: bytes, off: int) -> np.ndarray:
    return np.frombuffer(buf, "<f2", 3, off).astype(np.float64)


def anim_value(buf: bytes, off: int, frame: int) -> float:
    """Run-length decoded value of one channel at an integer frame."""
    k = frame
    while True:
        valid, total = buf[off], buf[off + 1]
        if total == 0:
            return 0.0
        if total > k:
            break
        k -= total
        off += (valid + 1) * 2
    if valid > k:
        return float(struct.unpack_from("<h", buf, off + (k + 1) * 2)[0])
    return float(struct.unpack_from("<h", buf, off + valid * 2)[0])


def _anim_location(m: Mdl, a: AnimDesc, frame: int) -> Tuple[Optional[bytes], int, int]:
    block, index = a.animblock, a.animindex
    if a.sectionframes:
        if a.numframes > a.sectionframes and frame == a.numframes - 1:
            frame, section = 0, a.numframes // a.sectionframes + 1
        else:
            section = frame // a.sectionframes
            frame -= section * a.sectionframes
        block, index = struct.unpack_from("<ii", m.data, a.offset + a.sectionindex + section * 8)
    if block == -1:
        return None, 0, frame
    if block == 0:
        return m.data, a.offset + index, frame
    if m.ani is None or block >= len(m.animblocks):
        return None, 0, frame
    return m.ani, m.animblocks[block][0] + index, frame


def decode_frame(m: Mdl, a: AnimDesc, frame: int) -> Tuple[np.ndarray, np.ndarray]:
    """Local (pos, quat) for every bone of ``m`` at ``frame`` (bones without tracks use defaults)."""
    pos = np.array([b.pos for b in m.bones])
    quat = np.array([b.quat for b in m.bones])
    buf, off, f = _anim_location(m, a, frame)
    if buf is None:
        return pos, quat
    guard = 0
    while 0 <= off < len(buf) - 4 and guard < 4096:
        guard += 1
        bone, flags, nxt = struct.unpack_from("<BBh", buf, off)
        data = off + 4
        if bone < len(m.bones):
            b = m.bones[bone]
            delta = bool(flags & ANIM_DELTA)
            if flags & ANIM_RAWROT:
                quat[bone] = quat48(buf, data)
            elif flags & ANIM_RAWROT2:
                quat[bone] = quat64(buf, data)
            elif flags & ANIM_ANIMROT:
                offs = struct.unpack_from("<3h", buf, data)
                e = np.array([anim_value(buf, data + offs[i], f) * b.rotscale[i] if offs[i] > 0 else 0.0 for i in range(3)])
                if not delta:
                    e = e + b.rot
                quat[bone] = euler_to_quat(e)
            elif delta:
                quat[bone] = (0, 0, 0, 1)
            if flags & ANIM_RAWPOS:
                p_off = data + (6 if flags & ANIM_RAWROT else 0) + (8 if flags & ANIM_RAWROT2 else 0)
                pos[bone] = vec48(buf, p_off)
            elif flags & ANIM_ANIMPOS:
                vp = data + (6 if flags & ANIM_ANIMROT else 0)
                offs = struct.unpack_from("<3h", buf, vp)
                p = np.array([anim_value(buf, vp + offs[i], f) * b.posscale[i] if offs[i] > 0 else 0.0 for i in range(3)])
                pos[bone] = p if delta else p + b.pos
            elif delta:
                pos[bone] = (0, 0, 0)
        if nxt == 0:
            break
        off += nxt
    n = np.linalg.norm(quat, axis=1, keepdims=True)
    n[n == 0] = 1
    return pos, quat / n


def quat_to_mat(q: np.ndarray) -> np.ndarray:
    x, y, z, w = q
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ])


def world_matrices(bones: List[Bone], pos: np.ndarray, quat: np.ndarray) -> np.ndarray:
    out = np.zeros((len(bones), 4, 4))
    for i, b in enumerate(bones):
        local = np.eye(4)
        local[:3, :3] = quat_to_mat(quat[i])
        local[:3, 3] = pos[i]
        out[i] = out[b.parent] @ local if 0 <= b.parent < i else local
    return out


def pick_blend(m: Mdl, s: SeqDesc, targets: Dict[str, float]) -> int:
    """Animdesc index for the blend cell closest to the requested pose parameter values."""
    idx = [0, 0]
    for axis in range(2):
        n = s.groupsize[axis]
        if n <= 1:
            continue
        pi = s.paramindex[axis]
        name = m.poseparams[pi].name.lower() if 0 <= pi < len(m.poseparams) else ""
        start, end = s.paramstart[axis], s.paramend[axis]
        target = targets.get(name, (start + end) / 2)
        values = [start + (end - start) * i / (n - 1) for i in range(n)]
        idx[axis] = int(np.argmin([abs(v - target) for v in values]))
    cell = idx[1] * max(1, s.groupsize[0]) + idx[0]
    return s.blends[min(cell, len(s.blends) - 1)]
