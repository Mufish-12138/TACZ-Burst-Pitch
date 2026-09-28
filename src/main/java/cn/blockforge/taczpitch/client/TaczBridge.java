package cn.blockforge.taczpitch.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 与 TaCZ 打交道的唯一入口。
 *
 * <p>这里用反射而不是直接 import，原因是本工程的编译期只提供 Minecraft / Forge 的类，
 * 不能把 TaCZ 作为编译依赖。用到的全部是 TaCZ 自己的类名、方法名和字段名，
 * 它们不受 Minecraft 混淆映射影响，所以在正式游戏里按字面量查找同样有效。</p>
 *
 * <p>如果 TaCZ 不存在或版本差异导致某个名字找不到，这里会整体关闭并只记录一条日志，
 * 游戏和 TaCZ 本身都不会受影响。</p>
 */
public final class TaczBridge {

    /** This is not a gunshot this mod should shape. */
    public static final int NOT_A_SHOT = 0;
    /** Ordinary gunshot (first- or third-person). */
    public static final int NORMAL_SHOT = 1;
    /** Silenced gunshot. */
    public static final int SILENCED_SHOT = 2;

    private static final String CLASS_GUN_SOUND_INSTANCE = "com.tacz.guns.client.sound.GunSoundInstance";
    private static final String CLASS_GUN_DISPLAY_INSTANCE = "com.tacz.guns.client.resource.GunDisplayInstance";
    private static final String CLASS_TIMELESS_API = "com.tacz.guns.api.TimelessAPI";
    private static final String CLASS_SOUND_MANAGER = "com.tacz.guns.sound.SoundManager";
    private static final String CLASS_I_GUN = "com.tacz.guns.api.item.IGun";
    private static final String CLASS_GUN_DATA = "com.tacz.guns.resource.pojo.data.gun.GunData";
    private static final String CLASS_BURST_DATA = "com.tacz.guns.resource.pojo.data.gun.BurstData";
    private static final String CLASS_CLIENT_GUN_INDEX = "com.tacz.guns.client.resource.index.ClientGunIndex";
    private static final String CLASS_COMMON_GUN_INDEX = "com.tacz.guns.resource.index.CommonGunIndex";

    /** 枪械 ItemStack 里存「弹匣当前余弹」的 NBT 标签名（TaCZ 1.20.1）。 */
    private static final String AMMO_COUNT_TAG = "GunCurrentAmmoCount";

    private static final Logger LOGGER = LogManager.getLogger("tacz_burst_pitch");

    private static boolean resolved;
    private static boolean ready;
    /** 弹匣余弹接口是否可用；它和音效接口各自独立，一个坏了不影响另一个。 */
    private static boolean ammoReady;
    /** 连发（BURST）参数接口是否可用；同样独立解析，坏了只是退回旧行为。 */
    private static boolean burstReady;

    private static Class<?> gunSoundClass;
    private static Method getRegistryNameMethod;
    private static Method getGunDisplayMethod;
    private static Method getSoundsMethod;
    private static Method getIGunOrNullMethod;
    private static Method getCurrentAmmoCountMethod;
    /** BURST 判定与连发子弹数所需的反射句柄。 */
    private static Method getFireModeMethod;
    private static Method getGunIdMethod;
    private static Method getClientGunIndexMethod;
    private static Method clientGetGunDataMethod;
    private static Method getCommonGunIndexMethod;
    private static Method commonGetGunDataMethod;
    private static Method getBurstDataMethod;
    private static Method getBurstCountMethod;
    /** Every SoundManager key whose value is a "shoot" sound (covers 1p and 3p variants). */
    private static List<String> shootSoundKeys = List.of("shoot");
    /** Every SoundManager key whose value is a "silenced shoot" sound (covers 1p and 3p variants). */
    private static List<String> silencedSoundKeys = List.of("silence");

