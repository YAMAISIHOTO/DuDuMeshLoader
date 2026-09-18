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

Yuu 于 2026-08-13 修改了该项目。主要修改包括：读取 Bedrock `poly_mesh` 的逐角点
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
