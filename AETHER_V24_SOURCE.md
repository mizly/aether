# Aether v24 source snapshot

This archive corresponds to:

`aether-1.1.6-26.1.2-multistage-reel-fix-v24.jar`

The regular editable project is under `src/`. Releases v21 through v24 were
finished by applying four small ASM bytecode patches to the v20 project build,
so those patch sources are included under `release-patches/` rather than being
silently omitted from this snapshot.

For inspection, `exact-v24-decompiled/` contains Java decompilations of the
four classes changed by that release chain. These decompiled files are a view
of the final bytecode; edit the normal project source and/or release patches
when making a new build.

## Release chain

1. Build the editable project (the v20 base).
2. Save the unpatched v20 `PestHuntingController.class`.
3. Apply `PatchV21` to the extracted JAR tree.
4. Apply `PatchV22`, passing the saved v20 hunting-controller class as its
   second argument.
5. Apply `PatchV23`.
6. Apply `PatchV24`.
7. Repack the extracted tree as the release JAR.

The supplied patches were replayed from the v20 base during packaging. The
SHA-256 hashes of every class touched by v21-v24 matched the released v24 JAR
exactly:

- `PestHuntingController.class`
- `PestTargetController.class`
- `PestNavigationCoordinator.class`
- `PestCombatCoordinator.class`

The v24 patch makes server reel prompts authoritative for multi-stage pests by
disabling the same-prompt retry and missing-prompt watchdog paths that could
reel a later mosquito stage early.
