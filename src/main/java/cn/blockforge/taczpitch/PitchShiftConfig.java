package cn.blockforge.taczpitch;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Client config, written to config/tacz_burst_pitch-client.toml.
 *
 * <p>A burst is split into three phases:</p>
 * <ul>
 *   <li><b>Early phase</b> - the first shots after the opening normal shot. Pitch and volume
 *       change by a fixed amount per shot.</li>
 *   <li><b>Mid phase</b> - pitch and volume float randomly around the value the early phase
 *       ended with.</li>
 *   <li><b>Late phase</b> - the last few rounds of the magazine. Random floating stops and pitch
 *       falls while volume rises by a fixed amount per shot.</li>
 * </ul>
 */
public final class PitchShiftConfig {

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.BooleanValue OVERRIDE_RANDOM_PITCH;
    public static final ForgeConfigSpec.DoubleValue RANDOM_PITCH_RANGE;
    public static final ForgeConfigSpec.IntValue EARLY_PHASE_SHOTS;
    public static final ForgeConfigSpec.DoubleValue EARLY_PITCH_DECREASE_PER_SHOT;
    public static final ForgeConfigSpec.DoubleValue MID_PITCH_RANDOM_RANGE;
    public static final ForgeConfigSpec.DoubleValue LATE_PITCH_DECREASE_PER_SHOT;
    public static final ForgeConfigSpec.BooleanValue VOLUME_SHAPING_ENABLED;
    public static final ForgeConfigSpec.DoubleValue EARLY_VOLUME_DECREASE_PER_SHOT;
    public static final ForgeConfigSpec.DoubleValue MID_VOLUME_RANDOM_RANGE;
    public static final ForgeConfigSpec.IntValue LATE_PHASE_SWITCH_COUNT;
    public static final ForgeConfigSpec.DoubleValue LATE_VOLUME_INCREASE_PER_SHOT;
    public static final ForgeConfigSpec.IntValue BURST_RESET_MILLIS;
    public static final ForgeConfigSpec.BooleanValue AFFECT_SILENCED;
    public static final ForgeConfigSpec.IntValue MAX_SIMULTANEOUS_SHOTS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("TACZ burst pitch falloff / TACZ 连发音效衰减").push("burst_pitch");

        ENABLED = builder
                .comment("Master switch. When false, gun sounds play exactly like in TaCZ.",
                        "总开关。false 时枪声完全保持 TaCZ 原样。")
                .define("enabled", true);

        OVERRIDE_RANDOM_PITCH = builder
                .comment("Whether this mod replaces TaCZ's own random gunshot pitch.",
                        "TaCZ gives every shot a random pitch (0.9 + random * 0.125, i.e. a 12.5% span",
                        "around 0.9625, roughly +/-6%). When true, that random part is thrown away and",
                        "replaced with the range below. Default true.",
                        "是否用本模组自己的随机音高覆盖 TaCZ 自带的随机音高。TaCZ 每发枪声的音调是",
                        "0.9 + 随机数 * 0.125（跨度 12.5%，约 ±6%）；开启后丢弃它自带的随机，",
                        "改用下面的幅度。默认 true。")
                .define("overrideTaczRandomPitch", true);

        RANDOM_PITCH_RANGE = builder
                .comment("Random pitch half-range of the burst's FIRST shot only.",
                        "The offset is sampled once when the burst starts and then kept as the pitch of",
                        "the whole burst, so every later shot falls off from that same sampled pitch",
                        "instead of rolling a new random on each shot.",
                        "0.02 = +/-2% (the new default, replacing TaCZ's roughly +/-6%);",
                        "0.0 = no random pitch at all.",
                        "连射「第一发」随机音高的半幅。只在整段连射开始时抽一次，之后每一发都以这个",
                        "抽到的音高为基准继续衰减，而不再逐发叠加随机。",
                        "0.02 即 ±2%（新的默认值，取代 TaCZ 自带的约 ±6%）；0 表示完全不加随机音高。")
                .defineInRange("randomPitchRange", 0.02D, 0.0D, 0.5D);

        EARLY_PHASE_SHOTS = builder
                .comment("How many shots the early phase contains.",
                        "The opening shot keeps the normal sound and is not counted, so the default 6",
                        "means the 2nd through 7th shots of a burst.",
                        "前期（Early phase）一共几发。第一发保持原音、不计入，默认 6 即第 2~7 发。")
                .defineInRange("earlyPhaseShots", 6, 1, 64);

