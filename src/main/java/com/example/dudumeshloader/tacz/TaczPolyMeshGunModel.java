package com.example.dudumeshloader.tacz;

import com.example.dudumeshloader.config.ClientConfig;
import com.example.dudumeshloader.core.PolyMeshModel;
import com.example.dudumeshloader.api.IPolyMeshBone;
import com.example.dudumeshloader.render.PolyMeshRenderTypes;
import com.example.dudumeshloader.render.PerformanceDiagnostics;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.client.model.IFunctionalRenderer;
import com.tacz.guns.client.model.GunModelConstant;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.client.animation.AnimationListener;
import com.tacz.guns.api.client.animation.ObjectAnimationChannel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.model.listener.model.ModelAdditionalMagazineListener;
import net.minecraft.client.Minecraft;
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
import java.util.stream.Collectors;

@OnlyIn(Dist.CLIENT)
public class TaczPolyMeshGunModel extends com.tacz.guns.client.model.BedrockGunModel {

    private PolyMeshModel polyMeshModel;
    private ResourceLocation cachedTexture = null;
    private List<IPolyMeshBone> cachedRootChildren = null;
    /** LODモデル用にテクスチャを固定する場合にセット。nullなら通常通りTimelessAPIから取得。 */
    private ResourceLocation overrideTexture = null;
    /**
     * MAG_NORMAL_NODE 配下に poly_mesh があるかどうかのキャッシュ（loadPolyMesh 時に確定）。
     * additional_magazine のメッシュ描画要否判定に使う。
     */
    private boolean cachedHasMagMesh = false;
    /**
     * additional_magazine ボーン自体に poly_mesh があるかどうかのキャッシュ。
     * MAG_ADDITIONAL_NODE サブツリーに直接メッシュを持つ場合の判定に使う。
     */
    private boolean cachedHasAdditionalMagMesh = false;
    /**
     * TaCZ 原生 BedrockGunModel.render/renderAccelerated 正在处理本模型时为 true。
     * Mixin 只在这个窗口内把主体 poly_mesh 追加到原生枪身阶段，避免其它直接调用误入。
     */
    private boolean nativeGunRenderActive = false;
    /** 本次原生枪身渲染是否处于非 GUI 场景。 */
    private boolean nativeGunRenderUseVBO = false;
    /** render 窗口内快照的 ItemDisplayContext 名，供诊断按场景分桶（FP/FIXED/GROUND）。 */
    private String nativeGunRenderContext = "NONE";
    /** AR consumer 退化时只记录一次，防止逐帧刷日志。 */
    private boolean warnedNonAcceleratedGunConsumer = false;
    /** 原生桥接异常只记录一次，避免渲染失败时进一步刷日志拖慢帧率。 */
    private boolean warnedNativeGunRenderFailure = false;
    /** 该枪是否纯 poly_mesh（geo.json 所有骨骼均无 cube），loadPolyMesh 时判定一次。 */
    private boolean cachedAllBonesPoly = false;
    /** cachedAllBonesPoly 是否已判定完成。 */
    private boolean cachedSkipCubesReady = false;

    private static final org.apache.logging.log4j.Logger MESH_LOG =
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader");

    public TaczPolyMeshGunModel(
            com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO pojo,
            com.tacz.guns.client.resource.pojo.model.BedrockVersion version) {
        super(pojo, version);
    }

