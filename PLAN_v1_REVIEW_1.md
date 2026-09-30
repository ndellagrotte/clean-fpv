# Adversarial Review — PLAN_v1.md

**Date:** 2026-09-29 · **Reviewer pass:** 1
**Method:** every load-bearing claim in PLAN_v1 was checked against the actual sources it
depends on: `../Cleanroom` (checkout on `feat/rotation-input-primitives`, plus `main` and
`origin/main`), `../barrel-roll-clrm-port` (build files + `ClientEventHandler`), the
resolved runtime classpath of that port, `../flexfov-clrm-port`, and the behavioural spec
(`../minecraft_fpv_port/FPV_DRONE_MOD_DESIGN.md`). Evidence is cited as file:line.

## Verdict

The plan is unusually well-grounded — most of its risky claims (motion echo, tick ordering,
roll primitives, event substitutes) are **correct** against the real source. However, it
contains **two High-severity design errors** (the joystick platform assumption and the
fisheye pass structure) that would each sink a phase as written, one camera-math hole
(front view), several undocumented spec deviations, and a phase-dependency bug. None are
fatal to the architecture; all are fixable in the plan text before Phase 0.

---

## A. High severity

### F1 — The joystick subsystem assumes `org.lwjgl.glfw`, which is not on the runtime classpath
**Claims:** §1 ("No bundled libraries … GLFW (LWJGL 3)"), §2 Input row, §6.1 `JoystickService`, Phase 2/8 done-when, §11 verification.
**Evidence:**
- Cleanroom `build.gradle:185–196`: LWJGL 3.4.3 modules are `lwjgl`, `jemalloc`, `opengl`,
  `openal`, `stb`, **`sdl`** — there is no `lwjgl-glfw`.
- Resolved `runtimeClasspath` of the barrel-roll port (same userdev stack): `lwjgl-sdl`,
  `lwjgl-opengl`, `lwjgl-stb`, … **no glfw**. The window/input backend is SDL via LWJGLY
  (`com.cleanroommc:lwjgly:1.0.0`).
- LWJGLY does ship `org.lwjgl.input.Controllers` — but decompiling it shows it is the old
  **jinput-based** LWJGL2 implementation (calls `net.java.games.input.ControllerEnvironment`).
  `jinput:2.0.10` is on the classpath as a legacy MC dependency, but its natives are not
  shipped by Cleanroom, so `Controllers.create()` will fail at native load in a default
  environment.

