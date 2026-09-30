package io.github.ndellagrotte.cleanfpv.common;

import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;
import net.minecraft.world.World;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * UUID → {@link ArmedState}, one instance per logical side (PLAN §4). On an integrated server
 * both instances live in one JVM, which is why they are separate: always pick by side
 * ({@link #of(World)} / {@link #of(boolean)}), never share.
 *
 * <ul>
 *   <li>{@link #SERVER}: written by the server packet handlers; read by server tick hooks
 *       (size/abilities re-assert, motion echo) and lifecycle cleanup.</li>
 *   <li>{@link #CLIENT}: <em>remote</em> players as seen by this client (relayed Arm/Build/
 *       Transform). The local player's own armed state is {@code ClientDroneContext}, not this
 *       registry; client handlers ignore their own UUID's echoes.</li>
 * </ul>
 *
 * <p>Thread-safety: the map is a {@link ConcurrentHashMap} so lookups from any thread are safe,
 * but {@link ArmedState} objects are plain mutable data and must only be mutated on the owning
 * side's main thread (server thread / client thread). Network handlers hop there via
 * {@code addScheduledTask} before touching the registry.
 */
public final class ArmRegistry {

    public static final ArmRegistry SERVER = new ArmRegistry("server");
    public static final ArmRegistry CLIENT = new ArmRegistry("client");

    private final String side;
    private final Map<UUID, ArmedState> states = new ConcurrentHashMap<>();

    private ArmRegistry(String side) {
        this.side = side;
    }

    public static ArmRegistry of(boolean remote) {
        return remote ? CLIENT : SERVER;
    }

    public static ArmRegistry of(World world) {
        return of(world.isRemote);
    }

    /** The state for {@code id}, or {@code null}. */
    public ArmedState get(UUID id) {
        return states.get(id);
    }

    public ArmedState getOrCreate(UUID id) {
        return states.computeIfAbsent(id, ArmedState::new);
    }

    public boolean isArmed(UUID id) {
        ArmedState s = states.get(id);
        return s != null && s.armed();
    }

    /** Sets the armed flag (creating the entry); returns whether it changed. */
    public boolean setArmed(UUID id, boolean armed) {
        ArmedState s = getOrCreate(id);
        boolean changed = s.armed() != armed;
        s.setArmed(armed, System.currentTimeMillis());
        return changed;
    }

    public void setBuild(UUID id, DroneBuild build) {
        getOrCreate(id).setBuild(build, System.currentTimeMillis());
    }

    public void setTransform(UUID id, TransformSnapshot transform) {
        getOrCreate(id).setTransform(transform, System.currentTimeMillis());
    }

    /** Removes and returns the entry (lifecycle: logout, death, respawn, dimension change). */
    public ArmedState remove(UUID id) {
        return states.remove(id);
    }

    /** Drops every entry (client: disconnect / world unload; server: server stopping). */
    public void clear() {
        states.clear();
    }

    /** Read-only live view of all entries. */
    public Collection<ArmedState> all() {
        return Collections.unmodifiableCollection(states.values());
    }

    public void forEachArmed(Consumer<ArmedState> action) {
        for (ArmedState s : states.values()) {
            if (s.armed()) {
                action.accept(s);
            }
        }
    }

    public int size() {
        return states.size();
    }

    @Override
    public String toString() {
        return "ArmRegistry{" + side + ", " + states.size() + " entries}";
    }
}