    @Override
    public void render(PoseStack poseStack, ItemStack stack, ItemDisplayContext transformType,
                       RenderType renderType, int light, int overlay) {
        long diagnosticsStartNs = PerformanceDiagnostics.begin();
        int previousFlushOrigin = PerformanceDiagnostics.enterGunFlushScope();
        try {

        if (!this.hasPolyMesh()) {
            super.render(poseStack, stack, transformType, renderType, light, overlay);
            return;
        }

        if (cachedTexture == null) {
            if (overrideTexture != null) {
                cachedTexture = overrideTexture;
            } else {
                TimelessAPI.getGunDisplay(stack).ifPresent(display ->
                        cachedTexture = display.getModelTexture()
                );
            }
        }

        if (cachedTexture == null) {
            super.render(poseStack, stack, transformType, renderType, light, overlay);
            return;
        }

        if (cachedHasAdditionalMagMesh) {
            polyMeshModel.setExcludeSubtree(GunModelConstant.MAG_ADDITIONAL_NODE);
        } else {
            polyMeshModel.clearExcludeSubtree();
        }

        // 保留原实现对 GUI 渲染瞬间的判定；VBO 在 GUI 内嵌预览中会显示异常，故 GUI 时禁用。
        // （注：VBO 与 Oculus 本身兼容；AR 冲突已由下方 disableAcceleration 处理，无需在此禁用 VBO。）
        nativeGunRenderUseVBO =
                !com.example.dudumeshloader.render.ScreenRenderTracker.isRenderingScreen();

        // AR 放行策略：第一人称手持禁用 AR（scope stencil 同步时序与 AR layer 延迟冲突，
        // 且手持单枪时 VBO 比 AR 实例化更快）。装 scope 时仍禁用 AR 保护 stencil。
        // 物品上下文（展示框 FIXED / 掉落物 GROUND）同样禁用 AR：这些上下文 AR 不 bake cube，
        // 反而每帧重传，直接渲染（同第一人称）更快。
        final boolean isFirstPerson =
                transformType == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                        || transformType == ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
        final boolean hasScopeStencil = getScopeStencilMode() != ScopeStencilMode.NONE;
        final boolean isItemContext = ClientConfig.meshDisableArForItem()
                && (transformType == ItemDisplayContext.FIXED
                        || transformType == ItemDisplayContext.GROUND);
        final boolean shouldRestoreAcceleration =
                (isFirstPerson || hasScopeStencil || isItemContext)
                        && com.tacz.guns.compat.ar.ARCompat.shouldAccelerate();
        if (shouldRestoreAcceleration) {
            com.tacz.guns.compat.ar.ARCompat.disableAcceleration();
        }

        // 只调用一次 TaCZ 原生入口。主体 poly_mesh 由 BedrockGunModelMixin 在原生枪身
        // 阶段追加：普通路径仍处于 stencil 清理前，AR 路径仍处于 -940 图层回调内。
        // 纯 poly_mesh 枪：清空 shouldRender 跳过 TACZ 原生的逐骨骼 cube 循环（poly_mesh 已替代）。
        // shouldRender 是共享字段（同枪型 FP/FIXED/GROUND 共用），必须 save/restore 避免污染其它上下文。
        List<BedrockPart> savedShouldRender = null;
        if (ClientConfig.meshSkipNativeCubes() && cachedSkipCubesReady && cachedAllBonesPoly) {
            List<BedrockPart> shouldRender = getShouldRender();
            if (!shouldRender.isEmpty()) {
                savedShouldRender = new ArrayList<>(shouldRender);
                shouldRender.clear();
            }
        }
        nativeGunRenderActive = true;
        nativeGunRenderContext = transformType == null ? "NULL" : transformType.name();
        poseStack.pushPose();
        try {
            super.render(poseStack, stack, transformType, renderType, light, overlay);
        } finally {
            poseStack.popPose();
            nativeGunRenderActive = false;
            if (savedShouldRender != null) {
                getShouldRender().addAll(savedShouldRender);
            }
            polyMeshModel.clearExcludeSubtree();
            if (shouldRestoreAcceleration) {
                com.tacz.guns.compat.ar.ARCompat.resetAcceleration();
            }
        }
        } finally {
            // 包含 TaCZ 原生枪体、附件和本模组 poly_mesh 阶段，不能与子阶段耗时直接相加。
            PerformanceDiagnostics.endGunRoot(diagnosticsStartNs);
            PerformanceDiagnostics.endGunRootByContext(nativeGunRenderContext, diagnosticsStartNs);
            PerformanceDiagnostics.leaveFlushScope(previousFlushOrigin);
        }
    }

