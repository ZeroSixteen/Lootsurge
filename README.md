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
| Unique | 0.025 % | orange name, wither + thunder + dragon + portal sounds, 64-block flame pillar, broadcast; named items with powers (see below) |

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

## Over-the-top items (past vanilla limits)

Normally enchant levels are capped at the game's maximum. Two optional entry settings lift that for chosen items:

| Key | Where | Meaning |
|---|---|---|
| `overcap` | on an enchantment object | `true` lets `maxLevel` go past vanilla, up to 255. Default `false`. |
| `name` | on an entry | Custom item name. Makes the item unmistakable and impossible to get from vanilla loot. |
| `nameColor` | on an entry | Color of that name. `orange` shows as Minecraft's gold/orange. |

```json
{
  "item": "minecraft:netherite_sword",
  "name": "Deadbane",
  "nameColor": "orange",
  "tiers": ["mythic"],
  "weight": 1.5,
  "enchantments": [
    { "id": "minecraft:sharpness", "minLevel": 9, "maxLevel": 10, "overcap": true },
    { "id": "minecraft:looting", "minLevel": 4, "maxLevel": 5, "overcap": true },
    { "id": "minecraft:mending", "minLevel": 1, "maxLevel": 1 }
  ]
}
```

Eight ready-made examples ship in the default pools (zombies, skeletons, endermen, wither skeletons, Warden, Wither, illagers and the fallback pool): Deadbane, Stormpiercer, Worldbreaker, Reaper, Titan's Bulwark, Colossus Greaves, Gale Cannon and Windstep. They are Mythic-only with a low weight, so they are very rare.

**Already have a config folder?** Defaults are only copied the first time. To get the new items, delete `config/lootsurge/pools/` (your `config.json` is kept) and restart.

## Unique tier (named items with real powers)

**Unique** is a seventh tier above Mythic: orange name, the biggest sound and light show (64-block pillar, server-wide broadcast), and items that exist **only** in Loot Surge pools. Default weight is `0.25` (half of Mythic). A pool only rolls Unique if it has at least one `unique` entry. Each Unique is tagged with a hidden marker, and its powers are read from your pool files, so players cannot copy powers onto other items.

### Defining one

Add `"unique"` (a-z, 0-9, `_`) and a `powers` block to a pool entry, with `"tiers": ["unique"]`. Every power is optional.

```json
{
  "unique": "ghastcaller",
  "item": "minecraft:netherite_sword",
  "name": "Ghastcaller",
  "nameColor": "orange",
  "tiers": ["unique"],
  "weight": 1,
  "enchantments": [
    { "id": "minecraft:sharpness", "minLevel": 8, "maxLevel": 10, "overcap": true },
    { "id": "minecraft:mending" }
  ],
  "powers": {
    "effects": [ { "id": "minecraft:fire_resistance", "amplifier": 0 } ],
    "cure": [ "minecraft:hunger" ],
    "immune": [ "fire", "explosion" ],
    "onHit": {
      "igniteSeconds": 10, "lightningChance": 0.2,
      "explosionChance": 0.3, "explosionPower": 2.5,
      "lifesteal": 0.3,
      "effects": [ { "id": "minecraft:wither", "amplifier": 2, "seconds": 8 } ]
    },
    "onUse": { "shoot": "large", "power": 4, "cooldownTicks": 30 }
  }
}
```

| Power | Works when | What it does |
|---|---|---|
| `effects` | item is worn (helmet, chestplate, leggings, boots) or held in the main hand | keeps those potion effects on you, refreshed every half second, no particles |
| `cure` | same | removes those effects (for example `hunger` makes rotten flesh harmless) |
| `immune` | same | cancels damage: `fire`, `explosion`, `fall`, `drowning`, `projectile`, `lightning` |
| `onHit.igniteSeconds` | held, on any hit you land (arrows from a held bow count) | sets the target on fire |
| `onHit.lightningChance` | same | chance (0-1) to strike the target with real lightning |
| `onHit.explosionChance` / `explosionPower` | same | chance of a blast that hurts mobs but breaks no blocks |
| `onHit.lifesteal` | same | heals you for this fraction of the damage dealt |
| `onHit.effects` | same | potion effects on the target (`seconds` required) |
| `onUse` | right-click while holding it | shoots a fireball: `shoot` is `large` (ghast, explodes) or `small` (blaze); `power` is the large blast size; `cooldownTicks` is the delay. |

After using a blast, lightning or fireball power you are immune to explosions, lightning and fire for 3 seconds, so your own power does not hurt you. Fireballs follow the `mobGriefing` game rule for block damage.

