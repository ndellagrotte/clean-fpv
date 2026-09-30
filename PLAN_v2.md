# Clean FPV — Architecture Plan v2

Clean-room FPV quadcopter simulator mod for **Cleanroom (Minecraft 1.12.2)**, re-implemented from
the behavioural spec in `../minecraft_fpv_port/FPV_DRONE_MOD_DESIGN.md` ("the spec"). v2 supersedes
`PLAN_v1.md` after the adversarial review `PLAN_v1_REVIEW_1.md`; §14 records what changed and why.
Formulas and constants are referenced by spec section rather than repeated. Source citations are
against `../Cleanroom` (generated sources under `module/minecraft/src/main/java`, Forge under
`module/forge/src/main/java`, Cleanroom under `module/cleanroom/src/main/java`).

## 1. Ground rules

- **Clean room.** Implement the spec's behaviour; never copy decompiled source, GLSL, textures, lang
  files, the original mod id/name, or any URL/key from the original. Identity: mod id `cleanfpv`,
  name "Clean FPV", package `io.github.ndellagrotte.cleanfpv`. The spec's empirical curve fits
  (§5.3 lift/drag) are marked tunable there; the mod uses its own curve shapes, matched to the
  spec's documented values (lift ≈ 1.0 at the ≈ 10.6° boost peak, drag 0.4 … 1.8), rather than
  copying them. Flight feel is then checked against the spec's physics evaluated as written:
  5" 4S (613 g) hovers at ≈ 32 % throttle, has thrust-to-weight ≈ 5, climbs ≈ 47 m/s, and reaches
  ≈ 51 m/s level. The Tiny Whoop (110 g) hovers at ≈ 57 %, has T/W ≈ 2.5, climbs ≈ 22 m/s, and
  reaches ≈ 27 m/s level. No global thrust rescale.
- **No mixins.** Rotation/input primitives come from the loader (Cleanroom PR #641, now identical
  on `origin/main`); everything else is Forge events, Cleanroom's public SDL API, and a small
  access transformer (§5).
