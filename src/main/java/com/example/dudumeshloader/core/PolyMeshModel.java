package com.example.dudumeshloader.core;

import com.example.dudumeshloader.api.IPolyMeshBone;
import com.example.dudumeshloader.compat.ar.ARCompat;
import com.example.dudumeshloader.config.ClientConfig;
import com.example.dudumeshloader.render.PerformanceDiagnostics;
import com.example.dudumeshloader.render.PolyMeshRenderTypes;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL15;

import java.util.*;

@OnlyIn(Dist.CLIENT)
public class PolyMeshModel {
    /**
     * 骨骼级 VBO 渲染（DuDuMeshLoader A1 方案）。
     *
     * <p>每个 poly_mesh 骨骼的几何在构造时上传 GPU 一次（VBO），每帧只提交该骨骼的
     * model matrix + 一次 drawWithShader，用 MC 标准实体着色器（ModelViewMat 同时变换
     * 位置与法线）。骨骼是刚性变换（旋转+平移，等比缩放）时 mat3(ModelViewMat) 即逆转置，
     * 法线正确，无需专用着色器，因此完全兼容 Iris（Iris 会把标准 shader 替换成 GBuffer）。</p>
     *
     * <p>仅当骨骼存在<b>非等比缩放</b>（x/y/z scale 不相等）时，标准着色器的法线才会
     * 失真——此时整棵模型回退到 CPU 逐顶点 VertexConsumer 路径（见 {@link #allBonesRigid}），
     * 保证正确性优先。</p>
     */
    private static final boolean ENABLE_LEGACY_VBO = true;

    private final IPolyMeshBone root;
    private final Map<String, List<PolyMesh>> meshMap = new HashMap<>();
    private final Set<String> translucentBones = new HashSet<>();
    private final boolean hasTranslucent;
    private final Set<String> meshAncestorBones = new HashSet<>();
    /** 所有骨骼是否都为刚性变换（无非等比缩放）。false 时整体回退 CPU 路径保证法线正确。 */
    private final boolean allBonesRigid;

    /**
     * 「このライト値については既に meshMap の全ボーンをアップロード済み」を
     * 記録する集合。allVboReady() をボーンツリーの走査無しで O(1) 判定できる
     * ようにするためのもの（詳細は allVboReady/ensureAllUploaded のコメント参照）。
     */
    private final Set<Integer> fullyUploadedLights = new HashSet<>();
    /** poly_mesh を持ち illuminated 扱いになるボーン名セット（祖先伝播考慮済み） */
    private final Set<String> illuminatedBones = new HashSet<>();
    /** 描画から除外するサブツリーのルートボーン名（additional_magazine 対応用） */
    private String excludeSubtreeRoot = null;
    /** excludeSubtreeRoot 配下のボーン名セット（毎回計算しないようキャッシュ） */
    private final Set<String> excludedBones = new HashSet<>();

    public PolyMeshModel(IPolyMeshBone root, JsonObject rawJson) {
        this(root, rawJson, -1f);
    }

    /**
     * @param smoothingAngleDeg 自定义平滑角（度），{@code < 0} 表示未设置（保持导出法线）。
     */
    public PolyMeshModel(IPolyMeshBone root, JsonObject rawJson, float smoothingAngleDeg) {
        this.root = root;
        // 同一模型的全部 mesh 共用构造时快照；运行中修改配置不会造成一半圆滑、一半分面。
        boolean smoothNormals = ClientConfig.smoothNormalsEnabled();
        parsePolyMeshes(rawJson, smoothNormals, smoothingAngleDeg);
        for (String name : meshMap.keySet()) {
            if (name.toLowerCase().contains("translucent")) translucentBones.add(name);
        }
        this.hasTranslucent = !translucentBones.isEmpty();
        buildMeshAncestors(this.root, new ArrayDeque<>());
        buildIlluminatedBones(this.root, false);
        this.allBonesRigid = checkAllBonesRigid(this.root);
    }

