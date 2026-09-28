package cn.blockforge.taczpitch.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import cn.blockforge.taczpitch.PitchShiftConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

/**
 * Keeps track of how many shots each player has fired in the current burst, and turns that
 * shot number into the pitch / volume factor for that shot.
 *
 * <p>This is deliberately split into two steps - that is the lesson from the crash this mod
 * used to have:</p>
 * <ul>
 *   <li>{@link #registerShot} runs inside Forge's PlaySoundEvent and only touches timestamps.
 *       It never reads anything from the sound instance;</li>
 *   <li>{@link #pitchFor} runs only after the sound engine has resolved the sound (triggered
 *       from {@link PitchShiftedSoundInstance#getPitch()}). Reading the original pitch there
 *       is safe.</li>
 * </ul>
 *
 * <p>The reason is that in 1.20.1 {@code AbstractSoundInstance.getPitch()} multiplies by the
 * {@code this.sound} field, and that field is still null when the event fires. Reading it too
 * early throws a NullPointerException.</p>
 *
 * <p>Pitch is built around one fixed reference for the whole burst, not as a discount on each
 * shot's own (random) pitch. That keeps a burst sounding like one smooth falloff. TaCZ's own
 * per-shot random pitch is replaced by this mod's own, smaller random range, which is sampled a
 * single time for the whole burst (the "sampled" first-shot pitch) - see {@link #pitchFor} and
 * {@link PitchShiftConfig#RANDOM_PITCH_RANGE}.</p>
 */
public final class BurstPitchTracker {

    private static final Map<UUID, Burst> BURSTS = new HashMap<>();
    /** Safety cap so the map cannot grow forever. A normal session never gets close. */
    private static final int MAX_TRACKED_PLAYERS = 64;

    /** Volume is clamped to this range no matter what the config says, so it never goes silent or clips. */
    private static final double MIN_VOLUME_FACTOR = 0.05D;
    private static final double MAX_VOLUME_FACTOR = 2.0D;

    /**
     * Hard safety floor for the pitch factor. This is intentionally <b>not</b> configurable:
     * a pitch of zero or below is invalid and would break the sound. It is low enough that the
     * configured early / mid / late phases never reach it in practice.
     */
    private static final double MIN_PITCH_FACTOR = 0.05D;

    /**
     * How TaCZ turns the pitch of one gunshot into a random value. TaCZ's
     * {@code SoundPlayManager.playShootSound} (local shot) and
     * {@code ModernKineticGunScriptAPI.shootOnce} (shot heard from another player) both use
     * {@code 0.9 + random.nextFloat() * 0.125}: a 0.125 (12.5%) span, i.e. roughly +/-6% of wobble
     * around 0.9625.
     *
     * <p>When the random-pitch override is on we throw TaCZ's random part away and keep only its
     * average (the middle of that span), then add our own, smaller random on top. That way the
     * average gunshot pitch is unchanged - only the wobble shrinks to the configured size.</p>
     */
    private static final double TACZ_PITCH_BASE = 0.9D;
    private static final double TACZ_PITCH_SPAN = 0.125D;
    private static final double TACZ_NOMINAL_PITCH = TACZ_PITCH_BASE + TACZ_PITCH_SPAN / 2.0D;

    private static final class Burst {
        private ResourceLocation lastSoundId;
        private long lastShotMillis;
        private long lastGapMillis;
        /** 0 means this is the first shot of the burst. Keeps growing across trigger pulls. */
        private int shotIndex;
        /** Actual pitch of this burst's first shot, recorded when it plays. Base for every later shot. */
        private float basePitch = Float.NaN;
        /**
         * For a BURST gun, how many rounds one trigger pull fires. -1 means the previous shot was not
         * a BURST shot (or the count could not be read).
         *
         * <p>This is only used to recognise a BURST gun and give it its own, more forgiving
         * continuation rule. It is deliberately <b>not</b> used to cut the burst into rounds: a BURST
         * gun must keep counting shots across trigger pulls.</p>
         */
        private int burstShotCount = -1;
    }

    private BurstPitchTracker() {
    }

