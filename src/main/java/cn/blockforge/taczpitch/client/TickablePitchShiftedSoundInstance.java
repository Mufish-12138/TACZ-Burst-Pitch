package cn.blockforge.taczpitch.client;

import java.util.UUID;

import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.resources.ResourceLocation;

/**
 * Pitch/volume shifted wrapper for TaCZ gun sounds the game has to tick every frame.
 *
 * <p>TaCZ plays a gunshot heard from <b>another player</b> as its
 * {@code EntityTrackingGunSoundInstance}, which implements {@link TickableSoundInstance}: the sound
 * engine calls {@link #tick()} every frame so the sound follows the shooter and stops itself once
 * that player is gone. Minecraft keys that behaviour purely off {@code instanceof
 * TickableSoundInstance}, so if we wrapped such a sound in a plain {@link PitchShiftedSoundInstance}
 * the engine would stop ticking it and the shot would be left behind at its spawn position.</p>
 *
 * <p>This subclass keeps the tickable contract while all pitch/volume shaping is inherited from
 * {@link PitchShiftedSoundInstance}. Every other method (location, pitch, volume, position, ...)
 * still forwards to the wrapped instance through the superclass.</p>
 */
public final class TickablePitchShiftedSoundInstance extends PitchShiftedSoundInstance
        implements TickableSoundInstance {

    private final TickableSoundInstance tickableDelegate;

    public TickablePitchShiftedSoundInstance(TickableSoundInstance delegate, UUID playerId, ResourceLocation soundId,
                                             int shotIndex, int remainingAmmo) {
        super(delegate, playerId, soundId, shotIndex, remainingAmmo);
        this.tickableDelegate = delegate;
    }

    @Override
    public void tick() {
        tickableDelegate.tick();
    }

    @Override
    public boolean isStopped() {
        return tickableDelegate.isStopped();
    }

    @Override
    public String toString() {
        return "TickablePitchShiftedSoundInstance[" + delegate + "]";
    }
}