    /**
     * 递归检查骨骼树是否全部为刚性变换（无非等比缩放）。
     *
     * <p>骨骼变换是级联的：任一祖先存在非等比缩放，都会让整条子链的法线在标准着色器下失真，
     * 因此只要遇到一个非等比缩放的骨骼即整体判定为 false（保守回退）。</p>
     */
    private boolean checkAllBonesRigid(IPolyMeshBone bone) {
        float sx = bone.getScaleX();
        float sy = bone.getScaleY();
        float sz = bone.getScaleZ();
        // 等比缩放（含 1）是刚性的；x/y/z 任一不相等即为非等比 → 非刚性
        if (Math.abs(sx - sy) > 1.0E-4f || Math.abs(sy - sz) > 1.0E-4f) {
            return false;
        }
        for (IPolyMeshBone child : bone.getChildren()) {
            if (!checkAllBonesRigid(child)) {
                return false;
            }
        }
        return true;
    }

    public boolean hasTranslucentMeshes() { return hasTranslucent; }

    /** 返回模型是否全部为刚性变换（无 VBO 法线失真风险），供诊断与 VBO 路径判定使用。 */
    public boolean isAllBonesRigid() { return allBonesRigid; }

    /**
     * poly_mesh 的 cutout 图元 RenderType。
     *
     * <p>默认单面（{@code cutoutCull}，开启背面剔除）以大幅降低过度绘制；
     * 当 {@link ClientConfig#meshSingleSided()} 为 false（模型三角绕序不一致、开启单面后出现破洞）时
     * 回退到双面（{@code cutoutNoCull}）。stencil 掩膜绘制（{@link #renderBonesStencilOnly}）
     * 需要两侧都填满，始终使用 NoCull，不在此处。</p>
     */
    private RenderType cutoutRenderType(ResourceLocation tex) {
        return cutoutRenderType(tex, false);
    }

    /**
     * @param forceNoCull 强制双面（关闭背面剔除）。用于被镜像的上下文（如 TaCZ 独立配件物品
     *                    AttachmentItemRenderer 先 {@code scale(-1,-1,1)} 翻转了三角形绕序，
     *                    单面 cutoutCull 会把平片 poly_mesh 整片剔除）。
     */
    private RenderType cutoutRenderType(ResourceLocation tex, boolean forceNoCull) {
        if (forceNoCull || !ClientConfig.meshSingleSided()) {
            return PolyMeshRenderTypes.cutoutNoCull(tex);
        }
        return PolyMeshRenderTypes.cutoutCull(tex);
    }

    private boolean buildMeshAncestors(IPolyMeshBone bone, Deque<String> path) {
        String name = bone.getName();
        path.addLast(name);
        boolean has = meshMap.containsKey(name);
        for (IPolyMeshBone child : bone.getChildren()) if (buildMeshAncestors(child, path)) has = true;
        if (has) meshAncestorBones.addAll(path);
        path.removeLast();
        return has;
    }

    /** illuminated フラグを祖先から子へ伝播させ、poly_mesh を持つボーンを illuminatedBones に登録する */
    private void buildIlluminatedBones(IPolyMeshBone bone, boolean parentIlluminated) {
        boolean illuminated = parentIlluminated || bone.isIlluminated();
        if (illuminated && meshMap.containsKey(bone.getName())) {
            illuminatedBones.add(bone.getName());
        }
        for (IPolyMeshBone child : bone.getChildren()) {
            buildIlluminatedBones(child, illuminated);
        }
    }

    private void parsePolyMeshes(JsonObject rawJson, boolean smoothNormals, float smoothingAngleDeg) {
        JsonArray geometries = rawJson.has("minecraft:geometry") ? rawJson.getAsJsonArray("minecraft:geometry") : null;
        if (geometries == null || geometries.isEmpty()) return;
        JsonObject geo = geometries.get(0).getAsJsonObject();
        float texW = geo.getAsJsonObject("description").get("texture_width").getAsFloat();
        float texH = geo.getAsJsonObject("description").get("texture_height").getAsFloat();
        JsonArray bones = geo.getAsJsonArray("bones");
        if (bones == null) return;
        for (JsonElement boneElem : bones) {
            JsonObject boneObj = boneElem.getAsJsonObject();
            if (!boneObj.has("poly_mesh") || !boneObj.has("name")) continue;
            String name = boneObj.get("name").getAsString();
            float pX=0, pY=0, pZ=0;
            if (boneObj.has("pivot")) {
                JsonArray p = boneObj.getAsJsonArray("pivot");
                pX=p.get(0).getAsFloat(); pY=p.get(1).getAsFloat(); pZ=p.get(2).getAsFloat();
            }
            PolyMesh mesh = new PolyMesh(
                    boneObj.getAsJsonObject("poly_mesh"), texW, texH,
                    new float[]{pX,pY,pZ}, smoothNormals, smoothingAngleDeg
            );
            if (mesh.getVertexCount() > 0) meshMap.computeIfAbsent(name, k -> new ArrayList<>()).add(mesh);
        }
    }