    private TaczBridge() {
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> gunSound = Class.forName(CLASS_GUN_SOUND_INSTANCE);
            Method registryName = gunSound.getMethod("getRegistryName");
            Method gunDisplay = Class.forName(CLASS_TIMELESS_API).getMethod("getGunDisplay", ItemStack.class);
            Method sounds = Class.forName(CLASS_GUN_DISPLAY_INSTANCE).getMethod("getSounds", String.class);
            Class<?> soundManager = Class.forName(CLASS_SOUND_MANAGER);
            List<String> shootKeys = new ArrayList<>();
            List<String> silenceKeys = new ArrayList<>();
            for (Field field : soundManager.getFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                    continue;
                }
                String key;
                try {
                    key = (String) field.get(null);
                } catch (Throwable ignored) {
                    continue;
                }
                if (key == null || key.isEmpty()) {
                    continue;
                }
                // TaCZ exposes keys such as SHOOT_SOUND / SHOOT_3P_SOUND / SILENCE_SOUND /
                // SILENCE_3P_SOUND. Collecting by field name covers both first- and third-person
                // shots without hard-coding every variant.
                String fieldName = field.getName();
                if (fieldName.contains("SHOOT")) {
                    shootKeys.add(key);
                } else if (fieldName.contains("SILENCE")) {
                    silenceKeys.add(key);
                }
            }
            if (shootKeys.isEmpty()) {
                shootKeys.add("shoot");
            }
            if (silenceKeys.isEmpty()) {
                silenceKeys.add("silence");
            }

