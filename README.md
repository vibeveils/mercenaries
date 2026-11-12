# Mercenaries: TF2 classes for Minecraft 26.3 (Fabric)

Play as any of the nine Team Fortress 2 mercenaries in normal Minecraft survival or creative. Pick a team, a class and a loadout, then fight mobs or other players with rockets, stickies, sentries and backstabs. Mining, building, crafting, hunger and your inventory all keep working.

**This repository contains no Valve assets.** No models, textures, sounds or Source/Half-Life 2 SDK code are included. Everything visual or audible from TF2 is generated on your own machine, from your own legally installed copy of Team Fortress 2, by `tools/extract_tf2_assets.py`. Without that pack the mod still works fully: you look like your normal skin and weapons use simple placeholder icons and vanilla sounds.

---

## 1. Requirements

| | |
|---|---|
| Minecraft | Java Edition **26.3** |
| Loader | Fabric Loader **0.20.1+** and **Fabric API 0.165.2+26.3** |
| Java | 25 (bundled with the Minecraft launcher) |
| Extractor | Python 3.9+, `pip install numpy pillow`; **ffmpeg** on PATH (or `pip install soundfile`) for sounds |
| TF2 | Installed through Steam (it's free) |

## 2. Build the mod

```bash
./gradlew build          # Windows: gradlew.bat build
# -> build/libs/mercenaries-0.1.0+26.3.jar
```
Put the jar and Fabric API in `.minecraft/mods/`.

### Building on GitHub
`.github/workflows/build.yml` runs on every push and pull request:
* **no-valve-assets** fails if any Source/TF2 files (`.vpk`, `.mdl`, `.vtf`, a generated pack, …) were committed;
* **extractor-test** runs `tools/test_extractor.py` on Python 3.12;
* **build** builds the jar with JDK 25 and uploads it as the `mercenaries-jar` artifact (Actions tab → run → Artifacts).

Push a tag like `v0.1.0` to publish a GitHub Release with the jar and the extractor attached:
```bash
git tag v0.1.0 && git push origin v0.1.0
```

## 3. Extract your TF2 assets (optional but recommended)

```bash
cd tools
python extract_tf2_assets.py                          # finds Steam's TF2 automatically
python extract_tf2_assets.py --tf2 "D:/SteamLibrary/steamapps/common/Team Fortress 2"
python extract_tf2_assets.py --out "%APPDATA%/.minecraft/resourcepacks/MercenariesTF2Assets.zip"
```
Then enable **MercenariesTF2Assets** in *Options → Resource Packs*.

What the script does:
* reads the game's VPK archives directly (no external tools such as Crowbar or VTFEdit);
* converts the nine class models (MDL/VVD/VTX) into skinned meshes with their full skeleton, and decodes TF2's own animations (stand / run / crouch / crouch-walk / air / swim for every weapon slot the class uses, including `$includemodel` animation files, sections and `.ani` blocks). The facing direction is measured from each model's own skeleton (hips, shoulders, head), so nothing is assumed about how a model was authored. Both RED and BLU skins are kept;
* converts each weapon twice: a first-person/inventory version and a third-person version that is bone-merged onto the class skeleton's hand, exactly how TF2 attaches weapons. TF2's own `anim_slot`/`item_slot` decides which animation set the class plays while holding it;
* uses `scripts/items/items_game.txt` to find each weapon's world model and backpack icon, plus models for projectiles, the sentry and the dispenser;
* decodes VTF textures (DXT1/3/5 and all common uncompressed formats) to PNG;
* converts sounds to mono OGG (with TF2's random variants): per-weapon fire / reload / melee hit / hit-world / draw / empty-click, shared effects, and each class's pain, death, "MEDIC!" and battle-cry voice lines;
* extracts TF2 HUD and menu art (health cross, ammo panels, meters, crosshair, class-select portraits, leaderboard icons);
* writes `<pack>_report.txt` next to the pack: which animations, sounds and HUD images were found or missing, plus the sequence names in each class's viewmodel. It contains no Valve content, so it's safe to share when reporting problems.

**After updating the mod, re-run the extractor.** Packs from older versions use a different model format and won't load.

Useful flags: `--list-weapons` (show how each weapon resolved), `--max-texture 2048`, `--player-scale 0.0235`, `--skip-sounds`, and `--orient orient.json` for per-weapon fixes:
```json
{ "minigun": { "rotate": [0, 180, 0], "size": 1.2 } }
```
The generated pack is for **personal use only**. It contains Valve's copyrighted assets, so don't share or upload it.

Run the extractor's self-test (synthetic data, no TF2 needed): `python tools/test_extractor.py`

## 4. Playing

| Control | Action |
|---|---|
| **M** | Open the team / class / loadout screen (shown automatically on first join) |
| **Left mouse** (gun held) | Primary fire. Hold for automatic weapons |
| **Right mouse** (gun held) | Secondary: scope, airblast, detonate stickies, spin minigun, deploy Über, build dispenser |
| **R** | Reload |
| **V** | Ability: Spy cloak |
| **Z** | Call for a Medic (voice line + alert to nearby teammate Medics) |
| **Jump in mid-air** | Scout double jump |
| **Sneak + left mouse** with PDA | Destroy your buildings |

* Your loadout goes into hotbar slots 1–4. Anything already there is moved to free inventory slots, never deleted. Switch to any other slot to mine and build as normal. While a gun is in your hand, left-click fires instead of mining.
* Merc weapons can't be dropped or lost on death; missing ones come back within a second. Choose **None (vanilla)** to return to plain Minecraft.
* Teams are ordinary scoreboard teams (`merc_red`, `merc_blu`) with friendly fire off.
* Ammo regenerates slowly while you're not firing. Dispensers refill it quickly. Engineers get metal from dispensers and passive regeneration.

### Classes and weapons (54 items)
| Class | Primary | Secondary | Melee |
|---|---|---|---|
| Scout | Scattergun, Force-A-Nature | Pistol, Bonk! Atomic Punch | Bat, Sandman |
| Soldier | Rocket Launcher, Direct Hit, Black Box | Shotgun, Gunboats | Shovel, Equalizer |
| Pyro | Flamethrower, Backburner | Shotgun, Flare Gun | Fire Axe, Axtinguisher |
| Demoman | Grenade Launcher, Loch-n-Load | Stickybomb Launcher, Scottish Resistance | Bottle, Eyelander |
| Heavy | Minigun, Natascha, Tomislav | Sandvich, Shotgun | Fists, G.R.U. |
| Engineer | Shotgun, Frontier Justice | Pistol | Wrench, Gunslinger (+ Construction PDA) |
| Medic | Syringe Gun, Blutsauger | Medi Gun, Kritzkrieg | Bonesaw, Ubersaw |
| Sniper | Sniper Rifle, Huntsman | SMG, Jarate | Kukri, Tribalman's Shiv |
| Spy | Revolver, Ambassador | Sapper | Knife, Big Earner |

Class passives: Scout gets speed and a double jump. Soldier and Demoman take less fall damage after blast jumps. Pyro takes 75% less fire damage. Heavy resists knockback. Medic regenerates. Spy has a cloak watch.

Damage uses TF2's numbers divided by `hpScale` (default 6.25, so a 125 HP Scout has 10 hearts). Crits deal 3×, mini-crits 1.35×, and hitscan weapons have ramp-up and fall-off.

### Server config: `config/mercenaries.json`
`hpScale`, `explosionsBreakBlocks` (default **false**), `hurtVanillaPlayers`, `passiveAmmoRegenPercent`, `passiveMetalRegen`, `promptOnFirstJoin`.

---

## 5. Status and known limitations (please read)

This was written without being able to compile or launch it: the build machine couldn't reach Fabric's or Mojang's Maven servers. Every Fabric API call was checked against Fabric API's own `26.3` source and test mods. A few vanilla method names couldn't be checked that way, though, and Minecraft 26.x renamed a lot. **Expect a handful of compile errors on the first `./gradlew build`**, almost all of them one-line renames. The places most likely to need a touch:

| File | Unverified vanilla API |
|---|---|
| `MercMesh.java`, `BuildingRenderer.java` | `RenderTypes.entityCutoutNoCull/entityTranslucent`, `VertexConsumer` chain |
| `MercProjectile.java` | `Entity#shouldBeSaved`, `Entity#hurtServer` (projectiles now extend plain `Entity`) |
| `MercDamage.java` | `ClientboundSetEntityMotionPacket(Entity)`, `registryAccess().lookupOrThrow(...).getOrThrow(...)` |
| `BuildingEntity.java` | `ValueInput.getIntOr/read`, `ValueOutput.store`, `Mob#removeWhenFarAway` |
| `MercServer.java` | `CustomModelData` record constructor, `PlayerTeam#setPlayerPrefix` |
| `MeshItemRenderer.java` | `NoDataSpecialModelRenderer.submit` signature |
| `PlayerModelMixin.java` | `PlayerModel#setupAnim(AvatarRenderState)`. Marked `require = 0`, so if it misses, the vanilla skin draws under the TF2 mesh instead of crashing |

Gameplay simplifications: no disguise kit, teleporters, capture points or random crits. The sniper zoom comes from the movement-speed FOV effect, so it needs *FOV Effects* above 0.

Not done yet: first-person viewmodel animations (TF2's `c_<class>_arms` draw/fire/reload sequences; the report lists their names so they can be mapped next), TF2 gesture layers (firing/reload gestures on the third-person body), aim pose blending (only head look), and the TF2 fonts in the HUD. While a class with an extracted model holds a TF2 weapon, the weapon is drawn on the TF2 hand bone; vanilla's held-item rendering is switched off for those weapons in third person.

## 6. Legal
Team Fortress 2, its characters, models, textures and sounds are © Valve Corporation. This is an unofficial fan project, not affiliated with or endorsed by Valve or Mojang. The format readers were written from the publicly documented structures in the [Source SDK 2013](https://github.com/ValveSoftware/source-sdk-2013) headers; no SDK code is included. The mod's own code and placeholder art are MIT licensed (see `LICENSE`).