    // =========================================================================
    // 描画エントリポイント (overlay を受け取るよう修正)
    // =========================================================================

    public void renderWithTranslucentSplit(PoseStack ps, MultiBufferSource buf,
                                           ResourceLocation tex, int light, int overlay, boolean useVBO) {
        renderWithTranslucentSplit(ps, buf, tex, light, overlay, useVBO, false);
    }

    public void renderWithTranslucentSplit(PoseStack ps, MultiBufferSource buf,
                                           ResourceLocation tex, int light, int overlay, boolean useVBO,
                                           boolean forceNoCull) {
        renderCutoutOnly(ps, buf, tex, light, overlay, useVBO, forceNoCull);
        if (hasTranslucent) renderTranslucentOnly(ps, buf, tex, light, overlay, useVBO, forceNoCull);
    }

    public void renderCutoutOnly(PoseStack ps, MultiBufferSource buf,
                                 ResourceLocation tex, int light, int overlay, boolean useVBO) {
        renderCutoutOnly(ps, buf, tex, light, overlay, useVBO, false);
    }

    public void renderCutoutOnly(PoseStack ps, MultiBufferSource buf,
                                 ResourceLocation tex, int light, int overlay, boolean useVBO,
                                 boolean forceNoCull) {
        boolean canUseVbo = ENABLE_LEGACY_VBO && useVBO && allBonesRigid;
        if (canUseVbo && allVboReady(light)) {
            renderVBO(ps, tex, light, false, forceNoCull);
        } else {
            if (canUseVbo) ensureAllUploaded(light);
            VertexConsumer vc = buf.getBuffer(cutoutRenderType(tex, forceNoCull));
            renderBonesConsumer(root, ps, vc, light, overlay, 1f,1f,1f,1f, false);
        }
    }

    public void renderTranslucentOnly(PoseStack ps, MultiBufferSource buf,
                                      ResourceLocation tex, int light, int overlay, boolean useVBO) {
        renderTranslucentOnly(ps, buf, tex, light, overlay, useVBO, false);
    }

    public void renderTranslucentOnly(PoseStack ps, MultiBufferSource buf,
                                      ResourceLocation tex, int light, int overlay, boolean useVBO,
                                      boolean forceNoCull) {
        if (!hasTranslucent) return;
        boolean canUseVbo = ENABLE_LEGACY_VBO && useVBO && allBonesRigid;
        if (canUseVbo && allVboReady(light)) {
            renderVBO(ps, tex, light, true, forceNoCull);
        } else {
            if (canUseVbo) ensureAllUploaded(light);
            VertexConsumer vc = buf.getBuffer(forceNoCull
                    ? PolyMeshRenderTypes.translucentNoCull(tex)
                    : PolyMeshRenderTypes.translucentCull(tex));
            renderBonesConsumer(root, ps, vc, light, overlay, 1f,1f,1f,1f, true);
        }
    }

    // =========================================================================
    // VBO 管理
    // =========================================================================

