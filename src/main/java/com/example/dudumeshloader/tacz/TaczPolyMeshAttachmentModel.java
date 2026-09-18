package com.example.dudumeshloader.tacz;

import com.example.dudumeshloader.core.PolyMeshModel;
import com.example.dudumeshloader.api.IPolyMeshBone;
import com.example.dudumeshloader.config.ClientConfig;
import com.example.dudumeshloader.render.PolyMeshRenderTypes;
import com.example.dudumeshloader.render.PerformanceDiagnostics;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.client.model.BedrockAttachmentModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import com.tacz.guns.client.resource.index.ClientAttachmentIndex;
import net.minecraft.client.Minecraft;
import org.apache.commons.lang3.tuple.Pair;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@OnlyIn(Dist.CLIENT)
public class TaczPolyMeshAttachmentModel extends BedrockAttachmentModel {

    private PolyMeshModel polyMeshModel;
    private ResourceLocation cachedTexture = null;
    private List<IPolyMeshBone> cachedRootChildren = null;

    /** 当前 super.render 是否正在 TaCZ 原生 -942 scope_body AR 收集阶段。 */
    private boolean nativeScopeArCollection;

    /** AR consumer 一旦拒绝特殊子树，后续帧完整回到已经验证过的同步路径。 */
    private boolean nativeScopeArCircuitOpen;

    private static final org.apache.logging.log4j.Logger MESH_LOG =
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader");

    public TaczPolyMeshAttachmentModel(BedrockModelPOJO pojo, BedrockVersion version) {
        super(pojo, version);
    }

    /**
     * 非 scope/sight 時（通常描画）の PolyMesh 描画。
     */

    private void renderPolyMeshNormalAttachment(
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource,
            ResourceLocation tex,
            int light, int overlay, boolean useVBO, boolean forceNoCull) {
        long diagnosticsStartNs = PerformanceDiagnostics.begin();
        try {
            polyMeshModel.renderCutoutOnly(poseStack, bufferSource, tex, light, overlay, useVBO, forceNoCull);
            if (polyMeshModel.hasTranslucentMeshes()) {
                polyMeshModel.renderTranslucentOnly(poseStack, bufferSource, tex, light, overlay, useVBO, forceNoCull);
            }
        } finally {
            PerformanceDiagnostics.endAttachmentNormal(diagnosticsStartNs);
        }
    }

