package cn.blockforge.taczpitch.client;

import cn.blockforge.taczpitch.TaczBurstPitchMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/**
 * Keeps the mod's own state in sync with the config file.
 *
 * <p>Forge hands out config values through a cache that is only refreshed when the config is
 * loaded or reloaded, and this mod additionally keeps per-player burst counters and the reflection
 * handles it resolved against TaCZ. Without clearing those on a config change, editing a value can
 * appear to do nothing until the file is touched again - which is exactly the "you have to change a
 * true/false option before other players' shots are processed" bug this class fixes.</p>
 *
 * <p>{@link ModConfigEvent} is a mod-bus event, so this subscriber uses {@code Bus.MOD} (unlike
 * {@link GunSoundPitchHandler}, which listens on the Forge bus).</p>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = TaczBurstPitchMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class TaczPitchConfigEvents {

    private TaczPitchConfigEvents() {
    }

    @SubscribeEvent
    public static void onConfigLoading(ModConfigEvent.Loading event) {
        refresh(event.getConfig());
    }

    @SubscribeEvent
    public static void onConfigReloading(ModConfigEvent.Reloading event) {
        refresh(event.getConfig());
    }

    @SubscribeEvent
    public static void onConfigUnloading(ModConfigEvent.Unloading event) {
        refresh(event.getConfig());
    }

    private static void refresh(ModConfig config) {
        if (config == null || config.getType() != ModConfig.Type.CLIENT
                || !TaczBurstPitchMod.MOD_ID.equals(config.getModId())) {
            return;
        }
        // Re-resolve TaCZ's classes/methods and drop every cached burst. The next shot after this
        // reads the new values, for the local player and for other players alike.
        TaczBridge.reset();
        BurstPitchTracker.clear();
        GunSoundPitchHandler.clearActiveShots();
    }
}
