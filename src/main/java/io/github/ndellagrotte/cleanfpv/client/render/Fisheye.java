package io.github.ndellagrotte.cleanfpv.client.render;

import io.github.ndellagrotte.cleanfpv.CleanFpv;
import io.github.ndellagrotte.cleanfpv.Reference;
import io.github.ndellagrotte.cleanfpv.client.ClientDroneContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Shader;
import net.minecraft.client.shader.ShaderGroup;
import net.minecraft.client.shader.ShaderUniform;
import net.minecraft.util.ResourceLocation;

/**
 * The fisheye post effect (spec §6.2, PLAN §6.4) on vanilla's post chain:
 * {@code assets/cleanfpv/shaders/post/fisheye.json} = {@code cleanfpv:fisheye} (main → swap) then
 * {@code cleanfpv:copy} (swap → main, opaque). The chain runs after the world and before the HUD,
 * so the OSD stays undistorted.
 *
 * <h2>Lifecycle</h2>
 * {@link #update()} runs once per frame at {@code RenderTickEvent} START (outside world rendering,
 * where creating framebuffers is safe) and reconciles the wanted state with the renderer:
 * <ul>
 *   <li>wanted = camera override active for the render-view entity ({@link CameraHooks#overrides})
 *       &amp;&amp; first person &amp;&amp; {@code activeModel().useFisheye};</li>
 *   <li>wanted and no group loaded → {@code loadShader} (only when {@code getShaderGroup() == null}:
 *       a blind call leaks the previous group). This also re-creates the chain after vanilla
 *       deleted it (F5, render-view change, resource reload);</li>
 *   <li>another group loaded (creeper/spider/enderman spectator shader, another mod) → left
 *       alone; no fisheye while it is active;</li>
 *   <li>not wanted and our group loaded → {@code stopUseShader}. Groups are recognised by
 *       {@link ShaderGroup#getShaderGroupName()}, so only ours is ever stopped.</li>
 * </ul>
 * Fallback: without shader/FBO support, or when the chain fails to load, one log line and no
 * fisheye. A failed load is retried only when the effect becomes wanted again (re-arm, leaving
 * third person), never per frame.
 *
 * <p>F4 ({@code EntityRenderer.switchUseShader}) flips vanilla's private "use shader" flag, which
 * has no getter: while the group stays loaded, F4 turns the effect off and on again, like for
 * vanilla's spectator shaders; a reload (F5 cycle, re-arm) turns it back on.
 *
 * <p>Uniforms ({@code FovY} radians, {@code Aspect}) are set per frame by {@link #applyUniforms()}
 * from {@code RenderWorldLastEvent}; program JSON values only apply at parse time.
 *
 * <p>Client render thread only.
 */
public final class Fisheye {

    public static final ResourceLocation CHAIN = new ResourceLocation(Reference.MOD_ID, "shaders/post/fisheye.json");
    private static final String CHAIN_NAME = CHAIN.toString();

    private static boolean unavailable;
    private static boolean loggedBusy;
    private static boolean loggedUnsupported;
    private static boolean wasWanted;

    private Fisheye() {}

    /**
     * Stops the fisheye now if our chain is active. Orchestration calls this on disarm (and on
     * disconnect/world unload); {@link #update()} would also stop it by itself one frame after the
     * override ends. Safe to call at any time and repeatedly.
     */
    public static void disable() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.entityRenderer != null && isOurs(mc.entityRenderer.getShaderGroup())) {
            mc.entityRenderer.stopUseShader();
        }
        wasWanted = false;
    }

    /** Whether our chain is the renderer's current group. */
    public static boolean isActive() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.entityRenderer != null && isOurs(mc.entityRenderer.getShaderGroup());
    }

    /** Per-frame reconcile; call at {@code RenderTickEvent} START. */
    static void update() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityRenderer renderer = mc.entityRenderer;
        if (renderer == null || mc.world == null) {
            return;
        }
        ShaderGroup group = renderer.getShaderGroup();
        boolean ours = isOurs(group);
        boolean wanted = wanted(mc);
        if (wanted && !wasWanted) {
            unavailable = false;
            loggedBusy = false;
        }
        wasWanted = wanted;
        if (!wanted) {
            if (ours) {
                renderer.stopUseShader();
            }
            return;
        }
        if (ours || unavailable) {
            return;
        }
        if (group != null) {
            if (!loggedBusy) {
                loggedBusy = true;
                CleanFpv.LOGGER.info("Fisheye skipped: another post shader ({}) is active", group.getShaderGroupName());
            }
            return;
        }
        if (!OpenGlHelper.shadersSupported || !OpenGlHelper.isFramebufferEnabled()) {
            unavailable = true;
            if (!loggedUnsupported) {
                loggedUnsupported = true;
                CleanFpv.LOGGER.info("Fisheye disabled: post-processing shaders or framebuffers are not available");
            }
            return;
        }
        renderer.loadShader(CHAIN);
        if (!isOurs(renderer.getShaderGroup())) {
            unavailable = true;
            CleanFpv.LOGGER.warn("Fisheye disabled: could not load {} (see the log above)", CHAIN_NAME);
        }
    }

    /**
     * Sets {@code FovY}/{@code Aspect} on every pass of our chain; call from {@code RenderWorldLastEvent}.
     * Uses {@code setSafe} (writes component 0 of a 1-float uniform): the plain {@code set(float)}
     * overload set also contains {@code set(org.lwjgl.util.vector.Matrix4f)}, whose class is not on
     * the compile classpath, so javac cannot resolve calls to {@code set}.
     */
    static void applyUniforms() {
        Minecraft mc = Minecraft.getMinecraft();
        ShaderGroup group = mc.entityRenderer.getShaderGroup();
        if (!isOurs(group)) {
            return;
        }
        float fovDeg = ClientDroneContext.get().cameraPose().vFovDeg();
        float fovRad = (float) Math.toRadians(Float.isFinite(fovDeg) && fovDeg > 0f ? fovDeg : 70f);
        float aspect = mc.displayHeight > 0 ? (float) mc.displayWidth / mc.displayHeight : 1f;
        for (Shader shader : group.listShaders) {
            ShaderUniform fov = shader.getShaderManager().getShaderUniform("FovY");
            if (fov != null) {
                fov.setSafe(fovRad, 0f, 0f, 0f);
            }
            ShaderUniform ratio = shader.getShaderManager().getShaderUniform("Aspect");
            if (ratio != null) {
                ratio.setSafe(aspect, 0f, 0f, 0f);
            }
        }
    }

    private static boolean wanted(Minecraft mc) {
        return mc.gameSettings.thirdPersonView == 0
                && ClientDroneContext.get().activeModel().useFisheye
                && CameraHooks.overrides(mc.getRenderViewEntity());
    }

    private static boolean isOurs(ShaderGroup group) {
        return group != null && CHAIN_NAME.equals(group.getShaderGroupName());
    }
}