    /**
     * Registers this shot and returns its index inside the current burst (0 = first shot).
     *
     * <p>It does not need to know whether the trigger is held: as long as the pause since the
     * previous shot does not clearly exceed the configured limit, it still counts as the same burst.
     * Once the player stops for longer than {@code burstResetMillis}, or switches gun, the index
     * resets to 0, i.e. the next shot is treated as a fresh burst.</p>
     *
     * <p><b>BURST guns</b> need one extra rule. TaCZ fires a BURST gun as one trigger pull that
     * immediately spits out {@code burstShotCount} rounds, and it plays one gun sound per round
     * (locally in {@code LocalPlayerShoot}, for other players from the server's per-round
     * {@code ServerMessageSound}). Because every round makes its own sound, the sound count already
     * equals the bullet count - there is no need to multiply anything.</p>
     *
     * <p>The important part is that a new trigger pull must <b>not</b> restart the falloff. If a
     * burst gun keeps firing with gaps that satisfy the continuous-fire rule, the shots keep counting
     * across trigger pulls: a 4-round burst followed by another 4-round burst at once makes the
     * second burst's first bullet the 5th bullet of one long burst, not a fresh burst. That is why
     * the round size is never used to reset the index here. The only difference for BURST guns is
     * that the pause between two trigger pulls is naturally longer than the pause between two rounds
     * of the same pull, so they break the burst only after the full configured
     * {@code burstResetMillis} rather than after a multiple of the previous gap.</p>
     *
     * <p>This method only reads the system clock and never touches the sound instance, so it
     * cannot trigger the NullPointerException described in the class comment.</p>
     *
     * @param burstShotCount rounds one trigger pull fires when the gun is in BURST mode; -1 for
     *                       everything else
     */
    public static int registerShot(Player player, ResourceLocation soundId, int burstShotCount) {
        long now = System.currentTimeMillis();
        long resetFloor = Math.max(50L, PitchShiftConfig.BURST_RESET_MILLIS.get());

        Burst burst = BURSTS.get(player.getUUID());
        if (burst == null) {
            if (BURSTS.size() >= MAX_TRACKED_PLAYERS) {
                BURSTS.clear();
            }
            burst = new Burst();
            burst.lastSoundId = soundId;
            burst.lastShotMillis = now;
            burst.lastGapMillis = 0L;
            burst.shotIndex = 0;
            burst.basePitch = Float.NaN;
            burst.burstShotCount = burstShotCount;
            BURSTS.put(player.getUUID(), burst);
            return 0;
        }

        long gap = now - burst.lastShotMillis;
        boolean sameGun = soundId.equals(burst.lastSoundId) && burst.burstShotCount == burstShotCount;

        boolean continues;
        if (burstShotCount > 0) {
            // BURST: keep counting across trigger pulls. Only a pause longer than the configured
            // limit counts as breaking the burst, because the pause between two trigger pulls is
            // already longer than the pause between rounds of one pull.
            continues = sameGun && gap <= resetFloor;
        } else {
            // A gap clearly longer than the previous one, or switching gun, breaks the burst.
            // lastGapMillis is capped at resetFloor, so the threshold is at most twice resetFloor -
            // that way slow-firing automatics are not mistaken for a new burst on every shot.
            continues = sameGun && gap <= Math.max(resetFloor, burst.lastGapMillis * 2L);
        }

        if (continues) {
            burst.shotIndex++;
        } else {
            burst.shotIndex = 0;
            burst.basePitch = Float.NaN;
        }

        burst.burstShotCount = burstShotCount;
        burst.lastSoundId = soundId;
        burst.lastGapMillis = Math.min(gap, resetFloor);
        burst.lastShotMillis = now;
        return burst.shotIndex;
    }

