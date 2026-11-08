# TaCZ 1.2.0（Forge 1.20.1）源码存档

本目录保存用户提供的 TaCZ 最新版源码，供本模组以后做兼容时对照查证，**不参与编译**，也不要打进 jar。

- 来源：用户上传的 `TACZ-1.20.1.zip`（上游提交 `618c927fe4b1a41ddbbfd11dfcb8af638ac570f0`，压缩包内文件日期 2026-11-05）
- 版本：`build.gradle` 里 `version = "1.2.0"`，`archivesBaseName = tacz-forge-1.20.1`，Forge `1.20.1-47.3.19`
- 存档内容：`tacz-1.20.1-1.2.0-src.zip` 里包含
  - `src/main/java/`（TaCZ 全部 Java 源码，约 800 个文件）
  - `src/main/resources/assets/tacz/custom/tacz_default_gun/assets/tacz/display/`（默认枪包的枪械显示配置，音效写法看这里）
  - `build.gradle`、`gradle.properties`、`settings.gradle`、`readme.md`、`changelogs/`、`docs/`
- 未存档：`src/main/resources` 下的模型、贴图、动画、音源（约 110 MB，与本模组无关）

查看方式（解压到临时目录即可，不要提交解压结果）：

```bash
unzip -q reference/tacz-1.20.1-1.2.0/tacz-1.20.1-1.2.0-src.zip -d /tmp/tacz
```

## 1.2.0 里和本模组有关的音效改动

| 位置 | 1.1.x 的行为 | 1.2.0 的行为 |
| --- | --- | --- |
| `GunDisplayInstance#getSounds(String)` 返回类型 | `ResourceLocation`（音效 id） | `SoundData` 对象，id 在 `SoundData#getSound()` 里 |
| 枪械显示配置的音效写法 | `"shoot": "tacz:m1911/m1911_shoot"` | 仍支持字符串；新增对象写法 `{"id": ..., "volume": ..., "pitch": ..., "volume_random_range": ..., "pitch_random_range": ...}` |
| 音源加载 | 原版 sound event | `tacz_sounds/<id>.ogg` 优先，没有才回退同名原版 sound event（`GunSoundInstance.TaczSound` 重定向） |
| 音效播放 | 各处直接 `new GunSoundInstance(...)` | 统一走 `com.tacz.guns.client.sound.SoundPlayManager`，自己维护并发上限 |

`SoundData`（`com.tacz.guns.client.resource.pojo.display.SoundData`）要点：

- `getSound()`：音效 id，永远非空；
- `hasPitch()` / `getPitch()`：枪包是否写死了音高；没写时用调用方传进来的 fallback；
- `hasPitchRandomRange()` / `getPitchRandomRange()`：写法同上的随机幅度；
- `resolveVolume` / `resolvePitch`：最终值 = 固定值（或 fallback） ± 随机幅度。

## 本模组的适配方式

- `TaczBridge#matchesSound` 现在会把 `getSounds` 的返回值先归一化成 `ResourceLocation`
  （`ResourceLocation` / `SoundEvent` / `SoundData` / 字符串四种形态都认），再和实际播放的音效 id 比对。
  不修这一步，新版下每一发枪声都会被判成「不是枪声」，本模组的处理完全不生效。
- 枪包若给 `shoot` / `shoot_3p` / `silence` / `silence_3p` 明确写了 `pitch`，本模组会用它当作整段连射的基准音高，
  再叠加自己的 `randomPitchRange`；没写时仍按 TaCZ 原有随机音高的中值（0.9625）作基准。
- `GunSoundInstance` / `EntityTrackingGunSoundInstance` 的类名、`getRegistryName()`、`IGun` 的余弹与射击模式接口
  在 1.2.0 中都没有改名，所以 `isGunSound`、余弹读取、BURST 判定不需要改。

## 第三人称（听别人开枪）在 1.2.0 下的核对结果

1.2.0 里别人开枪传到你这边，走的是 `ModernKineticGunScriptAPI#shootOnce`（服务端）→
`SoundManager#sendSoundToNearby`（发给射手以外、距离内的玩家）→ 客户端 `SoundPlayManager#playMessageSound`，
音效键是 `SoundManager.SHOOT_3P_SOUND`（`"shoot_3p"`）/ `SILENCE_3P_SOUND`（`"silence_3p"`），
最终 new 出来的是 `EntityTrackingGunSoundInstance`。逐项核对：

| 本模组依赖的东西 | 1.2.0 是否还成立 |
| --- | --- |
| 别人开枪的音效实例是 `GunSoundInstance` 的子类 | 成立（`EntityTrackingGunSoundInstance extends GunSoundInstance`） |
| 能拿到音效资源名（`getRegistryName()`） | 成立，仍然是 `@Nullable ResourceLocation` |
| 音效实例是 `TickableSoundInstance`，需要被包成可 tick 的壳 | 成立，`TickablePitchShiftedSoundInstance` 继续生效 |
| 枪声键覆盖三人的 3P 音效 | 成立，`TaczBridge` 是从 `SoundManager` 的静态 String 字段名扫 `SHOOT` / `SILENCE`，
自动拿到 `shoot` / `shoot_3p` / `silence` / `silence_3p` 四个键 |
| `getSounds(String)` 返回的是 `SoundData` | 与第一人称同一个方法，已被 `soundLocation()` 归一化，1P/3P 共用一条路径 |
| 枪包给 `shoot_3p` 单独写死的 `pitch` | 会被 `nominalShotPitch` 读到（`shoot` 的 id 和 `shoot_3p` 不同时才会命中 3P 键，正好） |
| 3P 音效的最终音高 | `playMessageSound` 只把服务端的 `0.9+random*0.125` 当 fallback，再由 `SoundData#resolvePitch` 覆盖，
所以本模组读到的 `getPitch()` 已经是解析后的值，和 1P 一致 |
| 别人开枪的音量 | `GunSoundInstance` 构造时就乘好了距离衰减，本模组在其上乘音量倍率，不影响相对远近 |

结论：3P 不需要额外适配，上一版对 `SoundData` 的归一化把 1P / 3P 一起修好了。
唯一补的是一处防御：1.20.1 的 `AbstractSoundInstance#getVolume()` 也会解引用内部 `Sound` 字段，
`PitchShiftedSoundInstance#getVolume()` 现在和 `getPitch()` 一样，在解析完成前直接返回安全值，
避免音效物理 / 光影声音处理这类会提前读音量的模组把它读成空指针。