- **No bundled libraries.** JOML (Cleanroom `api`), Gson (Minecraft), SDL3 via Cleanroom's
  `com.cleanroommc.client.sdl` (LWJGL 3.4.3 `lwjgl-sdl` is on the classpath; there is **no**
  `lwjgl-glfw`, and LWJGLY's `org.lwjgl.input.Controllers` is a jinput shim we do not use). No
  crash reporter, update JSON or remote services; only the mod channel is network-facing.
- **Fix, don't replicate, the original's dead code and bugs** (spec §13.1); §10 lists decisions.
- Java 25 toolchain, MCP names in source and AT.

## 2. Platform and toolchain

| Item | Decision |
|---|---|
| Loader | Cleanroom `main` (99caa8a7 or later). The roll axis, `MouseTurnEvent` and frame clock are on `origin/main`; the API files are byte-identical to `feat/rotation-input-primitives`, so no branch dependency remains. Until a release exists, depend on the local publish `com.cleanroommc:cleanroom-userdev:0.0.1-dev.1158.local.dirty` (built from main; re-check the version string after any re-publish). |
| Build | CleanroomGradle userdev copied from `../barrel-roll-clrm-port`: `settings.gradle` with `com.cleanroommc.cleanroomgradle.settings 0.17.5` + foojay; `build.gradle` with `implementation cleanroom.userdev(cleanroom_version) { accessTransformers.from(...) }`, icu4j/patchy excludes, `org.lwjgl.lwjgl` (LWJGL 2) runtime exclude, the `RunMinecraft` mods-dir dev-jar launch workaround, `reobfJar`, `client2` run. `gradle.properties`: `cleanroom_version`, `cg.repos.enableLocal = true`, `java_package`, identity fields, `use_access_transformer = true`. Remove the Unimined plugin, `enable_lwjglx`, shadow. |
| Math | JOML `Vector3f/Vector3d/Quaternionf` (`org.joml:joml:1.10.9`). |
| Input | Joysticks: `com.cleanroommc.client.sdl.SDL.joysticks()` (raw SDL3 joystick API, lazily initialises `SDL_INIT_JOYSTICK`). Keyboard: vanilla `KeyBinding`. Mouse: `InputEvent.MouseTurnEvent`. |
| Rendering | `GlStateManager` + `Tessellator/BufferBuilder`; post-processing through vanilla `ShaderGroup` JSON. |
| Networking | `SimpleNetworkWrapper` (`cleanfpv:main`), `IMessage`/`IMessageHandler`, handlers hop to the main thread via `addScheduledTask`. |
| Config | Gson JSON under `config/cleanfpv/`. |
| Tests | JUnit 5 for pure math (rates, quaternion integration, Euler decomposition, motor ODE, bounce, sweep against a synthetic AABB set). |

## 3. Loader primitives (PR #641 lineage, on main) and how the mod uses them

| Primitive | Used for | Notes |
|---|---|---|
| `Entity.roll/prevRoll`, `getRoll(pt)`, `setRoll(f)` | Local camera roll, remote players' roll | Written **per rendered frame**; advance `roll` and `prevRoll` together so `getRoll(pt)` returns the frame value. `setRoll` (wraps, validates, syncs `ROLL` server-side only) is used on arm/disarm and by the server handler. |
| `DataParameter<Float> Entity.ROLL` (id 254) | Free server→client roll for remote players / vanilla spectator | Client authoritative for its own roll (`EntityPlayerSP.onRollSynced` no-op). The mod still sends its quaternion (spec §8.1 #10): the server→tracker path byte-quantises yaw/pitch (`EntityTrackerEntry`), and roll alone does not define the body frame. |
| `InputEvent.MouseTurnEvent` (`net.minecraftforge.client.event`, `@Cancelable`) | Mouse pitch/roll while armed | Fires once per focused frame from both `EntityRenderer` turn call sites, including zero-delta frames. `getYaw()/getPitch()` are exactly what vanilla passes to `Entity.turn`: raw counts × `(sens·0.6+0.2)³·8`, invert sign applied to pitch, smooth-camera filtered. `turn()` then × 0.15 → degrees. **Event unit = degrees / 0.15.** Cancel while armed. |
| `ForgeHooksClient.beginFrame()/getFrameDeltaSeconds()`, `MouseTurnEvent.getFrameDeltaSeconds()` | Frame `dt` | Already clamped to [0, 0.1] s by the loader (first frame 1/60). The mod does not re-clamp. |

What the loader does **not** do, and the mod's answer:
- Camera does not read entity roll → the mod's `CameraSetup` handler supplies it (§6.4).
- Player model does not read roll → irrelevant; armed players are drawn as drones.
- Roll is not sent client→server → the Transform packet handler applies it server-side.

## 4. Architecture

```
CLIENT
 input/    JoystickService (Cleanroom SDL Joystick poll per frame; hotplug via SDL.events())
           ChannelMap + Calibration → StickState {thr,roll,pitch,yaw,angle,arm,angleSw,rclick}
           DroneMovementInput (replaces EntityPlayerSP.movementInput; zero vanilla move, expose raw keys)
           MouseInput (MouseTurnEvent → per-frame pitch/roll radians)
 flight/   ArmController (FSM) · Rates (BF curves) · Attitude (quat, body axes) · CameraRig (tilt, camera quat, Euler)
 physics/  DroneState · Stepper (tick 1/128 s substeps | realtime) · v1 ThrustModel · v2 BladeElement/MotorOde/Battery/Thermal
           Sweep (Y→X→Z, World.getCollisionBoxes) · Bounce · MassModel/DragModel
 render/   CameraHooks (CameraSetup override, FOVModifier) · Fisheye (ShaderGroup, 2 passes) · DroneRenderer/DroneModel
           PropDiscs (RenderWorldLast) · Hud · HideVanilla
 audio/    MotorSound (MovingSound)
 gui/      Wizard · Settings screens · widgets · Controls-screen button
 config/   Settings (Gson) · DroneBuild + presets · ModelStore
 net/      Channel · packets · RemoteDrones (history + interpolation) · ServerInfo (Hello)
 race/     client state/HUD/gate render; server authority module
COMMON/SERVER
 ArmRegistry (UUID→ArmedState, both sides) · server handlers (validate, abilities/size/roll, motion echo, relay) · lifecycle cleanup
```

Kept from spec §2: player entity *is* the drone; client-authoritative simulation; kinematic attitude
+ dynamic translation; hybrid timing; UUID-keyed registries. Changed: relay in the server handler
(works on integrated servers); race authority server-side; state cleared on world unload; a
server→client **Hello** handshake gates arming (§6.6).

Package layout under `io.github.ndellagrotte.cleanfpv`: `CleanFpv` (mod class, `@SidedProxy`),
`client/{input,flight,physics,render,audio,gui,hud}`, `common/{config,net,race}`, `server/`.
Physics/flight math has no Minecraft imports (unit-testable).

## 5. Hook map (spec → Cleanroom 1.12.2, no mixins)

| Spec hook / need | Substitute | Notes |
|---|---|---|
| Camera orientation | Per frame write `rotationYaw/prevRotationYaw` = **body** yaw, `rotationPitch/prevRotationPitch` = **camera** pitch (spec §4.3), `roll/prevRoll` = camera roll. `EntityViewRenderEvent.CameraSetup`: when the render-view entity is an armed drone (local live, or remote interpolated), **override** `setYaw/setPitch/setRoll` with the camera Euler triple (§6.2) instead of adding. | `orientCamera` (EntityRenderer.java:522-607) applies `Rz(roll)·Rx(pitch)·Ry(yaw)` after the event with default yaw = entity yaw + 180. Front view: vanilla already applies `Ry(180)` before the event (line 573) and collapses its X-sandwich to a translation, which conjugates the event angles to `Rz(−roll)·Rx(−pitch)·Ry(yaw)` — i.e. vanilla already "adds 180° yaw and negates pitch/roll". **The handler does nothing special for `thirdPersonView == 2`.** Vanilla hurt shake (`hurtCameraEffect`, lines 482-502) composes on top; kept (it is a damage cue; no event exists to cancel it). Death roll is moot because death disarms (§6.9). |
| Per-frame FOV | `EntityViewRenderEvent.FOVModifier.setFOV(vFovDeg)` while armed (spec §6.1 formula) | No settings mutation. |
| Entity size (0.2×0.2×0.1, eye 0.05) | AT `public net.minecraft.entity.Entity setSize(FF)V`; `setSize(0.2f,0.1f)` in `PlayerTickEvent` END on **both sides** and before the client physics step; `player.eyeHeight = 0.05f`; `stepHeight = 0`; restore `0.6×1.8`, `getDefaultEyeHeight()`, `0.6f` on disarm | `updateSize()` runs before END, so END re-shrinks each tick. |
| Abilities | Client: `capabilities.isFlying = true` each armed tick. Server arm handler: `allowFlying = true; isFlying = true; sendPlayerAbilities()`; restore on disarm | Floating kick is armed only when `!allowFlying` (NetHandlerPlayServer.java:561-569). |
| Movement keys | `DroneMovementInput extends MovementInputFromOptions` installed into `mc.player.movementInput`; armed: record raw keys, zero `moveForward/moveStrafe/jump/sneak` | Re-install on `EntityJoinWorldEvent` for a new `EntityPlayerSP` and identity-check each client tick. |
| Mouse | `InputEvent.MouseTurnEvent` (cancel while armed) | §3, §6.1. |
| Joystick | `SDL.joysticks()` / `Joystick.axis(i, 0f)` / `button(i)` / `hat(i)` polled on the main thread; `SDL.events()` bus for `JoystickEvent.Added/Removed` | §6.1. |
| `RenderPlayerEvent.Pre` | same (cancelable) | Drone renderer for armed players; skip own player in first person. |
| Hand/hotbar/crosshair/overlays | `RenderHandEvent`, `RenderGameOverlayEvent.Pre` (`HOTBAR`, `CROSSHAIRS`), `RenderBlockOverlayEvent`, `DrawBlockHighlightEvent` | |
| Fisheye | Vanilla post chain via `EntityRenderer.loadShader(cleanfpv:shaders/post/fisheye.json)` | §6.4. Runs after world, before HUD (EntityRenderer.java:968-988). |
| `RenderWorldLastEvent` | same | Prop discs, gates, ring. |
| Physics step | `TickEvent.PlayerTickEvent` START, `side == CLIENT`, `player == mc.player`; afterwards `motionX/Y/Z = 0` | START fires at the top of `EntityPlayer.onUpdate`. |
| Realtime physics / joystick refresh | `TickEvent.RenderTickEvent` START / END | |
| Controls button / draw hooks | `GuiScreenEvent.InitGuiEvent.Post` (`GuiControls`), `GuiScreenEvent.DrawScreenEvent.Post` | Button at `(w/2+160, h−29)`, 20×20: the bottom row `Reset All`/`Done` spans `w/2−155..w/2+155` (GuiControls.java:36-38) and the option rows fill y 18..62; the list spans `w/2−102..w/2+158` plus a scrollbar at ~`w/2+181`. Clips only below 360 scaled px; the `O` key always opens settings. |
| Lifecycle | Client: `FMLNetworkEvent.ClientDisconnectionFromServerEvent` (hop to client thread), `WorldEvent.Unload`, `LivingDeathEvent` for `mc.player`, new `EntityPlayerSP` in `EntityJoinWorldEvent`. Server: FML `PlayerEvent.PlayerLoggedOutEvent`, `PlayerRespawnEvent`, `PlayerChangedDimensionEvent`, `LivingDeathEvent` for `EntityPlayerMP` | All force disarm **with** full restore (§6.9). |
| Collision | Own `Sweep`: axis order **Y → X → Z** like `Entity.move` (Entity.java:695-727), `world.getCollisionBoxes(entity, aabb)` + `calculateY/X/ZOffset`, then `setPosition` | Matches the server's replay in `processPlayer`. Noclip bypasses. |
| Third-person 0.5 back-off | Optional polish, Phase 7: in `CameraSetup`, recompute vanilla's clamped `d3` (same 8-ray trace, EntityRenderer.java:540-570) and `GlStateManager.translate(0,0,δ)` with δ = `d3−0.5` (back view) or `0.5−d3` (front view, because the collapsed sandwich makes the translation `+d3`) | `EntityRenderer.thirdPersonDistance` is a dead `private final` field; the lerp uses the literal `4.0F` (line 540). No AT. |
| jME math / `SimpleChannel` / `TickableSound` / Mojang API | JOML / `SimpleNetworkWrapper` / `MovingSound` / player list only | |

Access transformer (`src/main/resources/cleanfpv_at.cfg`): `Entity.setSize(FF)V` (public),
`ShaderGroup.listShaders` (public, drop `final` not needed). Nothing else unless an event substitute
is missing.

## 6. Subsystem designs

### 6.1 Input (spec §3)
- `JoystickService` (client, main thread only):
  - Before the first `SDL.joysticks()` call: `SDL.hint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, "1")`
    so a radio keeps reporting while the window is unfocused (hints are ignored once the subsystem
    is up). First `SDL.joysticks().list()` initialises `SDL_INIT_JOYSTICK` and opens connected
    sticks; the window pump (`Pump.java`) drives `SDL_PollEvent` each frame, which refreshes device
    state, so **polling works even though axis/button events are dropped by the pump**.
  - Poll on `RenderTickEvent` END: `axis(i, 0f)` (deadzone 0 — the default 0.2 would eat stick
    resolution; calibration is ours), `button(i)`, `hat(i)` into `float[axes]`/`boolean[buttons]`.
  - Hotplug: register a listener on `SDL.events()` (an FML `EventBus`, not Forge's) for
    `JoystickEvent.Added/Removed`; device identity persisted as `guid()` + `name()`; reselect by
    GUID on reconnect, else first stick.
  - Optional: if `SDL.gamepads()` recognises the same device, pre-fill the gamepad scheme from
    `Gamepad.axis(GamepadAxis)`; radios are unmapped joysticks and use raw indices.
- `ChannelMap`: five axis indices, three switch indices (negative = virtual button on axis
  `axisCount + n`, > 0.1 threshold), invert flags for all eight (persisted — fixes spec §10), per-axis
  `[min,max]` calibration with the spec's normalisation and degenerate-range fallback (persist all axes).
- Two default schemes (radio / gamepad) as the spec §3.1 table.
- Keyboard (`DroneMovementInput`): A/D yaw ∓0.5, Space throttle 1.0, W 0.5, S −1 (spec §3.2); Arm = I,
  Settings = O via `KeyBinding`; ignored while a GUI is open. Arm key is an edge-triggered toggle;
  switch arm is level-triggered with 200 ms debounce; both honour the throttle-low guard.
- Right-click switch → `KeyBinding.setKeyBindState/onTick` on `keyBindUseItem`.
- Mouse (`MouseInput`): per frame, `rollRad = yawEvt × 0.15 × π/180 × mouseGain`,
  `pitchRad = pitchEvt × 0.15 × π/180 × mouseGain`, added **directly** to that frame's roll/pitch
  rotation angles (spec §3.2/§4.3: displacement, not rate; not multiplied by dt; unbounded).
  Vanilla sensitivity and invert therefore apply (the event is post-scaling); `mouseGain` (per model,
  default 1.0) is the mod's own multiplier. At vanilla sensitivity 0.5 the event equals raw counts,
  so the original's `0.007 rad/count` feel ≈ `mouseGain 2.7`; noted in the settings tooltip.
  Sign: positive event pitch = look up.

### 6.2 Flight controller (spec §4)
- `ArmController` FSM `DISARMED → ARMED` (+ skip-first-step). Arm requires a Hello from the server
  (§6.6). Arm: init body axes from look (`quat = fromAngles(pitch + tilt, −yaw, 0)`), start sound,
  install input override, shrink size, send Arm + Build packets, `setRoll(0)`. Disarm: restore all,
  `motion = v × 0.05`, `setRoll(0)`, `stopUseShader()`. Re-arm while armed is a no-op.
- `Rates`: spec §4.2, `RateTriple {rate, super, expo}`, defaults per scheme.
- `Attitude`: per frame, body-axis rotations in spec order yaw(−) about up, pitch about right, roll
  about forward; angles = stick rate × dt + mouse radians; renormalise, `right = up × forward`.
  3D-mode throttle mapping (spec §4.4). Stick pitch/roll are **not** negated in front view (§10).
- `CameraRig`: tilt = `activateAngle ? round(lerp((knob+1)/2, 2, 16)) × 5 : switchlessAngle`; camera
  basis = body basis rotated −tilt about `right`; decompose the camera basis into Minecraft's
  `Rz(roll)·Rx(pitch)·Ry(yaw+180)` convention (unit-tested round trip). Write body yaw and camera
  pitch to the player (current and prev), camera roll to `roll/prevRoll`; hand the full camera triple
  to `CameraHooks` for the `CameraSetup` override. Spectating an armed remote player uses the
  interpolated remote quaternion + fixed 30° tilt through the **same** override path (no separate roll
  add, so no double count).

### 6.3 Physics (spec §5)
- `DroneState` as spec §5. `Stepper`: tick mode on `PlayerTickEvent` START with `dt = 0.05` in ≤1/128 s
  substeps; realtime mode on `RenderTickEvent` START with `getFrameDeltaSeconds()`. Both pause while
  `mc.currentScreen != null`; `DrawScreenEvent.Post` refreshes the timestamp. While disarmed track
  `v = (pos − lastPos)/dt` so arming inherits velocity.
- Translation step: spec §5.2 (gravity, drag disk, sag, four motors, 500 m/s clamp). The server's
  `maxSpeed` (default 500 m/s) arrives in Hello and is enforced client-side.
- **Physics v1** (Phase 3): thrust = Σ first-order-lag `T = k·ω²`, linear+quadratic drag, mass slider;
  same interfaces as v2 (`highFidelity` flag swaps implementations).
- **Physics v2** (Phase 6): `BladeElement`, `MotorOde` RK4 with no-load clamp + NaN guards, `Battery`
  sag, `Thermal`; overheat OSD from live T; mass model as the spec's effective mass (§10).
- `DroneBuild` (spec §5.5) as a plain record + Gson. **Wire DTO carries all fields including
  `motorKv` and `propPitch`** (the server needs Kv for ω validation; 22 fields).
- `Sweep` (Y→X→Z) + `Bounce` (spec §5.6; pure math, unit-tested). **Tick-consistency rule:** after
  the last substep of a tick, sweep the straight line `tickStart → tickEnd`; if the horizontal result
  differs from `tickEnd` by > 0.2 blocks, snap to the swept point and zero the clipped velocity
  component. Reason: the server replays `lastGood → claimed` through `player.move` and flags "moved
  wrongly" when the horizontal error² > 0.0625 (NetHandlerPlayServer.java:526-544; the vertical
  term is always zeroed by the always-true test at line 531), teleporting back when the start was
  clear. Substep bounces can otherwise cut corners the straight replay cannot.

### 6.4 Camera and rendering (spec §6)
- `CameraHooks`: `CameraSetup` override (§5), `FOVModifier` (diagonal → vertical, spec §6.1).
- `Fisheye`: `assets/cleanfpv/shaders/post/fisheye.json` with `"targets": ["swap"]` and **two
  passes**: `cleanfpv:fisheye` `minecraft:main → swap`, then `cleanfpv:blit` `swap → minecraft:main`
  (the vanilla `blur.json` shape). Reason: `Shader.render` clears and binds the out-target before
  drawing the quad sampled from the in-target (Shader.java:81-82, Framebuffer.java:219-230), so
  `main → main` wipes its own input. Program JSON under `assets/cleanfpv/shaders/program/`: sampler
  must be named `DiffuseSampler` (Shader.java:67); vertex shader declares `Position`, `ProjMat`,
  `InSize`, `OutSize`; fragment implements the spec §6.2 equidistant mapping with uniforms `FovY`
  (rad) and `Aspect`. JSON `uniforms` are applied once at parse time, so per-frame values are set on
  `RenderWorldLastEvent` via AT `ShaderGroup.listShaders` → `Shader.getShaderManager()
  .getShaderUniform("FovY")`. Own blit program (own source), or vanilla `minecraft:blit` if its
  `ColorModulate` default suffices.
  - Enable when armed **and** `thirdPersonView == 0` (own drone) or spectating an armed pilot in first
    person; call `loadShader` only when `getShaderGroup() == null` (a blind call recreates **and leaks**
    the previous group: EntityRenderer.java:247-261); `stopUseShader()` on disarm and on perspective
    change. Vanilla F4 flips a private `useShader` flag (Minecraft.java:1690) with no getter: document
    "F4 toggles the post effect; press again". Fallback if `!OpenGlHelper.shadersSupported` or the
    chain fails to load: no fisheye + one log line.
- `DroneModel`/`DroneRenderer`, `PropDiscs`, `Hud`: as v1 (procedural boxes at 32 px/m, per-UUID cache
  invalidated on build packet / remote disarm / local save / local camera-angle change; prop angles
  integrated once per frame per drone from that drone's ω; discs at |ω| ≥ 30 rad/s; stick overlay,
  `angle: N`, `Overheating…`, race HUD).

### 6.5 Audio (spec §7)
`MotorSound extends MovingSound` (elytra-sound pattern), `SoundEvents.ITEM_ELYTRA_FLYING`,
`SoundCategory.PLAYERS`; `r = |ω0|/ωmax`, pitch `0.5+1.5r`, volume `0.25+0.75r`; starts on arm
regardless of GUI; stops on disarm/removal.

### 6.6 Networking (spec §8)
- Channel `cleanfpv:main`. Discriminators: **0 Hello (S2C)**, then spec §8.1 order shifted by one.
- **Hello** `{protocol int, maxSpeed float}` sent by the server on FML `PlayerLoggedInEvent`. The client
  stores it per connection and clears it on disconnect; **arming is refused without a Hello**
  (vanilla/unmodded server → HUD message). This replaces v1's unspecified "config sync".
- Client→server: Arm(bool), Build(DTO), Transform(quat, velocity xyz m/s, ω[4], epochMs) every armed
  tick. Velocity is an addition to spec #10: needed for the motion echo and usable for extrapolation.
- Server handler: validate (registry armed, finite, `|v| ≤ maxSpeed`, `ω ≤ 1.05 × Kv·2π/60·cells·4.2`
  from the stored Build), apply abilities/size/stepHeight, `player.setRoll(rollFromQuat)`, store, relay
  `sendToAllTracking(msg, player)` (sender ignores its echo by UUID). Works on integrated servers.
- **Motion echo**: `processPlayer` compares claimed displacement² from the tick-start position minus the
  server player's motion² against `100 blocks² × packets` (300 elytra) (NetHandlerPlayServer.java:
  468-514), and `update()` resets the player to the tick-start position after its own entity update, so
  server-side motion never moves the player. The server-side `PlayerTickEvent` handler therefore
  writes `motion = v/20 × ECHO_TICKS` (20, i.e. ~1 s of travel) at **END** (after `travel()` consumed
  it) and zeroes it at **START** (so the server's own `travel()`/`move()` runs with zero displacement
  and causes no block side effects). Scheduled packet tasks run before the world tick, so
  `processPlayer` sees the END value. The multi-tick echo is needed because `firstGood` is captured
  once per tick: k packets bunched into one tick claim `(kΔ)²` against the tick-start position, and a
  one-tick echo (`Δ²`) failed a 300 ms hitch at ~34 m/s.
- Race packets S2C only. `RemoteDrones` as v1 (two states, fresh vectors, sender-clock extrapolation,
  20 % per-frame smoothing guarded per frame, motor angle integration).

### 6.7 Settings and GUI (spec §10–11)
- `config/cleanfpv/settings.json` `{ models, currentModel, firstTimeSetup }`, per-model keys plus invert
  flags, full calibration, `mouseGain`, controller `guid`. Presets delete-protected; corrupt entry →
  dropped + log; corrupt file → backed up and recreated.
- Screens as spec §11; `GuiScreen` + own list rows; Controls button as §5. "New Preset" clones the
  **current** model (§10).

### 6.8 Race (spec §9), server-authoritative — as v1.

### 6.9 Lifecycle (new)
| Trigger | Client | Server |
|---|---|---|
| Disconnect / world unload | force disarm + restore (FOV, keys, size, shader, sound), clear Hello, clear remote/race state | `PlayerLoggedOutEvent`: remove from `ArmRegistry`, restore abilities/size |
| Death | `LivingDeathEvent` for `mc.player` → disarm + restore | `LivingDeathEvent` for `EntityPlayerMP` → remove, restore, relay Arm(false) |
| Respawn / dimension change | new `EntityPlayerSP` seen in `EntityJoinWorldEvent` → state is per player instance: reinstall `DroneMovementInput`, ensure disarmed | `PlayerRespawnEvent` / `PlayerChangedDimensionEvent` → remove, restore |
| Remote player leaves tracking | `RemoteDrones` entry dropped on `EntityLeaveWorld`/timeout | — |

## 7. Frame and tick sequencing

Per rendered frame (`Minecraft.runGameLoop`): `beginFrame()` → `RenderTickEvent START` (realtime
physics step) → 0..N client ticks → `updateCameraAndRender` → `MouseTurnEvent` (armed: capture mouse,
**run the attitude frame step**, cancel) → `FOVModifier` → `orientCamera`/`CameraSetup` (override) →
world → `RenderWorldLastEvent` (uniforms, discs, gates) → post chain → HUD → `RenderTickEvent END`
(poll joystick; if the frame step did not run because the window was unfocused, run it with zero
mouse). A frame-sequence counter guarantees at most one attitude step per frame.

Per client tick (`PlayerTickEvent` START, local): re-shrink, tick physics step, zero motion, send
Transform, ensure post shader. END (both sides): size/eye/step/abilities re-assert; server END also
writes the motion echo (START zeroes it).

Ordering rule: the attitude quaternion is only mutated in the frame step; physics reads it. Player
yaw/pitch/roll are outputs (except at arm time).

## 8. Server compatibility constraints
- `allowFlying` granted server-side while armed (floating kick).
- "moved too quickly": defeated by the motion echo; it tolerates bunches of up to `ECHO_TICKS` (20) move
  packets per server tick at the reported speed, so vanilla's check is effectively off while armed and
  the speed cap is `TransformRules`' `|v| ≤ maxSpeed`.
- "moved wrongly": server hitbox = client hitbox (size re-assert), `stepHeight = 0` both sides, Y→X→Z
  sweep parity, tick-consistency rule (§6.3). Creative/spectator are exempt from the check anyway.
- Servers without the mod: no Hello → arming refused.
- Third-party anti-cheat: out of scope.

## 9. Phases (each ends with a runnable client)

| Phase | Scope | Done when |
|---|---|---|
| 0 Toolchain | CleanroomGradle userdev from the Barrel Roll port; identity; empty AT; JUnit | `./gradlew build runClient` boots on the local Cleanroom build; `MouseTurnEvent` and `com.cleanroommc.client.sdl.SDL` resolve |
| 1 Skeleton | Mod class, proxies, settings store + presets, keybinds, controls button, minimal settings screen | Settings round-trip on disk; button visible at default GUI scale |
| 2 Input | JoystickService (SDL), ChannelMap, calibration, DroneMovementInput, MouseInput, stick overlay | Overlay shows live sticks from a radio, a gamepad, and keyboard/mouse; hot-unplug/replug recovers |
| 3 Flight feel | Arm FSM (Hello gate stubbed true in SP), rates, attitude, CameraRig, CameraSetup override, FOVModifier, hides, physics v1 | Flies acro with mouse+keys and radio; camera correct in first person, third-person back **and front** view; no double flip |
| 4 Collisions, sizing, minimal net | Sweep, bounce, tick-consistency rule, size/eye/step both sides, abilities, **Hello + Arm packet + server arm handler + motion echo** | Passes 1-block gaps and hugs walls at 20 m/s on Open-to-LAN (`client2`) with no "moved wrongly"/"too quickly" logs |
| 5 Multiplayer | Build/Transform packets, relay, remote drone render + interpolation, spectator camera, roll sync, lifecycle (§6.9) | Two clients see each other smoothly; death/respawn/dimension change/logout leave no stuck state |
| 6 Physics v2 | Blade element, motor RK4, battery, thermal, mass model, overheat OSD | Unit tests pass. Both presets match the spec reference (§1) within tolerance: 5" hover 29–35 %, T/W 4.7–5.4, level 48–54 m/s; whoop hover 54–61 %, T/W 2.35–2.75, level 24–29 m/s. v1 matches v2's static thrust. |
| 7 Render/audio polish | Fisheye (2-pass), procedural model, prop discs, motor sound, optional 0.5 back-off | HUD undistorted; screenshot review |
| 8 GUI | Wizard, remaining screens, rate chart | First-run flow completes with a gamepad and a radio |
| 9 Racing | Server race module, packets, gate/ring/leaderboard | Lap recorded on a LAN race |

## 10. Decisions on the original's quirks and spec deviations
Arming throttle guard enforced · overheat warning live · mass model kept at the original's effective
mass. The prop, split-cam and arm terms, lost to unit bugs, stay at 0 g, so the 5" is 613 g and the
whoop 110 g as in spec §5.4. The propulsion is tuned at that mass; counting the terms would add ≈ 21 %
and make every build fly heavier than the reference. Keyboard idle stays at 50 % throttle (spec §3.2):
the 5" climbs slowly there and the whoop sinks, as in the original · induced inflow still
omitted · remote prop spin fixed · relay always server-side · all invert flags persisted · clicks while
armed go to use-item · arm key toggles on press · no per-frame logging · no HTTP name lookup ·
leaderboard keeps best lap · renderer refreshed on camera-angle change · disconnect restores everything ·
dead classes not recreated.
Deviations from the spec, deliberate: **(a)** stick pitch/roll are not negated in front view (controls
stay body-relative, as in real line-of-sight flying; the camera flip is vanilla's) · **(b)** fisheye only
in first person · **(c)** "New Preset" clones the current model, not the 5" default · **(d)** the Build
DTO includes `motorKv`/`propPitch` · **(e)** Hello handshake gates arming · **(f)** velocity added to the
Transform packet · **(g)** mouse feel is `mouseGain`-scaled vanilla units, not `0.007 rad/count`.
Not deviations (v1 was wrong, v2 matches spec): mouse is a direct per-frame angle add, not a rate;
player yaw = body yaw, pitch = camera pitch.

## 11. Verification
- `./gradlew build test`: rates curve at defaults; bounce head-on 0.2 / glancing 0.65; camera basis →
  `Rz·Rx·Ry(+180)` Euler round trip incl. |pitch| near 90°; Y→X→Z sweep vs a hand-built AABB set; RK4
  no-load clamp; tick-consistency snap on a synthetic corner.
- `runClient`: arm with I, fly mouse+WASD; F5 through all three views (front view must not double
  flip); FOV; fisheye on/off and F4; HUD hidden/undistorted; 1-block gap.
- Controller: radio (unmapped joystick) and gamepad; wizard; unplug/replug; unfocused window keeps
  reading; axes/inverts/calibration persist.
- `runClient` + `client2` over Open to LAN and `runServer`: remote drone with roll and prop spin;
  spectator camera; wall-hugging at 20 m/s and a straight run at `maxSpeed` produce no server warnings;
  kill/respawn/nether portal/logout while armed leave no shrunken player, stuck shader or registry entry.
- Vanilla server (no mod): arming refused with a message.

## 12. Risks and open questions
- Loader is unreleased: track `cleanroom_version`; the API is on main so only the version string moves.
- Cleanroom's SDL pump drops joystick axis/button *events*; if a future loader change stops pumping
  device state, switch to `SDL_UpdateJoysticks` via LWJGL directly (still no bundling).
- `SDL_INIT_JOYSTICK` on Linux needs udev/hidraw permissions for some radios; log the device list.
- `setSize` re-assert timing vs other mods that resize players.
- Post chain edge cases (`fboEnable` off): fallback path.
- Motion echo depends on `processPlayer`'s `d11 − d10` comparison and `update()`'s position reset;
  both verified in Cleanroom's `NetHandlerPlayServer` (lines 176-177, 468-514); re-check on loader bumps.

## 13. Review disposition (PLAN_v1_REVIEW_1.md)
| # | Verdict | Resolution |
|---|---|---|
| F1 joystick on GLFW | **Accepted, fix differs** | No `lwjgl-glfw`; but the review missed that Cleanroom ships `com.cleanroommc.client.sdl` (Joysticks/Gamepads over LWJGL SDL3, in the published userdev jar). Use it; no loader change, no jinput. "LWJGLX" → LWJGLY. |
| F2 fisheye main→main | **Accepted** | Two passes through `swap`; `DiffuseSampler`. |
| F3 front view | **Accepted, simplified** | Derived: vanilla's `Ry(180)` conjugation already negates pitch/roll; handler does nothing special. Hurt shake kept; death disarms. Input negation dropped (§10a). |
| F4 thirdPersonDistance AT | **Accepted** | Dead field; AT removed; optional translate-based back-off. |
| F5 mouse units | **Accepted** | Defined against event units (degrees/0.15); vanilla sensitivity applies; `mouseGain`. |
| F6 mouse through rates | **Accepted** | Reverted to spec (direct add); body-yaw/camera-pitch reverted to spec; preset clone registered. |
| F7 Phase 4 depends on 5 | **Accepted** | Hello + Arm + server handler + echo moved into Phase 4. |
| F8 corner cutting | **Accepted, narrowed** | Only horizontal error counts (line 531 always true); Y→X→Z parity + tick-consistency rule. |
| F9 ω validation needs Kv | **Accepted** | Kv on the wire. |
| F10 death/logout | **Accepted** | §6.9 lifecycle table. |
| F11 maxSpeed sync | **Accepted** | Hello packet, discriminator 0. |
| F12 spectate double roll | **Accepted** | Single override path. |
| F13 loadShader per tick | **Accepted, worse than stated** | Also leaks the previous group; guard on `getShaderGroup() == null`. The review's "F4 shader cycle" is wrong: F4 only flips `useShader`. |
| F14 GuiControls coords | **Accepted** | `(w/2+160, h−29)`; measured layout in §5. |
| F15 quantisation wording | **Accepted** | Reworded. |
| F16 curve hygiene | **Accepted** | §1. |
| F17 branch divergence | **Partly refuted** | Branches diverge only by the elytra-deploy event; every API file is identical, and the 1158 publish is from main. Plan targets main. |
| F18 dt clamp | **Accepted** | Removed. |

## 14. Changes from v1
Input platform (SDL via Cleanroom, not GLFW) · fisheye two-pass + uniform mechanism + leak guard ·
CameraSetup override instead of add, no front-view special case, no distance AT · mouse units and direct
add · body-yaw/camera-pitch per spec · Hello handshake and Phase 4 reshuffle · Kv on the wire · Y→X→Z
sweep + tick-consistency rule · motion echo START/END split · lifecycle table · Controls button
position · loader branch note · review disposition.