        EARLY_PITCH_DECREASE_PER_SHOT = builder
                .comment("Early phase: fixed pitch-factor drop per shot. Default 0.007.",
                        "前期音调：每一发固定下调多少倍率，默认 0.007。")
                .defineInRange("earlyPitchDecreasePerShot", 0.007D, 0.0D, 0.5D);

        MID_PITCH_RANDOM_RANGE = builder
                .comment("Mid phase: pitch stops falling steadily and instead floats by this much",
                        "around the value the early phase ended with (0.015 = +/-0.015). Default 0.015.",
                        "中期音调：围绕前期结束时的音调上下浮动，默认 0.015 即 +/-0.015。")
                .defineInRange("midPitchRandomRange", 0.015D, 0.0D, 0.5D);

        LATE_PITCH_DECREASE_PER_SHOT = builder
                .comment("Late phase: random floating is replaced by a fixed pitch-factor drop per shot.",
                        "Default 0.01.",
                        "末期音调：取消随机浮动，改为每一发固定下调多少倍率，默认 0.01。")
                .defineInRange("latePitchDecreasePerShot", 0.01D, 0.0D, 0.5D);

        VOLUME_SHAPING_ENABLED = builder
                .comment("Whether the volume (amplitude) is shaped along with the pitch.",
                        "是否在改音调的同时也改音量（振幅）。false 时只改音调、音量保持 TaCZ 原样。")
                .define("volumeShapingEnabled", true);

        EARLY_VOLUME_DECREASE_PER_SHOT = builder
                .comment("Early phase: each shot's volume is multiplied by (1 - this) relative to the",
                        "previous shot. Default 0.06, i.e. 6% quieter each shot. The opening shot is not shaped.",
                        "前期音量：每发在上一发基础上乘 (1 - 这个值)，默认 0.06 即每发小 6%。第一发保持原音量。")
                .defineInRange("earlyVolumeDecreasePerShot", 0.06D, 0.0D, 0.5D);

        MID_VOLUME_RANDOM_RANGE = builder
                .comment("Mid phase: each shot's volume floats by this much around the early-phase end value",
                        "(0.03 = +/-3%). Default 0.03.",
                        "中期音量：围绕前期结束时的音量上下浮动，默认 0.03 即 +/-3%。")
                .defineInRange("midVolumeRandomRange", 0.03D, 0.0D, 0.5D);

        LATE_PHASE_SWITCH_COUNT = builder
                .comment("How many remaining rounds of the magazine trigger the late phase. Default 5.",
                        "弹匣还剩最后几发时进入末期，默认 5。")
                .defineInRange("latePhaseSwitchCount", 5, 0, 64);

        LATE_VOLUME_INCREASE_PER_SHOT = builder
                .comment("Late phase: random floating is replaced by a fixed volume increase per shot.",
                        "Default 0.047, i.e. 4.7% louder each shot.",
                        "末期音量：取消随机浮动，每发在上一发基础上增大这个比例，默认 0.047 即每发响 4.7%。")
                .defineInRange("lateVolumeIncreasePerShot", 0.047D, 0.0D, 0.5D);

        BURST_RESET_MILLIS = builder
                .comment("A pause longer than this many milliseconds counts as the burst being broken,",
                        "and the next shot starts over at the normal sound. Default 400.",
                        "停火超过这个毫秒数就视为「连射中断」，下一发重新从原音开始。默认 400。")
                .defineInRange("burstResetMillis", 400, 50, 5000);

        AFFECT_SILENCED = builder
                .comment("Whether silenced shots get the same shaping.",
                        "装了消音器时是否也做同样的处理。")
                .define("affectSilencedShots", true);

        MAX_SIMULTANEOUS_SHOTS = builder
                .comment("How many gunshot sounds of the same shooter and the same gun may sit in the",
                        "sound engine at the same time. The oldest is stopped to make room for the newest,",
                        "so every shot is still heard and only the tail of an older one is cut.",
                        "0 disables the limit. TaCZ itself allows 4, and the default here matches it.",
                        "同一名射手、同一把枪最多允许几条枪声同时待在声音引擎里，超过就把最早的那条掐掉，",
                        "给最新的一条让位。0 表示不限制。TaCZ 自己用 4，这里默认和它保持一致。")
                .defineInRange("maxSimultaneousGunSounds", 4, 0, 64);

        builder.pop();
        SPEC = builder.build();
    }

    private PitchShiftConfig() {
    }
}