    /**
     * 現在のボーンツリーを辿り、実際に描画され得る（isVisible()==true かつ
     * meshAncestorBones/excludedBones の条件を満たす）poly_mesh ボーン名を
     * 収集する。renderBonesVBO() の枝刈り条件と完全に一致させること。
     *
     * 【最適化の狙い】装着していない弾倉バリエーションなど、モデルによっては
     * 「同時に描画されることのない」代替パーツが多数のボーンに分割されている
     * ことがある。これらは isVisible()==false のため実際には描画されないが、
     * 元のコードは allVboReady() / ensureAllUploaded() で meshMap の
     * 全ボーンを無条件にチェック・アップロードしていたため、ライトレベルが
     * 変わるたびに「現在表示されていないボーン」まで含めて毎回フルアップロード
     * が走っていた。ボーン数が多い（＝装着バリエーションが豊富な）モデルほど
     * この無駄なコストが大きくなる。本来描画されるボーンだけに限定することで、
     * 見た目やボーンの独立した表示切り替えには一切影響を与えずに、この無駄を
     * 削減する。
     */
    /**
     * 現在のライト値について、meshMap の全ボーンが VBO アップロード済みかどうか。
     *
     * 【設計変更の経緯】以前はボーンツリーを毎フレーム走査して「今表示中の
     * ボーンだけ」を対象にチェックしていたが、これには2つの問題があった:
     *   1. ツリー走査自体のコスト（HashSet 割り当てを含む）が毎フレーム発生する
     *   2. リロード等でボーンの表示/非表示が頻繁に切り替わるアニメーション中や、
     *      移動によってライト値が細かく変動する状況で、"表示中ボーンの一部が
     *      未アップロード" と判定される頻度が上がり、その都度アップロード
     *      処理（ensureAllUploaded）が走ってしまう
     *
     * 今回は「あるライト値について、一度でも ensureAllUploaded() が完走した
     * ことがあるか」を {@link #fullyUploadedLights} で記録するだけにした。
     * ensureAllUploaded() は常に meshMap の全ボーン（表示/非表示問わず）を
     * 対象にするため、一度完走すれば、以後そのライト値については
     * どのボーンが表示されようと（表示が切り替わろうと）再チェックが不要に
     * なる。これにより allVboReady() はボーンツリーを一切走査しない、
     * 単純な O(1) の集合参照だけで済むようになった。
     */
    private boolean allVboReady(int light) {
        if (meshMap.isEmpty()) return false;
        return fullyUploadedLights.contains(light);
    }

    private void ensureAllUploaded(int light) {
        for (Map.Entry<String, List<PolyMesh>> entry : meshMap.entrySet()) {
            int bakeLight = illuminatedBones.contains(entry.getKey()) ? 15728880 : light;
            for (PolyMesh m : entry.getValue()) {
                m.ensureUploaded(bakeLight);
                m.ensureIndexedUploaded(bakeLight);
            }
        }
        fullyUploadedLights.add(light);
    }


    /**
     * 全 PolyMesh の VBO キャッシュを破棄する。
     *
     * Oculus シェーダー切り替え時など、レンダリング状態が大きく変わった際に
     * {@link com.example.dudumeshloader.render.ShaderStateTracker} から呼ばれる。
     * 次フレームで VBO が再生成されるため、影の反転が解消される。
     */
    public void invalidateVboCache() {
        for (List<PolyMesh> meshes : meshMap.values()) {
            for (PolyMesh m : meshes) m.invalidateVboCache();
        }
        fullyUploadedLights.clear();
    }

    // =========================================================================
    // VBO 描画パス
    // =========================================================================

    private void renderVBO(PoseStack ps, ResourceLocation tex, int light, boolean translucentPass,
                           boolean forceNoCull) {
        RenderType renderType = translucentPass
                ? (forceNoCull ? PolyMeshRenderTypes.translucentNoCull(tex) : PolyMeshRenderTypes.translucentCull(tex))
                : cutoutRenderType(tex, forceNoCull);

        renderType.setupRenderState();
        renderBonesVBO(root, ps, light, translucentPass);
        renderType.clearRenderState();
    }

    private void renderBonesVBO(IPolyMeshBone bone, PoseStack ps, int light, boolean translucentPass) {
        if (!bone.isVisible()) return;
        if (!meshAncestorBones.contains(bone.getName())) return;
        if (!excludedBones.isEmpty() && excludedBones.contains(bone.getName())) return;

        ps.pushPose();
        bone.applyTransform(ps);

        boolean isTranslucent = translucentBones.contains(bone.getName());
        if (isTranslucent == translucentPass) {
            List<PolyMesh> meshes = meshMap.get(bone.getName());
            if (meshes != null) {
                int actualLight = (bone.isIlluminated() || illuminatedBones.contains(bone.getName())) ? 15728880 : light;
                for (PolyMesh mesh : meshes) {
                    // 索引化就绪则走 glDrawElements（去重顶点），否则回退非索引 VBO。
                    if (mesh.isIndexedReady()) {
                        mesh.drawIndexed(ps.last().pose());
                    } else {
                        mesh.drawVBO(ps.last().pose(), actualLight);
                    }
                }
            }
        }

        for (IPolyMeshBone child : bone.getChildren()) {
            renderBonesVBO(child, ps, light, translucentPass);
        }

        ps.popPose();
    }

