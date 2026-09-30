package io.github.ndellagrotte.cleanfpv.client.net;

import io.github.ndellagrotte.cleanfpv.client.render.RemoteDroneSource;
import io.github.ndellagrotte.cleanfpv.client.render.RemoteDroneView;
import io.github.ndellagrotte.cleanfpv.common.TransformSnapshot;
import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;
import io.github.ndellagrotte.cleanfpv.server.net.TransformRules;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import org.joml.Quaternionf;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Armed remote pilots as this client sees them (spec §8.3, PLAN §6.6): per UUID the last two
 * received transforms, the latest build, and a smoothed attitude advanced once per rendered frame.
 * Installed as the {@link RemoteDroneSource} in {@code ClientDroneContext} by
 * {@link ClientNetHandler}; fed by its packet handlers. Client thread only.
 *
 * <h2>Per frame ({@link #updateFrame}, {@code RenderTickEvent} START)</h2>
 * For every entry: resolve the player entity, build the target attitude by extrapolating the
 * newest sample with the angular velocity of the last two (sender clock, elapsed time from the
 * newest packet's local receive time, capped at {@link RemoteInterpolation#MAX_EXTRAPOLATION_S}),
 * then move the smoothed attitude 20 % of the way there. This runs exactly once per frame no
 * matter how many readers (renderer, prop discs, spectator camera) call {@link RemoteDroneView#attitude}
 * afterwards, which fixes the spec's "twice per frame while spectating".
 *
 * <h2>Position</h2>
 * {@link RemoteDroneView#position} is the <b>entity's</b> render-interpolated position
 * ({@code lastTickPos → pos} at the partial tick). {@code EntityOtherPlayerMP} already smooths the
 * server's position updates over three ticks, and the vanilla nameplate, hitbox and shadow use the
 * same position, so the drone model stays glued to them. The transform's velocity is not used for
 * position.
 *
 * <h2>Visibility</h2>
 * {@link #get}/{@link #all} return only entries that have a transform, whose last transform is
 * younger than {@link #STALE_MS}, and whose entity is present in the client world. Stale or
 * entity-less entries stay stored (a later packet revives them) until removed by disarm, entity
 * removal (api-notes D6: {@code IWorldEventListener.onEntityRemoved}), world unload or disconnect.
 *
 * <h2>Entity roll</h2>
 * Each frame the visible pilot's entity gets {@code roll = prevRoll =} the camera roll of the
 * smoothed attitude at the build's camera angle ({@link TransformRules#cameraRollDeg}, the
 * {@code CameraSetup} convention). The server also sets the pilot's roll from the same formula
 * and the loader syncs it ({@code Entity.ROLL}); {@code tickRoll()} applies that value during the
 * client tick, and this frame write replaces it before anything renders (ticks run before
 * {@code RenderTickEvent} START, api-notes D2). Both are derived identically, so the only
 * difference is smoothing, and writing {@code prevRoll} too keeps {@code getRoll(pt)} constant.
 *
 * <h2>Renderer cache invalidation</h2>
 * Every received build is a new {@link DroneBuild} object: a renderer cache can compare
 * {@code view.build()} by identity. A disarmed pilot disappears from {@link #all()}.
 */
public final class RemoteDrones implements RemoteDroneSource {

    /** A pilot whose last transform is older than this (local ms) is hidden. */
    public static final long STALE_MS = 2000L;
    /** Camera tilt when no build has been received (spec default). */
    public static final float DEFAULT_TILT_DEG = 30f;

    private final Map<UUID, RemoteDrone> drones = new HashMap<>();
    private final List<RemoteDrone> visible = new ArrayList<>();
    private final Collection<RemoteDrone> visibleView = Collections.unmodifiableList(visible);
    private long frames;

    // ---------------------------------------------------------------------------------------------
    // RemoteDroneSource

    @Override
    public RemoteDroneView get(UUID playerId) {
        RemoteDrone d = drones.get(playerId);
        return d != null && d.visible ? d : null;
    }

    @Override
    public Collection<? extends RemoteDroneView> all() {
        return visibleView;
    }

    // ---------------------------------------------------------------------------------------------
    // Packet input (ClientNetHandler)

    /** A pilot armed: creates the entry (no-op if present). */
    public void onArmed(UUID id) {
        drones.computeIfAbsent(id, RemoteDrone::new);
    }

    public void onBuild(UUID id, DroneBuild build) {
        drones.computeIfAbsent(id, RemoteDrone::new).build = build;
    }

    public void onTransform(UUID id, TransformSnapshot t, long nowMs) {
        drones.computeIfAbsent(id, RemoteDrone::new).push(t, nowMs);
    }

    /** Drops one pilot (disarm, entity removed). Returns whether it was known. */
    public boolean remove(UUID id) {
        RemoteDrone d = drones.remove(id);
        if (d != null) {
            visible.remove(d);
        }
        return d != null;
    }

    /** Drops everything (world unload, disconnect). */
    public void clear() {
        drones.clear();
        visible.clear();
    }

    public boolean contains(UUID id) {
        return drones.containsKey(id);
    }

    // ---------------------------------------------------------------------------------------------
    // Per frame

    /**
     * Advances every pilot one frame: entity lookup, extrapolation, 20 % smoothing, entity roll,
     * visibility. Call once per rendered frame before anything reads the views.
     *
     * @param world    the client world, or {@code null} (then everything is hidden)
     * @param localId  the local player's UUID (never treated as remote), or {@code null}
     * @param nowMs    local {@code System.currentTimeMillis()}
     */
    public void updateFrame(World world, UUID localId, long nowMs) {
        frames++;
        visible.clear();
        for (RemoteDrone d : drones.values()) {
            d.advance(world, localId, nowMs, frames);
            if (d.visible) {
                visible.add(d);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** One remote pilot; implements the render-facing view. */
    static final class RemoteDrone implements RemoteDroneView {

        private final UUID id;
        private DroneBuild build;
        private TransformSnapshot older;
        private TransformSnapshot newer;
        private long newerReceivedMs;

        private final Quaternionf olderQ = new Quaternionf();
        private final Quaternionf newerQ = new Quaternionf();
        private final Quaternionf target = new Quaternionf();
        private final Quaternionf smoothed = new Quaternionf();
        private boolean hasSmoothed;
        private long lastFrame = -1L;

        private EntityPlayer entity;
        private final Vector3d lastPosition = new Vector3d();
        private boolean visible;

        RemoteDrone(UUID id) {
            this.id = id;
        }

        void push(TransformSnapshot t, long nowMs) {
            if (newer != null && t.epochMs < newer.epochMs) {
                return; // out of order (cannot happen on one TCP stream, but keep the history monotonic)
            }
            older = newer;
            newer = t;
            newerReceivedMs = nowMs;
        }

        void advance(World world, UUID localId, long nowMs, long frame) {
            if (frame == lastFrame) {
                return;
            }
            lastFrame = frame;
            entity = world != null && !id.equals(localId) ? world.getPlayerEntityByUUID(id) : null;
            if (entity != null && entity.isDead) {
                entity = null;
            }
            boolean fresh = newer != null && nowMs - newerReceivedMs <= STALE_MS;
            visible = fresh && entity != null;
            if (newer == null) {
                return;
            }
            newer.attitude(newerQ);
            if (older != null) {
                older.attitude(olderQ);
            }
            RemoteInterpolation.extrapolate(older != null ? olderQ : null, older != null ? older.epochMs : 0L,
                    newerQ, newer.epochMs, (nowMs - newerReceivedMs) / 1000.0, target);
            if (!hasSmoothed || !visible) {
                smoothed.set(target); // snap when (re)appearing
                hasSmoothed = true;
            } else {
                RemoteInterpolation.smooth(smoothed, target, RemoteInterpolation.SMOOTHING);
            }
            if (entity != null) {
                lastPosition.set(entity.posX, entity.posY, entity.posZ);
                if (visible) {
                    float roll = TransformRules.cameraRollDeg(smoothed.x, smoothed.y, smoothed.z, smoothed.w,
                            cameraTiltDeg());
                    entity.roll = roll;
                    entity.prevRoll = roll;
                }
            }
        }

        @Override
        public UUID playerId() {
            return id;
        }

        @Override
        public Vector3d position(float partialTicks, Vector3d dest) {
            EntityPlayer e = entity;
            if (e == null) {
                return dest.set(lastPosition);
            }
            return dest.set(e.lastTickPosX + (e.posX - e.lastTickPosX) * partialTicks,
                    e.lastTickPosY + (e.posY - e.lastTickPosY) * partialTicks,
                    e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * partialTicks);
        }

        @Override
        public Quaternionf attitude(Quaternionf dest) {
            return dest.set(smoothed);
        }

        @Override
        public float omega(int motor) {
            return newer != null ? newer.omega(motor) : 0f;
        }

        @Override
        public float cameraTiltDeg() {
            return build != null ? build.cameraAngle : DEFAULT_TILT_DEG;
        }

        @Override
        public DroneBuild build() {
            return build;
        }

        @Override
        public String toString() {
            return "RemoteDrone{" + id + ", visible=" + visible + '}';
        }
    }
}