    /**
     * Computes the pitch this shot should actually use.
     *
     * <p>Only call this after the sound instance has been resolved (see class comment).</p>
     *
     * <p>Two modes:</p>
     * <ul>
     *   <li><b>Random-pitch override on</b> (the default): TaCZ's own random pitch is discarded and
     *       the burst is rebuilt around TaCZ's average pitch (0.9625). The {@code randomPitchRange}
     *       offset is drawn <b>once per burst</b>, at its first shot, and stored as that burst's base
     *       pitch: later shots are {@code base * shape}, where {@code shape} is the early / mid /
     *       late falloff. So the whole burst is one "sampled" sound - just like a single audio
     *       sample played back at a fixed random speed - instead of each shot rolling its own
     *       random pitch.</li>
     *   <li><b>Override off</b>: the old behaviour, kept so the option can be turned off completely.
     *       The first shot records its own pitch as the burst base and returns it unchanged, and
     *       every later shot is the base times the falloff - so the burst keeps whatever random
     *       pitch TaCZ gave its first shot.</li>
     * </ul>
     *
     * @param originalPitch the shot's original pitch, already resolved by the game
     * @param remainingAmmo rounds left in the magazine before this shot; -1 when unknown
     */
    public static float pitchFor(UUID playerId, ResourceLocation soundId, int shotIndex, float originalPitch,
                                 int remainingAmmo) {
        boolean overrideRandomPitch = PitchShiftConfig.SPEC.isLoaded()
                && PitchShiftConfig.OVERRIDE_RANDOM_PITCH.get();

        Burst burst = BURSTS.get(playerId);
        boolean sameBurst = burst != null && burst.shotIndex == shotIndex && soundId.equals(burst.lastSoundId);

        double reference;
        if (overrideRandomPitch) {
            // Do not read TaCZ's random pitch at all: rebuild it around TaCZ's average.
            if (sameBurst && !Float.isNaN(burst.basePitch)) {
                // A later shot of the burst reuses the pitch sampled by its first shot.
                reference = burst.basePitch;
            } else {
                // First shot of the burst: take the one random sample that the whole burst plays at.
                // If the first shot's sound never resolved (e.g. a listener joined mid-burst and this
                // is the first shot they hear), sample here so the burst still gets a stable base.
                double sampled = TACZ_NOMINAL_PITCH * randomPitchJitter();
                if (sameBurst) {
                    burst.basePitch = (float) sampled;
                }
                reference = sampled;
            }
            return (float) (reference * pitchFactor(shotIndex, remainingAmmo));
        }

        reference = originalPitch;
        if (sameBurst) {
            if (shotIndex == 0) {
                if (Float.isNaN(burst.basePitch)) {
                    burst.basePitch = originalPitch;
                }
            } else if (!Float.isNaN(burst.basePitch)) {
                reference = burst.basePitch;
            }
        }
        return (float) (reference * pitchFactor(shotIndex, remainingAmmo));
    }

    /**
     * One random pitch multiplier of {@code 1 +/- randomPitchRange} (default +/-2%).
     *
     * <p>Unlike before, this is <b>not</b> drawn per shot. It is sampled once at the start of a
     * burst and then stored as the burst's base pitch (see {@link #pitchFor}), so every shot of the
     * burst plays at the same randomly sampled pitch and only the falloff shape changes between
     * shots.</p>
     */
    private static double randomPitchJitter() {
        double range = PitchShiftConfig.RANDOM_PITCH_RANGE.get();
        if (range <= 0.0D) {
            return 1.0D;
        }
        return 1.0D + ThreadLocalRandom.current().nextDouble(-range, range);
    }

    /**
     * Computes the volume factor this shot should use (1.0 = original volume).
     *
     * <p>Like pitch, the first shot is always 1.0 so it sounds exactly like vanilla TaCZ.</p>
     *
     * <p>This value is computed fresh on every call: the mid phase contains a random number, so a
     * sound instance must compute it once and cache it. Otherwise the engine reading the volume
     * several times would make one shot wobble.</p>
     *
     * @param remainingAmmo rounds left in the magazine before this shot; -1 when unknown
     */
    public static float volumeFor(int shotIndex, int remainingAmmo) {
        if (!PitchShiftConfig.SPEC.isLoaded() || !PitchShiftConfig.VOLUME_SHAPING_ENABLED.get()) {
            return 1.0F;
        }
        return (float) volumeFactor(shotIndex, remainingAmmo);
    }

