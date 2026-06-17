# AGENTS.md

## Build

```powershell
.\gradlew.bat build          # full build (includes proto generation)
.\gradlew.bat generateProto   # proto-only
.\gradlew.bat runClient       # launch Minecraft client with mod
.\gradlew.bat runServer       # launch dedicated server with mod
.\gradlew.bat runData         # generate data (resources, assets)
.\gradlew.bat runGameTestServer  # run game tests
```

- Use `gradlew.bat` (Windows). The wrapper is Gradle **9.2.1**.
- Java toolchain targets **JDK 25**. The path is hardcoded in `gradle.properties` as `org.gradle.java.home=D:/Program Files/Java/jdk-25` -- update this on other machines or remove it if JDK 25 is on `PATH`.

## Project

- **Minecraft 26.1** + **NeoForge 26.1.0.19-beta** (moddev plugin 2.0.141).
- Mod ID: `withme`. Group: `io.github.mousemeya.withme`.
- Two `@Mod` classes: `WithMe.java` (both sides) and `WithMeClient.java` (client-only, `dist = Dist.CLIENT`).
- This is an **RL Gymnasium environment** mod, not a typical content mod. Core interfaces live in `gym/env/` and `gym/space/`. Action/observation data is serialized via Protocol Buffers 4.30.2.

## Protobuf & Codegen

- Proto sources: `src/main/proto/`. Generated Java output: `build/generated/source/proto/main/java/`.
- **Protobuf stubs are not committed.** Run `.\gradlew.bat generateProto` before the IDE will resolve proto-generated imports.
- Template processing: `neoforge.mods.toml` is auto-generated from `src/main/templates/META-INF/` via the `generateModMetadata` task. Properties like `${mod_version}` are expanded from `gradle.properties`. The output goes to `build/generated/sources/modMetadata/` and is included in the final JAR automatically.
- Resource source sets include `src/generated/resources/`. `.bbmodel` files and datagen `.cache` are excluded from the JAR.

## Testing

- **No unit tests exist** (`src/test/` is empty).
- **No game tests exist** despite the `gameTestServer` run config being set up.
- There is no CI/CD configuration (`github/workflows/` is empty).

## Architecture Notes

- `McEnv` is the core RL interface (`reset()`, `step()`, `getActionSpace()`, `getObservationSpace()`). It models a Gymnasium `Env`.
- `McSpace<T>` is a generic action/observation space.
- `src/main/java/.../rpc/` and `src/main/proto/.../rpc/` directories exist but are empty -- placeholder for future RPC layer.