            gunSoundClass = gunSound;
            getRegistryNameMethod = registryName;
            getGunDisplayMethod = gunDisplay;
            getSoundsMethod = sounds;
            shootSoundKeys = List.copyOf(shootKeys);
            silencedSoundKeys = List.copyOf(silenceKeys);
            ready = true;
        } catch (Throwable throwable) {
            ready = false;
            LOGGER.warn("[TaCZ 连发音效衰减] 找不到 TaCZ 的音效接口，本模组不会改动任何枪声。", throwable);
        }

        // 弹匣余弹接口单独解析：即使版本不同导致它失效，音调部分仍然照常工作。
        try {
            Class<?> iGun = Class.forName(CLASS_I_GUN);
            getIGunOrNullMethod = iGun.getMethod("getIGunOrNull", ItemStack.class);
            getCurrentAmmoCountMethod = iGun.getMethod("getCurrentAmmoCount", ItemStack.class);
            ammoReady = true;
        } catch (Throwable throwable) {
            ammoReady = false;
            getIGunOrNullMethod = null;
            getCurrentAmmoCountMethod = null;
            LOGGER.warn("[TaCZ 连发音效衰减] 读不到 TaCZ 的弹匣余弹接口，音量逻辑会忽略「弹匣最后几发」规则。", throwable);
        }

        // 连发（BURST）参数也单独解析：TaCZ 对 BURST 枪是「一次扣扳机连射固定发数」，
        // 这里要能认出 BURST 枪，好让它使用自己那条更宽松的连射判定（只要停火时间没超过阈值，
        // 就跨扳机继续累计发数，而不是每扣一次扳机就从头算）。
        try {
            Class<?> iGun = Class.forName(CLASS_I_GUN);
            getFireModeMethod = iGun.getMethod("getFireMode", ItemStack.class);
            getGunIdMethod = iGun.getMethod("getGunId", ItemStack.class);
            Class<?> gunData = Class.forName(CLASS_GUN_DATA);
            getBurstDataMethod = gunData.getMethod("getBurstData");
            getBurstCountMethod = Class.forName(CLASS_BURST_DATA).getMethod("getCount");
            Class<?> timeless = Class.forName(CLASS_TIMELESS_API);
            getClientGunIndexMethod = timeless.getMethod("getClientGunIndex", ResourceLocation.class);
            clientGetGunDataMethod = Class.forName(CLASS_CLIENT_GUN_INDEX).getMethod("getGunData");
            getCommonGunIndexMethod = timeless.getMethod("getCommonGunIndex", ResourceLocation.class);
            commonGetGunDataMethod = Class.forName(CLASS_COMMON_GUN_INDEX).getMethod("getGunData");
            burstReady = true;
        } catch (Throwable throwable) {
            burstReady = false;
            getFireModeMethod = null;
            getGunIdMethod = null;
            getClientGunIndexMethod = null;
            clientGetGunDataMethod = null;
            getCommonGunIndexMethod = null;
            commonGetGunDataMethod = null;
            getBurstDataMethod = null;
            getBurstCountMethod = null;
            LOGGER.warn("[TaCZ 连发音效衰减] 读不到 TaCZ 的连发（BURST）参数接口，连发枪会按普通连射处理。", throwable);
        }
    }

    public static boolean isReady() {
        resolve();
        return ready;
    }

    /**
     * Throws away the cached TaCZ lookups so the next use resolves them again.
     *
     * <p>{@link #resolve()} remembers its result for the whole session. If the very first look-up
     * happened before TaCZ was ready, {@code ready} would stay {@code false} forever and every
     * gunshot would be left untouched - a "permanently cached" failure. Resetting on a config
     * load/reload gives the bridge another chance instead of staying broken until restart.</p>
     */
    public static void reset() {
        resolved = false;
        ready = false;
        ammoReady = false;
        burstReady = false;
        gunSoundClass = null;
        getRegistryNameMethod = null;
        getGunDisplayMethod = null;
        getSoundsMethod = null;
        getIGunOrNullMethod = null;
        getCurrentAmmoCountMethod = null;
        getFireModeMethod = null;
        getGunIdMethod = null;
        getClientGunIndexMethod = null;
        clientGetGunDataMethod = null;
        getCommonGunIndexMethod = null;
        commonGetGunDataMethod = null;
        getBurstDataMethod = null;
        getBurstCountMethod = null;
        shootSoundKeys = List.of("shoot");
        silencedSoundKeys = List.of("silence");
    }

    /** 这个音效实例是不是 TaCZ 自己造的枪械音效。 */
    public static boolean isGunSound(Object sound) {
        resolve();
        return ready && sound != null && gunSoundClass.isInstance(sound);
    }

    /** 取 TaCZ 枪械音效对应的资源名（shoot / reload / draw ...）。 */
    public static ResourceLocation registryName(Object gunSound) {
        if (!ready) {
            return null;
        }
        try {
            Object result = getRegistryNameMethod.invoke(gunSound);
            return result instanceof ResourceLocation location ? location : null;
        } catch (Throwable throwable) {
            return null;
        }
    }

    /**
     * Decides whether a sound resource is a gunshot produced by the gun in the given stack.
     *
     * <p>Both first- and third-person shoot / silenced sound keys are accepted, so shots fired by
     * other players are recognised just like the local player's own shots.</p>
     *
     * @return {@link #NORMAL_SHOT}, {@link #SILENCED_SHOT} or {@link #NOT_A_SHOT}
     */
    public static int classifyShot(ItemStack gunStack, ResourceLocation soundId) {
        resolve();
        if (!ready || gunStack == null || gunStack.isEmpty() || soundId == null) {
            return NOT_A_SHOT;
        }
        try {
            Object displayOptional = getGunDisplayMethod.invoke(null, gunStack);
            if (!(displayOptional instanceof Optional<?> optional)) {
                return NOT_A_SHOT;
            }
            Object display = optional.orElse(null);
            if (display == null) {
                return NOT_A_SHOT;
            }
            for (String key : shootSoundKeys) {
                if (matchesSound(display, key, soundId)) {
                    return NORMAL_SHOT;
                }
            }
            for (String key : silencedSoundKeys) {
                if (matchesSound(display, key, soundId)) {
                    return SILENCED_SHOT;
                }
            }
            return NOT_A_SHOT;
        } catch (Throwable throwable) {
            return NOT_A_SHOT;
        }
    }

    /**
     * Compares one gun-display sound key against a sound resource name.
     *
     * <p>Depending on the TaCZ version {@code GunDisplayInstance.getSounds(String)} may hand back
     * a {@link ResourceLocation}, a {@link SoundEvent} or a plain string, so all three shapes are
     * accepted. A key the gun does not define simply returns null and counts as no match.</p>
     */
    private static boolean matchesSound(Object display, String key, ResourceLocation soundId) {
        try {
            Object result = getSoundsMethod.invoke(display, key);
            if (result == null) {
                return false;
            }
            if (result instanceof ResourceLocation location) {
                return soundId.equals(location);
            }
            if (result instanceof SoundEvent event) {
                return soundId.equals(event.getLocation());
            }
            return soundId.toString().equals(result.toString());
        } catch (Throwable throwable) {
            return false;
        }
    }

    /**
     * 读这一发打出前，弹匣里还剩几发子弹。
     *
     * <p>优先走 TaCZ 的官方接口 {@code IGun.getCurrentAmmoCount(ItemStack)}；
     * 如果这个接口在当前 TaCZ 版本里名字对不上，就退回直接读 NBT 里的
     * {@code GunCurrentAmmoCount}。两条路都拿不到时返回 -1，调用方会当作「未知」处理。</p>
     *
     * <p>注意：本地玩家自己的枪声是客户端预测播放的，客户端此刻还没扣弹，
     * 所以这个返回值是<b>包含正在打的这一发</b>的数量：读到 1 就代表这是弹匣里最后一发。</p>
     */
    public static int currentAmmoCount(ItemStack gunStack) {
        resolve();
        if (gunStack == null || gunStack.isEmpty()) {
            return -1;
        }
        if (ammoReady) {
            try {
                Object gun = getIGunOrNullMethod.invoke(null, gunStack);
                if (gun == null) {
                    return -1;
                }
                Object result = getCurrentAmmoCountMethod.invoke(gun, gunStack);
                if (result instanceof Integer count) {
                    return count;
                }
            } catch (Throwable throwable) {
                // 接口对不上就安静地退回 NBT，不反复刷日志。
                return ammoCountFromNbt(gunStack);
            }
        }
        return ammoCountFromNbt(gunStack);
    }

    /**
     * 读出这把枪的 BURST（连发）模式一扣扳机固定连射几发。
     *
     * <p>TaCZ 的 BURST 枪不是「打一发」：一次开火会按 {@code burstData.count} 连续射出发数，
     * 而且每射出一发就播放一次枪声（本地由 {@code LocalPlayerShoot} 循环播放，其他玩家由服务端
     * 每一发发一条 {@code ServerMessageSound}）。所以枪声触发的次数本身就是子弹数，本模组不需要
     * 再额外把发数加倍。</p>
     *
     * <p>这个值用来<b>认出</b> BURST 枪，好让它走自己那条连射判定：只要两轮之间的停火时间没超过
     * 配置的阈值，发数就跨扳机继续往上累计（4 连发的第二轮第一发接在第一轮第四发后面，算第 5 发），
     * 而不是每扣一次扳机就重新从第一发算起。</p>
     *
     * @return 连发子弹数；不是 BURST 模式或读不到时返回 -1
     */
    public static int burstShotCount(ItemStack gunStack) {
        resolve();
        if (!burstReady || !ammoReady || gunStack == null || gunStack.isEmpty()) {
            return -1;
        }
        try {
            Object gun = getIGunOrNullMethod.invoke(null, gunStack);
            if (gun == null) {
                return -1;
            }
            Object fireMode = getFireModeMethod.invoke(gun, gunStack);
            if (!(fireMode instanceof Enum<?> mode) || !"BURST".equals(mode.name())) {
                return -1;
            }
            Object gunId = getGunIdMethod.invoke(gun, gunStack);
            if (!(gunId instanceof ResourceLocation id)) {
                return -1;
            }
            Object gunData = findGunData(id);
            if (gunData == null) {
                return -1;
            }
            Object burstData = getBurstDataMethod.invoke(gunData);
            if (burstData == null) {
                return -1;
            }
            Object count = getBurstCountMethod.invoke(burstData);
            if (count instanceof Integer value && value > 0) {
                return value;
            }
        } catch (Throwable ignored) {
            // 读不到就当作非 BURST，保持旧行为，绝不影响开枪。
        }
        return -1;
    }

    /** 按枪械 id 取 {@code GunData}。客户端索引优先（客户端一定加载了它），退回通用索引。 */
    private static Object findGunData(ResourceLocation gunId) {
        Object index = firstPresent(getClientGunIndexMethod, gunId);
        if (index != null) {
            Object data = invokeQuietly(clientGetGunDataMethod, index);
            if (data != null) {
                return data;
            }
        }
        index = firstPresent(getCommonGunIndexMethod, gunId);
        if (index != null) {
            Object data = invokeQuietly(commonGetGunDataMethod, index);
            if (data != null) {
                return data;
            }
        }
        return null;
    }

    /** 调用一个返回 {@code Optional} 的静态查询，返回其中存在的值，没有则 null。 */
    private static Object firstPresent(Method method, Object argument) {
        try {
            Object result = method.invoke(null, argument);
            if (result instanceof Optional<?> optional) {
                return optional.orElse(null);
            }
        } catch (Throwable ignored) {
            // 忽略：调用方会退回另一条路或判为未知。
        }
        return null;
    }

    private static Object invokeQuietly(Method method, Object target) {
        try {
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int ammoCountFromNbt(ItemStack gunStack) {        try {
            CompoundTag tag = gunStack.getTag();
            if (tag != null && tag.contains(AMMO_COUNT_TAG, Tag.TAG_INT)) {
                return tag.getInt(AMMO_COUNT_TAG);
            }
        } catch (Throwable ignored) {
            // 读 NBT 失败就当未知处理。
        }
        return -1;
    }
}