    /**
     * 由 BedrockAttachmentModelMixin 在 TaCZ 原生 renderTempPart 阶段调用。
     *
     * <p>PoseStack 此时已包含叶骨之前的全部祖先变换，stencil/color/depth 状态也仍由
     * TaCZ 管理。poly_mesh 必须继续使用已经验证过的 TRIANGLES RenderType，不能再写入
     * cube 的 QUADS consumer；完成后立即刷新，确保真正绘制发生在本次 stencil 阶段内。</p>
     */
    public void renderSpecialPolyMeshSubtree(String boneName, PoseStack poseStack,
                                             int light, int overlay) {
        if (polyMeshModel == null || cachedTexture == null) return;

        // 保留旧 attachmentSpecial 的起点；资格扫描单列，方便解释附件根中的剩余耗时。
        long precheckStartNs = PerformanceDiagnostics.begin();
        boolean hasSpecialMesh = false;
        try {
            hasSpecialMesh = polyMeshModel.hasMeshInSubtree(boneName);
        } finally {
            PerformanceDiagnostics.endAttachmentSpecialPrecheck(precheckStartNs, hasSpecialMesh);
        }
        if (!hasSpecialMesh) return;

        boolean nativeScopeArAttempt = isNativeScopeArCollectionFor(boneName);
        long diagnosticsStartNs = PerformanceDiagnostics.begin();
        boolean shouldSubmitTypedBatch = !nativeScopeArAttempt;
        MultiBufferSource.BufferSource bufferSource = null;
        RenderType cutoutRenderType = null;
        boolean nativeOutcomeRecorded = false;
        try {
            try {
                bufferSource = Minecraft.getInstance()
                        .renderBuffers().bufferSource();
                // TaCZ 原生 renderTempPart 使用 entityCutout；这里只替换图元模式，
                // CULL、cutout、光照与 overlay 语义保持一致。
                cutoutRenderType = PolyMeshRenderTypes.cutoutCull(cachedTexture);

                long getBufferStartNs = PerformanceDiagnostics.begin();
                VertexConsumer cutoutConsumer;
                try {
                    cutoutConsumer = bufferSource.getBuffer(cutoutRenderType);
                } finally {
                    PerformanceDiagnostics.endAttachmentSpecialGetBuffer(
                            boneName, getBufferStartNs);
                }

                if (nativeScopeArAttempt) {
                    int submittedVertices = polyMeshModel.renderSpecialSubtreeTrianglesAccelerated(
                            boneName, poseStack, cutoutConsumer, light, overlay,
                            1.0F, 1.0F, 1.0F, 1.0F);
                    if (submittedVertices < 0) {
                        PerformanceDiagnostics.nativeScopeArRejected();
                        nativeOutcomeRecorded = true;
                        if (openNativeScopeArCircuit(
                                boneName, "AR consumer rejected scope_body")) {
                            PerformanceDiagnostics.nativeScopeArCircuitTrip();
                        }
                    } else {
                        PerformanceDiagnostics.nativeScopeArAccepted();
                        nativeOutcomeRecorded = true;
                    }
                } else {
                    // 非 AR：优先走索引化 VBO 直绘（和枪模型 renderVBO 同一套 drawIndexed），
                    // 避免 scope_body 等大几何每帧 CPU 逐顶点直写。cutoutCull 的 setup 不含
                    // stencil shard，不会覆盖 TaCZ 在 renderTempPart 前设置的模板状态。
                    // 骨骼含非等比缩放时回退 CPU 直写以保证法线正确。
                    // 注：掉落物/展示框的「不显示」根因是纹理反查失败 + 单面剔除（已在 render
                    // 的纹理反查与 renderPolyMeshNormalAttachment 的 forceNoCull 中修复），
                    // 与 VBO 直绘无关，故这里对所有非 AR 场景统一走 VBO，避免非第一人称回退 CPU。
                    if (ClientConfig.meshScopeVbo() && polyMeshModel.isAllBonesRigid()) {
                        cutoutRenderType.setupRenderState();
                        try {
                            polyMeshModel.renderSpecialSubtreeVbo(boneName, poseStack, light);
                        } finally {
                            cutoutRenderType.clearRenderState();
                        }
                    } else {
                        polyMeshModel.renderSpecialSubtreeTrianglesDirectProfiled(
                                boneName, poseStack, cutoutConsumer, light, overlay,
                                1.0F, 1.0F, 1.0F, 1.0F);
                    }
                }
            } finally {
                if (shouldSubmitTypedBatch && bufferSource != null && cutoutRenderType != null) {
                    // 普通 BufferSource 在此同步提交；Oculus FullyBuffered 的指定类型刷新是空操作，
                    // 紧随其后的 TaCZ 原生 OculusCompat.endBatch 会在同一 stencil 状态下统一提交。
                    PerformanceDiagnostics.endAttachmentSpecialTypedBatch(
                            boneName, bufferSource, cutoutRenderType);
                }
            }
        } catch (RuntimeException | LinkageError error) {
            if (nativeScopeArAttempt) {
                if (!nativeOutcomeRecorded) {
                    PerformanceDiagnostics.nativeScopeArRejected();
                }
                if (openNativeScopeArCircuit(boneName, error.getClass().getSimpleName())) {
                    PerformanceDiagnostics.nativeScopeArCircuitTrip();
                }
            }
            // Mixin 位于 TaCZ 的状态清理之前；异常不能阻断其 visible/stencil/PoseStack 复原。
            MESH_LOG.error("[MeshyLoader] Failed to render attachment special poly_mesh bone: {}",
                    boneName, error);
        } finally {
            PerformanceDiagnostics.endAttachmentSpecial(boneName, diagnosticsStartNs);
        }
    }