    /**
     * 由 {@code BedrockGunModelMixin} 在 TaCZ 原生枪身调用结束后立即执行。
     *
     * <p>普通路径必须在 TaCZ 清除 stencil 前同步提交并刷新；AR 路径只登记网格，
     * 由 TaCZ 已设置的 -940 图层及 before/after 回调统一执行，不能在这里 endBatch。</p>
     */
    public void renderPolyMeshAtNativeStage(PoseStack poseStack, int light, int overlay,
                                            boolean accelerated) {
        if (!nativeGunRenderActive || polyMeshModel == null || cachedTexture == null) {
            return;
        }

        long diagnosticsStartNs = PerformanceDiagnostics.begin();
        try {
            MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance()
                    .renderBuffers().bufferSource();
            try {
                // PolyMesh 递归内部若在第三方 consumer/AR 首次建缓存时抛异常，独立栈可避免
                // 未完成的骨骼 pushPose 污染 TaCZ 随后继续使用的原始 PoseStack。
                PoseStack meshPoseStack = new PoseStack();
                meshPoseStack.last().pose().set(poseStack.last().pose());
                meshPoseStack.last().normal().set(poseStack.last().normal());

                if (accelerated) {
                    renderPolyMeshAccelerated(
                            meshPoseStack, bufferSource, cachedTexture, light, overlay, nativeGunRenderUseVBO);
                } else if (getScopeStencilMode() != ScopeStencilMode.NONE) {
                    renderPolyMeshWithStencil(
                            meshPoseStack, bufferSource, cachedTexture, light, overlay, nativeGunRenderUseVBO);
                } else {
                    renderPolyMeshNormal(
                            meshPoseStack, bufferSource, cachedTexture, light, overlay, nativeGunRenderUseVBO);
                }
            } catch (RuntimeException | LinkageError e) {
                // 注入点位于 TaCZ 状态清理之前，桥接异常不能越过原生 reset/clear 逻辑。
                if (!warnedNativeGunRenderFailure) {
                    warnedNativeGunRenderFailure = true;
                    MESH_LOG.error("[MeshyLoader] Failed to render gun poly_mesh at TaCZ native stage", e);
                }
            }
        } finally {
            PerformanceDiagnostics.endGunPolyStage(diagnosticsStartNs, accelerated);
            PerformanceDiagnostics.endGunPolyStageByContext(nativeGunRenderContext, diagnosticsStartNs, accelerated);
        }
    }

    /** 当前附件让 TaCZ 为枪身启用的 stencil 模式。 */
    private ScopeStencilMode getScopeStencilMode() {
        ItemStack scopeItem = getCurrentAttachmentItem().get(
                com.tacz.guns.api.item.attachment.AttachmentType.SCOPE);
        if (scopePosPath == null || scopeItem == null || scopeItem.isEmpty()) {
            return ScopeStencilMode.NONE;
        }
        com.tacz.guns.api.item.IAttachment attachment =
                com.tacz.guns.api.item.IAttachment.getIAttachmentOrNull(scopeItem);
        if (attachment == null) {
            return ScopeStencilMode.NONE;
        }
        return TimelessAPI.getClientAttachmentIndex(attachment.getAttachmentId(scopeItem))
                .map(index -> {
                    if (index.isScope() && index.isSight()) {
                        return ScopeStencilMode.BOTH;
                    }
                    return index.isScope() ? ScopeStencilMode.SCOPE : ScopeStencilMode.NONE;
                })
                .orElse(ScopeStencilMode.NONE);
    }


    /**

     * geo.json を読み込み、poly_mesh ボーンを PolyMeshModel に登録する。
     *
     * <h3>キューブ・メッシュ混在対応</h3>
     * cubes の消去は一切行わない。理由は次の通り:
     * <ul>
     *   <li><b>poly_mesh のみ</b>のボーンは geo.json 上で cubes が元々空。
     *       TacZ 側は何も描画しないため PolyMesh との二重描画は起きない。</li>
     *   <li><b>cubes のみ</b>のボーンは PolyMeshModel の meshMap に存在しないため
     *       PolyMesh 側は何もしない。TacZ が正常にキューブを描画する。</li>
     *   <li><b>両方を持つ混在ボーン</b>は TacZ がキューブを、PolyMesh がメッシュを
     *       それぞれ描画し、両者が正しく合わさる。cubes を消す必要はない。</li>
     * </ul>
     */

    // =========================================================================
    // PolyMesh 描画ヘルパー
    // =========================================================================

