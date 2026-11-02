# Loot Surge expansion – status

**Nothing in this expansion has been compiled or run.** The build environment used to write it cannot download Gradle,
so every file below was written and read-checked but never built. Build with GitHub Actions and run the checklist at the bottom.

| Phase | What | State |
|---|---|---|
| 1 | Iron stacking, every drop identifiable (entity tags, `labelAllDrops`), stronger flavor tooltips | written |
| 2 | Source pools for blocks, farming, fishing, animals, bosses (`config/lootsurge/sources/*.json`), extra mob pools (`"additional": true`) | written (core existed before) |
| 3 | The Giant Beanstalk (`Beanstalk.java`, `beanstalk.json`, `sources/beanstalk.json`) | written |
| 4 | Elytras: Ender Dragon's Elytra, Emberwing, Galewing, Tidecaller Wings (+ Phantom Wings kept) | written |
| 5 | Six Unique shulker boxes + identity tracking (`ShulkerIdentity.java`) | written, limits below |
| 6 | Sage's Puppyfang with Bleeding and Stun (`Mechanics.java`) | written |
| 7 | Wolf progression (`wolf_fight`, `wolf_defend`, `wolf_ally`, `wolf_breed`, `tame:wolf`), Packleader's Mantle; Hiro untouched | written |
| 8 | The Woolbound Dream (`Woolbound.java`) | written |
| 9 | New mod icon and pack icon | done (art checked visually) |
| 10 | Resource pack: art, worn textures and fallbacks for all 13 new Uniques (116 total) | built |
| 11 | Compile, unit tests, in-game tests | **not done** |

## Known limits
- Unique shulker identity is restored by tracking the placed block and marking the dropped item. A box that is destroyed without
  dropping an item (void, lava) cannot be marked. It never creates or removes items, so contents cannot be duplicated or lost.
- The Woolbound Dream cannot be placed. A client without the mod briefly shows a ghost bed that the server corrects.
- Without the resource pack, shulker and bed Uniques use a vanilla look and the elytras use the normal elytra texture.
- Bleed and Stun are applied by weapons with the `bleed`/`stun` flags; Stun on mobs uses NoAI with a load-time safety cleanup.