    private boolean isNativeScopeArCollectionFor(String boneName) {
        return nativeScopeArCollection
                && boneName != null
                && boneName.equals(getLeafName(scopeBodyPath));
    }

    private boolean openNativeScopeArCircuit(String boneName, String reason) {
        if (!nativeScopeArCircuitOpen) {
            nativeScopeArCircuitOpen = true;
            MESH_LOG.warn(
                    "[MeshyLoader] Native scope AR circuit opened for bone {}: {}; "
                            + "falling back to synchronous rendering on following frames",
                    boneName, reason);
            return true;
        }
        return false;
    }

    /**
     * 第一人称完全闲置时补画 ocular_sight 的局部入口。
     *
     * <p>cube 与 poly_mesh 分别使用 QUADS/TRIANGLES，但两个 RenderType 都只写颜色、
     * 不写深度。状态由 RenderType 自己在 setup/clear 中局部管理，不能使用全局
     * {@code RenderSystem.depthMask(false)}，否则 Oculus 的全批次刷新会污染其他实体。</p>
     *
     * @return 已接管本次叶骨绘制时返回 true；纹理尚未准备好时返回 false。
     */
    public boolean renderIdleSightSubtree(BedrockPart part, String boneName,
                                          PoseStack poseStack,
                                          ItemDisplayContext transformType,
                                          int light, int overlay) {
        if (cachedTexture == null) {
            return false;
        }

        long diagnosticsStartNs = PerformanceDiagnostics.begin();
        try {
            MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance()
                    .renderBuffers().bufferSource();
            RenderType cubeRenderType = PolyMeshRenderTypes
                    .cutoutCullColorOnlyQuads(cachedTexture);
            RenderType meshRenderType = PolyMeshRenderTypes
                    .cutoutCullColorOnlyTriangles(cachedTexture);

            try {
                VertexConsumer cubeConsumer = bufferSource.getBuffer(cubeRenderType);
                part.render(poseStack, transformType, cubeConsumer, light, overlay);

                if (polyMeshModel != null && polyMeshModel.hasMeshInSubtree(boneName)) {
                    VertexConsumer meshConsumer = bufferSource.getBuffer(meshRenderType);
                    polyMeshModel.renderSpecialSubtreeTrianglesDirect(
                            boneName, poseStack, meshConsumer, light, overlay,
                            1.0F, 1.0F, 1.0F, 1.0F);
                }
            } finally {
                // 普通 BufferSource 在这里按类型提交；Oculus FullyBuffered 会由紧随其后的
                // TaCZ OculusCompat.endBatch 统一提交，并逐 RenderType 应用局部写掩码。
                PerformanceDiagnostics.endTypedBatch(bufferSource, meshRenderType);
                PerformanceDiagnostics.endTypedBatch(bufferSource, cubeRenderType);
            }
        } catch (RuntimeException | LinkageError error) {
            // 已进入局部批次后不再回落到原生深度写入，避免同一镜片半绘制后重复提交。
            MESH_LOG.error("[MeshyLoader] Failed to render idle sight ocular bone: {}",
                    boneName, error);
        } finally {
            PerformanceDiagnostics.endAttachmentIdleSight(diagnosticsStartNs);
        }
        return true;
    }

