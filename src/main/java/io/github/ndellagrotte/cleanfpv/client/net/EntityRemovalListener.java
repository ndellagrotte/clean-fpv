package io.github.ndellagrotte.cleanfpv.client.net;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IWorldEventListener;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * World listener that only forwards {@code onEntityRemoved}: 1.12 has no entity-leave event
 * (api-notes D6), and {@code World.onEntityRemoved} notifies every {@link IWorldEventListener}
 * when an entity is unloaded or destroyed (untracked, logged out, dead, dimension change). One
 * instance per client world, added on {@code WorldEvent.Load}; it dies with the world.
 */
final class EntityRemovalListener implements IWorldEventListener {

    private final Consumer<Entity> onRemoved;

    EntityRemovalListener(Consumer<Entity> onRemoved) {
        this.onRemoved = onRemoved;
    }

    @Override
    public void onEntityRemoved(Entity entity) {
        onRemoved.accept(entity);
    }

    @Override
    public void onEntityAdded(Entity entity) {
    }

    @Override
    public void notifyBlockUpdate(World world, BlockPos pos, IBlockState oldState, IBlockState newState, int flags) {
    }

    @Override
    public void notifyLightSet(BlockPos pos) {
    }

    @Override
    public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {
    }

    @Override
    public void playSoundToAllNearExcept(@Nullable EntityPlayer player, SoundEvent sound, SoundCategory category,
                                         double x, double y, double z, float volume, float pitch) {
    }

    @Override
    public void playRecord(SoundEvent sound, BlockPos pos) {
    }

    @Override
    public void spawnParticle(int particleId, boolean ignoreRange, double x, double y, double z,
                              double xSpeed, double ySpeed, double zSpeed, int... parameters) {
    }

    @Override
    public void spawnParticle(int id, boolean ignoreRange, boolean minimiseParticleLevel, double x, double y,
                              double z, double xSpeed, double ySpeed, double zSpeed, int... parameters) {
    }

    @Override
    public void broadcastSound(int soundId, BlockPos pos, int data) {
    }

    @Override
    public void playEvent(EntityPlayer player, int type, BlockPos pos, int data) {
    }

    @Override
    public void sendBlockBreakProgress(int breakerId, BlockPos pos, int progress) {
    }
}