### Default Uniques (all found in the matching mob's pool)

| Mob | Unique | Power in one line |
|---|---|---|
| Zombie | Rotbound Plate | chestplate: eat rotten flesh with no downsides, regen, resistance, absorption |
| Zombie | Ghoulcleaver | axe: 50% lifesteal, strength, weakens and starves targets |
| Skeleton | Boneshot Hellbow | bow: right-click fires rapid small fireballs, arrows ignite |
| Skeleton | Marrowsprings | boots: no fall damage, jump boost, speed |
| Creeper | Fusebreaker | sword: explosions and occasional lightning on hit |
| Creeper | Blastproof Greaves | leggings: immune to explosions and lightning |
| Spider | Venomfang | sword: heavy poison and slow on hit, lifesteal, speed |
| Enderman | Voidpick | pickaxe: Haste V while held, night vision |
| Blaze / Ghast | Ghastcaller | sword: right-click launches ghast fireballs, burns, fire and blast immune |
| Blaze / Ghast | Tearsplitter | bow: right-click launches ghast fireballs |
| Blaze / Ghast | Blazeheart Mantle | chestplate: fire immune, strength |
| Wither skeleton | Witherbane | axe: Wither III on hit, 50% lifesteal |
| Wither | Crown of the Nether Star | helmet: strength, resistance, regen, absorption, immune to blasts and arrows |
| Warden | Heart of the Deep Dark | chestplate: resistance IV, strength, haste, night vision |
| Drowned | Stormcaller | trident: 60% lightning on hit |
| Guardian | Eye of the Deep | helmet: water breathing, dolphin's grace, conduit power, no drowning |
| Phantom | Phantom Wings | elytra: slow falling, speed, night vision, no fall damage |
| Illager | Raidbreaker | axe: blasts and lifesteal on hit, Hero of the Village |
| Witch | Hexweave Hat | helmet: cleanses every bad effect, regen |
| Slime | Gelsprings | boots: Jump Boost VII, no fall damage |
| Piglin brute and friends | Gilded Brutebreaker | axe: fire, blasts, lifesteal |
| Breeze / Trial | Galebreaker | mace: jump boost, speed, no fall damage |
| Shulker and end mobs | Shulker Bulwark | chestplate: resistance, immune to arrows and falls |

New config values: `tiers.unique` in `config.json` (same keys as other tiers). If your config does not have it, the built-in default is used.

### Balancing and compatibility notes

- Levels above vanilla scale hard. Sharpness 10 deals roughly 7 extra damage and Efficiency 12 mines almost instantly. Keep these Mythic-only and low weight.
- Some enchantments behave oddly or break at very high levels (Protection past about 20 stacks to full damage immunity; huge Thorns, Unbreaking or Quick Charge values can cause odd results). Stay in single digits to low teens.
- Overcapped items look normal to vanilla clients but may show roman numerals oddly; mods that clamp enchant levels (some anvil or enchant-display mods) can reduce them.
- Anvils and grindstones may refuse to combine or may reset over-level enchants, depending on other mods. Test before relying on them.
- A bad enchant ID or item ID is skipped with a log line, not a crash.

## Commands, creative tab and Mod Menu

### `/lootsurge` commands

| Command | Who | What it does |
|---|---|---|
| `/lootsurge` or `/lootsurge help` | everyone | shows the command list |
| `/lootsurge list` | everyone | lists every loaded Unique id and name |
| `/lootsurge info` | everyone | shows whether the mod is on and how many mobs and Uniques are loaded |
| `/lootsurge give <unique>` | ops | gives you the Unique with its name, enchants at top level and powers |
| `/lootsurge test <tier>` | ops | spawns a test drop of that tier at your feet with all its sounds, particles and pillar (`common` ... `mythic`, `unique`) |
| `/lootsurge activityroll <activity> <material>` | ops | forces an activity Unique roll (ignores chances) for testing |
| `/lootsurge pity` | everyone | shows your dry-streak counters (needs pity enabled) |
| `/lootsurge reload` | ops | reloads `config.json` and all pool files without restarting |

Press Tab after `give` or `test` to autocomplete ids and tiers. `give` works with Uniques you added yourself in your pool files, not only the defaults.

### Creative tab

A **Loot Surge Uniques** tab (nether star icon) in the creative inventory holds every Unique. It is built from the Uniques bundled in the mod, so custom Uniques you add to your own pool files are available through `/lootsurge give` instead. The tab only appears for players who have the mod installed.

### Mod Menu options screen

