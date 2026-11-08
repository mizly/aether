<p align="center">
  <img src="/src/main/resources/assets/aether/icons/logo_blue.svg" width="128" alt="Aether logo">
</p>
<br>

<h1 align="center">aether</h1>

<div align="center">
  
  > greatest os farming mod since farmhelper!

</div>

<p align="center">
  hypixel skyblock farming qol mod for <b>26.3</b>
</p>
<p align="center">
  <a href="https://discord.com/invite/BZxsfAcYYW">
    <img src="https://cdn.simpleicons.org/discord/808080" width="32" alt="Discord">
  </a>
</p>

---

## features
- **farming qol** - auto farming, pest destroyer, auto pest exchange, auto spray, auto loadouts, auto greenhouse, auto composter ... + many more!
- **visual** - nick hider, purse spoofer, sawdust spoofer, fun visual features
- **failsafes** - too many failsafes to list

---

## how to install
1. install [Fabric Loader 0.20.1 or newer for 26.3](https://fabricmc.net/use/installer/) and use Java 25.
2. Install [Fabric API 0.165.2+26.3](https://modrinth.com/mod/fabric-api/versions?g=26.3) & [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin).
3. build this 26.3 port with `gradlew.bat build --no-daemon` (Windows) or `./gradlew build --no-daemon`; the mod is `build/libs/aether-1.3.1-26.3.jar`.
4. put aether & the other jar files into `.minecraft/mods`.
5. launch minecraft (make sure you're on the fabric profile).

> `/aether` opens the GUI.........

This port targets Minecraft 26.3 only. Rendering uses the 26.3 RenderPearl API and SPIR-V-compatible shaders; keyboard, mouse, and PiP window controls use SDL. Do not install the 26.2 Aether JAR alongside it.

Verified with Java 25, Fabric Loader 0.20.1, Fabric API 0.165.2+26.3, and Fabric Language Kotlin 1.14.2+kotlin.2.4.21: clean build and 380 passing tests; client smoke checks covered custom menus, left/right mouse input, HUD dragging, a local world, effect shaders, and PiP window restoration. Live Hypixel farming was not exercised.

## contributing
contributions are welcome, so feel free to make a PR!
if your PR adds a new feature, for example a new farm design or a new meta, please attach a video of the feature in your PR.

To contribute to language packs, see the [`translations/`](translations/) directory.

<!-- translation-coverage:start -->
## Translation Coverage

| Locale | Covered | Percentage |
| --- | ---: | ---: |
| `jp_jp` | 303/770 | 39.4% |
| `pr_us` | 253/770 | 32.9% |
| `pt_pt` | 477/770 | 61.9% |
| `ru_ru` | 492/770 | 63.9% |
| `test` | 516/770 | 67.0% |
| `zh_cn` | 508/770 | 66.0% |
<!-- translation-coverage:end -->