**Impact:** `org.lwjgl.glfw.GLFW` will not link (`ClassNotFoundException`) at runtime. The
entire controller path — wizard (Phase 8), radio flying, §11 controller verification — is
built on it. Bundling `lwjgl-glfw` violates the plan's own §1 ground rule *and* doesn't
obviously work: joystick callbacks require an initialized GLFW instance, while the window
is owned by SDL.
**Fix:** pick one path and rewrite §1/§2/§6.1:
   1. SDL gamecontroller API via the already-present `lwjgl-sdl` (needs care: SDL is
      process-global and owned by the window layer; hot-plug events ride SDL's event loop),
   2. verify whether LWJGLY/jinput actually works with shipped natives (likely not), or
   3. push joystick support into the loader (consistent with the PR-#641 pattern) and keep
      the mod GLFW-free.
Also rename "LWJGLX shim" → **LWJGLY** (the actual Cleanroom artifact; LWJGLX is a
different project).

### F2 — Fisheye post pass "`minecraft:main` in/out" destroys its own input
**Claim:** §6.4 `fisheye.json` (one pass, `minecraft:main` in/out).
**Evidence:** `Shader.render` (`module/minecraft/.../shader/Shader.java:61–97`):
   - line 67 binds `framebufferIn`'s texture as `DiffuseSampler`;
   - line 81 calls `framebufferOut.framebufferClear()`, which **binds and clears** the
     output FBO (`Framebuffer.java:219–232`) *before* the fullscreen quad is drawn.
   With `in == out == main`, the clear wipes the very texture being sampled (and a
   same-texture feedback loop is undefined behaviour regardless of the clear). Vanilla
   chains (`blur`, etc.) always ping-pong through a `"swap"` target for this reason.
**Fix:** two passes — `main → swap` (fisheye), `swap → main` (blit) — with `"swap"` declared
in `targets`, exactly the vanilla blur shape. The program JSON must name its sampler
`DiffuseSampler` (hard-coded at Shader.java:67). The fallback path (§6.4, flexfov
`Shader.java` — file verified to exist) remains valid.

---

## B. Medium severity

### F3 — Third-person front-view camera math is asserted, not derived; vanilla already applies its own flip
**Claim:** §5 first row: "same handler adds 180° yaw / negates pitch+roll when `thirdPersonView == 2`."
**Evidence:** Cleanroom `EntityRenderer.orientCamera` runs the front-view machinery
*before* the `CameraSetup` event: `f2 += 180` for the back-off vector (line 546–548),
`GlStateManager.rotate(180, 0,1,0)` plus a ±180°-about-X sandwich (lines 572–580), and only
then applies the event's roll/pitch/yaw (lines 594–601). The event angles therefore compose
**on top of** vanilla's flip. The plan's rule — copied from the 1.16-era spec — is exactly
the kind of naive +180/negate that double-flips here. The cited pattern source
(barrel-roll `ClientEventHandler#onCameraSetup`, verified) only adds roll and does not
handle front view at all, so no tested precedent exists in-repo. The spec also negates
stick pitch/roll inputs in front view (§4.3) — an input-side change the plan omits.
**Fix:** derive the composition explicitly (or disable the flip by writing counter-angles),
and add a front-view check to Phase 3's done-when. Also note the vanilla death/hurt camera
rotations (`hurtCameraEffect`, lines 482–502) still compose with the drone camera while
armed.

### F4 — `EntityRenderer.thirdPersonDistance` is a dead field; the AT + set-0.5 hook cannot work
**Claim:** §5 row "`ActiveRenderInfo` / `getMaxZoom` (0.5 back-off)".
**Evidence:** `EntityRenderer.java:99`: `private final float thirdPersonDistance = 4.0F;` —
never read. `orientCamera` line 540 lerps to the **literal** `4.0F`
(`this.thirdPersonDistancePrev + (4.0F - this.thirdPersonDistancePrev) * partialTicks`).
An AT (even `public-f`) changes nothing; the 0.5 back-off needs a different mechanism
(e.g. handle it entirely in the mod's CameraSetup transform, or accept the vanilla
ray-clamped distance).
**Fix:** drop the AT entry and the row, or redesign; as written it's a no-op shipping in
`cleanfpv_at.cfg`.

### F5 — Mouse input double-counts sensitivity; units are wrong
**Claim:** §6.1 "Mouse: … scaled by `sensitivity × 0.007` rad."
**Evidence:** the PR's `InputEvent.MouseTurnEvent` javadoc + `ForgeHooksClient.onMouseTurn`
(the event fires the *post-vanilla-scaling* values: `(sens·0.6+0.2)³·8`, invert-sign
applied, smooth-cam filtered; `turn()` then ×0.15 → degrees). The spec's `0.007 rad` factor
applied to **raw mouse counts** in the 1.16 original. Multiplying the event value by the
mod's own sensitivity *and* the 0.007 constant double-scales and silently couples drone
feel to the player's vanilla "Mouse Sensitivity" slider.
**Fix:** define the conversion against event units explicitly (e.g. radians =
`eventYaw × 0.15 × π/180`, mod sensitivity in those units), and state whether vanilla
sensitivity should apply at all to a goggles-style control.

### F6 — Undocumented deviation: mouse now goes through the rate curves
**Spec §3.2/§4.3:** mouse deltas are added **directly** to the frame's quaternion rotation
(radians/frame, unbounded turn rate). **Plan §6.1/§7:** mouse becomes *stick deltas* fed
through the Betaflight curves — i.e. capped at the configured max rate (~700°/s default).
This is a fundamental feel change for mouse-acro flying (flick = rate-limited instead of
proportional). It may be the better design, but §10 ("decisions on the original's quirks")
doesn't list it.
**Fix:** add it to §10 with a one-line rationale, or match the spec. Same register treatment
for: player yaw/pitch = **camera** angles (§6.2) vs spec §4.3's body-yaw/camera-pitch sync
(affects what the server and third-party systems see as the look direction); and "New
Preset" cloning the *current* model (§6.7) vs the original's clone-the-5″-default.

### F7 — Phase 4's done-when depends on Phase 5 scope
**Claim:** Phase 4: "Passes through 1-block gaps **on a LAN server** without rubber-banding".
**Evidence:** a LAN/integrated server only shrinks its `EntityPlayerMP` inside the
**server arm handler** (§5 hook map), and rubber-banding is only prevented by the
**Transform packet + motion echo** — all Phase 5 items (§6.6). Even singleplayer gap
testing needs the integrated-server-side size re-assert.
**Fix:** move the minimal Arm packet + server handler (size/abilities) into Phase 4, or
reword Phase 4's criterion to singleplayer physics only.

### F8 — "moved wrongly" corner-cutting is a live rubber-band risk the plan doesn't cover
**Evidence:** `NetHandlerPlayServer.java:517–559`: the server replays the claimed
straight-line delta through vanilla collision (`this.player.move`, line 526) and flags
"moved wrongly" when the post-move position differs from the claimed position by
> 0.25 blocks (d11 > 0.0625, line 539), teleporting back if the start was clear. The
client's **bounce/reflection** produces per-tick net displacements whose straight line can
clip a corner that both endpoints avoid (client sweep is endpoint-based). At race speeds
(20 m/s = 1 block/tick) around pillars/gates this fires.
**Fix:** document as a known risk, match vanilla's axis order (X→Y→Z) in `Sweep` so the
simple-translation case replays identically, and add a wall-hugging race-speed test to
Phase 5. (The motion-echo itself is sound — see "verified" below.)

### F9 — Server-side ω validation is impossible with the wire DTO as specified
**Claim:** §6.6 validates "ω ≤ no-load", but §6.3 says the wire DTO omits `motorKv`, and
no-load = `Kv_rad · cells · 4.2` needs Kv. (Cells are sent; Kv is not.)
**Fix:** send Kv, or weaken the check to finite + a hard constant, or drop the claim.

### F10 — Lifecycle gaps: death/respawn and server-side logout
**Evidence/analysis:** the plan forces disarm on *disconnect* and world unload, but:
   - **death while armed**: `PlayerTickEvent` stops firing for the dead player; on respawn
     the armed FSM re-asserts the 0.2×0.1 hitbox on the *new* `EntityPlayerSP` (re-install
     hooks re-fire), leaving a tiny, half-restored player until disarm. Add explicit
     disarm-on-death.
   - **dedicated server logout**: nothing removes the player from the server `ArmRegistry`
     (stale armed state on rejoin; slow UUID leak). Add `PlayerLoggedOutEvent` cleanup.
Also §12's "isolate PR uses in two files" is optimistic: roll/MouseTurnEvent/frame-delta
touch `CameraRig`, the CameraSetup/FOV handlers, `ArmController` (arm/disarm `setRoll`) and
the input classes — count 4–6 files.

---

## C. Low severity / nits

- **F11** — `maxSpeed` is "sent to clients on join and enforced client-side" (§6.3) but
  §6.6's packet list has no config-sync packet. Use the "packet 0 handshake" mentioned in
  the same section, or fold it into a join message.
- **F12** — roll double-add risk when spectating: §3's generic CameraSetup handler adds
  `getRoll(partialTicks)` for the render-view entity, while §6.2's spectate path applies
  the full interpolated remote quaternion "the same way". Specify that spectate mode
  *replaces* the generic roll add.
- **F13** — "Re-assert [the post shader] each client tick": `EntityRenderer.loadShader`
  (lines 232–250) **recreates** the `ShaderGroup` (and reallocates FBOs) unconditionally.
  Re-assert must be `getShaderGroup() == null → loadShader`, not a blind call per tick.
- **F14** — GuiControls injection at `(w/2+159, 18)` is the 1.16 layout; 1.12.2's
  `GuiKeyBindingList` spans `w/2−155 … w/2+155`, so the button fits but can clip at large
  GUI scales / narrow windows. Verify visually in Phase 1.
- **F15** — "vanilla yaw/pitch packets are quantised" (§3): true for the
  server→tracker path (`EntityTrackerEntry` byte-encodes angles), false for the client's
  own C05 look packets (floats). The *conclusion* (send the quaternion for remote
  fidelity) is right; the stated reason is half-right.
- **F16** — Clean-room hygiene: §6.3/Physics v2 consumes the spec's §5.3 empirical
  cl/cd curve fits, whose functional forms *and constants* were recovered by
  decompilation. The spec itself marks them "tunable". Add one sentence to §1: constants
  are re-derived/tuned against expected behaviour, not copied verbatim.
- **F17** — Loader dependency framing is stale: the roll-axis commit (`0c9634c5`) is
  already on `origin/main`, and `origin/main` (99caa8a7) already contains a
  frame-clock/MouseTurnEvent lineage (b34e664e) that has **diverged** from the local
  `feat/rotation-input-primitives` (fc8fb6b8, not a descendant). The plan should note the
  branch/origin-main divergence and re-check API names before Phase 0 — the local publish
  may be replaceable by a released build sooner than §2 suggests.
- **F18** — §7 says clamp `getFrameDeltaSeconds()` "to a sane max (e.g. 0.1 s)" — the
  clock already clamps to [0, 0.1] in `ForgeHooksClient.beginFrame()`. Harmless, but the
  plan should not imply it's the mod's job.

---

## D. Claims that survived adversarial checking (keep as-is)

- **PR #641 primitives, exactly as described:** `Entity.roll/prevRoll/getRoll(pt)/setRoll`
  (wraps to [-180,180), discards non-finite; `setRoll` also syncs the DataWatcher),
  `DataParameter<Float> ROLL` reserved id **254** by `createKey`, `EntityPlayerSP.onRollSynced`
  no-ops its own echo, and per-frame writers advancing `roll`+`prevRoll` together is
  explicitly blessed by the loader javadoc. `InputEvent.MouseTurnEvent` is `@Cancelable`,
  client-bus, fires from both `EntityRenderer` turn call sites, once per focused frame
  including zero-delta frames — §3's description and §7's unfocused-frame handling match.
- **Motion echo is mechanically sound:** `NetHandlerPlayServer` (lines 470–514; the plan's
  "~line 505" is accurate) compares claimed displacement² minus **the server player's
  motion²** against `100 blocks² × packets` (300 elytra), and the allowance scales with
  burst count. Echoing velocity/20 into `motionX/Y/Z` keeps `d11 − d10 ≈ 0` at any steady
  speed; acceleration margin at the 500 m/s clamp with an aggressive build is
  ~10 blocks² ≪ 100. The floating-kick suppression via `allowFlying` matches lines
  561–569.
- **Tick ordering claims:** `PlayerTickEvent` START fires at the top of
  `EntityPlayer.onUpdate` → `super.onUpdate()` → `updateSize()` → END
  (EntityPlayer.java:212/242/283/360), so END re-shrink per tick works; zeroed motion +
  `capabilities.isFlying` (EntityPlayer.travel skips gravity for flyers) makes vanilla
  `move()` a true no-op.
- **APIs verified present/public:** `EntityPlayer.eyeHeight` (public) +
  `getDefaultEyeHeight()`; `EntityPlayerSP.movementInput` (public);
  `EntityViewRenderEvent.FOVModifier.setFOV`; `EntityRenderer.loadShader`/`stopUseShader`/
  `loadEntityShader` (+ the F4 `shaderIndex` shader-cycle really exists in 1.12.2);
  `SimpleNetworkWrapper.sendToAllTracking(IMessage, Entity)`; `World.getCollisionBoxes`;
  `AxisAlignedBB.calculateX/Y/ZOffset`; ShaderGroup resolves `minecraft:main` in JSON and
  custom uniforms via `ShaderManager.getShaderUniform` (AT `listShaders` or per-shader
  access both viable; `RenderWorldLastEvent` fires before the chain renders, so per-frame
  uniform setting is timeable).
- **Toolchain claims:** CleanroomGradle settings plugin 0.17.5, `cleanroom.userdev(...)`,
  icu4j/patchy excludes, LWJGL2 exclusion, `client2` run, `use_access_transformer`,
  `cg.repos.enableLocal`, version `0.0.1-dev.1158.local.dirty`, JOML 1.10.9, Java 25
  (Cleanroom README: "Java 25") — all confirmed against the barrel-roll port and Cleanroom.
- **Render-order sequencing** (§7): `MouseTurnEvent` (inside `updateCameraAndRender` mouse
  handling) → `getFOVModifier`/`FOVModifier` → `orientCamera`/`CameraSetup` → world →
  `RenderWorldLastEvent` → hand → post chain → HUD matches the actual call order.

---

## E. Recommended edits for PLAN v1.1

1. Rewrite the joystick platform story (F1) and re-point §11's controller verification.
2. Restructure `fisheye.json` to ping-pong through `swap` (F2); sampler name `DiffuseSampler`.
3. Derive + test the front-view camera composition; suppress vanilla hurt/death rotations
   while armed if "goggles" is the goal (F3).
4. Delete the `thirdPersonDistance` AT row (F4).
5. Define mouse units against `MouseTurnEvent` semantics (F5); register the mouse-through-
   rates deviation in §10 (F6), plus the camera-yaw and preset-clone deviations.
6. Move minimal Arm packet + server handler into Phase 4 (F7); document corner-cut
   rubber-band risk + axis-order matching (F8).
7. Fix the ω-validation claim or the DTO (F9); add death/logout lifecycle rules (F10).
8. Small fixes: maxSpeed sync channel (F11), spectate roll branch (F12), conditional
   `loadShader` (F13), GuiControls coords (F14), quantisation wording (F15), curve-constant
   hygiene note (F16), loader-branch status (F17), redundant dt clamp (F18).