    // =========================================================================
    // VertexConsumer フォールバックパス
    // =========================================================================

    private void renderBonesConsumer(IPolyMeshBone bone, PoseStack ps, VertexConsumer buf,
                                     int light, int overlay, float r, float g, float b, float a,
                                     boolean translucentPass) {
        if (!bone.isVisible()) return;
        if (!meshAncestorBones.contains(bone.getName())) return;
        if (!excludedBones.isEmpty() && excludedBones.contains(bone.getName())) return;

        ps.pushPose();
        bone.applyTransform(ps);

        boolean isTranslucent = translucentBones.contains(bone.getName());
        if (isTranslucent == translucentPass) {
            List<PolyMesh> meshes = meshMap.get(bone.getName());
            if (meshes != null) {
                int actualLight = (bone.isIlluminated() || illuminatedBones.contains(bone.getName())) ? 15728880 : light;
                for (PolyMesh mesh : meshes) mesh.compileConsumer(ps.last(), buf, actualLight, overlay, r, g, b, a);
            }
        }
        for (IPolyMeshBone child : bone.getChildren()) {
            renderBonesConsumer(child, ps, buf, light, overlay, r, g, b, a, translucentPass);
        }

        ps.popPose();
    }

    /** 指定ボーン配下を通常描画から除外する（additional_magazine アニメーション中に使用） */
    public void setExcludeSubtree(String rootBoneName) {
        if (rootBoneName.equals(excludeSubtreeRoot)) return;
        excludeSubtreeRoot = rootBoneName;
        excludedBones.clear();
        IPolyMeshBone bone = findBone(this.root, rootBoneName);
        if (bone != null) collectSubtreeBones(bone, excludedBones);
    }

    public void addExcludeSubtree(String rootBoneName) {
        IPolyMeshBone bone = findBone(this.root, rootBoneName);
        if (bone != null) {
            collectSubtreeBones(bone, excludedBones);
            excludeSubtreeRoot = null;
        }
    }

    public void clearExcludeSubtree() {
        excludeSubtreeRoot = null;
        excludedBones.clear();
    }

    private void collectSubtreeBones(IPolyMeshBone bone, Set<String> result) {
        result.add(bone.getName());
        for (IPolyMeshBone child : bone.getChildren()) collectSubtreeBones(child, result);
    }

    public void renderSubtree(String rootBoneName, PoseStack ps, MultiBufferSource buf,
                              ResourceLocation tex, int light, int overlay, boolean useVBO) {
        IPolyMeshBone targetBone = findBone(this.root, rootBoneName);
        if (targetBone == null) return;
        VertexConsumer vc = buf.getBuffer(cutoutRenderType(tex));
        renderBonesConsumer(targetBone, ps, vc, light, overlay, 1f, 1f, 1f, 1f, false);
    }

    /**
     * additional_magazine の FunctionalRenderer から直接呼ぶ版。
     * TacZ が用意した VertexConsumer にそのまま書き込むため
     * MultiBufferSource / endBatch / turnOnLightLayer は一切不要。
     *
     * @param vertexConsumer TacZ の IFunctionalRenderer が渡す VertexConsumer
     */
    public void renderSubtreeDirect(String rootBoneName, PoseStack ps,
                                    VertexConsumer vertexConsumer, int light, int overlay) {
        IPolyMeshBone targetBone = findBone(this.root, rootBoneName);
        if (targetBone == null) return;
        renderBonesConsumerQuadsDirect(targetBone, ps, vertexConsumer, light, overlay, false);
    }