    /**
     * TaCZ 的 AR 枪身阶段只登记网格，不在这里刷新批次或改动 stencil。
     * getBuffer 时仍处于 TaCZ 的 -940 图层和 scope before/after 回调窗口内。
     */
    private void renderPolyMeshAccelerated(PoseStack poseStack,
                                           MultiBufferSource.BufferSource bufferSource,
                                           ResourceLocation tex, int light, int overlay,
                                           boolean useVBO) {
        VertexConsumer cutoutConsumer = bufferSource.getBuffer(PolyMeshRenderTypes.cutoutNoCull(tex));
        boolean canUseAcceleratedPipeline =
                com.example.dudumeshloader.compat.ar.ARCompat.isAccelerated(cutoutConsumer);
        if (polyMeshModel.hasTranslucentMeshes()) {
            VertexConsumer translucentConsumer =
                    bufferSource.getBuffer(PolyMeshRenderTypes.translucentCull(tex));
            canUseAcceleratedPipeline &=
                    com.example.dudumeshloader.compat.ar.ARCompat.isAccelerated(translucentConsumer);
        }
        PerformanceDiagnostics.gunArStageConsumer(canUseAcceleratedPipeline);
        if (!canUseAcceleratedPipeline) {
            if (!warnedNonAcceleratedGunConsumer) {
                warnedNonAcceleratedGunConsumer = true;
                MESH_LOG.warn("[MeshyLoader] Native AR gun consumer is not accelerated; applying scope-safe handling");
            }
            if (getScopeStencilMode() == ScopeStencilMode.NONE) {
                renderPolyMeshNormal(poseStack, bufferSource, tex, light, overlay, useVBO);
            }
            return;
        }

        // AR consumer 已接管时同样优先走 VBO（drawWithShader 立即绘制），避免 AR 的
        // consumer 缓存路径在手持单枪场景下反而更慢。AR 通常已被上方的 disableAcceleration
        // 关闭，此分支仅作为极端情况的兜底。
        polyMeshModel.renderCutoutOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
        if (polyMeshModel.hasTranslucentMeshes()) {
            polyMeshModel.renderTranslucentOnly(
                    poseStack, bufferSource, tex, light, overlay, useVBO);
        }
    }

    private void renderPolyMeshWithStencil(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                           ResourceLocation tex, int light, int overlay, boolean useVBO) {
        polyMeshModel.renderCutoutOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
        if (!PerformanceDiagnostics.endGunOculusBatch(bufferSource)) {
            PerformanceDiagnostics.endTypedBatch(bufferSource, PolyMeshRenderTypes.cutoutCull(tex));
            PerformanceDiagnostics.endTypedBatch(bufferSource, PolyMeshRenderTypes.cutoutNoCull(tex));
            PerformanceDiagnostics.endTypedBatch(bufferSource, RenderType.entityCutoutNoCull(tex));
            PerformanceDiagnostics.endTypedBatch(bufferSource, RenderType.entityCutout(tex));
        }
        if (polyMeshModel.hasTranslucentMeshes()) {
            polyMeshModel.renderTranslucentOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
            if (!PerformanceDiagnostics.endGunOculusBatch(bufferSource)) {
                PerformanceDiagnostics.endTypedBatch(bufferSource, PolyMeshRenderTypes.translucentCull(tex));
                PerformanceDiagnostics.endTypedBatch(bufferSource, RenderType.entityTranslucentCull(tex));
            }
        }
    }

    private void renderPolyMeshNormal(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                      ResourceLocation tex, int light, int overlay, boolean useVBO) {
        polyMeshModel.renderCutoutOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
        if (!PerformanceDiagnostics.endGunOculusBatch(bufferSource)) {
            PerformanceDiagnostics.endTypedBatch(bufferSource, PolyMeshRenderTypes.cutoutCull(tex));
            PerformanceDiagnostics.endTypedBatch(bufferSource, PolyMeshRenderTypes.cutoutNoCull(tex));
            PerformanceDiagnostics.endTypedBatch(bufferSource, RenderType.entityCutoutNoCull(tex));
            PerformanceDiagnostics.endTypedBatch(bufferSource, RenderType.entityCutout(tex));
        }
        if (polyMeshModel.hasTranslucentMeshes()) {
            polyMeshModel.renderTranslucentOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
            if (!PerformanceDiagnostics.endGunOculusBatch(bufferSource)) {
                if (net.minecraftforge.fml.ModList.get().isLoaded("oculus")) {
                    PerformanceDiagnostics.endTypedBatch(bufferSource, PolyMeshRenderTypes.translucentCull(tex));
                    PerformanceDiagnostics.endTypedBatch(bufferSource, RenderType.entityTranslucentCull(tex));
                } else {
                    com.example.dudumeshloader.render.MeshyBatchFlushHandler.markTranslucentPending(tex);
                }
            }
        }
    }

