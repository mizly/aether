<p align="center">
  <img src="/src/main/resources/assets/aether/icons/logo_blue.svg" width="128" alt="Aether logo">
</p>
<br>

<h1 align="center">aether</h1>

<div align="center">
  
  > greatest os farming mod since farmhelper!

</div>

<p align="center">
  hypixel skyblock farming qol mod for <b>26.1.2</b>
</p>
<p align="center">
  <a href="https://discord.com/invite/BZxsfAcYYW">
    <img src="https://cdn.simpleicons.org/discord/808080" width="32" alt="Discord">
  </a>
</p>

---

## features
- **farming qol** - auto farming, pest destroyer, auto pest exchange, auto spray, auto loadouts, auto greenhouse, auto composter ... + many more!
- **visual** - nick hider, purse spoofer, sawdust spoofer
- **failsafes** - too many failsafes to list

---

## how to install
1. install [fabric for 26.1.2](https://fabricmc.net/use/installer/).
2. Install [fabric API](https://modrinth.com/mod/fabric-api/versions?g=26.1.2) & [fabric language kotlin](https://modrinth.com/mod/fabric-language-kotlin).
3. download the latest jar from the [releases page](https://github.com/mizly/aether/releases).
4. put aether & the other jar files into `.minecraft/mods`.
5. launch minecraft (make sure you're on the fabric profile).

> `/aether` opens the GUI.........

## contributing
contributions are welcome, so feel free to make a PR!

To contribute to language packs, see https://github.com/iceangelsaint/aether-language-packs

---

## ci secrets
release automation lives in two workflows: `.github/workflows/build.yml` builds the jar and
creates the github release, and `.github/workflows/discord-notify.yml` runs on `release: published`
to attach the source archive + sha256 sidecars and announce the release on discord.

repo secrets used:

| secret | required | used by | purpose |
| --- | --- | --- | --- |
| `GITHUB_TOKEN` | provided automatically | both | create the release, upload assets |
| `DISCORD_WEBHOOK_URL` | optional | discord-notify.yml | primary discord webhook, skipped when unset |
| `DISCORD_WEBHOOK_URL_2` | optional | discord-notify.yml | secondary discord webhook, skipped when unset |
| `RELEASE_PAT` | optional | build.yml | needed only if you want the release event to auto-trigger discord-notify.yml (releases made with `GITHUB_TOKEN` do not fire workflow triggers) |

without `RELEASE_PAT`, run **Discord Release Notify** manually from the actions tab and pass the release tag.

---
