# GTM Material Entry Sync Fix

A small Forge mod for Minecraft 1.20.1 that stops **GregTech Modern (GTCEu)** from freezing the game when
several threads hit its lazily-filled material caches for the first time at once.

>[!NOTE]
> Not affiliated with, or endorsed by, the GregTech CEu team.

## The problem

GTCEu fills several static caches lazily, from whichever thread asks first. They are plain fastutil
open-addressing hash maps with no synchronisation:

| Cache | Filled by |
|---|---|
| `ITEM_MATERIAL_ENTRY_COLLECTED`, `TAG_MATERIAL_ENTRY` | `ChemicalHelper.getMaterialEntry` |
| `FLUID_MATERIAL` | `ChemicalHelper.getMaterial(Fluid)` |
| `MATERIAL_ENTRY_ITEM_MAP` | `ChemicalHelper.getItems` (and everything that calls it, e.g. `ChemicalHelper.get`) |
| `MATERIAL_ENTRY_BLOCK_MAP` | `ChemicalHelper.getBlocks` (and `getBlock`) |

In a typical modpack that means:

- the **render thread** (`LevelRenderer.gtceu$renderContextAwareOutline`),
- the **REI cache threads** (tooltip building via `TooltipsHandler.appendTooltips` and
  `appendFluidTooltips`), and
- map integrations that look up fluid veins (JourneyMap, Xaero's, FTB Chunks), which call
  `getMaterial(Fluid)` and may do so from their own threads

can all write to the same map simultaneously. Concurrent writes can leave an open-addressing table with no
empty slot, after which `Object2ObjectOpenHashMap.get()` probes forever. Symptoms are a frozen game, or a
render thread and REI threads pinned at 100% CPU, all inside `Object2ObjectOpenHashMap.get`.

Even without a hang, a reader that sees a half-filled map returns the "no entry" result and can cache it, so
the wrong answer sticks.

## What this mod does

It serialises these `ChemicalHelper` methods on a single monitor, so a lazy fill can only ever run on one
thread at a time:

- `getMaterialEntry(ItemLike)`
- `getMaterialEntry(TagKey<Item>)`
- `getMaterial(Fluid)`
- `getItems(MaterialEntry)`
- `getBlocks(MaterialEntry)`

It uses plain Mixin only (no MixinExtras).

At the head of each method, if the current thread does not already hold the lock, it takes it and re-invokes
the method. The re-entrant call sees that the lock is held and falls through to GTM's original code, which
therefore runs under the lock. Methods that call each other (`getMaterialEntry(ItemLike)` calls the `TagKey`
overload, and `getMaterialStack`, `getPrefix`, `get` and `getBlock` all call into the methods above) nest
safely because the lock is re-entrant and one shared monitor is used for all five.

## Requirements

- Minecraft 1.20.1
- Forge 47.x
- GregTech CEu Modern `7.5.3` or newer (1.20.1 builds)

The mod is needed on both sides (`side = "BOTH"`), although the freeze reported so far is client-side.

## Installing

Drop the jar into your `mods` folder next to GTCEu.

To confirm the mixin actually applied, look for this line in `logs/latest.log` once something has asked for a
material entry:

```
[gtmsyncfix]: ChemicalHelper.getMaterialEntry lock is active (mixin applied).
```

If that line never appears, the mixin is not being loaded. Note that only the two `getMaterialEntry`
overloads log it; the other three methods share the same mixin class, so if it applied for one it applied
for all (a missing target method would fail at startup, see below).

## Building

You need JDK 17.

```
./gradlew build
```

The jar ends up in `build/libs/`. The bundled `gradlew` is a small bootstrap script that downloads Gradle 8.8
on first run, so it needs `curl` or `wget` and `unzip` (or PowerShell on Windows).

## Notes for mod developers

**Forge 1.20.1 only reads mixin configs from the jar manifest.** The `[[mixins]]` block in `mods.toml` is a
NeoForge feature and is silently ignored on Forge 1.20.1: no error, no log line, the mixin just never loads.
This project registers its config with the `MixinConfigs` manifest attribute in `build.gradle`. If you copy
this setup, keep that attribute.

**Injectors are required.** `gtmsyncfix.mixins.json` sets `defaultRequire: 1`, so if a future GTCEu renames or
removes one of the five methods the game fails at startup instead of silently running without the fix.
`getMaterial`, `getItems` and `getBlocks` are matched by name only; each is currently the only method with that
name in `ChemicalHelper`. If GTCEu adds an overload, give the injector a full descriptor.

The five methods above are the only ones covered. These are known and **not** locked by this mod:

- `ItemMaterialData.reinitializeMaterialData` and `registerMaterialEntry`, and
  `MixinHelpers.generateGTDynamicTags` (writes `FLUID_MATERIAL`). These are server-side writers that run on
  recipe or tag reload and can race the lazy fills if a client thread is active at the same moment. Locking
  them would hold the monitor for the whole rebuild.
- `PatternPreviewWidget.CACHE`, a plain `HashMap` filled with `computeIfAbsent` in the widget constructor.
  REI/JEI can construct the widget off the render thread. Fixing it needs a redirect inside the constructor
  rather than a method-head lock.

The first caller still does the full lazy fill, so other threads may wait briefly the first time. After that
the maps are populated and calls are cheap, but note that `getItems` and `getBlocks` stay serialised on every
call, not only the first.

## Licence

[MIT](LICENSE). GregTech CEu Modern is licensed separately (LGPL-3.0) and is only used as a compile-time
dependency; none of its code is included here.