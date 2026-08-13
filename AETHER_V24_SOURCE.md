# Aether v24 release patches (historical)

> **Do not apply `release-patches/` to this project.** The v21-v24 behaviour is
> already in `src/`. The patch chain below targets the old v20 bytecode and no
> longer matches this source; running it produces a corrupt jar. Build with
> `./gradlew build` and nothing else. See "Current status" at the bottom.

This directory originally accompanied:

`aether-1.1.6-26.1.2-multistage-reel-fix-v24.jar`

Releases v21 through v24 were finished by applying four small ASM bytecode
patches to the v20 project build, so those patch sources are kept under
`release-patches/` rather than being silently dropped. `exact-v24-decompiled/`
holds Java decompilations of the four classes that chain changed - a view of
the final v24 bytecode, not editable source.

## Release chain (as used for the v24 jar, against the v20 base)

1. Build the editable project (the v20 base).
2. Save the unpatched v20 `PestHuntingController.class`.
3. Apply `PatchV21` to the extracted JAR tree.
4. Apply `PatchV22`, passing the saved v20 hunting-controller class as its
   second argument.
5. Apply `PatchV23`.
6. Apply `PatchV24`.
7. Repack the extracted tree as the release JAR.

The v24 patch made server reel prompts authoritative for multi-stage pests by
disabling the same-prompt retry and missing-prompt watchdog paths that could
reel a later mosquito stage early.

## Current status

`src/` is no longer the v20 base those patches expect - it is later work in
which the patch effects were folded into source and the vestigial members the
patches had stubbed out were deleted. Replaying the chain now fails loudly:

- `PatchV22` wants `maintainFollowDistance(Minecraft, Entity, boolean)`; the
  source declares a four-argument version.
- `PatchV23` wants an `initiatePestRotation` call in
  `PestCombatCoordinator.handleFlyToPest`; that call site is gone.
- `PatchV24` wants the 900/6000/3000 ms reel timers; those constants no longer
  exist.
- `PatchV21` targets `isPullInProgress` and `HINT_PITCH_WAIT_TIMEOUT_MS`, both
  of which have been removed. It has no assertions, so it fails *silently* and
  corrupts `maintainFollowDistance` - the reason for the warning at the top.

The v24 reel fix itself now lives in `PestHuntingController.handleReel` as the
`huntReelPromptLatched` latch, which waits for the server prompt to clear
instead of neutering the retry timers.
