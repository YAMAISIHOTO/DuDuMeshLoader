# 第三方软件与修改声明

本文件适用于 DuDuMeshLoader `0.2.0`（TacZ Mesh Loader 的修改版）。

## TacZ Mesh Loader

- 原作者：VellEagle
- 原项目：<https://github.com/VellEagle/TacZMeshLoader/tree/1.20.1>
- 固定上游提交：`17dfcc188d5a7b3d3d23e77ffcf9a7402f3c8d0f`
- 许可证：GNU General Public License v3.0 only（GPL-3.0-only）
- 许可证全文：`LICENSES/TacZMeshLoader-GPL-3.0-only.txt`（发行 JAR 内为 `META-INF/LICENSES/TacZMeshLoader-GPL-3.0-only.txt`）
- 上游版权归属：原项目及其贡献者保留各自代码的版权（Copyright (C) VellEagle 及 TacZMeshLoader 贡献者）

原项目贡献者包括 VellEagle、denys-shatin 等；各贡献者保留其各自代码的版权。

SteveDuYu 于 2026-08-13 修改了该项目。主要修改包括：读取 Bedrock `poly_mesh` 的逐角点
法线索引、法线矩阵处理、三角形拆分与 RenderType、Accelerated Rendering 网格缓存、
光影状态切换与批次刷新兼容，以及相关说明和发行材料。本修改版整体继续按
GPL-3.0-only 分发。

## SimpleBedrockModel

- 项目：SimpleBedrockModel
- 作者/贡献者：TartaricAcid、MaydayMemory、MoePus、Hidomatn、xjqsh 等
- 项目地址：<https://github.com/MCModderAnchor/SimpleBedrockModel>
- `-all.jar` 内嵌版本：`2.2.2-forge+mc1.20.1`
- 内嵌版本源码：<https://github.com/MCModderAnchor/SimpleBedrockModel/tree/2.2.2-forge-mc1.20.1>
- 实现参考版本：`2.5.1-forge-mc1.20.1`
- 参考版本源码：<https://github.com/MCModderAnchor/SimpleBedrockModel/tree/2.5.1-forge-mc1.20.1>
- 许可证：GNU Lesser General Public License v3.0（LGPL-3.0）
- 许可证全文：`LICENSES/SimpleBedrockModel-LGPL-3.0.txt`

本项目的 poly_mesh 解析与三角化语义、三角 RenderType，以及 Accelerated Rendering
缓存设计改编自 SimpleBedrockModel 2.5.1 的 `BedrockPolyMesh`、
`BedrockModelRenderTypes` 和 `AcceleratedRenderer`。这些改编代码作为本 GPL-3.0-only
项目的一部分分发；未复制 SimpleBedrockModel 的示例资产。

SimpleBedrockModel 作为独立 Jar-in-Jar 库保留自己的 mod id 和版本。外层元数据接受
`[2.2.2,)`，Forge 可在运行时选择兼容的更高版本。接收者可以取得上述精确源码以及
本项目的完整构建脚本，修改或替换 SBM 后重新构建组合产物；本项目不附加禁止修改、
调试、逆向或再分发的限制。

### 本仓库内 SimpleBedrockModel 二进制的来源与替换方式

LGPL-3.0 要求接收者能够替换该库并重新构建组合产物。为此，以下信息一并给出：

- 存放位置：本仓库根目录 `libs/`，随源码一同分发（不依赖任何外部二进制分发点）。
- 文件与校验值（下载后可自行比对，确认与上游一致、未被改动）：

  | 文件 | 用途 | SHA-256 |
  |------|------|---------|
  | `libs/simplebedrockmodel-2.2.2-forge+mc1.20.1.jar` | 编译依赖 + 内嵌进 `-all.jar` | `12b7b664e9c434b73f1655f8402050385c526802f2de059ac51abfd60bbbb809` |
  | `libs/simplebedrockmodel-2.2.2-forge+mc1.20.1-sources.jar` | 对应源码，便于阅读与修改 | `4b886dc17e72b780517b74bca2741008ddaca634d242b756f3e698cae57606c9` |

- 为何放在仓库内：该构件在公共 Maven 仓库上已无法取得（`maven central` 返回 404，
  本仓库亦未配置 jitpack）。项目构建脚本通过 `repositories { flatDir { dir 'libs' } }`
  引用它，即实物完全来自本仓库内的上述文件。
- **如何替换或修改**：直接覆盖 `libs/` 下的同名文件即可（`build.gradle` 按
  `<name>-<version>.jar` 的文件名匹配，无需改动构建脚本）；随后照常执行构建命令，
  生成的 `-all.jar` 就会内嵌替换后的版本。若改为在源码层修改，请自上游仓库取得
  对应 tag 的源码，按 LGPL-3.0 要求附带你的修改说明后再重新构建。

## Mayday Animation Engine（MAE）

- 项目：Mayday Animation Engine
- 作者：MaydayMemory
- 项目地址：<https://github.com/286799714/MaydayAnimationEngine>
- `-all.jar` 中经 SimpleBedrockModel 传递内嵌的版本：`1.1.2`
- 1.1.2 源码包：<https://repo.maven.apache.org/maven2/com/maydaymemory/mae/1.1.2/mae-1.1.2-sources.jar>
- 许可证：MIT License
- Copyright (c) 2024 MaydayMemory
- 许可证全文：`LICENSES/MaydayAnimationEngine-MIT.txt`

如果同一实例中的其他模组提供兼容的更高版本，Forge 可能在运行时选择该版本；
2026-08-13 的验收实例实际选择了 MAE 1.1.4。MAE 1.1.2 与 1.1.4 的 Maven Central
元数据均标记为 MIT；这里不声称上游存在 1.1.4 Git tag。

## TaCZ（Timeless and Classics Zero，运行时依赖）

- 项目：TaCZ / Timeless and Classics Zero
- 作者：craneblock、MaydayMemory、TartaricAcid、F1zeiL、xjqsh 等
- 项目地址：<https://modrinth.com/mod/timeless-and-classics-zero>
- 依赖版本：1.20.1 的 `1.1.8-hotfix`
- 许可证：
  - **代码**：GNU GPL 3.0（GPL-3.0）
  - **资产（模型/贴图/动画/音效等）**：CC BY-NC-ND 4.0（署名-非商业-禁止演绎）

本模组通过 Mixin 在运行时注入并调用 TaCZ 的公开 API 与渲染生命周期，**未复制 TaCZ 的
源码，也未打包 TaCZ 的任何资产**（模型、贴图、音效均不在本仓库与发行 JAR 内）。
因此本模组不受其资产 CC BY-NC-ND 4.0 的限制，仅需以 GPL-3.0 兼容方式引用其代码 API。

## 分发提示

本声明不是对各许可证正文的替代。分发二进制时，应同时提供 GPL-3.0、LGPL-3.0、
MAE MIT 许可证文本，以及与该二进制精确对应、可供重建的本项目源码。完整许可证以
随附文件和各上游项目为准。