    /**
     * TaCZ 的倍镜特殊节点会在既定的 stencil 阶段提供 QUADS consumer。
     * 这里必须同步写入完整子树，不能按普通透明层规则跳过骨骼，否则 ocular
     * 写入的模板形状会不完整，division 也会失去对应的裁剪区域。
     */
    /**
     * 倍镜特殊阶段（stencil 内）的 VBO 直绘入口。
     *
     * <p>与 CPU 直写（{@link #renderSpecialSubtreeTrianglesDirect}）不同，这里用已上传的
     * 索引化 VBO（glDrawElements）立即绘制指定骨骼子树，避免每帧对 scope_body 等大几何
     * 逐顶点 CPU 变换。调用方需先 setupRenderState 对应 RenderType；stencil 状态由 TaCZ 在
     * renderTempPart 前设置，本方法不碰。</p>
     */
    public void renderSpecialSubtreeVbo(String rootBoneName, PoseStack ps, int light) {
        IPolyMeshBone targetBone = findBone(this.root, rootBoneName);
        if (targetBone == null) return;
        renderBonesVboSubtree(targetBone, ps, light);
    }

    private void renderBonesVboSubtree(IPolyMeshBone bone, PoseStack ps, int light) {
        if (!bone.isVisible()) return;
        if (!meshAncestorBones.contains(bone.getName())) return;
        if (!excludedBones.isEmpty() && excludedBones.contains(bone.getName())) return;

        ps.pushPose();
        try {
            bone.applyTransform(ps);
            List<PolyMesh> meshes = meshMap.get(bone.getName());
            if (meshes != null) {
                int actualLight = (bone.isIlluminated() || illuminatedBones.contains(bone.getName()))
                        ? 15728880 : light;
                for (PolyMesh mesh : meshes) {
                    mesh.ensureUploaded(actualLight);
                    mesh.ensureIndexedUploaded(actualLight);
                    if (mesh.isIndexedReady()) {
                        mesh.drawIndexed(ps.last().pose());
                    } else {
                        mesh.drawVBO(ps.last().pose(), actualLight);
                    }
                }
            }
            for (IPolyMeshBone child : bone.getChildren()) {
                renderBonesVboSubtree(child, ps, light);
            }
        } finally {
            ps.popPose();
        }
    }

    public void renderSpecialSubtreeDirect(String rootBoneName, PoseStack ps,
                                           VertexConsumer vertexConsumer, int light, int overlay) {
        IPolyMeshBone targetBone = findBone(this.root, rootBoneName);
        if (targetBone == null) return;
        renderBonesConsumerQuadsDirect(targetBone, ps, vertexConsumer, light, overlay, true);
    }

    /**
     * TaCZ 倍镜特殊阶段使用的三角形直写入口。
     *
     * <p>该入口仍由外层保证在原生 stencil 状态有效期间同步提交，但不再把
     * poly_mesh 的三角形补成 QUADS。这样 attachment 与已验证的 SBM 主路径使用
     * 相同的逐角点法线、三角拓扑与着色器插值方式。</p>
     */
    public void renderSpecialSubtreeTrianglesDirect(String rootBoneName, PoseStack ps,
                                                     VertexConsumer vertexConsumer,
                                                     int light, int overlay,
                                                     float red, float green,
                                                     float blue, float alpha) {
        renderSpecialSubtreeTrianglesDirectInternal(rootBoneName, ps, vertexConsumer,
                light, overlay, red, green, blue, alpha, null);
    }

    /**
     * perfdiag.2 专用入口。渲染行为与普通 triangles 入口完全相同，只在诊断启用时
     * 记录子树 inclusive 耗时，以及其中每个 mesh 的顶点变换/写入子项。
     */
    public void renderSpecialSubtreeTrianglesDirectProfiled(String rootBoneName, PoseStack ps,
                                                             VertexConsumer vertexConsumer,
                                                             int light, int overlay,
                                                             float red, float green,
                                                             float blue, float alpha) {
        if (!PerformanceDiagnostics.isEnabled()) {
            renderSpecialSubtreeTrianglesDirectInternal(rootBoneName, ps, vertexConsumer,
                    light, overlay, red, green, blue, alpha, null);
            return;
        }

        long diagnosticsStartNs = PerformanceDiagnostics.begin();
        try {
            renderSpecialSubtreeTrianglesDirectInternal(rootBoneName, ps, vertexConsumer,
                    light, overlay, red, green, blue, alpha, rootBoneName);
        } finally {
            PerformanceDiagnostics.endAttachmentSpecialBonePass(rootBoneName, diagnosticsStartNs);
        }
    }

