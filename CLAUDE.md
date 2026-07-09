# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Aether is a Fabric client-side mod for Hypixel Skyblock (farming QoL: auto farming,
pest handling, failsafes, visual spoofers). It targets **Minecraft 26.1.2** and
**Java 25**. There is no test suite; verification is done by building and running in-game.

## Commands

```bash
./gradlew build            # compile + produce the mod jar in build/libs/
./gradlew clean build
./gradlew runClient        # launch a dev Minecraft client with the mod (fabric-loom)
```

- The artifact version comes from `aether_version` in `gradle.properties`. CI (`.github/workflows/build.yml`)
  rebuilds on push to `main`/`master`, auto-computes a `X.Y.Z-rN` release tag, publishes a GitHub
  release, and posts a Discord webhook. Do not hand-edit release tags.
- Optional translation packs are fetched at build time only when `-PaetherTranslationRepoRawUrl`
  and `-PaetherTranslationLocales` (or the matching env vars) are set; otherwise the fetch is skipped.

## Architecture

Everything runs on the client. The entrypoint declared in `src/main/resources/fabric.mod.json`
is `dev.aether.AetherClient` (`ClientModInitializer`). `dev.aether.Aether` is a no-op
`ModInitializer` and is not wired in. Startup order in `AetherClient.onInitializeClient`:
config → proxy → install bootstrap hooks → `ClientFeatureBootstrap.initialize()`.

### Mixin ↔ feature decoupling via bootstrap hooks (most important pattern)

Mixins in `dev.aether.mixin` must not depend on feature code directly. Instead they call
**static methods on `dev.aether.bootstrap.AetherBootstrapHooks`**, which delegate to an
installed `FeatureHooks` implementation. The live implementation is
`dev.aether.feature.LiveAetherBootstrapHooks`, installed at startup. `FeatureHooks` is an
interface of `default` no-op methods, so `AetherBootstrapHooks` returns safe defaults before
install and after `reset()`.

To wire a new mixin behavior into feature code: add a method to the `FeatureHooks` interface,
add the delegating static in `AetherBootstrapHooks`, implement it in `LiveAetherBootstrapHooks`,
and call the static from the mixin. Mixins are registered in
`src/main/resources/aether.mixins.json` (`compatibilityLevel: JAVA_25`); adding a mixin class
requires adding it there.

### Feature wiring

`dev.aether.feature.ClientFeatureBootstrap.initialize()` is the single place that boots all
runtime subsystems (config load, sound/theme/profit managers, macro worker thread, HUD registry,
world render events, and the `AetherScreenHooks` / `AetherChatEvents` / `AetherCommandRegistrar`
/ `AetherTickHandlers` registrations in `dev.aether.bootstrap`). `shutdown()` tears them down on
client stop; `onConfigProfileLoaded` re-syncs after a config profile switch. Register new
managers/tick handlers here.

### Modules

`dev.aether.modules.*` holds feature logic, generally as singleton `*Manager` classes with static
`syncFromConfig` / tick / start-stop methods called from bootstrap and tick handlers. `pathfinding`
is a large self-contained subsystem (pather, movement, etherwarp, rotation strategies).

### Macros

`dev.aether.macro`: `AbstractMacro` is a per-tick state machine (`updateState` decides state,
`invokeState` presses keys via `holdKeys`; keys not listed are released). Concrete farms live in
`macro/impl`. **All background macro work runs through the single `MacroWorkerThread` singleton**
(`submit(label, runnable)`), never `new Thread(...)`. Runnables may block, but any call touching
Minecraft game state must be dispatched via `client.execute(...)`.

### Config

`dev.aether.config.AetherConfig` is the central registry: every setting is a
`public static final ConfigEntry<T>` created with a `Config.bool/integer/floatVal/doubleVal/...`
factory, which both builds the entry and registers it for JSON persistence to `aether_config.json`.
Read with `.get()`, write with `.set(...)`; call `AetherConfig.save()` after mutating from UI code.
Entry types are in `config/entries`. Profiles, presets, and themes have dedicated `*Manager`/
`*PresetManager` classes.

### UI

`dev.aether.ui.MainGUI` is a custom NanoVG-rendered screen (LWJGL nanovg via
`dev.aether.renderer.NVGRenderer` / `NanoVGManager`), not vanilla widgets. Settings pages are
contributed through a **`ServiceLoader` registry**: implementations of
`dev.aether.ui.MainGUIRegistryProvider` are listed in
`src/main/resources/META-INF/services/dev.aether.ui.MainGUIRegistryProvider`, and each registers a
tab/subtab of `Setting` objects (`ui/settings`: `ToggleSetting`, `SliderSetting`, `DropdownSetting`,
`ActionSetting`, `TextSetting`, ...). **To add a settings page you must add your provider class to
that services file** — it is not auto-discovered otherwise. `/aether` opens the GUI.

### Other subsystems

- `dev.aether.proxy` — SOCKS proxy support (netty), init'd early in `AetherClient`.
- `dev.aether.hud` — draggable HUD elements registered via `HudRegistry`.
- `dev.aether.notification` — in-game + desktop (JNA) notifications.
- `dev.aether.renderer` — NanoVG/SVG rendering, blur framebuffer, world-space highlighters.
- Failsafe sounds use the bundled mp3 libs; all listed native deps (nanovg, jna, soundlibs) are shaded into the jar via Gradle `include(...)`.

# other

- dont leave any comments, none ;)
- ALWAYS git commit along the way!!
- dont add yourself as a co author on any commits
- commits should be structured like feat: fix: etc, dont add capitaisation or full stopns. also dont add descriptions to the commits, just the title is enough.
