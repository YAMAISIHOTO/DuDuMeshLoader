# DuDuMeshLoader

一个为 [TaCZ（Timeless and Classics Zero）](https://modrinth.com/mod/timeless-and-classics-zero) 优化的 **Bedrock `poly_mesh` 加载与渲染加速模组**，是 [TacZMeshLoader](https://github.com/VellEagle/TacZMeshLoader/tree/1.20.1) 的修改版（fork）。

在保留 TaCZ 原有 `model_type: "mesh"` 与枪包目录结构的前提下，为高面数枪械模型提供索引化 VBO 渲染、骨骼级缓存、单面剔除等优化，并额外提供装饰方块与武器架系统。

## 功能

- **索引化 VBO 渲染**：顶点按 `(position, uv, normal)` 去重后走 `glDrawElements`，大幅降低 GPU 顶点变换量
- **退化四边形检测**：识别 Bedrock 导出器「三角形伪装成 quad」的情况，顶点数再省约 25%
- **骨骼级 VBO 缓存**：降低 GPU 上传开销
- **单面渲染**：`poly_mesh` cutout 默认开启背面剔除（`meshSingleSided`，可关闭回退双面）
- **自定义平滑角**：枪的 display JSON 里可配 `"poly_mesh_smoothing_angle"` 运行时重算法线（未配置则沿用导出法线）
- **`block_deco` 装饰方块**：纯装饰方块，支持 `a/b/c/slab/null` 四种碰撞档位
- **`weapon_rack` 武器架**：潜行右键开合动画、枪/配件独立槽位、每槽位 `scale` 缩放、多枪型挂载
- **内置附件解析缓存**：缓存枪型级内置附件，减少每帧 `ItemStack` 重建
- **AR/Oculus 兼容**：当 Accelerated Rendering 或 Oculus 加载时安全回退，规避 Iris GBuffer 冲突

## 版本

| 版本 | 平台 | 目录 |
|------|------|------|
| 0.2.0 | Minecraft 1.20.1 Forge + Java 17 | 本仓库 |
| 0.2.0 | Minecraft 1.21.1 NeoForge + Java 21 | `DuDuMeshLoader-1.21.1` |

## 安装

- Minecraft 1.20.1
- Forge 47.x
- Java 17
- **TaCZ 1.1.8 或更高（必需）**
- Accelerated Rendering（可选，用于高面数模型加速）

只把 `dudumeshloader-0.2.0-all.jar` 放入 `mods` 目录即可。`-all.jar` 已通过 Forge Jar-in-Jar 内嵌 SimpleBedrockModel，无需单独安装。安装前请删除旧版 TacZ Mesh Loader，相同 mod id 的两个 JAR 不能共存。

## 构建

需要 JDK 17：

```bash
./gradlew build
# Windows: gradlew.bat build
```

正式安装包是 `build/libs/dudumeshloader-0.2.0-all.jar`（含内嵌依赖），不是无 `-all` 后缀的普通 JAR。

## 来源与许可证

本项目整体为 **GPL-3.0-only**，见 `LICENSE`（上游 TacZ Mesh Loader 的 GPL-3.0 全文见 `LICENSES/TacZMeshLoader-GPL-3.0-only.txt`）。完整第三方声明见 `THIRD_PARTY_NOTICES.md`。

| 组件 | 许可证 | 说明 |
|------|--------|------|
| TacZMeshLoader（上游） | GPL-3.0-only | 原作者 VellEagle 等 |
| SimpleBedrockModel | LGPL-3.0 | Jar-in-Jar 内嵌，`LICENSES/` 有全文 |
| Mayday Animation Engine | MIT | 经 SBM 传递内嵌 |
| TaCZ（运行时依赖） | 代码 GPL-3.0 / 资产 CC BY-NC-ND 4.0 | 仅 mixin 依赖其 API，未打包其任何资产 |

本修改版由 Yuu 于 2026-08 起修改。分发二进制时，请同时提供与该 JAR 精确对应的完整源码，并保留 `LICENSE`、`LICENSES/`、`THIRD_PARTY_NOTICES.md` 和本说明。
