# Clean FPV — Architecture Plan v1

Clean-room FPV quadcopter simulator mod for **Cleanroom (Minecraft 1.12.2)**, re-implemented
from the behavioural spec in `../minecraft_fpv_port/FPV_DRONE_MOD_DESIGN.md` ("the spec").
This document is the build guide: platform, loader primitives, architecture, hook map,
subsystem designs, sequencing, phases and verification. Formulas and constants are referenced
by spec section rather than repeated.

## 1. Ground rules

- **Clean room.** Implement the spec's behaviour; never copy decompiled source, GLSL, textures,
  lang files, the original mod id/name, or any URL/key from the original (spec legal note).
  New identity: mod id `cleanfpv`, name "Clean FPV", package `io.github.ndellagrotte.cleanfpv`.
- **No mixins.** Rotation/input primitives come from the loader (Cleanroom PR #641);
  everything else is Forge events plus a small access transformer (§5). Where the spec's hook
  has no 1.12.2 event, §5 names the substitute.
- **No bundled libraries.** JOML (in Cleanroom), Gson (in Minecraft), GLFW (LWJGL 3). No crash
  reporter, no update JSON, no hard-coded remote services; anything network-facing beyond the
  mod channel is opt-in config.
- **Fix, don't replicate, the original's dead code and bugs** (spec §13.1); §10 lists the
  decisions.
- Java 25 toolchain (Cleanroom requirement), MCP names in source and AT.

## 2. Platform and toolchain

| Item | Decision |
|---|---|
| Loader | Cleanroom with PR #641 (`feat/rotation-input-primitives`). Until merged/released, depend on a local publish: in `../Cleanroom` run `./gradlew publishToMavenLocal`, set `cleanroom_version` to the printed version (currently `0.0.1-dev.1158.local.dirty`). |
| Build | Replace the Unimined template with **CleanroomGradle userdev**, copied from `../barrel-roll-clrm-port` (`settings.gradle` with `com.cleanroommc.cleanroomgradle.settings 0.17.5`; `build.gradle` with `implementation cleanroom.userdev(cleanroom_version)`, the icu4j/patchy/lwjgl2 excludes, the `RunMinecraft` mods-dir launch workaround, `reobfJar`, and the `client2` run for LAN tests). `gradle.properties`: `cleanroom_version`, `cg.repos.enableLocal = true`, mod identity fields, `use_access_transformer = true`. |
| Math | JOML `Vector3f/Vector3d/Quaternionf` (Cleanroom `api org.joml:joml:1.10.9`). |
| Input | `org.lwjgl.glfw.GLFW` joystick API (main thread only). Keyboard via vanilla `KeyBinding` + `org.lwjgl.input.Keyboard` key codes (LWJGLX shim). |
| Rendering | GL 1.x–2.x through `GlStateManager` + `Tessellator/BufferBuilder`; post-processing through vanilla `ShaderGroup` JSON pipeline (Cleanroom supports `modid:name` program names). |
| Networking | `SimpleNetworkWrapper` (`cleanfpv:main`) with `IMessage`/`IMessageHandler`, handlers hop to the main thread via `addScheduledTask`. |
| Config | Gson JSON under `config/cleanfpv/` (models file + active model + first-run flag). |
| Tests | JUnit 5 for pure math (rates, quaternion integration, motor ODE, collision response). |

## 3. Loader primitives from PR #641 and how the mod uses them

| Primitive | Used for | Notes |
|---|---|---|
| `Entity.roll`, `prevRoll`, `getRoll(partialTicks)`, `setRoll(float)` | The local player's camera roll and remote players' roll | Roll is written **per rendered frame** (attitude integrates per frame, spec §4.3). Like the Barrel Roll port, advance `player.roll` and `player.prevRoll` together so `getRoll(partialTicks)` returns the frame value with no tick interpolation; use `setRoll` only on arm/disarm (it wraps and validates). |
| DataWatcher `Entity.ROLL` (id 254) sync | Free server→client roll for remote players and vanilla spectator camera | Client is authoritative for its own roll (`EntityPlayerSP.onRollSynced` no-op). The server-side transform packet handler calls `player.setRoll(...)` so the loader syncs it to trackers. The mod still sends its full quaternion (spec §8.1 #10) because vanilla yaw/pitch packets are quantised. |
| `InputEvent.MouseTurnEvent` (`@Cancelable`, `net.minecraftforge.client.event`) | Mouse pitch/roll stick input while armed | Fires once per focused frame from both `EntityRenderer` turn call sites with the yaw/pitch that `turn()` would receive. While armed: read `getYaw()`/`getPitch()`, cancel the event so vanilla look does not move, and run the frame step (§7). Do not import `net.minecraftforge.fml.common.gameevent.InputEvent` by mistake. |
| `ForgeHooksClient.beginFrame()` / `getFrameDeltaSeconds()` / `MouseTurnEvent.getFrameDeltaSeconds()` | The per-frame `dt` for attitude integration and realtime physics | Replaces the spec's wall-clock delta between `CameraSetup` events. Clamp to a sane max (e.g. 0.1 s) after GUI close/lag. |

What the PR does **not** do, and the mod's answer:

- Camera does not read entity roll → one `EntityViewRenderEvent.CameraSetup` handler adds
  `player.getRoll(partialTicks)` for the render-view entity (pattern from the PR's test mod
  and the Barrel Roll port `ClientEventHandler#onCameraSetup`).
- Player model does not read roll → irrelevant here: armed players are drawn as drones from
  the mod's quaternion (`RenderPlayerEvent.Pre` cancel + own renderer).
- Roll is not sent client→server → the transform packet handler applies it server-side.

## 4. Architecture

```
CLIENT
 input/    JoystickService (GLFW poll per frame, callback for connect)
           ChannelMap + Calibration (settings) → StickState {thr,roll,pitch,yaw,angle,arm,angleSw,rclick}
           DroneMovementInput (replaces EntityPlayerSP.movementInput: zero vanilla move, expose raw keys)
           MouseTurnEvent handler → per-frame mouse pitch/roll deltas
 flight/   ArmController (FSM, debounce, throttle guard) · Rates (BF curves) · Attitude (quat, body axes)
           CameraRig (tilt → camera basis → yaw/pitch/roll written to player + Entity.roll)
 physics/  DroneState (v, ω[4], heat[4], T[4], axes) · Stepper (tick 1/128 s substeps | realtime)
           v1: ThrustModel (first-order lag) · v2: BladeElement, MotorOde (RK4), Battery, Thermal
           Sweep (World.getCollisionBoxes AABB sweep) · Bounce (spec §5.6) · MassModel/DragModel
 render/   CameraHooks (CameraSetup, FOVModifier) · Fisheye (ShaderGroup post) · DroneRenderer/DroneModel
           PropDiscs (RenderWorldLast) · Hud (stick overlay, text) · HideVanilla (hand/hotbar/crosshair/overlays)
 audio/    MotorSound (MovingSound, pitch/volume from ω0)
 gui/      Wizard screens · Settings screens · widgets (RateChart, SnappySlider, lists) · Controls-screen button
 config/   Settings (Gson) · DroneBuild + presets · ModelStore
 net/      Channel · packets (Arm, Build, Transform, race S2C) · RemoteDrones (history + interpolation)
 race/     client state/HUD/gate render; server authority module (gate crossing, timing, tracks)
COMMON/SERVER
 ArmRegistry (UUID→armed, both sides) · server packet handlers (validate, apply abilities/size/roll, relay via sendToAllTracking)
```

Key decisions (spec §2, kept): the player entity *is* the drone; client-authoritative
simulation; kinematic attitude + dynamic translation; hybrid timing; UUID-keyed registries.
Changes from the original: relay lives in the server-side handler (works on integrated
servers too); race authority is server-side; state maps are cleared on world unload.

Package layout under `io.github.ndellagrotte.cleanfpv`: `CleanFpv` (mod class, `@SidedProxy`
like the template), `client/{input,flight,physics,render,audio,gui,hud}`, `common/{config,net,race}`,
`server/`. Physics/flight math classes have no Minecraft imports so they are unit-testable.

## 5. Hook map (spec → Cleanroom 1.12.2, no mixins)

| Spec hook / need | 1.12.2 substitute | Notes |
|---|---|---|
| `CameraSetup` roll/yaw/pitch | Write `rotationYaw/prevRotationYaw`, `rotationPitch/prevRotationPitch`, `roll/prevRoll` per frame; `EntityViewRenderEvent.CameraSetup` adds `getRoll(partialTicks)` | Vanilla orient uses the entity angles; roll only needs the handler. Third-person front view: same handler adds 180° yaw / negates pitch+roll when `thirdPersonView == 2`. |
| Per-frame `GameSettings.fov` write | `EntityViewRenderEvent.FOVModifier.setFOV(vFovDeg)` while armed | No settings mutation, nothing to restore. Formula spec §6.1. |
| `EntityEvent.Size` (0.2×0.2×0.1, eye 0.05) | AT `public net.minecraft.entity.Entity setSize(FF)V`; call `setSize(0.2f,0.1f)` in `PlayerTickEvent` END on **both sides** and again before the client physics step; `player.eyeHeight = 0.05f` (public Forge field); `player.stepHeight = 0`; restore `setSize(0.6f,1.8f)`, `getDefaultEyeHeight()`, `0.6f` on disarm | `updateSize()` runs after living update and before post-tick, so END re-shrinks every tick. |
| `abilities.isFlying` + `sendPlayerAbilities` | Client: `capabilities.isFlying = true`, `sendPlayerAbilities()` each armed tick. Server arm handler: `capabilities.allowFlying = true; isFlying = true; sendPlayerAbilities()`, restore on disarm | Server rejects `isFlying` without `allowFlying` and kicks "floating" players otherwise. |
| KeyBinding interceptor | `DroneMovementInput extends MovementInputFromOptions` installed into `mc.player.movementInput` (public); when armed it records raw key states for the flight controller and zeroes `moveForward/moveStrafe/jump/sneak` | Re-install on `EntityJoinWorldEvent` for the local player / identity check each client tick (vanilla recreates it on world load and respawn). Sneak is intercepted too. |
| Mouse deltas | `InputEvent.MouseTurnEvent` (cancel while armed) | §3. |
| `RenderPlayerEvent.Pre` | same (cancelable) | Draw `DroneRenderer` for armed players; skip own player in first person. |
| Hand / hotbar / crosshair / block overlay / outline | `RenderHandEvent` (cancel), `RenderGameOverlayEvent.Pre` ElementType `HOTBAR`/`CROSSHAIRS` (cancel), `RenderBlockOverlayEvent` `OverlayType.BLOCK` (cancel), `DrawBlockHighlightEvent` (cancel when outline off) | |
| Fisheye post pass (`RenderGameOverlayEvent.Pre(ALL)` FBO copy) | Vanilla post chain: `mc.entityRenderer.loadShader(new ResourceLocation("cleanfpv","shaders/post/fisheye.json"))`; program `cleanfpv:fisheye` under `assets/cleanfpv/shaders/program/`; vertex shader reuses vanilla `sobel`/`blit` semantics with our own source | Runs after world, before HUD (HUD undistorted for free); resize handled by vanilla. Re-assert per client tick because F4, `stopUseShader` and `loadEntityShader` reset it; call `stopUseShader()` on disarm. Uniform `FovY` (vertical FOV rad) set each frame — AT `public net.minecraft.client.shader.ShaderGroup listShaders` or set via `Shader.getShaderManager().getShaderUniform`. Fallback if the chain misbehaves: own `Framebuffer` + `ShaderManager` pass on `RenderGameOverlayEvent.Pre(ALL)` with full GL state snapshot/restore (see `../flexfov-clrm-port/.../render/Shader.java` for a Cleanroom GL example). |
| `RenderWorldLastEvent` | same | Prop discs, race gates. |
| `LivingUpdateEvent` physics step | `TickEvent.PlayerTickEvent` phase START, `side == CLIENT`, `player == mc.player` | Fires at the top of `EntityPlayer.onUpdate`, before vanilla movement. After the step set `motionX/Y/Z = 0` so vanilla `move()` is a no-op. |
| `RenderTickEvent` START realtime physics / joystick refresh | same (`TickEvent.RenderTickEvent`) | |
| `InitGuiEvent.Post` controls button, `DrawScreenEvent.Post` | `GuiScreenEvent.InitGuiEvent.Post` (`GuiControls`), `GuiScreenEvent.DrawScreenEvent.Post` | |
| `LoggedInEvent`/`LoggedOutEvent` | `FMLNetworkEvent.ClientConnectedToServerEvent` / `ClientDisconnectionFromServerEvent`, `WorldEvent.Unload` | Force disarm **with** full restore (fixes spec quirk). |
| `Entity.collide` (AT) | Own axis-by-axis sweep with `world.getCollisionBoxes(entity, aabb)` + `AxisAlignedBB.calculateXOffset/YOffset/ZOffset`, then `setPosition` | Public API; gives attempted vs actual for spec §5.6 without vanilla step-up/sneak logic. Noclip (spectator) bypasses. |
| `ActiveRenderInfo` / `getMaxZoom` (0.5 back-off) | AT `public net.minecraft.client.renderer.EntityRenderer thirdPersonDistance` and set 0.5 while armed (restore 4.0) | Optional polish. |
| `ModelRenderer.compile` / `Model.renderType` | Not needed: blades are drawn with `Tessellator` (twisted triangle strips) inside the renderer; boxes use `ModelRenderer` | |
| jME math | JOML | |
| `SimpleChannel` | `SimpleNetworkWrapper` | Registration order = discriminator ids (spec §8). |
| `TickableSound` | `MovingSound` subclass (vanilla `ElytraSound` pattern), `SoundEvents.ITEM_ELYTRA_FLYING`, `SoundCategory.PLAYERS` | Placeholder tone as spec §7. |
| Mojang name API | Vanilla player list only (`NetHandlerPlayClient.getPlayerInfo(uuid)`); no HTTP | Spec §8.4 endpoint is retired. |

Access transformer (`src/main/resources/cleanfpv_at.cfg`, MCP names) — expected entries:
`Entity.setSize(FF)V`, `ShaderGroup.listShaders`, `EntityRenderer.thirdPersonDistance`.
Add others only when an event substitute does not exist.

## 6. Subsystem designs

### 6.1 Input (spec §3)
- `JoystickService`: `GLFW.glfwSetJoystickCallback` for connect/disconnect (only one
  callback exists process-wide; install once, chain any previous). Poll
  `glfwGetJoystickAxes/Buttons` on `RenderTickEvent` END (main thread) into `float[8]` /
  `byte[24]`-style buffers sized from the device. Optional: if `glfwJoystickIsGamepad`, also
  expose `glfwGetGamepadState` for SDL-mapped defaults.
- `ChannelMap`: five axis indices, three switch indices (negative = virtual button on axis
  `axisCount + n` with > 0.1 threshold), invert flags for all eight (all persisted — fixes spec
  §10 bug), per-axis `[min,max]` calibration with the spec's normalisation and degenerate-range
  fallback (persist all axes, not just 4).
- Two default schemes (radio / gamepad) exactly as the spec §3.1 table.
- Keyboard (`DroneMovementInput`): A/D yaw ∓0.5, Space throttle 1.0, W 0.5, S −1 (spec §3.2);
  Arm = I, settings = O via `KeyBinding` + `ClientRegistry.registerKeyBinding`; ignored while a
  GUI is open. Arm key is **edge-triggered toggle** (fixes spec quirk of level-trigger); switch
  arm is level-triggered with 200 ms debounce; both honour the throttle-low guard.
- Right-click switch → `KeyBinding.setKeyBindState/onTick` on `keyBindUseItem`.
- Mouse: `MouseTurnEvent` yaw → roll stick delta, pitch → pitch stick delta, scaled by
  `sensitivity × 0.007 rad`. Deltas are displacements, so they are **not** multiplied by dt
  (vanilla semantics); dt only scales stick rates.

### 6.2 Flight controller (spec §4)
- `ArmController` FSM `DISARMED → ARMED` (+ skip-first-step flag). Arm: init body axes from
  look so the *camera* matches (`quat = fromAngles(pitch + tilt, −yaw, 0)`), start sound,
  install input override, shrink size, send Arm + Build packets, `setRoll(0)`. Disarm: restore
  everything, `motion = v × 0.05`, `setRoll(0)`, `stopUseShader`. Re-arming while armed is a
  no-op (no double sound).
- `Rates`: spec §4.2 formula, `RateTriple {rate, super, expo}` with setter validation; defaults
  per scheme.
- `Attitude`: per-frame body-axis quaternion rotations yaw(−)/pitch/roll, renormalise, rebuild
  `right = up × forward` (spec §4.3). 3D-mode throttle mapping (spec §4.4).
- `CameraRig`: tilt = `activateAngle ? round(lerp((knob+1)/2, 2, 16)) × 5 : switchlessAngle`;
  camera basis = body basis rotated −tilt about `right`; decompose to Minecraft's
  `Rz(roll)·Rx(pitch)·Ry(yaw+180)` Euler convention; write yaw/pitch (both current and prev) and
  `roll/prevRoll` to the player. Player yaw/pitch therefore equal the **camera** angles; the
  body quaternion travels in the transform packet. Spectating an armed remote player: apply
  interpolated remote quaternion + fixed 30° tilt the same way.

### 6.3 Physics (spec §5)
- `DroneState` as spec §5 (velocity, ω[4] rad/s, heat[4], T[4], axes, lastPos, timestamps).
- `Stepper`: tick mode on `PlayerTickEvent` START with `dt = 0.05` split into ≤1/128 s
  substeps; realtime mode on `RenderTickEvent` START with `getFrameDeltaSeconds()` clamped to
  0.1 s. Both pause while `mc.currentScreen != null`; `DrawScreenEvent.Post` refreshes the
  timestamp. While disarmed track `v = (pos − lastPos)/dt` so arming inherits velocity.
- Translation step: spec §5.2 verbatim (gravity, drag disk, sag, four motors, 500 m/s clamp).
  Multiplayer: the only speed cap is the spec's 500 m/s (25 blocks/tick) hard clamp, exposed
  as server config `maxSpeed` (default 500 m/s, sent to clients on join and enforced
  client-side). Vanilla's "moved too quickly" check is neutralised by the server-side motion
  echo (§6.6, §8) rather than by lowering the cap.
- **Physics v1** (Phase 3): thrust along body-up = `Σ` first-order-lag motor thrust from a
  simple T = k·ω² fit, linear+quadratic drag, mass slider. Same interfaces as v2 so the
  fidelity flag (`highFidelity`) swaps implementations.
- **Physics v2** (Phase 6): `BladeElement` (5 segments, hub skip, chord ramp, AoA, spec §5.3
  lift/drag curves), `MotorOde` RK4 with no-load clamp and NaN guards, `Battery` sag, winding
  resistance from the magnetostatic turn estimate, `Thermal` Newtonian cooling (spec §5.4).
  Overheat warning driven by live per-motor T (fixes dead code). Mass model with the unit bugs
  fixed (arms, props, split-cam counted in grams).
- `DroneBuild` (spec §5.5 table, ranges, two presets) as a plain record + Gson; wire DTO omits
  `motorKv`/`propPitch`.
- `Sweep` + `Bounce` (spec §5.6: θ, reflected point, `e = sinθ·0.65 + (1−sinθ)·0.2`, stop
  below 1 m/s). `Bounce` is pure math → unit test.

### 6.4 Camera and rendering (spec §6)
- `CameraHooks`: `CameraSetup` (roll add, front-view flip), `FOVModifier` (diagonal→vertical).
- `Fisheye`: `assets/cleanfpv/shaders/post/fisheye.json` (one pass, `minecraft:main` in/out) +
  `assets/cleanfpv/shaders/program/fisheye.{json,vsh,fsh}` written from the spec §6.2 mapping
  (equidistant fisheye: `image`, `angle`, `scale`, `stretch`, black outside [0,1]); uniforms
  `FovY`, `Aspect`. Toggle via `loadShader`/`stopUseShader`; re-assert each client tick.
- `DroneModel`/`DroneRenderer`: procedural boxes from `DroneBuild` at 32 px per metre scaled to
  1 m = 1 block (plates, standoffs 4/8, stack, battery placement, antenna, cams, arms at
  45°+n·90°, bells, hubs, `nBlades` twisted-strip blades). Cached per UUID; invalidated on
  build packet, remote disarm, local settings save **and local camera-angle change**.
- `PropDiscs`: per-motor angle integrated **once** per frame per drone from that drone's ω
  (remote ω from transform packets — fixes both spec §13.1 prop bugs); blades hidden and
  translucent disc quad (texture by blade count 2–5, own art) shown at |ω| ≥ 30 rad/s;
  `RenderWorldLastEvent`, all cached drones, own drone skipped in first person.
- `Hud`: stick overlay (spec §6.4 geometry), optional crosshair passthrough, `angle: N` and
  `Overheating…` lines, race HUD on the right.

### 6.5 Audio (spec §7)
`MotorSound extends MovingSound`: `r = |ω0|/ωmax`, pitch `0.5+1.5r`, volume `0.25+0.75r`,
repeat, stops on disarm/removal; always started on arm (no GUI condition).

### 6.6 Networking (spec §8)
- Channel `cleanfpv:main`, protocol string in packet 0 handshake optional. Discriminators in
  spec §8.1 order. All handlers `addScheduledTask`.
- Client→server: Arm(bool), Build(DTO), Transform(quat, velocity xyz, ω[4], epochMs) every
  armed tick. Velocity (3 floats, m/s) is an addition to the spec §8.1 #10 layout: the server
  needs it for the motion echo below, and remote clients can use it for position extrapolation.
- Server handler: validate (armed registry, finite floats, ω ≤ no-load, |velocity| ≤
  `maxSpeed`), apply `allowFlying/isFlying`, size/stepHeight, `player.setRoll(rollFromQuat)`,
  store in `ArmRegistry`, relay with `sendToAllTracking(msg, player)` (sender excluded by UUID
  check client-side as the spec). Works identically on integrated servers.
- **Motion echo**: the handler also writes the packet velocity (m/s → blocks/tick, ÷20) into
  the server player's `motionX/Y/Z` every armed tick. `NetHandlerPlayServer.processPlayer`
  compares the claimed displacement² against the server player's motion² plus a 100 blocks²
  allowance per packet, so echoing the velocity makes the check pass at any speed up to
  `maxSpeed` without patching the loader. Vanilla's own `move()` on the server player also
  consumes that motion, so the echo is written after the server tick's movement (in
  `PlayerTickEvent` END, server side) and cleared on disarm.
- Race packets S2C only, sent by the server race module (§6.8).
- `RemoteDrones`: last two states per UUID, axis-angle delta with fresh vectors (fixes shared
  static bug), extrapolate with sender clock, 20 % smoothing per **frame** (guard against the
  double call by caching per frame), motor angle integration.

### 6.7 Settings and GUI (spec §10–11)
- `config/cleanfpv/settings.json` (Gson): `{ models: {name: Model}, currentModel, firstTimeSetup }`
  with the spec's per-model keys plus the missing invert flags and full calibration. Built-in
  presets: build keys ignored on load; delete-protected (their controller settings reset instead).
  Corrupt entry → dropped with a log line and defaults; corrupt file → backed up and recreated.
- Screens as spec §11 (wizard steps, model home, choose model, channel mapping, rates with
  `RateChart`, drone build, other). Vanilla `GuiScreen` + own `GuiListExtended` rows; button
  injected into `GuiControls` at `(w/2+159, 18)`. "New Preset" clones the **current** model.

### 6.8 Race (spec §9), server-authoritative
- Server module: track/gate definitions (JSON in world folder), per-tick gate-crossing test
  (segment of player movement vs gate voxel volume), lap timing, leaderboard by **best** lap,
  commands to create tracks/gates and toggle race mode. Sends packets 1–5, 8, 9.
- Client: state store as spec (instance, cleared on disconnect), wireframe gates, direction
  ring with inverse-fisheye mapping, leaderboard HUD, `h:m:s:cc` formatting.

## 7. Frame and tick sequencing

Per rendered frame (`Minecraft.runGameLoop`): `beginFrame()` → `RenderTickEvent START`
(joystick already polled last frame; realtime physics step if enabled) → 0..N client ticks →
`updateCameraAndRender` → `MouseTurnEvent` (armed: capture mouse deltas, **run the attitude
frame step**, cancel) → `FOVModifier` → `CameraSetup` (roll add) → world render → post shader →
HUD (`RenderGameOverlayEvent`) → `RenderTickEvent END` (poll joystick; if the frame step did not
run because the window was unfocused, run it with zero mouse input). A frame-sequence counter
guarantees the attitude step runs at most once per frame.

Per client tick (`PlayerTickEvent` START, local): re-shrink size, physics tick step
(tick mode), zero vanilla motion, send Transform packet, re-assert post shader. `PlayerTickEvent`
END (both sides): size/eye/step/abilities re-assert for armed players.

Ordering rule: the attitude quaternion is only mutated in the frame step; physics reads it.
Yaw/pitch/roll on the player are outputs, never inputs (except at arm time).

## 8. Server compatibility constraints
- `allowFlying` must be granted server-side for armed players (floating kick).
- "moved too quickly": defeated by the server-side motion echo (§6.6), so the effective cap
  is the `maxSpeed` config (default 500 m/s = 25 blocks/tick, the spec's hard clamp). Note the
  check runs per packet, so a client that bursts several movement packets in one tick still
  needs the echo to be current. "moved wrongly": server hitbox must match the client's (size
  re-assert on server) and `stepHeight` must be 0 on both sides.
- Spectator/creative interplay: `noClip` bypasses the sweep; `capabilities.isFlying` stays
  true while armed.
- Fake players and non-armed players are ignored by the relay.

## 9. Phases (each ends with a runnable client)

| Phase | Scope | Done when |
|---|---|---|
| 0 Toolchain | Switch to CleanroomGradle userdev from the Barrel Roll port; rename identity; `runClient` boots on the local Cleanroom build; empty `cleanfpv_at.cfg`; JUnit wired | `./gradlew build runClient` works, `MouseTurnEvent` compiles |
| 1 Skeleton | Mod class, proxies, settings store + defaults + presets, keybinds, controls button, minimal settings screen | Settings round-trip on disk |
| 2 Input | JoystickService, ChannelMap, calibration, DroneMovementInput, stick overlay | Overlay shows live sticks from a controller and keyboard |
| 3 Flight feel | Arm FSM, rates, attitude, CameraRig, roll via loader, FOVModifier, vanilla hides, physics v1 | Flies acro with mouse+keys and with a radio; camera roll correct in first and third person |
| 4 Collisions & sizing | Sweep, bounce, size/eye/step handling both sides, abilities | Passes through 1-block gaps on a LAN server without rubber-banding |
| 5 Multiplayer | Packets, server handlers/relay, remote drone render + interpolation, spectator camera, roll sync | Two clients (`runClient` + `client2`) see each other's drones smoothly |
| 6 Physics v2 | Blade element, motor RK4, battery, thermal, mass model, overheat OSD, `highFidelity` flag | Unit tests pass; default 5" build hovers near 50 % throttle |
| 7 Render/audio polish | Fisheye post, procedural drone model, prop discs, motor sound | Screenshot review; HUD undistorted |
| 8 GUI | Wizard, remaining settings screens, rate chart | First-run flow completes with a gamepad |
| 9 Racing | Server race module, packets, gate/arrow/leaderboard rendering | Lap recorded on a LAN race |

## 10. Decisions on the original's quirks (spec §13.1)
Enforce arming throttle guard · overheat warning live · mass unit bugs fixed · induced inflow
still omitted (documented) · remote prop spin fixed · relay always server-side · all invert
flags persisted · clicks while armed go to the use-item binding (no buffering) · arm key
toggles on press · no per-frame logging/exception spam (switch index validated at load) · no
HTTP name lookup · leaderboard keeps best lap · own renderer refreshed on camera-angle change ·
disconnect restores FOV/keys/size · dead classes not recreated.

## 11. Verification
- `./gradlew build test` (math unit tests: rates curve values at the spec defaults, bounce
  head-on 0.2 / glancing 0.65, quaternion round-trip to Minecraft Euler, RK4 no-load clamp).
- `./gradlew runClient`: arm with I, fly with mouse+WASD; check roll in third person, FOV,
  fisheye toggle, HUD hidden/undistorted, hitbox through a 1-block gap.
- Controller: plug a gamepad, run the wizard, verify axes/inverts/calibration persist across
  restarts.
- `./gradlew runClient` + `client2` over Open to LAN (and `runServer` dedicated): remote drone
  visible with roll and prop spin, spectator camera on the other pilot, no "moved wrongly" logs.
- `F4`/respawn/dimension change do not leave the shader or size state stuck.

## 12. Risks and open questions
- PR #641 is unmerged: API names may shift; isolate uses in `flight/CameraRig` and
  `input/MouseInput` so a rename is a two-file change. Track the local publish version in
  `gradle.properties`.
- `setSize` re-assert timing vs other mods that also resize players.
- Vanilla post chain edge cases (`fboEnable` off, `shadersSupported` false): fall back to no
  fisheye with a log line.
- GLFW joystick callback is process-global; if another mod registers one, chain it.
- The motion echo relies on `NetHandlerPlayServer` subtracting the server player's motion²
  before the "moved too quickly" comparison; verify against the Cleanroom source at
  `Cleanroom/module/minecraft/src/main/java/net/minecraft/network/NetHandlerPlayServer.java`
  (~line 505) when implementing Phase 5, and add a LAN test at `maxSpeed`. Anti-cheat plugins
  on third-party servers may still reject high speeds; that is out of scope.