    @Override
    public void render(@Nullable ItemStack attachmentItem, ItemStack currentGunItem, PoseStack poseStack,
                       ItemDisplayContext transformType, RenderType renderType, int light, int overlay) {
        long diagnosticsStartNs = PerformanceDiagnostics.begin();
        int previousFlushOrigin = PerformanceDiagnostics.enterAttachmentFlushScope();
        try {

        if (!this.hasPolyMesh()) {
            super.render(attachmentItem, currentGunItem, poseStack, transformType, renderType, light, overlay);
            return;
        }

        // ---- テクスチャ解決 ----
        if (cachedTexture == null) {
            if (attachmentItem != null) {
                IAttachment iAttachment = IAttachment.getIAttachmentOrNull(attachmentItem);
                if (iAttachment != null) {
                    TimelessAPI.getClientAttachmentIndex(iAttachment.getAttachmentId(attachmentItem))
                            .ifPresent(index -> cachedTexture = index.getModelTexture());
                }
            } else {
                for (Map.Entry<ResourceLocation, ClientAttachmentIndex> entry : TimelessAPI.getAllClientAttachmentIndex()) {
                    ClientAttachmentIndex index = entry.getValue();
                    if (index.getAttachmentModel() == this) {
                        cachedTexture = index.getModelTexture();
                        break;
                    }
                    // 单独附件物品在掉落物/展示框会切 LOD 模型：主模型比对不上时，比对 LOD 实例。
                    Pair<BedrockAttachmentModel, ResourceLocation> lod = index.getLodModel();
                    if (lod != null && lod.getLeft() == this) {
                        cachedTexture = lod.getRight() != null ? lod.getRight() : index.getModelTexture();
                        break;
                    }
                }
            }
        }

        if (cachedTexture == null) {
            super.render(attachmentItem, currentGunItem, poseStack, transformType, renderType, light, overlay);
            return;
        }

        // インベントリのプレイヤープレビュー（ドール表示）など、GUI 画面が開いている
        // 状態では VBO 直接描画が正しく表示されないことが実機で確認されているため、
        // その場合は VBO を無効化する。
        final boolean isGuiLike = com.example.dudumeshloader.render.ScreenRenderTracker.isRenderingScreen();
        final boolean useVBO = !isGuiLike;

        Minecraft mc2 = Minecraft.getInstance();
        MultiBufferSource.BufferSource bufferSource = mc2.renderBuffers().bufferSource();

        // 仅第一人称 scope_body 放行 TaCZ 原生 -942 AR layer。ocular/division 仍由
        // 原生 before/after 回调同步直写；GUI、第三人称与熔断状态完整沿用旧保护路径。
        final boolean taczAccelerationActive = com.tacz.guns.compat.ar.ARCompat.shouldAccelerate();
        final boolean nativeScopeArCandidate = canUseNativeScopeAr(
                transformType, isGuiLike, taczAccelerationActive);
        final boolean useNativeScopeAr;
        if (nativeScopeArCandidate) {
            PerformanceDiagnostics.nativeScopeArAttempt();
            if (nativeScopeArCircuitOpen) {
                PerformanceDiagnostics.nativeScopeArCircuitBypass();
                useNativeScopeAr = false;
            } else {
                useNativeScopeAr = true;
            }
        } else {
            useNativeScopeAr = false;
        }

        final boolean shouldRestoreAcceleration = taczAccelerationActive && !useNativeScopeAr;
        if (shouldRestoreAcceleration) {
            com.tacz.guns.compat.ar.ARCompat.disableAcceleration();
        }
        boolean accelerationStillDisabled = shouldRestoreAcceleration;
        boolean previousNativeScopeArCollection = nativeScopeArCollection;
        nativeScopeArCollection = useNativeScopeAr;
        try {
            try {
                super.render(attachmentItem, currentGunItem, poseStack, transformType, renderType, light, overlay);
            } finally {
                nativeScopeArCollection = previousNativeScopeArCollection;
                if (accelerationStillDisabled) {
                    com.tacz.guns.compat.ar.ARCompat.resetAcceleration();
                    accelerationStillDisabled = false;
                }
            }
            if (!isGuiLike) {
                mc2.gameRenderer.lightTexture().turnOnLightLayer();
            }

            // scope/sight 的特殊子树已在 super.render() 内通过原生 renderTempPart 绘制；
            // 这里仅补画当前仍可见的普通 poly_mesh，避免绕过 stencil 后重复显示。
            // 单独附件物品（attachmentItem==null）在掉落物/展示框会被 AttachmentItemRenderer
            // 以 scale(-1,-1,1) 翻转，单面 cutoutCull 会把平片整片背面剔除，故强制双面。
            renderPolyMeshNormalAttachment(
                    poseStack, bufferSource, cachedTexture, light, overlay, useVBO,
                    attachmentItem == null);
            boolean hasTrans = this.polyMeshModel.hasTranslucentMeshes();

            if (!PerformanceDiagnostics.endAttachmentOculusBatch(bufferSource)) {
                PerformanceDiagnostics.endTypedBatch(bufferSource, PolyMeshRenderTypes.cutoutNoCull(cachedTexture));
                PerformanceDiagnostics.endTypedBatch(bufferSource, RenderType.entityCutoutNoCull(cachedTexture));
                PerformanceDiagnostics.endTypedBatch(bufferSource, RenderType.entityCutout(cachedTexture));
                if (hasTrans) {
                    if (net.minecraftforge.fml.ModList.get().isLoaded("oculus")) {
                        PerformanceDiagnostics.endTypedBatch(
                                bufferSource, PolyMeshRenderTypes.translucentCull(cachedTexture));
                        PerformanceDiagnostics.endTypedBatch(
                                bufferSource, RenderType.entityTranslucentCull(cachedTexture));
                    } else {
                        com.example.dudumeshloader.render.MeshyBatchFlushHandler.markTranslucentPending(cachedTexture);
                    }
                }
            }

            if (!isGuiLike) {
                mc2.gameRenderer.lightTexture().turnOffLightLayer();
            }
        } finally {
            nativeScopeArCollection = previousNativeScopeArCollection;
            if (accelerationStillDisabled) {
                com.tacz.guns.compat.ar.ARCompat.resetAcceleration();
            }
        }
        } finally {
            // 包含 TaCZ 原生附件与本模组后置普通 poly_mesh，属于 inclusive 根阶段。
            PerformanceDiagnostics.endAttachmentRoot(diagnosticsStartNs);
            PerformanceDiagnostics.leaveFlushScope(previousFlushOrigin);
        }
    }

