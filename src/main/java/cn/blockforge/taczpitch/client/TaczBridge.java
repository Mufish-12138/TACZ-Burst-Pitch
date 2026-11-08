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

/**
 * 与 TaCZ 打交道的唯一入口。
 *
 * <p>这里用反射而不是直接 import，原因是本工程的编译期只提供 Minecraft / Forge 的类，
 * 不能把 TaCZ 作为编译依赖。用到的全部是 TaCZ 自己的类名、方法名和字段名，
 * 它们不受 Minecraft 混淆映射影响，所以在正式游戏里按字面量查找同样有效。</p>
 *
 * <p>如果 TaCZ 不存在或版本差异导致某个名字找不到，这里会整体关闭，
 * 游戏和 TaCZ 本身都不会受影响。</p>
 *
 * <p><b>本模组不往 logs 里写任何东西。</b>所以这里所有的失败分支都是静默的：
 * 找不到接口就当作「不改音效」，读不到数据就退回默认行为。日志里看不到本模组的任何行，
 * 这是刻意的——它只是个听感修饰，不该刷玩家的日志。</p>
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
    /**
     * TaCZ 1.2.0 起，{@code GunDisplayInstance#getSounds} 不再返回音效 id，而是返回这个包装对象：
     * 真正的 id 在 {@code getSound()} 里，另外还带音高/音量的固定值和随机幅度。
     */
    private static final String CLASS_SOUND_DATA = "com.tacz.guns.client.resource.pojo.display.SoundData";

    /** 枪械 ItemStack 里存「弹匣当前余弹」的 NBT 标签名（TaCZ 1.20.1）。 */
    private static final String AMMO_COUNT_TAG = "GunCurrentAmmoCount";

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
    /** TaCZ 1.2.0 的 SoundData 反射句柄；旧版 TaCZ 没有这个类，全部为 null 时走旧路径。 */
    private static Class<?> soundDataClass;
    private static Method soundDataGetSoundMethod;
    private static Method soundDataHasPitchMethod;
    private static Method soundDataGetPitchMethod;
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
            // TaCZ 不在、或者这个版本的类名对不上：整体关闭，静默不改任何枪声。
            ready = false;
        }

        // TaCZ 1.2.0 把枪声音效改成了 SoundData 包装对象（音效 id + 固定音高/音量 + 随机幅度）。
        // 这里单独解析它：有就用新路径，没有（旧版 TaCZ）就还按原来的「直接返回音效 id」处理。
        try {
            soundDataClass = Class.forName(CLASS_SOUND_DATA);
            soundDataGetSoundMethod = soundDataClass.getMethod("getSound");
            soundDataHasPitchMethod = soundDataClass.getMethod("hasPitch");
            soundDataGetPitchMethod = soundDataClass.getMethod("getPitch");
        } catch (Throwable ignored) {
            // 旧版 TaCZ 没有这个类，属正常情况。
            soundDataClass = null;
            soundDataGetSoundMethod = null;
            soundDataHasPitchMethod = null;
            soundDataGetPitchMethod = null;
        }

        // 弹匣余弹接口单独解析：即使版本不同导致它失效，音调部分仍然照常工作。
        try {
            Class<?> iGun = Class.forName(CLASS_I_GUN);
            getIGunOrNullMethod = iGun.getMethod("getIGunOrNull", ItemStack.class);
            getCurrentAmmoCountMethod = iGun.getMethod("getCurrentAmmoCount", ItemStack.class);
            ammoReady = true;
        } catch (Throwable throwable) {
            // 读不到就静默跳过「弹匣最后几发」规则，音调照常。
            ammoReady = false;
            getIGunOrNullMethod = null;
            getCurrentAmmoCountMethod = null;
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
            // 读不到就静默当作非 BURST，保持旧行为。
            burstReady = false;
            getFireModeMethod = null;
            getGunIdMethod = null;
            getClientGunIndexMethod = null;
            clientGetGunDataMethod = null;
            getCommonGunIndexMethod = null;
            commonGetGunDataMethod = null;
            getBurstDataMethod = null;
            getBurstCountMethod = null;
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
        soundDataClass = null;
        soundDataGetSoundMethod = null;
        soundDataHasPitchMethod = null;
        soundDataGetPitchMethod = null;
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
            Object display = gunDisplay(gunStack);
            if (display == null) {
                return NOT_A_SHOT;
            }
            if (matchingSoundKey(display, shootSoundKeys, soundId) != null) {
                return NORMAL_SHOT;
            }
            if (matchingSoundKey(display, silencedSoundKeys, soundId) != null) {
                return SILENCED_SHOT;
            }
            return NOT_A_SHOT;
        } catch (Throwable throwable) {
            return NOT_A_SHOT;
        }
    }

    /**
     * 枪包给这一发枪声写死的音高（TaCZ 1.2.0 的 {@code SoundData.pitch}）。
     *
     * <p>新版 TaCZ 允许枪包给每个音效单独指定音高和随机幅度。如果枪包写了固定音高，
     * 本模组的「随机音高覆盖」就用它当作整段连射的基准，而不是硬套 TaCZ 自带的 0.9625 中值，
     * 这样枪包自己设计的音色不会被本模组抹掉。枪包没写（或旧版 TaCZ）时返回 NaN，调用方按老规则处理。</p>
     *
     * @return 枪包写死的音高；没有或读不到时返回 {@link Float#NaN}
     */
    public static float nominalShotPitch(ItemStack gunStack, ResourceLocation soundId) {
        resolve();
        if (!ready || soundDataClass == null || gunStack == null || gunStack.isEmpty() || soundId == null) {
            return Float.NaN;
        }
        try {
            Object display = gunDisplay(gunStack);
            if (display == null) {
                return Float.NaN;
            }
            Object value = matchingSoundKey(display, shootSoundKeys, soundId);
            if (value == null) {
                value = matchingSoundKey(display, silencedSoundKeys, soundId);
            }
            if (value == null || !soundDataClass.isInstance(value)) {
                return Float.NaN;
            }
            Object hasPitch = soundDataHasPitchMethod.invoke(value);
            if (!(hasPitch instanceof Boolean flag) || !flag) {
                return Float.NaN;
            }
            Object pitch = soundDataGetPitchMethod.invoke(value);
            if (pitch instanceof Float fixed && fixed > 0.0F) {
                return fixed;
            }
        } catch (Throwable ignored) {
            // 读不到就当枪包没写，退回 TaCZ 的平均音高。
        }
        return Float.NaN;
    }

    /** 取这把枪的显示数据（模型、音效、贴图都在里面）；拿不到返回 null。 */
    private static Object gunDisplay(ItemStack gunStack) {
        try {
            Object displayOptional = getGunDisplayMethod.invoke(null, gunStack);
            if (displayOptional instanceof Optional<?> optional) {
                return optional.orElse(null);
            }
        } catch (Throwable ignored) {
            // TaCZ 还没加载完或接口对不上时一律当作取不到。
        }
        return null;
    }

    /**
     * 在给定的几个音效键里找出哪一个和正在播放的音效 id 相同。
     *
     * @return 命中的原始值（旧版是 {@link ResourceLocation}，1.2.0 起是 {@code SoundData}）；
     *         没有命中返回 null
     */
    private static Object matchingSoundKey(Object display, List<String> keys, ResourceLocation soundId) {
        for (String key : keys) {
            Object value = soundValue(display, key);
            if (soundId.equals(soundLocation(value))) {
                return value;
            }
        }
        return null;
    }

    /** 调 {@code GunDisplayInstance#getSounds(String)}；这个键没定义或调用失败时返回 null。 */
    private static Object soundValue(Object display, String key) {
        try {
            return getSoundsMethod.invoke(display, key);
        } catch (Throwable throwable) {
            return null;
        }
    }

    /**
     * 把 {@code getSounds} 的返回值归一化成音效 id。
     *
     * <p>不同 TaCZ 版本的形状不一样，官方一直以来换了两次：</p>
     * <ul>
     *   <li>旧版：直接是 {@link ResourceLocation}；</li>
     *   <li>中间某些版本：{@link SoundEvent}；</li>
     *   <li>1.2.0 起：{@code SoundData} 包装对象，真正的 id 在 {@code getSound()} 里。</li>
     * </ul>
     * <p>字符串形态也顺手兼容。都不认识时返回 null，当作「不是这个音效」。</p>
     */
    private static ResourceLocation soundLocation(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof ResourceLocation location) {
            return location;
        }
        if (value instanceof SoundEvent event) {
            return event.getLocation();
        }
        if (soundDataClass != null && soundDataClass.isInstance(value)) {
            try {
                return soundLocation(soundDataGetSoundMethod.invoke(value));
            } catch (Throwable throwable) {
                return null;
            }
        }
        if (value instanceof CharSequence text) {
            try {
                return ResourceLocation.tryParse(text.toString());
            } catch (Throwable throwable) {
                return null;
            }
        }
        return null;
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

    private static int ammoCountFromNbt(ItemStack gunStack) {
        try {
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
