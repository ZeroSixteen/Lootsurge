# Loot Surge

**Created by ZeroSixteen** · Fabric mod for **Minecraft Java Edition 26.2** · MIT license

Mobs keep all of their normal vanilla drops and XP. On top of that, a kill can roll a **bonus drop** from a pool made for that mob. Every bonus drop has a **tier** (Common → Mythic), and each tier has its own sound, particles and optional announcement. Rare tiers shoot a **light pillar** into the sky over the loot so you can spot it from far away.

Everything is decided on the server and sent as normal particle and sound packets, so **no client install is needed**. It works in single player, on a dedicated server, and for players who do not have the mod.

- Mod ID: `lootsurge` · Package: `com.zerosixteen.lootsurge`
- Needs: Minecraft 26.2, Fabric Loader 0.19.5 or newer, Fabric API, Java 25
- Does not need any other mod (no Cloth Config, no Kotlin, no GeckoLib, no Architectury)

## Tiers

| Tier | Default chance per bonus drop | Feedback |
|---|---|---|
| Common | 64 % | small sparkle, soft pickup chime |
| Uncommon | 25 % | bigger sparkle, amethyst chime |
| Rare | 8.5 % | end-rod burst and ring, level-up sound, **light pillar** |
| Epic | 2 % | firework burst, challenge-complete jingle + beacon hum, action-bar message, taller pillar |
| Legendary | 0.45 % | totem burst, thunder + jingle, flame pillar, chat message |
| Mythic | 0.05 % | dragon growl + thunder + portal sound, soul-fire pillar, 2–3 items, **server-wide broadcast** |

Each mob also has its own `dropChance` (for example 15 % for an enderman), so a Mythic drop is a real jackpot. All numbers are editable.

## What can drop

