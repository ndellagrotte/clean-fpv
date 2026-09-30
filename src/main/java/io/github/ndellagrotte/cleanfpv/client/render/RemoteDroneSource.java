package io.github.ndellagrotte.cleanfpv.client.render;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Lookup of {@link RemoteDroneView}s, implemented by {@code client.net.RemoteDrones} and installed
 * into {@code ClientDroneContext.setRemoteDrones} at client init. Defaults to {@link #EMPTY}.
 */
public interface RemoteDroneSource {

    /** The armed remote pilot with this UUID, or {@code null}. */
    RemoteDroneView get(UUID playerId);

    /** All armed remote pilots currently known. */
    Collection<? extends RemoteDroneView> all();

    RemoteDroneSource EMPTY = new RemoteDroneSource() {
        @Override
        public RemoteDroneView get(UUID playerId) {
            return null;
        }

        @Override
        public Collection<? extends RemoteDroneView> all() {
            return List.of();
        }
    };
}