    /**
     * 把特殊子树登记到当前 TaCZ 原生 AR layer。
     *
     * <p>调用方必须保证当前正处于 scope_body 的 {@code -942} 收集阶段。这里先验证
     * consumer 确实由 AR 接管；验证失败时返回 {@code -1}，绝不自动落回 direct，
     * 因为此刻 stencil before 回调尚未执行，同帧同步绘制会使用错误模板。</p>
     *
     * @return 成功登记的顶点数；consumer 不支持 AR 时返回 {@code -1}
     */
    public int renderSpecialSubtreeTrianglesAccelerated(String rootBoneName, PoseStack ps,
                                                         VertexConsumer vertexConsumer,
                                                         int light, int overlay,
                                                         float red, float green,
                                                         float blue, float alpha) {
        if (!ARCompat.isAccelerated(vertexConsumer)) {
            return -1;
        }
        IPolyMeshBone targetBone = findBone(this.root, rootBoneName);
        if (targetBone == null) {
            return 0;
        }
        return renderBonesConsumerTrianglesAccelerated(targetBone, ps, vertexConsumer,
                light, overlay, red, green, blue, alpha);
    }

    private int renderBonesConsumerTrianglesAccelerated(IPolyMeshBone bone, PoseStack ps,
                                                         VertexConsumer consumer,
                                                         int light, int overlay,
                                                         float red, float green,
                                                         float blue, float alpha) {
        if (!bone.isVisible()) return 0;
        if (!meshAncestorBones.contains(bone.getName())) return 0;
        if (!excludedBones.isEmpty() && excludedBones.contains(bone.getName())) return 0;

        int submittedVertices = 0;
        ps.pushPose();
        try {
            bone.applyTransform(ps);

            List<PolyMesh> meshes = meshMap.get(bone.getName());
            if (meshes != null) {
                int actualLight = (bone.isIlluminated() || illuminatedBones.contains(bone.getName()))
                        ? 15728880 : light;
                for (PolyMesh mesh : meshes) {
                    // 这里只允许 AR 接管；失败后由附件模型熔断到下一帧旧同步路径。
                    if (!mesh.tryCompileConsumerAccelerated(
                            ps.last(), consumer, actualLight, overlay,
                            red, green, blue, alpha)) {
                        return -1;
                    }
                    submittedVertices += mesh.getVertexCount();
                }
            }
            for (IPolyMeshBone child : bone.getChildren()) {
                int childVertices = renderBonesConsumerTrianglesAccelerated(
                        child, ps, consumer, light, overlay, red, green, blue, alpha);
                if (childVertices < 0) {
                    return -1;
                }
                submittedVertices += childVertices;
            }
        } finally {
            ps.popPose();
        }
        return submittedVertices;
    }

    private void renderSpecialSubtreeTrianglesDirectInternal(String rootBoneName, PoseStack ps,
                                                              VertexConsumer vertexConsumer,
                                                              int light, int overlay,
                                                              float red, float green,
                                                              float blue, float alpha,
                                                              String diagnosticsRootBoneName) {
        IPolyMeshBone targetBone = findBone(this.root, rootBoneName);
        if (targetBone == null) return;
        renderBonesConsumerTrianglesDirect(targetBone, ps, vertexConsumer, light, overlay,
                red, green, blue, alpha, diagnosticsRootBoneName);
    }

    private void renderBonesConsumerTrianglesDirect(IPolyMeshBone bone, PoseStack ps,
                                                     VertexConsumer consumer,
                                                     int light, int overlay,
                                                     float red, float green,
                                                     float blue, float alpha,
                                                     String diagnosticsRootBoneName) {
        if (!bone.isVisible()) return;
        if (!meshAncestorBones.contains(bone.getName())) return;
        if (!excludedBones.isEmpty() && excludedBones.contains(bone.getName())) return;

        ps.pushPose();
        try {
            bone.applyTransform(ps);

            List<PolyMesh> meshes = meshMap.get(bone.getName());
            if (meshes != null) {
                int actualLight = (bone.isIlluminated() || illuminatedBones.contains(bone.getName()))
                        ? 15728880 : light;
                for (PolyMesh mesh : meshes) {
                    // 特殊阶段必须同步完成，不能进入 AR 延迟队列。
                    if (diagnosticsRootBoneName == null) {
                        mesh.compileConsumerDirect(ps.last(), consumer, actualLight, overlay,
                                red, green, blue, alpha);
                    } else {
                        long vertexWriteStartNs = PerformanceDiagnostics.begin();
                        try {
                            mesh.compileConsumerDirect(ps.last(), consumer, actualLight, overlay,
                                    red, green, blue, alpha);
                        } finally {
                            PerformanceDiagnostics.endAttachmentSpecialVertexWrite(
                                    diagnosticsRootBoneName, vertexWriteStartNs,
                                    mesh.getVertexCount());
                        }
                    }
                }
            }
            for (IPolyMeshBone child : bone.getChildren()) {
                // stencil 子树必须完整输出，不能按 translucent 名称跳过后代。
                renderBonesConsumerTrianglesDirect(child, ps, consumer, light, overlay,
                        red, green, blue, alpha, diagnosticsRootBoneName);
            }
        } finally {
            ps.popPose();
        }
    }