Weapons, armor, tools, enchanted books with real enchantments and levels (clamped to the game's maximum), potion ingredients, ores, food and special items such as totems, elytra, shulker shells and netherite scraps. Pools differ per mob: skeletons favor bows and arrows, endermen favor pearls and mining tools, witches favor potion material and utility books, the Warden and Wither have their own high-end pools, and so on. A fallback pool covers any other monster.

## Install

1. Build the jar (see below) or download it from the GitHub Actions run / Release.
2. Put `lootsurge-1.0.0.jar` into your instance's `mods` folder (Prism Launcher: right-click the instance → Folder → `.minecraft/mods`). Fabric API must be in the same folder.
3. Start the game once. The config is created at `config/lootsurge/`.

## Build the jar on GitHub (no setup needed)

1. Create a new GitHub repository and upload everything in this folder (keep the `.github` folder).
2. Open the **Actions** tab. The `build` workflow runs by itself on every push.
3. Open the finished run and download **LootSurge-mod-jar** under *Artifacts*. Unzip it; the `.jar` inside is the mod.
4. Optional: push a tag such as `v1.0.0` and the jar is also attached to a GitHub Release.

## Build the jar on your own PC

You need JDK 25.

- Windows: `gradlew.bat build`
- macOS / Linux: `./gradlew build`

The mod is `build/libs/lootsurge-1.0.0.jar`. Ignore the file ending in `-sources.jar`.

## Configuration

`config/lootsurge/config.json` (created on first start; delete a key to get its default back):

| Key | Meaning |
|---|---|
| `enabled` | master switch |
| `requirePlayerKill` | only roll when a player (or a player's arrow, trident, etc.) kills the mob |
| `skipBabyMobs` | no bonus loot from baby mobs |
| `fallbackMonstersOnly` | the fallback pool only applies to hostile monsters |
| `globalChanceMultiplier` | multiplies every pool's `dropChance` (0–100) |
| `maxActivePillars` | most light pillars alive at once (0 turns pillars off) |
| `excludedEntities` | entity IDs that never drop bonus loot |
| `debugLogging` | log every bonus drop |
| `tiers.<name>` | per-tier settings, below |

Per tier (`common`, `uncommon`, `rare`, `epic`, `legendary`, `mythic`):

| Key | Meaning |
|---|---|
| `displayName`, `color` | name and chat/label color |
| `weight` | relative chance of this tier (all six weights are added up) |
| `minItems`, `maxItems` | how many items one drop gives (1–8) |
| `itemLabel` | show a colored name tag above the dropped item |
| `announce` | `none`, `actionbar`, `chat` (killer only) or `broadcast` (everyone) |
| `sound` | `{ "id", "volume", "pitch", "delayTicks" }` |
| `extraSounds` | list of more sounds, each with the same keys, layered with delays |
| `burst` | `{ "particle", "count", "spread", "speed" }` puff at the mob |
| `ring` | `{ "particle", "count", "radius" }` circle on the ground |
| `pillar` | `{ "enabled", "particle", "accentParticle", "height", "segmentLength", "particlesPerSegment", "accentParticlesPerSegment", "spread", "intervalTicks", "maxSeconds" }` |

Values are range-checked; a bad value falls back to a safe one and a warning appears in the log. Particles must be ones that need no extra data (`end_rod`, `firework`, `flame`, `totem_of_undying`, `electric_spark`, `soul_fire_flame`, `happy_villager`, ...).

## Pools (`config/lootsurge/pools/*.json`)

One file per group of mobs. Files starting with `_` are ignored. Reload with `/reload` or restart.

```json
{
  "entities": ["minecraft:zombie", "minecraft:husk"],
  "dropChance": 0.08,
  "tierWeights": { "rare": 1.5, "mythic": 0.5 },
  "entries": [
    { "item": "minecraft:iron_sword", "tiers": ["uncommon", "rare"], "weight": 10,
      "damage": { "min": 0.1, "max": 0.6 } },
    { "item": "minecraft:diamond_sword", "tiers": ["epic"], "weight": 4,
      "enchantments": [
        { "id": "minecraft:sharpness", "minLevel": 3, "maxLevel": 5 },
        { "id": "minecraft:looting",   "minLevel": 1, "maxLevel": 3, "chance": 0.6 }
      ], "maxEnchantments": 2 },
    { "item": "minecraft:enchanted_book", "tiers": ["legendary"], "weight": 2,
      "pick": "one",
      "enchantments": [ "minecraft:mending", "minecraft:unbreaking" ] },
    { "item": "minecraft:arrow", "tiers": ["common"], "weight": 20, "count": { "min": 4, "max": 12 } }
  ]
}
```

- `entities`: entity IDs this pool applies to. Use `"fallback": true` instead for the catch-all pool (only one).
- `dropChance`: 0–1 chance that a kill rolls bonus loot at all.
- `tierWeights`: optional per-pool multipliers on the global tier weights (default 1.0).
- Entry: `item` (required), `tiers` (default: all), `weight`, `count` (number or `{min,max}`), `damage` (`{min,max}` fraction of durability used), `enchantments` (IDs or objects with `minLevel`, `maxLevel`, `chance`), `pick` (`all` or `one`), `maxEnchantments`.
- If a tier has no entries in a pool, the drop falls back to the next lower tier that does.
- Enchanted books use `minecraft:enchanted_book` with an `enchantments` list; levels never exceed what the game allows.
- Modded mobs and items work too: use their IDs. Unknown IDs are skipped with a log message, never a crash.

## How duplicates and odd deaths are handled

- Bonus loot is only added after the mob's death event on the server thread, one roll per death.
- A small memory of recently processed mob UUIDs blocks a second roll for the same mob.
- Players, armor stands and anything in `excludedEntities` are ignored.
- With `requirePlayerKill`, deaths from lava, falling, etc. give nothing.
- Any error in the mod is caught and logged; vanilla drops are never affected.

Limitation: a mod that makes a mob die more than once with a new UUID each time cannot be detected.

## Light pillar: how it works

The server sends ordinary particle packets in a stacked column above the item every few ticks until the item is picked up, despawns, or `maxSeconds` pass. Because they are vanilla particles, the pillar is visible without any client mod. Clients far away or with particles set to "Minimal" see less. If you want to reduce load, lower `maxActivePillars`, `height` or `particlesPerSegment`.

## Troubleshooting

- **Nothing drops**: check `enabled`, that you killed the mob yourself (`requirePlayerKill`), and that its entity ID is in a pool. Turn on `debugLogging`.
- **A pool is ignored**: look in `logs/latest.log` for lines starting with `[LootSurge]`; they name the file and entry.
- **No pillar**: particles must not be set to Minimal, and `maxActivePillars` must be above 0.
- **Crash on start**: make sure Fabric API is installed and you are on Minecraft 26.2 with Java 25.

## Compatibility notes

Loot Surge adds items after the fact and does not replace any loot table, so it should sit next to loot-changing mods. Mods that change or cancel mob deaths, or that give extra drops, will stack with it. This was not tested against the instance's full mod list, so report anything odd.

## Credits

Created by **ZeroSixteen**. MIT license, see `LICENSE`.