    /**
     * Volume curve, sharing the same early / mid / late split as pitch:
     *
     * <ul>
     *   <li>first shot: 1.0, original volume;</li>
     *   <li>early phase (first {@code earlyPhaseShots} shots of the burst): each shot is the
     *       previous one multiplied by {@code 1 - earlyVolumeDecreasePerShot} (default 6% quieter
     *       per shot);</li>
     *   <li>mid phase: volume stays around the early-phase end value and floats by
     *       {@code +/- midVolumeRandomRange} per shot (default +/-3%, louder or quieter);</li>
     *   <li>last {@code latePhaseSwitchCount} rounds of the magazine (default 5): random floating
     *       stops and volume rises by {@code lateVolumeIncreasePerShot} per shot (default 4.7%),
     *       which sounds like the gun "pushing" right before it runs dry.</li>
     * </ul>
     */
    private static double volumeFactor(int shotIndex, int remainingAmmo) {
        if (shotIndex <= 0) {
            return 1.0D;
        }

        int earlyShots = Math.max(1, PitchShiftConfig.EARLY_PHASE_SHOTS.get());
        double earlyDecrease = PitchShiftConfig.EARLY_VOLUME_DECREASE_PER_SHOT.get();
        double perShotKeep = Math.max(0.0D, 1.0D - earlyDecrease);
        // Volume at the end of the early phase, used as the base for the mid and late phases.
        double midBase = Math.pow(perShotKeep, earlyShots);

        double factor;
        if (shotIndex <= earlyShots) {
            factor = Math.pow(perShotKeep, shotIndex);
        } else {
            int lateCount = PitchShiftConfig.LATE_PHASE_SWITCH_COUNT.get();
            if (lateCount > 0 && remainingAmmo >= 1 && remainingAmmo <= lateCount) {
                // remainingAmmo is counted before this shot, so 5 -> first late shot, 1 -> last round.
                int progress = Math.min(lateCount, lateCount - remainingAmmo + 1);
                double increase = PitchShiftConfig.LATE_VOLUME_INCREASE_PER_SHOT.get();
                factor = midBase * Math.pow(1.0D + increase, progress);
            } else {
                double range = PitchShiftConfig.MID_VOLUME_RANDOM_RANGE.get();
                double offset = range <= 0.0D
                        ? 0.0D
                        : ThreadLocalRandom.current().nextDouble(-range, range);
                factor = midBase * (1.0D + offset);
            }
        }
        return Math.max(MIN_VOLUME_FACTOR, Math.min(MAX_VOLUME_FACTOR, factor));
    }

    /**
     * Computes the pitch factor for this shot (1.0 = original pitch), in three phases:
     *
     * <ul>
     *   <li>first shot (shotIndex = 0) is always 1.0 and sounds exactly like vanilla TaCZ;</li>
     *   <li>early phase: starting at shot 2, {@code earlyPhaseShots} shots, each dropping
     *       {@code earlyPitchDecreasePerShot} (default 0.007);</li>
     *   <li>mid phase: after the early phase pitch no longer falls steadily, it floats by
     *       {@code +/- midPitchRandomRange} (default +/-0.03) around the early-phase end value -
     *       it can go back up or drop lower;</li>
     *   <li>late phase: with {@code latePhaseSwitchCount} rounds left (default 5) and past the
     *       early phase, random floating stops and each shot drops
     *       {@code latePitchDecreasePerShot} (default 0.01);</li>
     *   <li>the result never goes above 1.0 and never below a hard internal safety floor; there is
     *       no configurable minimum pitch anymore.</li>
     * </ul>
     *
     * <p>All parameters are read from the config on the fly, so tuning the file in-game adjusts
     * the feel.</p>
     *
     * @param remainingAmmo rounds left in the magazine before this shot; -1 means unknown, which
     *                      is treated as an ordinary mid-phase shot
     */
    private static double pitchFactor(int shotIndex, int remainingAmmo) {
        if (shotIndex <= 0) {
            return 1.0D;
        }

        int earlyShots = Math.max(1, PitchShiftConfig.EARLY_PHASE_SHOTS.get());
        double earlyDropPerShot = PitchShiftConfig.EARLY_PITCH_DECREASE_PER_SHOT.get();

        // Pitch at the end of the early phase, used as the base for the mid and late phases.
        double earlyEndFactor = 1.0D - earlyDropPerShot * earlyShots;

        double factor;
        if (shotIndex <= earlyShots) {
            factor = 1.0D - earlyDropPerShot * shotIndex;
        } else {
            int lateCount = PitchShiftConfig.LATE_PHASE_SWITCH_COUNT.get();
            if (lateCount > 0 && remainingAmmo >= 1 && remainingAmmo <= lateCount) {
                // remainingAmmo is counted before this shot, so 5 -> first late shot, 1 -> last round.
                int progress = Math.min(lateCount, lateCount - remainingAmmo + 1);
                factor = earlyEndFactor - PitchShiftConfig.LATE_PITCH_DECREASE_PER_SHOT.get() * progress;
            } else {
                double range = PitchShiftConfig.MID_PITCH_RANDOM_RANGE.get();
                double offset = range <= 0.0D
                        ? 0.0D
                        : ThreadLocalRandom.current().nextDouble(-range, range);
                factor = earlyEndFactor + offset;
            }
        }
        return Math.max(MIN_PITCH_FACTOR, Math.min(1.0D, factor));
    }

    public static void clear() {
        BURSTS.clear();
    }
}
