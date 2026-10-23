# 第三方组件与许可证 / Third-party notices

本项目（NexioSchedule）以 **AGPL-3.0** 发布，见根目录 [LICENSE](LICENSE)。
以下第三方组件各自保留其原许可证；AGPL 不改变这些组件的授权条款。

## 随源码分发的修改版组件（Apache-2.0）

| 组件 | 来源 | 修改 |
|---|---|---|
| `miuix/` | [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix)（Apache-2.0） | KMP 移植与本地定制，见 `miuix/NOTICE` |
| `backdrop/` | [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) / `io.github.kyant0:backdrop`（Apache-2.0） | 本地修复与材质分级，见 `backdrop/NOTICE` |

两个模块目录内均附有 `LICENSE`（Apache-2.0 全文）与 `NOTICE`。

## 通过依赖引入的组件

- AndroidX / Jetpack Compose / Kotlin / kotlinx（Apache-2.0）
- [OkHttp](https://github.com/square/okhttp)（Apache-2.0）
- [Gson](https://github.com/google/gson)（Apache-2.0）
- [Shizuku](https://github.com/RikkaApps/Shizuku)（Apache-2.0）
- [Kyant0 Capsule](https://github.com/Kyant0/Capsule)、[Shapes](https://github.com/Kyant0/kyant-shapes)（Apache-2.0）

## 专有组件

本项目不包含或分发任何专有 SDK。

完整的 Apache-2.0 文本见 `miuix/LICENSE` / `backdrop/LICENSE`，或
<https://www.apache.org/licenses/LICENSE-2.0>。
