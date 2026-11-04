# Upgrading to the expansion

Existing configs keep working. New files (`uniques/expansion.json`, `sources/*_elytra.json`, `sources/shulker_uniques.json`,
`sources/beanstalk.json`, `beanstalk.json`) are copied once into your config folder when the mod first starts; nothing is overwritten.
Delete `config/lootsurge/.lootsurge_added_v2` to re-add any you removed.

## New config you can edit
- `beanstalk.json`: chance, heights, lifetime (0 = permanent), dimensions, cooldown.
- `sources/dragon_elytra.json`, `ghast_elytra.json`, `breeze_elytra.json`, `guardian_elytra.json`, `shulker_uniques.json`: `dropChance` per mob. 
- `uniques/expansion.json`: every new Unique. Bleed/Stun numbers live under `powers.params` of `sages_puppyfang`
  (`bleedChance`, `bleedSeconds`, `bleedDamage`, `bleedCooldownTicks`, `stunChance`, `stunTicks`, `stunCooldownTicks`).
  Woolbound: `durationTicks`, `resistanceAmp`, `speedAmp`. Elytra boost: `boostStrength`, `boostCooldownTicks`.
- `uniques/activities.json` (optional): add `wolf_fight`, `wolf_defend`, `wolf_ally`, `wolf_breed`, `dye` to `chances` and `cooldownTicks`.
  Defaults in code: 5%, 2%, 0.4%, 6%, 2%.
- `config.json`: `labelAllDrops` (default true) labels every drop, including Common.

## Resource pack
Replace the old `LootSurge-Visuals` pack with the new zip. All previous art is kept; 13 Uniques are new.

## Crossbow update
- 18 new Unique crossbows, one per mob drop table (sources/xbow_*.json, "additional": true, rare per-kill chances; Warden and Wither 25-30%).
- Defaults: added `_added_v3.txt` marker so existing configs get the new source files once (never overwritten).
- Jar fallbacks: assets/lootsurge/items/<id>.json use the vanilla crossbow models; the Visuals pack adds custom art with pull/charged/rocket states.
