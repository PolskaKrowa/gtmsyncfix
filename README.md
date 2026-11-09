# GTM Material Entry Sync Fix

A small Forge mod for Minecraft 1.20.1 that stops **GregTech Modern (GTCEu)** from freezing the game when
several threads ask `ChemicalHelper.getMaterialEntry` for the first time at once.

>[!NOTE]
> Not affiliated with, or endorsed by, the GregTech CEu team.

## The problem

GTCEu fills `ITEM_MATERIAL_ENTRY_COLLECTED` and `TAG_MATERIAL_ENTRY` lazily. Both are plain fastutil
open-addressing hash maps with no synchronisation, and whichever thread asks first does the filling.

In a typical modpack that means:

- the **render thread** (`LevelRenderer.gtceu$renderContextAwareOutline`), and
- the **REI cache threads** (tooltip building via `TooltipsHandler.appendTooltips`)

can all write to the same map simultaneously. Concurrent writes can leave an open-addressing table with no
empty slot, after which `Object2ObjectOpenHashMap.get()` probes forever. Symptoms are a frozen game, or a
render thread and REI threads pinned at 100% CPU, all inside `Object2ObjectOpenHashMap.get` called from
`ChemicalHelper.getMaterialEntry`.

## What this mod does

It serialises both `getMaterialEntry` overloads (`ItemLike` and `TagKey<Item>`) on a single monitor, so the
lazy fill can only ever run on one thread at a time. It uses plain Mixin only (no MixinExtras).

At the head of each method, if the current thread does not already hold the lock, it takes it and re-invokes
the method. The re-entrant call sees that the lock is held and falls through to GTM's original code, which
therefore runs under the lock. The `ItemLike` overload calls the `TagKey` overload internally, and that nested
call also falls through.

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

If that line never appears, the mixin is not being loaded.

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

Only the two `getMaterialEntry` overloads are covered. Other GTM methods that lazily fill similar maps
(`getMaterial(Fluid)`, `getItems`, `getBlocks`, and `registerMaterialEntry` writing to `TAG_MATERIAL_ENTRY`)
share the same pattern but are not locked by this mod.

The first caller still does the full lazy fill, so other threads may wait briefly the first time. After that
the maps are populated and calls are cheap.

## Licence

[MIT](LICENSE). GregTech CEu Modern is licensed separately (LGPL-3.0) and is only used as a compile-time
dependency; none of its code is included here.