If you have Mod Menu installed, open Mods -> Loot Surge -> the config button. It has buttons for bonus loot on/off, require player kill, skip baby mobs, debug logging, the drop chance multiplier and the maximum number of light pillars. Each click saves `config.json` straight away and, in single player, reloads it. Tier odds, sounds and pools are still edited in `config/lootsurge/`.

Mod Menu is optional and is not bundled. The screen only exists on the client and never loads on a dedicated server. If a future Mod Menu release changes its API, only that button is affected; the rest of the mod keeps working.

## Expansion: activity Uniques, armor, Hiro's Spirit and pity

Added on top of everything above; no existing tier, weight, chance, pool or item was changed.

### Rarity visuals now survive pickup and re-drop (bug fix)

The floating name and light pillar used to exist only on the item entity that was first spawned. A drop of tier Rare or higher now also carries its tier inside the item stack (custom data key `lootsurge_tier`). Whenever any item entity appears (thrown by a player, dropped from a broken chest, loaded with a chunk after a restart), the server reads the tier and puts the label and pillar back. The tag lives in the stack, so it works the same in single player, multiplayer, chests and hoppers. Tiers whose label and pillar are both off are never tagged, so Common stacks still merge with vanilla items.

### Activity drops

Doing everyday things can roll one tiny chance at a Unique from a pool that depends on the **material**:

| Activity | Counts | Material keys |
|---|---|---|
| `woodcutting` | breaking logs, stripped logs, wood, stems, hyphae, bamboo | `oak spruce birch jungle acacia dark_oak mangrove cherry pale_oak bamboo crimson warped` |
| `mining` | stone types and ores | `stone deepslate granite diorite andesite tuff calcite netherrack basalt blackstone end_stone obsidian ...`, ores by short name (`coal iron gold copper diamond emerald redstone lapis nether_gold nether_quartz ancient_debris`) |
| `excavation` | dirt, grass, coarse/rooted dirt, mud, sand, red sand, gravel, clay, snow, powder snow, soul sand/soil | the block name, or the group `dirt sand gravel clay mud snow moss` |
| `nature` | flowers, leaves, saplings, vines, mushrooms, moss, nether plants, grass and ferns | the block name, or `flower leaves sapling vine mushroom nether plant` |
| `farming` | fully grown crops (wheat, carrots, potatoes, beetroots, nether wart, melons, pumpkins, cane, cocoa, berries, cave vines) | the block name |
| `shear`, `milk`, `honey`, `egg`, `breed`, `tame`, `horse` | shearing, milking (cow, goat, mooshroom), honey from a full hive, egg laying near you, breeding, taming, horse taming | the animal (`sheep`, `cow`, `wolf`, `cat`, ...) or `animal` |
| `fishing`, `archaeology`, `brewing`, `water`, `lava` | catching a fish, brushing suspicious sand/gravel, finishing a potion, scooping water or lava with a bucket | `fish`, `sand`/`gravel`, `potion`, `water`, `lava` |

Each Unique lists `"sources": ["woodcutting:oak@3", "mining:diamond@2", "tame:*@1"]` (`activity:material@weight`, `*` = any). On a roll the game collects every Unique that matches the exact material, the material group and `activity:*`, then picks by weight. A drop is a normal Unique drop: orange name, sounds, pillar, announcement.

Defaults live in `config/lootsurge/uniques/activities.json`: chance per activity (for example 0.15 % per log, 0.1 % per stone, 1 % per fish), `cooldownTicks`, `materialMultipliers` (for example `"mining:ancient_debris": 4.0`) and `pity`. Every Unique file in `uniques/*.json` uses the same entry format as pool files.

Anti-abuse: one roll per player per activity per cooldown (a tree-feller or vein-miner swing rolls once, not once per block); a block position cannot roll again for 5 minutes (place-and-break loops give nothing); crops count only when fully grown; creative and spectator players never roll; everything runs on the server. Test with `/lootsurge activityroll <activity> <material>`.

### New Uniques (activity tools and armor)

Powers are data-driven **triggers**: a Unique worn or held reacts to an activity (`woodcutting`, `mining`, `fishing`, ... or `hurt`, `hit`, `sprint_burst`, `last_stand`) with a chance, a cooldown and a list of actions: `drop`, `duplicate`, `xp`, `effect`, `effect_nearby`, `sound`, `particles`, `ring`, `column`, `lightning`, `shockwave`, `grow`, `spawn`, `reveal`, `pull`, `heal`, `teleport`, `repair`. Flags add `lava_walk`, `magnet`, `glow_hostiles`, `hunger_saver`, `sprint_burst`, `last_stand`. See the files in `uniques/` for examples; copy one and edit it.

