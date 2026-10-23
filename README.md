# Clean FPV

A clean-room FPV quadcopter simulator for Minecraft 1.12.2 on [Cleanroom](https://github.com/CleanroomMC/Cleanroom).
Arm a drone, fly it with a joystick (SDL3 through Cleanroom's `com.cleanroommc.client.sdl` API), a gamepad, or keyboard and mouse,
with rate curves, body-axis attitude, a fisheye FPV camera, motor sound, a settings UI with presets, and server-authoritative
racing. The design is written up in `PLAN_v2.md`; `PLAN_v1.md` and `PLAN_v1_REVIEW_1.md` are the history behind it.

## Requirements

- Cleanroom 0.7.12-alpha or newer (`MouseTurnEvent`, the SDL joystick API and the joystick hotplug fix all ship there).
- JDK 25 to build.

## Building

The build uses [CleanroomGradle](https://github.com/CleanroomMC/CleanroomGradle) and
[Conventions](https://github.com/CleanroomMC/Conventions), following the
[CleanroomModTemplate](https://github.com/CleanroomMC/CleanroomModTemplate).

```
./gradlew build          # jars in build/libs: cleanfpv-<version>.jar (players), cleanfpv-<version>-mcp.jar (dev names)
./gradlew runClient      # dev client, username from minecraft_username
./gradlew runClient2     # second dev client (Player2, run/client2) for LAN / multiplayer testing
./gradlew runServer
./gradlew -q printVersion
```

Every build option is documented next to its property in `gradle.properties`.

### Versioning

The version is computed from git tags. Tag `1.0.0` on `main` to release it; `./gradlew -q printVersion` prints the current value.
Untagged builds get a `-dev.N` counter.

### Tokens

`@{NAME}` markers in classes and resources (`Reference.java`, `mcmod.info`, `pack.mcmeta`) are replaced at build time by
Token Envoy. `tokens.properties` declares them; `MOD_VERSION` and `MOD_AUTHORS` are computed in `build.gradle`.

### Access transformers

`src/main/resources/META-INF/cleanfpv_at.cfg` opens `Entity.setSize`, `Entity.isInWeb` and `ShaderGroup.listShaders`.
CleanroomGradle applies the file to the SRG-named jar before renaming to MCP names, so entries use **SRG names**
(`func_70105_a`, `field_148031_d`); MCP-named entries match nothing.

### Code style

FormatJ, ClearSkies and Checkstyle come from Conventions and are off by default (`enable_formatting`, `enable_checkstyle`).
`./gradlew clearSkiesApply formatJavaApply` reformats; `./gradlew extractConventions applyLicenseHeader` refreshes the
convention-managed files (`LICENSE`, `HEADER`, `checkstyle.xml`, `formatj.toml`, `cliff.toml`, `.editorconfig`,
`.gitattributes`, the managed region of `.gitignore`) and the license header on sources.

## License

LGPL-3.0-only, see `LICENSE`.
