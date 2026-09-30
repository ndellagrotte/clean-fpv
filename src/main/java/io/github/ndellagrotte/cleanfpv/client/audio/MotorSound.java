package io.github.ndellagrotte.cleanfpv.client.audio;

import net.minecraft.client.audio.MovingSound;
import net.minecraft.entity.Entity;
import net.minecraft.init.SoundEvents;
import net.minecraft.util.SoundCategory;

import java.util.function.DoubleSupplier;

/**
 * Looping motor tone attached to one drone (spec §7, PLAN §6.5): vanilla's elytra wind loop
 * ({@link SoundEvents#ITEM_ELYTRA_FLYING}, {@link SoundCategory#PLAYERS}, repeat, no delay) with
 * pitch and volume driven by motor 0 through {@link MotorTone}.
 *
 * <p>{@link #update()} runs once per client tick in the sound engine (20 Hz, paused with the game):
 * it follows the entity's position and ends the sound once {@link #finish()} was called or the
 * entity is dead/removed. The initial volume is {@link MotorTone#MIN_VOLUME} (never 0, which the
 * engine would silently skip).
 */
public final class MotorSound extends MovingSound {

    private final Entity entity;
    private final DoubleSupplier omega0;
    private final DoubleSupplier omegaMax;
    private boolean finished;

    /**
     * @param entity   the pilot (position source)
     * @param omega0   motor 0 speed, rad/s (signed; magnitude used)
     * @param omegaMax the drone's no-load speed, rad/s
     */
    public MotorSound(Entity entity, DoubleSupplier omega0, DoubleSupplier omegaMax) {
        super(SoundEvents.ITEM_ELYTRA_FLYING, SoundCategory.PLAYERS);
        this.entity = entity;
        this.omega0 = omega0;
        this.omegaMax = omegaMax;
        this.repeat = true;
        this.repeatDelay = 0;
        applyTone();
        follow();
    }

    public Entity entity() {
        return entity;
    }

    /** Ends the loop at the engine's next update. */
    public void finish() {
        finished = true;
        donePlaying = true;
    }

    public boolean isFinished() {
        return finished;
    }

    @Override
    public void update() {
        if (finished || entity.isDead) {
            finished = true;
            donePlaying = true;
            return;
        }
        follow();
        applyTone();
    }

    private void follow() {
        xPosF = (float) entity.posX;
        yPosF = (float) entity.posY;
        zPosF = (float) entity.posZ;
    }

    private void applyTone() {
        float r = MotorTone.ratio(omega0.getAsDouble(), omegaMax.getAsDouble());
        pitch = MotorTone.pitch(r);
        volume = MotorTone.volume(r);
    }
}
