package cn.blockforge.taczpitch;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * TACZ Burst Pitch Falloff.
 *
 * <p>This mod does one thing: while a burst weapon keeps firing, the opening shot plays at the
 * normal gun sound and every following shot is pushed through an early, mid and late burst phase
 * that lowers its pitch (and optionally shapes its volume). When the burst is broken, the next
 * shot starts over at the normal sound. On top of that, TaCZ's own random gunshot pitch
 * (roughly +/-6%) is replaced with a much smaller, configurable range (default +/-2%). Every
 * client shapes the shots it hears, including shots fired by other players.</p>
 *
 * <p>All logic runs on the client; the server only loads the mod and is otherwise unaffected.</p>
 */
@Mod(TaczBurstPitchMod.MOD_ID)
public final class TaczBurstPitchMod {

    public static final String MOD_ID = "tacz_burst_pitch";

    public TaczBurstPitchMod() {
        // 纯客户端功能，配置只在客户端读写。
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, PitchShiftConfig.SPEC);
        }
    }
}
