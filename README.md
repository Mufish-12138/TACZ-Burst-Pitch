# TACZ Burst Pitch Falloff / TaCZ 连发音效衰减

- 作者：Mufish
- 版本：1.2.0
- 环境：Minecraft 1.20.1 · Forge 47.4.0 · Java 17
- 依赖：Timeless and Classics Zero（TaCZ，1.0 及以上，推荐 1.2.0）

## 这是什么

TaCZ 的枪声每发都是同一个音色随机变调。这个模组在**你听到枪声的那一侧**做处理：
连射时第一发保持原音，之后按「前期 → 中期 → 末期」三段把音调压低、顺带把音量做一点起伏，
停火超过设定时间后重新从原音开始。

别人的枪声也一样处理（TaCZ 里那叫第三人称 / 3P 枪声），所以联机时大家听到的听感一致——
前提是对方也装了这个模组，因为这纯粹是客户端行为。

不改 TaCZ 的射击、动画、后坐力，也不发任何额外网络包。

## 配置文件

`config/tacz_burst_pitch-client.toml`，全部是客户端配置：

| 配置项 | 默认 | 作用 |
| --- | --- | --- |
| `enabled` | true | 总开关 |
| `overrideTaczRandomPitch` | true | 是否用本模组的随机音高替换 TaCZ 自带的约 ±6% 随机 |
| `randomPitchRange` | 0.02 | 整段连射只抽一次的随机音高半幅 |
| `earlyPhaseShots` | 6 | 前期发数（第 2~7 发） |
| `earlyPitchDecreasePerShot` | 0.007 | 前期每发降多少音调倍率 |
| `midPitchRandomRange` | 0.015 | 中期音调浮动幅度 |
| `latePitchDecreasePerShot` | 0.01 | 末期每发降多少音调倍率 |
| `volumeShapingEnabled` | true | 是否同时做音量起伏 |
| `earlyVolumeDecreasePerShot` | 0.06 | 前期每发小多少 |
| `midVolumeRandomRange` | 0.03 | 中期音量浮动幅度 |
| `latePhaseSwitchCount` | 5 | 弹匣剩几发进入末期 |
| `lateVolumeIncreasePerShot` | 0.047 | 末期每发响多少 |
| `burstResetMillis` | 400 | 停火超过多久算连射中断 |
| `affectSilencedShots` | true | 消音枪声是否同样处理 |
| `maxSimultaneousGunSounds` | 4 | 同一人同一把枪最多同时几条枪声（与 TaCZ 默认值一致） |

## 关于日志

本模组**不往 `logs/` 里写任何东西**。接口对不上、读不到数据的失败分支全部是静默的，
表现为「这一发没被处理」，而不是报错或刷屏。

## 兼容性

- TaCZ 1.2.0 起枪声音效改成了 `SoundData` 对象（`id` + 固定音高/音量 + 随机幅度），本模组认这个新结构；
- 旧版 TaCZ（音效字段直接是音效 id）同样能跑，不会因为这次改动失效；
- 枪包若给 `shoot` / `shoot_3p` / `silence` / `silence_3p` 写了固定 `pitch`，本模组拿它当整段连射的基准。