Not duplicated from Vein Miner, TreeFeller or auto-replant: none of them break extra blocks or replant. "Special materials" such as Ancient Timber or Frost Wool are stand-ins made from vanilla items (coloured wool, ice, charcoal, amethyst, and so on), because the mod adds no new items.

**Woodcutting (9):** Worldbreaker, The Green Reaper, Timber Tyrant, Stormchopper, Yggdrasil's Branch, Panda King's Cleaver, Stemsplitter, Sakura Storm, Ashen Hatchet

**Mining (9):** Fortune's Favor, Magmaheart, Deepdelver, The Void Digger, King's Ransom, Starfall, The World Eater, Geode Heart, Cinderfang

**Excavation (7):** King's Excavator, Grave Robber, Fossil Finder, Soul Digger, Yeti's Spade, Swamp King's Shovel, Pharaoh's Spade

**Nature and farming (12):** Harvest Moon, The Reaper's Bounty, Golden Harvest, Chronos' Hourglass, Glowvine Crook, Blightbloom, Eden's Promise, The Florist's Folly, The Leaf Blower, Shroom Boom, Warpbloom Sickle, Farmer's Overalls

**Livestock, bees and taming (10):** King's Shears, Frostbite, Phoenix's Shears, Golden Udder, Beastmaster's Bounty, Queen's Decree, Shepherd's Chain, Cluckmaster's Hoe, Centaur's Saddle, Hiro's Spirit

**Fishing and other activities (12):** Leviathan's Call, Gilded Hook, Davy Jones' Debt, Rainbow's End, Abyssal Hook, Prometheus, Fortune's Compass, Tidal Crown, Alchemist's Fortune, Hell's Crucible, Relic Seeker's Brush, Lamplighter's Lens

**Armor (21):** Boots of the Beanstalk, Crown of the Deep, Nomad's Mantle, Magma Walker, Phantom Treads, Greedy Greaves, Warden's Visage, Scarecrow's Crown, Ender's Embrace, The Last Stand, Gravewalkers, Merchant's Gloves, Yeti's Snowshoes, Leviathan's Fins, Lumberjack's Flannel, Stonecutter's Apron, Beekeeper's Veil, Parrot's Perch, Nine Lives, Cinder Sandals, Thunderhide

### Hiro's Spirit

A very rare reward for taming a wolf, in honour of Hiro the husky. Right-click the bone to call **Hiro**: a glowing, named, tamed wolf with speed, strength and resistance, snow and end-rod sparkles, a low howl-like call over a bell chime, and vanilla wolf AI so he follows and fights beside you. Every few seconds he points out the nearest ore (or a dropped item) with an action-bar message and a trail of snowflakes. He stays for 3 minutes (cooldown 1 minute), only one Hiro per player exists, and leftovers after a restart are removed. He is a normal wolf entity with effects, not a custom model, so no client mod is needed.

### Pity system (off by default)

In `uniques/activities.json`: `"pity": { "enabled": false, "perActivity": true, "startAfter": 300, "bonusPerRoll": 0.01, "maxMultiplier": 8.0 }`. When on, each eligible activity roll that gives nothing adds 1 to the player's counter; after `startAfter` misses the activity chance is multiplied by `1 + (misses - startAfter) * bonusPerRoll`, capped at `maxMultiplier`. A Unique drop resets that counter. Counters are saved in `config/lootsurge/pity.json`, and `/lootsurge pity` shows yours. No other tier is touched.

### Updating an existing install

Your `pools/` folder is never modified. The new `uniques/` folder is created only if it does not exist; to get future default changes, delete or rename it and restart. The jar needs no new dependencies.

## Visual overhaul (Loot Surge Visuals resource pack)

Every Unique (103: 23 from mob pools, 80 from `uniques/*.json`) has its own animated 32x32 item texture; the 34 armor pieces and elytra also have their own worn textures. Bows have pull frames and fishing rods have a cast frame. The mod gives each Unique the item model id `lootsurge:<unique id>` (and, for armor, the equipment asset `lootsurge:<unique id>`); the jar bundles vanilla-looking fallbacks, and the **LootSurge-Visuals.zip** pack replaces them. The pack only adds files under `assets/lootsurge`, so ordinary items never change.

Install: put `LootSurge-Visuals.zip` in `.minecraft/resourcepacks` (Prism: instance Folder, then `resourcepacks`) and enable it. Needs the mod on the client. For servers with vanilla clients set `visuals.customItemModels` to `false` in `uniques/activities.json`. Uniques dropped before this update get their model the first time they are held or worn. Worn-armor textures are static (Minecraft cannot animate them); inventory icons are animated.
