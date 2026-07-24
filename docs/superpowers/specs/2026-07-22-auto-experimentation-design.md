# Auto Experimentation Table — Design

Date: 2026-07-22. Approved by nasko in-session.

## Goal

One button/toggle runs the whole Experimentation Table routine hands-off: pause farming,
walk to the table, play Superpairs plus both addon games, claim, renew charges with
bits + XP levels up to a configured cap (0-3/day), repeat until charges are gone,
resume farming.

## Architecture

Two layers, mirroring existing mod patterns:

### 1. Tick-driven solvers (`dev.aether.modules.experimentation`)

Registered in `AetherAutomationTickHandler.handleContainerMenus`, gated on the
`AUTO_EXPERIMENTS` master toggle. They act on whichever experiment GUI is open, so they
also work if the table is opened manually, and they self-recover when Hypixel closes and
reopens screens mid-game.

Common mechanics (verified against Odin AutoExperiments, Skyblock-Client and Skyblocker
solvers, adapted to modern mappings):

- Phase signal: slot 49's hover name — `Remember the pattern!` (show phase) vs
  `Timer: …` (input phase).
- Clicks: `ClientUtils.performSlotClick(screen, idx, 0, PICKUP)` with humanized delays
  from `EXPERIMENTS_CLICK_DELAY_MIN/MAX`.

**SuperpairsSolver** (`Superpairs (…)` title): reveal-remember-pair. Hidden cards are
cyan stained glass. Click unknown cards, remember every revealed `ItemStack` per slot
(full-stack `ItemStack.matches` comparison — two different pairs can share a display
name). Priority: complete a known pair > click the partner of a just-revealed card
> reveal an unknown. Plays until the board completes or locks (stall guard). Neither
Odin nor Skyblocker auto-clicks Superpairs; this is novel.

**ChronomatronSolver** (`Chronomatron (…)`): during show phase, track the glinted
(`hasFoil`) terracotta on slots 17–34 element by element (Skyblocker's replay-count
method — same colour can repeat). Store the chain as *items* (colours), not slot ids,
so board shuffles can't break replay. During input phase, click the chain in order.
Stop threshold: 15 chains (max XP toggle on) or `11 - serumCount`.

**UltrasequencerSolver** (`Ultrasequencer (…)`): during show phase, map slots 9–44 whose
hover name is a pure number. During input phase, click remembered slots ascending.
Stop threshold: 20 (max XP toggle) or `9 - serumCount`.

### 2. Worker orchestrator (`ExperimentationManager`)

Linear worker-thread flow (Bazaar/Composter style) under new
`MacroState.State.EXPERIMENTING`:

1. Pause farming if it was running (disable macro, remember to resume).
2. Navigate: walk-pathfind to the saved stand position, rotate to the saved table
   block, use-click, wait for the table GUI title.
3. Menu loop: click each experiment whose lore offers play; wait while a game GUI is
   open (the tick solvers play it); reopen the table as needed.
4. Renewals: find the renew button, log its bits/XP cost from lore at runtime (no
   hard-coded prices), click through any confirm screen, bounded by
   `EXPERIMENTS_RENEWALS_PER_DAY` (0-3).
5. Finish: close, chat summary, restore farming state.

Abort checkpoints (`MacroWorkerThread.shouldAbortTask`) at every step, per-game and
per-navigation timeouts, unexpected screens end the run cleanly with a chat message.

Table position is captured by a "Set table spot" action: stand at the table looking at
it; saves player position plus crosshair block.

## Config (AetherConfig) + UI

New `ExperimentationRegistryProvider` (Modules section, ServiceLoader-registered):
master toggle `AUTO_EXPERIMENTS`, Run Now action, Set Table Spot action,
`EXPERIMENTS_MAX_CLICKS` (play to 15/20/full board), `EXPERIMENTS_SERUM_COUNT` (0-3),
`EXPERIMENTS_RENEWALS_PER_DAY` (0-3), `EXPERIMENTS_CLICK_DELAY_MIN/MAX`, and the saved
table coordinates.

Out of scope: highlight-only rendering (Skyblocker covers that), scheduling/cron runs,
Metaphysical Serum consumption automation.

## Testing

No test suite exists; verification is `./gradlew build` plus in-game runs with
Show Debug on (solvers and orchestrator log each phase decision).