    private enum ScopeStencilMode {
        NONE,
        SCOPE,
        BOTH
    }

    public void loadPolyMesh(ResourceLocation modelLocation) {
        loadPolyMesh(modelLocation, -1f);
    }

    /**
     * @param smoothingAngleDeg 自定义平滑角（度），{@code < 0} 表示未设置（保持导出法线），
     *                          {@code >= 0} 时按该角度运行时重算逐顶点法线。
     */
    public void loadPolyMesh(ResourceLocation modelLocation, float smoothingAngleDeg) {
        // 幂等护栏：索引可能被反复构建（例如每帧惰性加载/资源重载），
        // 若 poly_mesh 已解析则直接跳过，避免反复重解析 geo.json 造成卡死。
        if (this.polyMeshModel != null) {
            return;
        }
        try {
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

                this.polyMeshModel = new PolyMeshModel(adaptedRoot, rawJson, smoothingAngleDeg);

                // cubes の消去は一切行わない（クラス Javadoc 参照）
                // 但し tacz_charms 挂饰 mod 需要 cube 才能判定锚点/碰撞盒，为纯 poly_mesh 骨骼注入虚拟 cube。
                com.example.dudumeshloader.compat.CharmCompat.injectVirtualCubes(this, this.polyMeshModel);

                this.cachedTexture = null;
                this.cachedRootChildren = null;

                com.example.dudumeshloader.render.ShaderStateTracker.register(this.polyMeshModel);
                cachedHasMagMesh = this.polyMeshModel.hasMeshInSubtree(GunModelConstant.MAG_NORMAL_NODE);
                cachedHasAdditionalMagMesh = this.polyMeshModel.hasMeshInSubtree(GunModelConstant.MAG_ADDITIONAL_NODE);

                // 判定该枪是否「纯 poly_mesh」（所有骨骼都无 cube）。若为纯 poly，则 TACZ 原生的
                // cube 渲染是纯冗余，可在 render 时清空 shouldRender 跳过整个逐骨骼 cube 循环。
                this.cachedAllBonesPoly = computeAllBonesPoly(rawJson);
                this.cachedSkipCubesReady = true;

                // loadPolyMesh 後に additional_magazine の FunctionalRenderer を再セットアップする。
                // BedrockGunModel のコンストラクタで setFunctionalRenderer が呼ばれた時点では
                // cachedHasMagMesh / cachedHasAdditionalMagMesh がまだ false のため、
                // PolyMesh 描画のフックが適用されていない。
                // poly_mesh が確定した今のタイミングで改めてフックを適用する。
                if (cachedHasMagMesh || cachedHasAdditionalMagMesh) {
                    applyAdditionalMagazineMeshHook();
                }

                MESH_LOG.info("[MeshyLoader] Loaded poly_mesh from: {}", modelLocation);
            }
        } catch (Exception e) {
            MESH_LOG.error("[MeshyDebug][loadPolyMesh] FAILED: location={}", modelLocation, e);
        }
    }

    /**
     * 判断该枪 geo.json 是否「纯 poly_mesh」（所有骨骼均无 cube）。
     * 纯 poly 时 TACZ 原生的 cube 渲染是冗余的，可在 render 时清空 shouldRender 跳过整个循环。
     */
    private boolean computeAllBonesPoly(JsonObject rawJson) {
        JsonArray geometries = rawJson.has("minecraft:geometry")
                ? rawJson.getAsJsonArray("minecraft:geometry") : null;
        if (geometries == null || geometries.isEmpty()) {
            return false;
        }
        JsonObject geo = geometries.get(0).getAsJsonObject();
        JsonArray bones = geo.getAsJsonArray("bones");
        if (bones == null || bones.isEmpty()) {
            return false;
        }
        for (JsonElement boneElem : bones) {
            JsonObject boneObj = boneElem.getAsJsonObject();
            if (boneObj.has("cubes")) {
                JsonArray cubes = boneObj.getAsJsonArray("cubes");
                if (cubes != null && cubes.size() > 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * additional_magazine ボーンの FunctionalRenderer に poly_mesh 描画フックを適用する。
     *
     * <p>このメソッドは loadPolyMesh() の末尾から呼ばれる。
     * BedrockGunModel のコンストラクタで設定された FunctionalRenderer の上に、
     * PolyMesh の magazine サブツリー描画を追加でラップする。</p>
     *
     * <h3>描画戦略</h3>
     * <ul>
     *   <li>TacZ オリジナルの FunctionalRenderer（キューブ描画 + visible 制御）を先に実行</li>
     *   <li>additional_magazine の visible が true のとき（アニメーション中）のみ、
     *       続けて poly_mesh の magazine サブツリーを同じ VertexConsumer に書き込む</li>
     *   <li>additional_magazine サブツリー自体に poly_mesh がある場合はそちらも描画</li>
     * </ul>
     */
    private void applyAdditionalMagazineMeshHook() {
        com.tacz.guns.client.model.bedrock.ModelRendererWrapper wrapper =
                modelMap.get(GunModelConstant.MAG_ADDITIONAL_NODE);
        if (wrapper == null) return;

        com.tacz.guns.client.model.bedrock.BedrockPart part = wrapper.getModelRenderer();
        if (!(part instanceof com.tacz.guns.client.model.FunctionalBedrockPart functionalPart)) return;

        // 現在セットされている FunctionalRenderer を取得しておく
        // （BedrockGunModel のコンストラクタが設定した renderAdditionalMagazine ラムダ）
        java.util.function.Function<com.tacz.guns.client.model.bedrock.BedrockPart,
                IFunctionalRenderer> existingFunction = functionalPart.functionalRenderer;

        functionalPart.functionalRenderer = (bp) -> {
            // 既存のレンダラー（TacZ オリジナル: キューブ描画）を取得
            IFunctionalRenderer originalRenderer = (existingFunction != null) ? existingFunction.apply(bp) : null;

            return (poseStack, vertexBuffer, transformType, light, overlay) -> {
                // 1. TacZ オリジナル処理（additional_magazine + magazine キューブ描画）
                if (originalRenderer != null) {
                    originalRenderer.render(poseStack, vertexBuffer, transformType, light, overlay);
                }

                // 2. additional_magazine の visible が true のときのみ poly_mesh を描画する。
                //    visible は ModelAdditionalMagazineListener によってアニメーション再生中に
                //    true にセットされる。false のときは描画しない（TacZ と同じ挙動）。
                if (!bp.visible) return;

                if (hasPolyMesh()) {
                    // 2a. magazine（MAG_NORMAL_NODE）サブツリーの poly_mesh を描画。
                    //     TacZ オリジナルが magazine キューブを複製して描画するのと同様に、
                    //     PolyMesh 側も magazine メッシュを additional_magazine の座標に描画する。
                    if (cachedHasMagMesh) {
                        polyMeshModel.renderSubtreeDirect(
                                GunModelConstant.MAG_NORMAL_NODE, poseStack, vertexBuffer, light, overlay);
                    }
                    // 2b. additional_magazine サブツリー自体の poly_mesh を描画。
                    if (cachedHasAdditionalMagMesh) {
                        polyMeshModel.renderSubtreeDirect(
                                GunModelConstant.MAG_ADDITIONAL_NODE, poseStack, vertexBuffer, light, overlay);
                    }
                }
            };
        };
    }

    /**
     * BedrockGunModel のコンストラクタが MAG_ADDITIONAL_NODE に対して
     * setFunctionalRenderer を呼んだタイミングでは、まだ loadPolyMesh() が
     * 実行されていないため cachedHasMagMesh が false になっている。
     * そのため、ここでは super を呼ぶだけにとどめ、実際のフック適用は
     * loadPolyMesh() 末尾の applyAdditionalMagazineMeshHook() に委ねる。
     */
    @Override
    public void setFunctionalRenderer(String node,
                                      java.util.function.Function<com.tacz.guns.client.model.bedrock.BedrockPart,
                                              IFunctionalRenderer> function) {
        super.setFunctionalRenderer(node, function);
        // loadPolyMesh() 後に再度呼ばれた場合（外部から上書き）は何もしない。
        // フック適用は loadPolyMesh() → applyAdditionalMagazineMeshHook() が担う。
    }

    /**
     * アニメーション用リスナーのサプライ。
     * BedrockGunModel の実装を継承しつつ、additional_magazine ノードに対して
     * {@link MeshAdditionalMagazineListener} を返すことで、
     * poly_mesh モデルの additional_magazine サブツリーの visible も
     * 同時に制御する。
     */
    @Override
    public AnimationListener supplyListeners(String nodeName, ObjectAnimationChannel.ChannelType type) {
        AnimationListener listener = super.supplyListeners(nodeName, type);
        if (listener == null) return null;

        if (GunModelConstant.MAG_ADDITIONAL_NODE.equals(nodeName) && hasPolyMesh()
                && (cachedHasMagMesh || cachedHasAdditionalMagMesh)) {
            // BedrockGunModel.supplyListeners は MAG_ADDITIONAL_NODE に対して
            // すでに ModelAdditionalMagazineListener を返している（BedrockPart.visible を true にする）。
            // ここではさらにそれをラップして、PolyMeshModel 側の除外制御も連動させる。
            return new MeshAdditionalMagazineListener(listener, this);
        }
        return listener;
    }

    /**
     * アニメーションリセット時に additional_magazine poly_mesh の除外設定も
     * cleanAnimationTransform に合わせてリセットする。
     */
    @Override
    public void cleanAnimationTransform() {
        super.cleanAnimationTransform();
        // super.cleanAnimationTransform() が additionalMagazineNode.visible = false にする。
        // PolyMeshModel 側の除外設定は render() の冒頭で毎フレーム再設定するため、
        // ここで明示的にリセットする必要はない。
    }

    public boolean hasPolyMesh() { return polyMeshModel != null; }

    /** LODモデル用テクスチャを固定する。checkLod介入時に呼ぶ。 */
    public void setOverrideTexture(ResourceLocation texture) {
        this.overrideTexture = texture;
        this.cachedTexture = null;
    }

    public static void register() {
        com.tacz.guns.api.client.other.GunModelTypeManager.registerModelType(
                "mesh", TaczPolyMeshGunModel::new);
        MESH_LOG.info("[DuDuMeshLoader] Registered TacZ model type: meshy");
    }

    // =========================================================================
    // 内部クラス
    // =========================================================================

    /**
     * additional_magazine アニメーションリスナーの PolyMesh 対応版。
     *
     * <p>BedrockGunModel の {@link ModelAdditionalMagazineListener} は
     * {@code BedrockPart.visible = true} にするだけだが、このリスナーは
     * {@link PolyMeshModel#clearExcludeSubtree()} を追加で呼ぶことで、
     * render() 冒頭で設定した除外を解除し、FunctionalRenderer 経由での
     * poly_mesh 描画が正しく動くようにする。</p>
     *
     * <p>実際の poly_mesh 描画は {@link #applyAdditionalMagazineMeshHook()} が
     * セットした FunctionalRenderer 内で行うため、ここでは除外制御のみ担当する。</p>
     */
    private static class MeshAdditionalMagazineListener implements AnimationListener {
        private final AnimationListener delegate;
        private final TaczPolyMeshGunModel model;

        MeshAdditionalMagazineListener(AnimationListener delegate, TaczPolyMeshGunModel model) {
            this.delegate = delegate;
            this.model = model;
        }

        @Override
        public void update(float[] values, boolean blend) {
            delegate.update(values, blend);
            // additional_magazine アニメーション再生中は polyMeshModel の
            // additional_magazine サブツリー除外を解除する。
            // これにより FunctionalRenderer 内の renderSubtreeDirect が機能する。
            if (model.polyMeshModel != null) {
                model.polyMeshModel.clearExcludeSubtree();
            }
        }

        @Override
        public float[] initialValue() { return delegate.initialValue(); }

        @Override
        public ObjectAnimationChannel.ChannelType getType() { return delegate.getType(); }
    }

    /**
     * BedrockPart（TacZ ボーン）を {@link IPolyMeshBone} に適合させるアダプタ。
     */
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
