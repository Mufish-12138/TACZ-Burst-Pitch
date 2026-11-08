package cn.blockforge.taczpitch.client;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import cn.blockforge.taczpitch.PitchShiftConfig;
import cn.blockforge.taczpitch.TaczBurstPitchMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Hook: Forge's {@link PlaySoundEvent}.
 *
 * <p>Before Minecraft's sound engine starts playing a sound, Forge fires this event and lets us
 * swap the {@link SoundInstance} that will be played. We only pick out gunshot sounds and wrap
 * them in a {@link PitchShiftedSoundInstance}; everything else is left untouched.</p>
 *
 * <p>Both the local player's own shots and shots fired by <b>other players</b> are handled. The
 * shooter is identified by matching the sound's world position against nearby players holding a
 * gun whose shoot sound is the one being played. Because sound shaping always happens on the
 * listener's side, this is what makes a burst sound identical for everyone who hears it - as long
 * as those listeners also have this mod installed.</p>
 *
 * <p>None of TaCZ's firing, animation or recoil logic is touched, so it cannot break them. Even if
 * the wrapping fails, players just hear the original gunshot.</p>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = TaczBurstPitchMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GunSoundPitchHandler {

    /**
     * How far from a gunshot's position a player may be and still be considered its shooter.
     * TaCZ plays the sound at (roughly) the shooter's eyes/muzzle, so a few blocks of slack covers
     * the offset while still telling nearby players apart.
     */
    private static final double SHOOTER_SEARCH_RADIUS = 4.0D;

    /**
     * Gunshots we have wrapped and that may still be playing, used to reproduce TaCZ's own
     * concurrent-sound cap. Keyed by shooter and sound so one player's shots never cut off
     * another player's.
     */
    private static final Map<String, ArrayDeque<SoundInstance>> ACTIVE_SHOTS = new HashMap<>();

    private GunSoundPitchHandler() {
    }

    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        if (!PitchShiftConfig.SPEC.isLoaded() || !PitchShiftConfig.ENABLED.get()) {
            return;
        }
        if (!TaczBridge.isReady()) {
            return;
        }
        SoundInstance sound = event.getSound();
        if (!TaczBridge.isGunSound(sound)) {
            return;
        }
        ResourceLocation soundId = TaczBridge.registryName(sound);
        if (soundId == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer localPlayer = minecraft.player;
        if (localPlayer == null || minecraft.level == null) {
            return;
        }

        Player shooter = findShooter(minecraft, localPlayer, sound, soundId);
        if (shooter == null) {
            return;
        }
        // Other players' shots are always shaped too. There used to be a separate on/off option for
        // this, but that option was read from Forge's cached config value and only refreshed when
        // the config was reloaded, so other players' shots silently fell back to vanilla until the
        // file was touched. The feature is now part of the core behaviour instead.

        ItemStack gunStack = shooter.getMainHandItem();
        int shotKind = TaczBridge.classifyShot(gunStack, soundId);
        if (shotKind == TaczBridge.NOT_A_SHOT) {
            return;
        }
        if (shotKind == TaczBridge.SILENCED_SHOT && !PitchShiftConfig.AFFECT_SILENCED.get()) {
            return;
        }

        // Never read sound.getPitch() here! When the event fires the sound has not been resolved
        // yet, and in 1.20.1 AbstractSoundInstance#getPitch() multiplies by its internal (still
        // null) Sound field, which was the NullPointerException crash players used to hit.
        // So only register "which shot of the burst this is"; pitch is computed after resolution.
        //
        // For a gun in BURST mode this also reads how many rounds one trigger pull fires, so the
        // tracker can recognise the BURST gun and use its own continuation rule: shots keep counting
        // across trigger pulls, so a second burst does not restart the falloff. TaCZ plays one sound
        // per round, so the sound count already is the bullet count. It returns -1 for every other
        // fire mode, which keeps the original timing rule.
        int burstShotCount = TaczBridge.burstShotCount(gunStack);
        // TaCZ 1.2.0 lets a gun pack declare a fixed pitch for its shot sound. When it does, that
        // pitch is the reference the whole burst falls off from; otherwise the tracker uses TaCZ's
        // average random pitch. NaN means "the pack did not declare one".
        float nominalPitch = TaczBridge.nominalShotPitch(gunStack, soundId);
        int shotIndex = BurstPitchTracker.registerShot(shooter, soundId, burstShotCount, nominalPitch);

        // Read the magazine count here as well. For the local player the shot is client-predicted,
        // so the client has not decremented the magazine yet: this is the count *before* the shot,
        // which is exactly what the late-phase check needs. For other players this reads the synced
        // held-item stack; if the count is unavailable it returns -1 and the late phase is skipped.
        int remainingAmmo = TaczBridge.currentAmmoCount(gunStack);

        // The first shot is wrapped too: that way its resolved pitch is recorded as the base of the
        // whole burst and every later shot falls off from it. Its factor is 1.0, so it sounds
        // exactly like the original TaCZ shot.
        //
        // Shots fired by other players arrive as TaCZ's EntityTrackingGunSoundInstance, which is a
        // TickableSoundInstance that follows the shooter and stops itself. If we wrapped that in a
        // plain SoundInstance the sound engine would never tick it, so we keep the tickable contract
        // by wrapping it in a tickable wrapper. The local player's own shots use a plain instance.
        SoundInstance shifted = sound instanceof TickableSoundInstance tickable
                ? new TickablePitchShiftedSoundInstance(tickable, shooter.getUUID(), soundId, shotIndex, remainingAmmo)
                : new PitchShiftedSoundInstance(sound, shooter.getUUID(), soundId, shotIndex, remainingAmmo);
        event.setSound(shifted);
        limitConcurrentShots(minecraft, shooter, soundId, shifted);
    }

    /**
     * Works out which player fired the sound that is about to play.
     *
     * <p>Sounds played relative to the listener always belong to the local player. Otherwise the
     * sound's world position is compared against every player in the level, and the closest one
     * holding a gun whose shoot sound matches wins. This covers the local player's own shots (TaCZ
     * plays those at the player too) and shots fired by other players.</p>
     */
    private static Player findShooter(Minecraft minecraft, LocalPlayer localPlayer, SoundInstance sound,
                                      ResourceLocation soundId) {
        if (sound.isRelative()) {
            return TaczBridge.classifyShot(localPlayer.getMainHandItem(), soundId) != TaczBridge.NOT_A_SHOT
                    ? localPlayer
                    : null;
        }

        double soundX = sound.getX();
        double soundY = sound.getY();
        double soundZ = sound.getZ();

        Player best = null;
        double bestDistanceSqr = SHOOTER_SEARCH_RADIUS * SHOOTER_SEARCH_RADIUS;
        for (AbstractClientPlayer candidate : minecraft.level.players()) {
            if (TaczBridge.classifyShot(candidate.getMainHandItem(), soundId) == TaczBridge.NOT_A_SHOT) {
                continue;
            }
            double distanceSqr = candidate.distanceToSqr(soundX, soundY, soundZ);
            if (distanceSqr <= bestDistanceSqr) {
                bestDistanceSqr = distanceSqr;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * TaCZ limits how many shots of the same gun may overlap, but it tracks the instance we
     * replaced, so that limit would be lost. This restores it (per shooter and sound), keeping the
     * sound the same as vanilla TaCZ.
     */
    private static void limitConcurrentShots(Minecraft minecraft, Player shooter, ResourceLocation soundId,
                                             SoundInstance shifted) {
        int limit = PitchShiftConfig.MAX_SIMULTANEOUS_SHOTS.get();
        if (limit <= 0) {
            return;
        }
        String key = shooter.getUUID() + "|" + soundId;
        ArrayDeque<SoundInstance> playing = ACTIVE_SHOTS.computeIfAbsent(key, id -> new ArrayDeque<>());
        playing.removeIf(sound -> !minecraft.getSoundManager().isActive(sound));
        playing.addLast(shifted);
        while (playing.size() > limit) {
            SoundInstance oldest = playing.pollFirst();
            if (oldest != null) {
                minecraft.getSoundManager().stop(oldest);
            }
        }
    }

    /** Clear all state when leaving the world so leftover timers cannot affect the next burst. */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        BurstPitchTracker.clear();
        ACTIVE_SHOTS.clear();
    }

    /**
     * Drops the "already playing" bookkeeping. Called when the config is loaded or reloaded so a
     * parameter change cannot be masked by sounds that were wrapped with the old values.
     */
    public static void clearActiveShots() {
        ACTIVE_SHOTS.clear();
    }
}