    private boolean canUseNativeScopeAr(ItemDisplayContext transformType,
                                        boolean isGuiLike,
                                        boolean taczAccelerationActive) {
        if (!taczAccelerationActive
                || !com.example.dudumeshloader.compat.ar.ARCompat.isLoaded()
                || isGuiLike
                || !transformType.firstPerson()
                || !isScope()
                || polyMeshModel == null) {
            return false;
        }

        String scopeBodyName = getLeafName(scopeBodyPath);
        if (scopeBodyName == null || !polyMeshModel.hasMeshInSubtree(scopeBodyName)) {
            return false;
        }

        // ocular_ring 位于更早的 -943 layer；若其中也有 poly_mesh，整段继续走旧同步路径，
        // 避免在其 before 回调尚未执行时改变既有模板语义。
        String ocularRingName = getLeafName(ocularRingPath);
        return ocularRingName == null || !polyMeshModel.hasMeshInSubtree(ocularRingName);
    }

    @Nullable
    private static String getLeafName(List<BedrockPart> path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        BedrockPart leaf = path.get(path.size() - 1);
        return leaf == null ? null : leaf.name;
    }

    /** 供 Mixin 在配置延迟 -942 回调时快照本次是否启用了原生 scope AR。 */
    public boolean isNativeScopeArCollectionActive() {
        return nativeScopeArCollection;
    }


