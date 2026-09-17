# Repository Guidelines

## Project Structure & Module Organization

Aether is a client-side Fabric mod for Hypixel SkyBlock, targeting Minecraft 26.1.2 and Java 25.

- `src/main/java/dev/aether/`: Java source. Feature logic lives in `modules/` and `macro/`; UI and rendering in `ui/`, `hud/`, and `renderer/`; Minecraft hooks in `mixin/`.
- `src/main/resources/`: Fabric metadata, mixin configuration, and `assets/aether/` fonts, icons, shaders, sounds, and presets.
- `src/test/java/dev/aether/`: tests mirroring source packages.
- `translations/` and `scripts/sync_translations.py`: language packs and synchronization tooling.
- `build/`: generated artifacts; `run/`: development client data. Both are ignored.

## Build, Test, and Development Commands

Use JDK 25 and the checked-in Gradle wrapper (`gradlew.bat` on Windows). Translation tooling requires Python 3.

- `./gradlew build --no-daemon`: compile, run standard tests, and package JARs in `build/libs/`; matches the CI build workflow.
- `./gradlew runClient`: launch the Fabric development client.
- `./gradlew test`: run the JUnit suite.
- `./gradlew test --tests 'dev.aether.util.BazaarUtilsTest'`: run one test class.
- `python scripts/sync_translations.py`: synchronize locale keys and README translation coverage; commit generated changes to satisfy CI.

## Coding Style & Naming Conventions

Follow adjacent code: four-space Java indentation and same-line opening braces. Use `PascalCase` types, `camelCase` methods and fields, and `UPPER_SNAKE_CASE` constants. Keep packages lowercase under `dev.aether`. No formatter or linter is configured; avoid unrelated formatting changes. Avoid writing comments whenever possible.

## Testing Guidelines

Use JUnit 5 Jupiter, `*Test.java` filenames, and descriptive camelCase test methods. Add focused regression tests for behavior changes; no numerical coverage threshold is configured. Reports appear in `build/reports/tests/test/index.html`.

Graphics tests are opt-in: `AETHER_TEST_OPENGL=1 ./gradlew test` requires a working display and OpenGL 3.3 context. Manually verify affected gameplay and UI in the development client.

## Commit & Pull Request Guidelines

Follow recent history with concise imperative subjects, such as `fix: recognize bazaar purchase confirmations` or `feat: add wireframe mode`. Keep commits focused.

PRs should describe behavior changes, link related issues, and report automated and manual validation. The README requests a video for new features; include screenshots for UI changes when useful.

## Translation Updates

Edit English strings in `src/main/resources/assets/aether/lang/en_us.json`; `translations/en_us.json` is synchronized from that source. Update other locale files in `translations/`, then run the synchronization script.