    /**
     * TaCZ 提供的 FunctionalRenderer 与倍镜 renderTempPart consumer 都属于 QUADS 图层，
     * 不能直接写入三点图元，因此这些同步兼容入口使用 a/b/c/c；主体仍走三角管线。
     */
    private void renderBonesConsumerQuadsDirect(IPolyMeshBone bone, PoseStack ps,
                                                VertexConsumer consumer, int light, int overlay,
                                                boolean includeTranslucent) {
        if (!bone.isVisible()) return;
        if (!meshAncestorBones.contains(bone.getName())) return;
        if (!excludedBones.isEmpty() && excludedBones.contains(bone.getName())) return;

        ps.pushPose();
        bone.applyTransform(ps);

        if (includeTranslucent || !translucentBones.contains(bone.getName())) {
            List<PolyMesh> meshes = meshMap.get(bone.getName());
            if (meshes != null) {
                int actualLight = (bone.isIlluminated() || illuminatedBones.contains(bone.getName())) ? 15728880 : light;
                for (PolyMesh mesh : meshes) {
                    mesh.compileConsumerQuadsDirect(ps.last(), consumer, actualLight, overlay,
                            1.0f, 1.0f, 1.0f, 1.0f);
                }
            }
        }
        for (IPolyMeshBone child : bone.getChildren()) {
            renderBonesConsumerQuadsDirect(child, ps, consumer, light, overlay, includeTranslucent);
        }
        ps.popPose();
    }

    public void renderBonesStencilOnly(String rootBoneName, PoseStack ps, MultiBufferSource buf,
                                       ResourceLocation tex, int light, int overlay) {
        IPolyMeshBone bone = findBone(this.root, rootBoneName);
        if (bone == null) return;
        // stencil 掩膜必须两侧都填满，故此处固定使用 NoCull（不受单面配置影响）。
        VertexConsumer vc = buf.getBuffer(PolyMeshRenderTypes.cutoutNoCull(tex));
        renderBonesConsumer(bone, ps, vc, light, overlay, 0f, 0f, 0f, 0f, false);
    }

    /** ボーン名でツリーを検索する */
    private IPolyMeshBone findBone(IPolyMeshBone bone, String name) {
        if (name.equals(bone.getName())) return bone;
        for (IPolyMeshBone child : bone.getChildren()) {
            IPolyMeshBone found = findBone(child, name);
            if (found != null) return found;
        }
        return null;
    }

    public boolean hasMeshInSubtree(String boneName) {
        IPolyMeshBone bone = findBone(this.root, boneName);
        if (bone == null) return false;
        return hasMeshInSubtreeInternal(bone);
    }

    /** 返回指定骨骼名下的全部 poly_mesh（无则返回空列表）。 */
    public List<PolyMesh> getMeshes(String boneName) {
        List<PolyMesh> meshes = meshMap.get(boneName);
        return meshes == null ? List.of() : meshes;
    }

    private boolean hasMeshInSubtreeInternal(IPolyMeshBone bone) {
        if (meshMap.containsKey(bone.getName())) return true;
        for (IPolyMeshBone child : bone.getChildren()) {
            if (hasMeshInSubtreeInternal(child)) return true;
        }
        return false;
    }

    public void close() {
        for (List<PolyMesh> meshes : meshMap.values()) {
            for (PolyMesh m : meshes) m.close();
        }
    }
}