    /**
     * geo.json を読み込み、poly_mesh ボーンを PolyMeshModel に登録する。
     *
     * cubes の消去は一切行わない（TaczPolyMeshGunModel と同じ方針）。
     */
    public void loadPolyMesh(ResourceLocation modelLocation) {
        try {
            if (this.polyMeshModel != null) {
                this.polyMeshModel.close();
            }

            var resource = Minecraft.getInstance().getResourceManager()
                    .getResource(modelLocation).orElseThrow();

            try (var reader = new InputStreamReader(resource.open())) {
                JsonObject rawJson = JsonParser.parseReader(reader).getAsJsonObject();

                IPolyMeshBone adaptedRoot = new IPolyMeshBone() {
                    @Override public String getName()    { return "meshy_dummy_root"; }
                    @Override public float getPivotX()   { return 0; }
                    @Override public float getPivotY()   { return 0; }
                    @Override public float getPivotZ()   { return 0; }
                    @Override public float getRotX()     { return 0; }
                    @Override public float getRotY()     { return 0; }
                    @Override public float getRotZ()     { return 0; }
                    @Override public boolean isVisible() { return true; }
                    @Override public void applyTransform(PoseStack ps) {}
                    @Override
                    public List<? extends IPolyMeshBone> getChildren() {
                        if (cachedRootChildren != null) return cachedRootChildren;
                        cachedRootChildren = getShouldRender().stream()
                                .map(TaczPartAdapter::new).collect(Collectors.toList());
                        return cachedRootChildren;
                    }
                };

                this.polyMeshModel = new PolyMeshModel(adaptedRoot, rawJson);

                // cubes の消去は一切行わない（クラス Javadoc 参照）

                this.cachedTexture = null;
                this.cachedRootChildren = null;
                this.nativeScopeArCollection = false;
                this.nativeScopeArCircuitOpen = false;

                com.example.dudumeshloader.render.ShaderStateTracker.register(this.polyMeshModel);

                MESH_LOG.info("[MeshyLoader] Loaded attachment poly_mesh from: {}", modelLocation);
            }
        } catch (Exception e) {
            MESH_LOG.error("[MeshyDebug][loadPolyMesh] FAILED: location={}", modelLocation, e);
        }
    }

    public boolean hasPolyMesh() { return polyMeshModel != null; }

    /** 供倍镜状态桥接判断特殊子树是否真的包含 poly_mesh。 */
    public boolean hasPolyMeshInSubtree(String boneName) {
        return polyMeshModel != null && boneName != null
                && polyMeshModel.hasMeshInSubtree(boneName);
    }

    private static class TaczPartAdapter implements IPolyMeshBone {
        private final BedrockPart part;
        private List<IPolyMeshBone> cachedChildren;
        TaczPartAdapter(BedrockPart part) { this.part = part; }
        @Override public String getName()        { return part.name == null ? "" : part.name; }
        @Override public float getPivotX()       { return part.x; }
        @Override public float getPivotY()       { return part.y; }
        @Override public float getPivotZ()       { return part.z; }
        @Override public float getRotX()         { return part.xRot; }
        @Override public float getRotY()         { return part.yRot; }
        @Override public float getRotZ()         { return part.zRot; }
        @Override public float getScaleX()       { return part.xScale == 0 ? 1f : part.xScale; }
        @Override public float getScaleY()       { return part.yScale == 0 ? 1f : part.yScale; }
        @Override public float getScaleZ()       { return part.zScale == 0 ? 1f : part.zScale; }
        @Override public boolean isVisible()     { return part.visible; }
        @Override public boolean isIlluminated() { return part.illuminated; }
        @Override
        public List<? extends IPolyMeshBone> getChildren() {
            if (cachedChildren != null) return cachedChildren;
            cachedChildren = new ArrayList<>();
            if (part.children != null) {
                for (BedrockPart c : part.children) cachedChildren.add(new TaczPartAdapter(c));
            }
            return cachedChildren;
        }
        @Override public void applyTransform(PoseStack ps) { part.translateAndRotateAndScale(ps); }
    }
}
