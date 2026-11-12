"""Generates the mod's own (original, non-Valve) resources: lang, item models, sounds.json and
simple placeholder icons (coloured tiles with a two-letter code). Re-run after editing Weapons.java."""
import json, re
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "src/main/resources/assets/mercenaries"
java = (ROOT / "src/main/java/dev/mercenaries/weapon/Weapons.java").read_text()
weapons = re.findall(r'WeaponDef\.of\("(\w+)", "([^"]+)", (\w+), (\w+), (\w+)\)', java)

CLASS_COL = {"SCOUT": (123,155,184), "SOLDIER": (140,110,74), "PYRO": (194,85,59), "DEMOMAN": (79,110,58),
             "HEAVY": (154,75,61), "ENGINEER": (199,154,62), "MEDIC": (110,140,192), "SNIPER": (107,122,59), "SPY": (74,74,92)}
# 3x5 pixel font for the tile codes (original)
FONT = {
 'A':"010101111101101",'B':"110101110101110",'C':"011100100100011",'D':"110101101101110",'E':"111100110100111",
 'F':"111100110100100",'G':"011100101101011",'H':"101101111101101",'I':"111010010010111",'J':"001001001101010",
 'K':"101101110101101",'L':"100100100100111",'M':"101111111101101",'N':"110101101101101",'O':"010101101101010",
 'P':"110101110100100",'Q':"010101101110011",'R':"110101110101101",'S':"011100010001110",'T':"111010010010010",
 'U':"101101101101111",'V':"101101101101010",'W':"101101111111101",'X':"101101010101101",'Y':"101101010010010",'Z':"111001010100111"}

def tile(path, code, col, shape):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    dark = tuple(max(0, c - 60) for c in col)
    if shape == "round":
        d.ellipse([1, 1, 14, 14], fill=col + (255,), outline=dark + (255,))
    else:
        d.rounded_rectangle([1, 1, 14, 14], 3, fill=col + (255,), outline=dark + (255,))
    for ci, ch in enumerate(code[:2].upper()):
        bits = FONT.get(ch, FONT['X'])
        for i, b in enumerate(bits):
            if b == '1':
                x, y = 4 + ci * 4 + i % 3, 5 + i // 3
                img.putpixel((x, y), (255, 255, 255, 255))
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)

def code_for(name):
    words = [w for w in re.split(r"[^A-Za-z]+", name) if w and w.lower() not in ("the", "of", "n")]
    return (words[0][0] + (words[1][0] if len(words) > 1 else words[0][1])) if words else "XX"

lang = {
 "key.categories.mercenaries.mercenaries": "Mercenaries", "key.category.mercenaries.mercenaries": "Mercenaries",
 "key.mercenaries.loadout": "Class & loadout", "key.mercenaries.reload": "Reload", "key.mercenaries.ability": "Ability (Spy cloak)",
 "entity.mercenaries.projectile": "Projectile", "entity.mercenaries.building": "Building",
}
sounds = {}
FALLBACK = {"ROCKET": "minecraft:entity.firework_rocket.launch", "GRENADE": "minecraft:entity.snowball.throw",
            "STICKY": "minecraft:entity.snowball.throw", "FLARE": "minecraft:item.firecharge.use", "SYRINGE": "minecraft:entity.arrow.shoot",
            "BOW": "minecraft:entity.arrow.shoot", "JAR": "minecraft:entity.splash_potion.throw", "FLAMETHROWER": "minecraft:item.firecharge.use",
            "SWING": "minecraft:entity.player.attack.sweep", "CONSUMABLE": "minecraft:entity.generic.eat", "MEDIGUN": "minecraft:block.beacon.ambient",
            "BUILDER": "minecraft:block.anvil.place", "SAPPER": "minecraft:block.redstone_torch.burnout"}
for wid, name, cls, slot, kind in weapons:
    lang[f"item.mercenaries.{wid}"] = name
    shape = "round" if kind in ("SWING",) else "square"
    tile(RES / f"textures/item/{wid}.png", code_for(name), CLASS_COL[cls], shape)
    (RES / f"models/item/{wid}.json").write_text(json.dumps({"parent": "minecraft:item/handheld", "textures": {"layer0": f"mercenaries:item/{wid}"}}, indent=2))
    (RES / f"items/{wid}.json").write_text(json.dumps({"model": {"type": "minecraft:model", "model": f"mercenaries:item/{wid}"}}, indent=2))
    sounds[f"weapon.{wid}.fire"] = {"sounds": [{"name": FALLBACK.get(kind, "minecraft:entity.generic.explode"), "type": "event", "volume": 0.6}]}

tile(RES / "textures/item/placeholder.png", "ME", (128, 128, 128), "square")

FX = {"explode": "minecraft:entity.generic.explode", "sticky_detonate": "minecraft:entity.generic.explode",
      "minigun_spin": "minecraft:block.beehive.work", "minigun_windup": "minecraft:block.piston.extend",
      "flame_loop": "minecraft:block.fire.ambient", "airblast": "minecraft:entity.breeze.wind_burst",
      "medigun_heal": "minecraft:block.beacon.ambient", "uber": "minecraft:block.beacon.power_select",
      "crit": "minecraft:entity.player.attack.crit", "backstab": "minecraft:entity.player.attack.strong",
      "headshot": "minecraft:entity.arrow.hit_player", "cloak": "minecraft:entity.illusioner.mirror_move",
      "uncloak": "minecraft:entity.illusioner.mirror_move", "melee_hit": "minecraft:entity.player.attack.strong",
      "melee_world": "minecraft:block.stone.hit", "sentry_shoot": "minecraft:block.dispenser.launch",
      "sentry_scan": "minecraft:block.note_block.hat", "sentry_spot": "minecraft:block.note_block.bell",
      "build": "minecraft:block.anvil.use", "reload": "minecraft:item.crossbow.loading_middle",
      "sniper_charge": "minecraft:item.spyglass.use", "jarate_break": "minecraft:entity.splash_potion.break",
      "eat": "minecraft:entity.generic.eat", "drink": "minecraft:entity.generic.drink"}
for k, v in FX.items():
    sounds[f"fx.{k}"] = {"sounds": [{"name": v, "type": "event", "volume": 0.8}]}
(RES / "sounds.json").write_text(json.dumps(sounds, indent=2))
(RES / "lang/en_us.json").write_text(json.dumps(lang, indent=2, ensure_ascii=False))

# mod icon (original): team-split badge with an "M"
icon = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
d = ImageDraw.Draw(icon)
d.rounded_rectangle([2, 2, 61, 61], 12, fill=(184, 56, 59, 255))
d.polygon([(61, 2), (61, 61), (2, 61)], fill=(88, 133, 162, 255))
d.rounded_rectangle([2, 2, 61, 61], 12, outline=(40, 30, 25, 255), width=3)
for i, b in enumerate(FONT['M']):
    if b == '1':
        x, y = 14 + (i % 3) * 12, 8 + (i // 3) * 10
        d.rectangle([x, y, x + 11, y + 9], fill=(245, 235, 210, 255))
icon.save(RES / "icon.png")
print(f"{len(weapons)} weapons, {len(sounds)} sounds")
