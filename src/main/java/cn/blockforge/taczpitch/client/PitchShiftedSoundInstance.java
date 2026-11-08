package cn.blockforge.taczpitch.client;

import java.util.UUID;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

/**
 * 把 TaCZ 原本要播放的那个枪声实例包一层，只改 {@link #getPitch()} 的返回值，
 * 其余方法原样转发给里面的实例。
 *
 * <p>这样不用改 TaCZ 的任何代码，也不影响它自己读取音效文件、做距离衰减等逻辑。</p>
 *
 * <p><b>注意音调是「懒算」的</b>：这个实例刚在 PlaySoundEvent 里被创建时，
 * 里面的枪声还没被游戏解析（内部 Sound 字段是 null），此时读原音高会空指针。
 * 所以这里不在构造时算，而是等游戏调用 {@link #getPitch()} 时才算——那时解析已经完成。
 * 这也是上一版一开枪就崩的根因所在。</p>
 */
public class PitchShiftedSoundInstance implements SoundInstance {

    protected final SoundInstance delegate;
    private final UUID playerId;
    private final ResourceLocation soundId;
    /** 这一发在本次连射里的序号，0 = 第一发。 */
    private final int shotIndex;
    /** 这一发打出前弹匣里还剩几发；-1 表示没取到（例如不是 TaCZ 的枪）。 */
    private final int remainingAmmo;
    /** 音量倍率只算一次：慢速阶段带随机数，重算会让同一个枪声忽大忽小。 */
    private float volumeFactor = Float.NaN;
    /** 音调倍率也只算一次：慢速段同样带随机数，重算会让同一个枪声忽高忽低。 */
    private float pitchFactor = Float.NaN;

    public PitchShiftedSoundInstance(SoundInstance delegate, UUID playerId, ResourceLocation soundId, int shotIndex,
                                     int remainingAmmo) {
        this.delegate = delegate;
        this.playerId = playerId;
        this.soundId = soundId;
        this.shotIndex = shotIndex;
        this.remainingAmmo = remainingAmmo;
    }

    @Override
    public ResourceLocation getLocation() {
        return delegate.getLocation();
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        return delegate.resolve(manager);
    }

    @Override
    public Sound getSound() {
        return delegate.getSound();
    }

    @Override
    public SoundSource getSource() {
        return delegate.getSource();
    }

    @Override
    public boolean isLooping() {
        return delegate.isLooping();
    }

    @Override
    public boolean isRelative() {
        return delegate.isRelative();
    }

    @Override
    public int getDelay() {
        return delegate.getDelay();
    }

    @Override
    public float getVolume() {
        // 和下面 getPitch() 同理：1.20.1 的 AbstractSoundInstance#getVolume() 会去乘内部
        // Sound 字段（音量/音高在 Sound 里是 SampledFloat），解析完成前读它会空指针。
        // 别的模组（音效物理、光影的声音处理等）可能提前来读，这里一律先挡住。
        if (delegate.getSound() == null) {
            return 1.0F;
        }
        if (Float.isNaN(volumeFactor)) {
            volumeFactor = BurstPitchTracker.volumeFor(shotIndex, remainingAmmo);
        }
        return delegate.getVolume() * volumeFactor;
    }

    @Override
    public float getPitch() {
        // 正常情况下游戏是在 resolve() 之后才调用这里，原始音效已经能安全读取。
        // 万一还有别的模组在解析之前就来读，就退回 1.0（正常音），绝不冒空指针的风险。
        if (delegate.getSound() == null) {
            return 1.0F;
        }
        if (Float.isNaN(pitchFactor)) {
            pitchFactor = BurstPitchTracker.pitchFor(playerId, soundId, shotIndex, delegate.getPitch(), remainingAmmo);
        }
        return pitchFactor;
    }

    @Override
    public double getX() {
        return delegate.getX();
    }

    @Override
    public double getY() {
        return delegate.getY();
    }

    @Override
    public double getZ() {
        return delegate.getZ();
    }

    @Override
    public SoundInstance.Attenuation getAttenuation() {
        return delegate.getAttenuation();
    }

    @Override
    public boolean canStartSilent() {
        return delegate.canStartSilent();
    }

    @Override
    public boolean canPlaySound() {
        return delegate.canPlaySound();
    }

    @Override
    public String toString() {
        return "PitchShiftedSoundInstance[shot=" + shotIndex + ", " + delegate + "]";
    }
}
