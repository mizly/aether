# GUI preview harness

Renders the new GUI (`dev.aether.ui.gui.GuiView`) offscreen and writes PNGs, so layouts can be checked
without starting Minecraft. It runs only through its own Gradle task, in its own JVM, and is skipped
unless `AETHER_GUI_PREVIEW=1` is set.

## Run

```bash
AETHER_GUI_PREVIEW=1 \
LD_LIBRARY_PATH=/run/opengl-driver/lib:/run/current-system/sw/share/nix-ld/lib \
./gradlew guiPreview
```

The `LD_LIBRARY_PATH` part is for NixOS; without it GLFW fails with `GLX: Failed to load GLX`.
A display is needed (the window stays hidden; drawing goes into a framebuffer).

Filters take comma-separated lists:

```bash
./gradlew guiPreview -Dpreview.style=aurora -Dpreview.theme=default,sage -Dpreview.scenario=home
```

Output goes to `build/reports/gui-preview/<style>/<theme>/<scenario>.png`. The style folder is the id the
view resolved, so it is `debug` until real styles are registered in `StyleRegistry.defaults()`. Themes are
`default` (the built-in colours) or any `ThemePreset` name; without a filter it renders `default` and `sage`.

## Writing scenarios

Scenarios live in `PreviewScenarios.all()`:

```java
Scenario.of("search", 1280, 800)
        .key(Inputs.shortcut(GLFW.GLFW_KEY_F))
        .type("pest")
        .advance(300L)
```

- Sizes and coordinates are layout units, one pixel each.
- Every step draws a frame, because hover and hit regions come from the frame before.
- `ManualClock` only moves on `advance(ms)`, in 16 ms frames, so animations are deterministic.
- Steps: `navigate`, `hover`, `click`, `drag`, `scroll`, `key`, `type`, `advance`, or `then(session -> ...)`.

## What is faked

- `PreviewGuiHost` stands in for the game: a fixed account, session strip and garden data, an in-memory
  clipboard, and counters instead of screens. It never saves key options.
- The world behind the GUI is a flat colour with the view's scrim drawn over it, like the shell's vanilla fill.
- Minecraft item icons draw as the canvas placeholder until the renderer's item painter is installed.
- The settings registry is the real one, built headless.
- Frames start with `nvgBeginFrame` directly until `NanoVGManager.beginFrame(w, h, pxRatio)` lands.
