package com.example.dudumeshloader.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.example.dudumeshloader.tacz.TaczPolyMeshAttachmentModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.client.model.BedrockAttachmentModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.objectweb.asm.Opcodes;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * 把 poly_mesh 特殊骨骼接入 TaCZ 原生的倍镜模板流程。
 *
 * <p>TaCZ 会在 {@code renderTempPart} 调用前设置好 stencil、颜色写入、深度写入，
 * 并在调用后立即刷新原生 RenderType。这里先保留原有 cube 绘制，再在同一 stencil
 * 阶段用独立的 TRIANGLES RenderType 同步绘制并刷新 poly_mesh，使 ocular、division、
 * scope_body 与 ocular_ring 既复用原生状态时序，也保留 SBM 圆滑法线管线。</p>
 */
@Mixin(value = BedrockAttachmentModel.class, remap = false)
public abstract class BedrockAttachmentModelMixin {

    @Shadow
    protected List<List<BedrockPart>> ocularNodePaths;

    @Shadow
    protected List<Boolean> isScopeOcular;

    @Shadow
    protected List<BedrockPart> scopeBodyPath;

    @Shadow
    private boolean isScope;

    @Shadow
    private boolean isSight;

    @Shadow
    private void renderTempPart(PoseStack poseStack, ItemDisplayContext transformType,
                                RenderType renderType, int light, int overlay,
                                List<BedrockPart> path) {
        throw new AssertionError();
    }

    /** 第一人称完全闲置时，使用局部只写颜色的 RenderType 接管 sight 目镜。 */
    @Unique
    private boolean meshyloader$renderingIdleSightOcular;

    /** 配置 TaCZ 延迟 -942 layer 时快照本次是否需要闲置镜体真实深度。 */
    @Unique
    private boolean meshyloader$nativeScopeIdleDepthSnapshot;

    /**
     * 第一人称闲置时让 scope_body 按真实深度完整绘制。
     *
     * <p>TaCZ 原版会先用 ocular 写 stencil，再只在 stencil==0 处画镜体。对于带真实镜孔的
     * poly_mesh 倍镜，这会让镜片轮廓内的镜体完全没有颜色和深度，斜视时镜片便会盖到镜筒上。
     * 这里只在完全未瞄准且 scope_body/当前 scope ocular 都确有 poly_mesh 时，把镜体这一遍
     * 临时改成 GL_ALWAYS；开始瞄准后仍完整使用 TaCZ 原有 stencil 层级。</p>
     */
    @Redirect(
            method = "renderScope(" +
                    "Lcom/mojang/blaze3d/vertex/PoseStack;" +
                    "Lnet/minecraft/world/item/ItemDisplayContext;" +
                    "Lnet/minecraft/client/renderer/RenderType;II)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/tacz/guns/client/model/BedrockAttachmentModel;renderTempPart(" +
                            "Lcom/mojang/blaze3d/vertex/PoseStack;" +
                            "Lnet/minecraft/world/item/ItemDisplayContext;" +
                            "Lnet/minecraft/client/renderer/RenderType;II" +
                            "Ljava/util/List;)V",
                    ordinal = 1
            ),
            require = 1
    )
    private void meshyloader$renderIdleScopeBodyDepthForScope(
            BedrockAttachmentModel instance,
            PoseStack poseStack,
            ItemDisplayContext transformType,
            RenderType renderType,
            int light,
            int overlay,
            List<BedrockPart> path) {
        meshyloader$renderIdleScopeBodyDepth(
                poseStack, transformType, renderType, light, overlay, path);
    }

    @Redirect(
            method = "renderBoth(" +
                    "Lcom/mojang/blaze3d/vertex/PoseStack;" +
                    "Lnet/minecraft/world/item/ItemDisplayContext;" +
                    "Lnet/minecraft/client/renderer/RenderType;II)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/tacz/guns/client/model/BedrockAttachmentModel;renderTempPart(" +
                            "Lcom/mojang/blaze3d/vertex/PoseStack;" +
                            "Lnet/minecraft/world/item/ItemDisplayContext;" +
                            "Lnet/minecraft/client/renderer/RenderType;II" +
                            "Ljava/util/List;)V",
                    ordinal = 1
            ),
            require = 1
    )
    private void meshyloader$renderIdleScopeBodyDepthForBoth(
            BedrockAttachmentModel instance,
            PoseStack poseStack,
            ItemDisplayContext transformType,
            RenderType renderType,
            int light,
            int overlay,
            List<BedrockPart> path) {
        meshyloader$renderIdleScopeBodyDepth(
                poseStack, transformType, renderType, light, overlay, path);
    }

    @Unique
    private void meshyloader$renderIdleScopeBodyDepth(
            PoseStack poseStack,
            ItemDisplayContext transformType,
            RenderType renderType,
            int light,
            int overlay,
            List<BedrockPart> path) {
        if (!meshyloader$shouldUseIdleScopeBodyDepth(transformType, path)) {
            renderTempPart(poseStack, transformType, renderType, light, overlay, path);
            return;
        }

        RenderSystem.stencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
        try {
            // renderTempPart 会在返回前同步提交普通 BufferSource 与 Oculus FullyBuffered 批次。
            renderTempPart(poseStack, transformType, renderType, light, overlay, path);
        } finally {
            // renderOcularAndDivision 仍需要 TaCZ 在 scope_body 之后预期的 EQUAL 0 状态。
            RenderSystem.stencilFunc(GL11.GL_EQUAL, 0, 0xFF);
        }
    }

    @Unique
    private boolean meshyloader$shouldUseIdleScopeBodyDepth(
            ItemDisplayContext transformType,
            List<BedrockPart> path) {
        if (!transformType.firstPerson() || !isScope || !meshyloader$isFullyIdle()
                || path != scopeBodyPath
                || !((Object) this instanceof TaczPolyMeshAttachmentModel polyMeshModel)) {
            return false;
        }

        String scopeBodyName = meshyloader$getLeafName(path);
        if (scopeBodyName == null || !polyMeshModel.hasPolyMeshInSubtree(scopeBodyName)) {
            return false;
        }

        for (int i = 0; i < ocularNodePaths.size(); i++) {
            // 组合镜使用 ocular_scope；纯 scope 则沿用 TaCZ 的 generic ocular/ocular_sight 分类。
            boolean activeScopeOcular = isSight
                    ? isScopeOcular.get(i)
                    : !isScopeOcular.get(i);
            if (!activeScopeOcular) {
                continue;
            }
            String ocularName = meshyloader$getLeafName(ocularNodePaths.get(i));
            if (ocularName != null && polyMeshModel.hasPolyMeshInSubtree(ocularName)) {
                return true;
            }
        }
        return false;
    }

    @Inject(
            method = "renderScopeAccelerated(" +
                    "Lcom/mojang/blaze3d/vertex/PoseStack;" +
                    "Lnet/minecraft/world/item/ItemDisplayContext;" +
                    "Lnet/minecraft/client/renderer/RenderType;II)V",
            at = @At("HEAD"),
            require = 1
    )
    private void meshyloader$snapshotNativeScopeIdleDepthForScope(
            PoseStack poseStack,
            ItemDisplayContext transformType,
            RenderType renderType,
            int light,
            int overlay,
            CallbackInfo ci) {
        meshyloader$snapshotNativeScopeIdleDepth(transformType);
    }

    @Inject(
            method = "renderBothAccelerated(" +
                    "Lcom/mojang/blaze3d/vertex/PoseStack;" +
                    "Lnet/minecraft/world/item/ItemDisplayContext;" +
                    "Lnet/minecraft/client/renderer/RenderType;II)V",
            at = @At("HEAD"),
            require = 1
    )
    private void meshyloader$snapshotNativeScopeIdleDepthForBoth(
            PoseStack poseStack,
            ItemDisplayContext transformType,
            RenderType renderType,
            int light,
            int overlay,
            CallbackInfo ci) {
        meshyloader$snapshotNativeScopeIdleDepth(transformType);
    }

    @Unique
    private void meshyloader$snapshotNativeScopeIdleDepth(ItemDisplayContext transformType) {
        meshyloader$nativeScopeIdleDepthSnapshot =
                (Object) this instanceof TaczPolyMeshAttachmentModel polyMeshModel
                        && polyMeshModel.isNativeScopeArCollectionActive()
                        && meshyloader$shouldUseIdleScopeBodyDepth(transformType, scopeBodyPath);
    }

    /**
     * TaCZ 的 -942 before 会先同步生成 ocular stencil，最后留下 EQUAL 0。
     * 在原回调完成后才把闲置镜体改为 ALWAYS，保证 AR 中缓存的 scope_body
     * 仍按真实深度遮住青绿色镜片，而不破坏模板生成。
     */
    @Redirect(
            method = "renderScopeAccelerated(" +
                    "Lcom/mojang/blaze3d/vertex/PoseStack;" +
                    "Lnet/minecraft/world/item/ItemDisplayContext;" +
                    "Lnet/minecraft/client/renderer/RenderType;II)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/tacz/guns/compat/ar/ARCompat;" +
                            "setRenderBeforeFunction(Ljava/lang/Runnable;)V",
                    ordinal = 1
            ),
            require = 1
    )
    private void meshyloader$wrapNativeScopeBodyBeforeForScope(
            Runnable original,
            PoseStack poseStack,
            ItemDisplayContext transformType,
            RenderType renderType,
            int light,
            int overlay) {
        meshyloader$setNativeScopeBodyBefore(original);
    }

    @Redirect(
            method = "renderBothAccelerated(" +
                    "Lcom/mojang/blaze3d/vertex/PoseStack;" +
                    "Lnet/minecraft/world/item/ItemDisplayContext;" +
                    "Lnet/minecraft/client/renderer/RenderType;II)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/tacz/guns/compat/ar/ARCompat;" +
                            "setRenderBeforeFunction(Ljava/lang/Runnable;)V",
                    ordinal = 1
            ),
            require = 1
    )
    private void meshyloader$wrapNativeScopeBodyBeforeForBoth(
            Runnable original,
            PoseStack poseStack,
            ItemDisplayContext transformType,
            RenderType renderType,
            int light,
            int overlay) {
        meshyloader$setNativeScopeBodyBefore(original);
    }

    @Unique
    private void meshyloader$setNativeScopeBodyBefore(Runnable original) {
        boolean useIdleDepth = meshyloader$nativeScopeIdleDepthSnapshot;
        com.tacz.guns.compat.ar.ARCompat.setRenderBeforeFunction(() -> {
            original.run();
            if (useIdleDepth) {
                RenderSystem.stencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
            }
        });
    }

    /** 在 -942 after 执行前恢复 TaCZ 原本预期的 EQUAL 0，再交还 ocular/division 清理。 */
    @Redirect(
            method = "renderScopeAccelerated(" +
                    "Lcom/mojang/blaze3d/vertex/PoseStack;" +
                    "Lnet/minecraft/world/item/ItemDisplayContext;" +
                    "Lnet/minecraft/client/renderer/RenderType;II)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/tacz/guns/compat/ar/ARCompat;" +
                            "setRenderAfterFunction(Ljava/lang/Runnable;)V",
                    ordinal = 1
            ),
            require = 1
    )
    private void meshyloader$wrapNativeScopeBodyAfterForScope(
            Runnable original,
            PoseStack poseStack,
            ItemDisplayContext transformType,
            RenderType renderType,
            int light,
            int overlay) {
        meshyloader$setNativeScopeBodyAfter(original);
    }

    @Redirect(
            method = "renderBothAccelerated(" +
                    "Lcom/mojang/blaze3d/vertex/PoseStack;" +
                    "Lnet/minecraft/world/item/ItemDisplayContext;" +
                    "Lnet/minecraft/client/renderer/RenderType;II)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/tacz/guns/compat/ar/ARCompat;" +
                            "setRenderAfterFunction(Ljava/lang/Runnable;)V",
                    ordinal = 1
            ),
            require = 1
    )
    private void meshyloader$wrapNativeScopeBodyAfterForBoth(
            Runnable original,
            PoseStack poseStack,
            ItemDisplayContext transformType,
            RenderType renderType,
            int light,
            int overlay) {
        meshyloader$setNativeScopeBodyAfter(original);
    }

    @Unique
    private void meshyloader$setNativeScopeBodyAfter(Runnable original) {
        boolean useIdleDepth = meshyloader$nativeScopeIdleDepthSnapshot;
        com.tacz.guns.compat.ar.ARCompat.setRenderAfterFunction(() -> {
            if (useIdleDepth) {
                RenderSystem.stencilFunc(GL11.GL_EQUAL, 0, 0xFF);
            }
            original.run();
        });
    }

    @Unique
    private static String meshyloader$getLeafName(List<BedrockPart> path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        BedrockPart leaf = path.get(path.size() - 1);
        return leaf == null ? null : leaf.name;
    }

    @Redirect(
            method = "renderTempPart",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/tacz/guns/client/model/bedrock/BedrockPart;render(" +
                            "Lcom/mojang/blaze3d/vertex/PoseStack;" +
                            "Lnet/minecraft/world/item/ItemDisplayContext;" +
                            "Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V"
            )
    )
    private void meshyloader$renderSpecialPolyMesh(
            BedrockPart part,
            PoseStack poseStack,
            ItemDisplayContext transformType,
            VertexConsumer vertexConsumer,
            int light,
            int overlay) {

        if (meshyloader$renderingIdleSightOcular
                && (Object) this instanceof TaczPolyMeshAttachmentModel polyMeshModel
                && part.name != null
                && polyMeshModel.renderIdleSightSubtree(
                        part, part.name, poseStack, transformType, light, overlay)) {
            return;
        }

        // TaCZ 原版始终使用白色顶点色；实体镜片颜色由枪包纹理决定。
        part.render(poseStack, transformType, vertexConsumer, light, overlay);

        if ((Object) this instanceof TaczPolyMeshAttachmentModel polyMeshModel && part.name != null) {
            polyMeshModel.renderSpecialPolyMeshSubtree(
                    part.name, poseStack, light, overlay);
        }
    }

    @Inject(
            method = "render(" +
                    "Lnet/minecraft/world/item/ItemStack;" +
                    "Lnet/minecraft/world/item/ItemStack;" +
                    "Lcom/mojang/blaze3d/vertex/PoseStack;" +
                    "Lnet/minecraft/world/item/ItemDisplayContext;" +
                    "Lnet/minecraft/client/renderer/RenderType;II)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/tacz/guns/client/model/BedrockAttachmentModel;renderSight(" +
                            "Lcom/mojang/blaze3d/vertex/PoseStack;" +
                            "Lnet/minecraft/world/item/ItemDisplayContext;" +
                            "Lnet/minecraft/client/renderer/RenderType;II)V",
                    shift = At.Shift.BEFORE,
                    ordinal = 0
            ),
            require = 1
    )
    private void meshyloader$renderIdleSightOcularBeforeSight(
            ItemStack attachmentItem,
            ItemStack currentGunItem,
            PoseStack poseStack,
            ItemDisplayContext transformType,
            RenderType renderType,
            int light,
            int overlay,
            CallbackInfo ci) {
        meshyloader$renderIdleSightOculars(poseStack, transformType, renderType, light, overlay);
    }

    @Inject(
            method = "render(" +
                    "Lnet/minecraft/world/item/ItemStack;" +
                    "Lnet/minecraft/world/item/ItemStack;" +
                    "Lcom/mojang/blaze3d/vertex/PoseStack;" +
                    "Lnet/minecraft/world/item/ItemDisplayContext;" +
                    "Lnet/minecraft/client/renderer/RenderType;II)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/tacz/guns/client/model/BedrockAttachmentModel;renderBoth(" +
                            "Lcom/mojang/blaze3d/vertex/PoseStack;" +
                            "Lnet/minecraft/world/item/ItemDisplayContext;" +
                            "Lnet/minecraft/client/renderer/RenderType;II)V",
                    shift = At.Shift.BEFORE,
                    ordinal = 0
            ),
            require = 1
    )
    private void meshyloader$renderIdleSightOcularBeforeBoth(
            ItemStack attachmentItem,
            ItemStack currentGunItem,
            PoseStack poseStack,
            ItemDisplayContext transformType,
            RenderType renderType,
            int light,
            int overlay,
            CallbackInfo ci) {
        meshyloader$renderIdleSightOculars(poseStack, transformType, renderType, light, overlay);
    }

    /**
     * TaCZ 只拿 ocular_sight 写模板，第一人称闲置状态不会把实体镜片画出来。
     * 这里在原生 sight/both 流程之前补画非 scope 目镜。cube 与 poly_mesh 都由
     * 局部 COLOR_WRITE RenderType 接管，随后 TaCZ 的 division 仍可稳定显示在镜片上。
     * 一旦开始瞄准，立即完全交还原生模板流程。
     */
    @Unique
    private void meshyloader$renderIdleSightOculars(
            PoseStack poseStack,
            ItemDisplayContext transformType,
            RenderType renderType,
            int light,
            int overlay) {
        if (!transformType.firstPerson() || !isSight || !meshyloader$isFullyIdle()
                || !((Object) this instanceof TaczPolyMeshAttachmentModel)) {
            return;
        }

        boolean restoreAcceleration = com.tacz.guns.compat.ar.ARCompat.shouldAccelerate();
        if (restoreAcceleration) {
            com.tacz.guns.compat.ar.ARCompat.disableAcceleration();
        }
        boolean previousIdleState = meshyloader$renderingIdleSightOcular;
        meshyloader$renderingIdleSightOcular = true;
        try {
            for (int i = 0; i < ocularNodePaths.size(); i++) {
                if (!isScopeOcular.get(i)) {
                    renderTempPart(poseStack, transformType, renderType, light, overlay,
                            ocularNodePaths.get(i));
                }
            }
        } finally {
            meshyloader$renderingIdleSightOcular = previousIdleState;
            if (restoreAcceleration) {
                com.tacz.guns.compat.ar.ARCompat.resetAcceleration();
            }
        }
    }

    @Unique
    private static boolean meshyloader$isFullyIdle() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return true;
        }
        float aimingProgress = IClientPlayerGunOperator.fromLocalPlayer(minecraft.player)
                .getClientAimingProgress(minecraft.getFrameTime());
        return aimingProgress <= 1.0E-4F;
    }

    /**
     * TaCZ 的非第一人称分支只画 scope_body 与 ocular_ring，完全漏掉 ocular。
     * 在最终普通模型绘制前局部补画全部目镜，renderTempPart 会负责临时可见和立即提交。
     */
    @Inject(
            method = "render(" +
                    "Lnet/minecraft/world/item/ItemStack;" +
                    "Lnet/minecraft/world/item/ItemStack;" +
                    "Lcom/mojang/blaze3d/vertex/PoseStack;" +
                    "Lnet/minecraft/world/item/ItemDisplayContext;" +
                    "Lnet/minecraft/client/renderer/RenderType;II)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/tacz/guns/client/model/BedrockAnimatedModel;render(" +
                            "Lcom/mojang/blaze3d/vertex/PoseStack;" +
                            "Lnet/minecraft/world/item/ItemDisplayContext;" +
                            "Lnet/minecraft/client/renderer/RenderType;II)V",
                    opcode = Opcodes.INVOKESPECIAL,
                    shift = At.Shift.BEFORE,
                    ordinal = 0
            ),
            require = 1
    )
    private void meshyloader$renderNonFirstPersonOculars(
            ItemStack attachmentItem,
            ItemStack currentGunItem,
            PoseStack poseStack,
            ItemDisplayContext transformType,
            RenderType renderType,
            int light,
            int overlay,
            CallbackInfo ci) {
        if (transformType.firstPerson() || ocularNodePaths.isEmpty()) {
            return;
        }

        boolean restoreAcceleration = com.tacz.guns.compat.ar.ARCompat.shouldAccelerate();
        if (restoreAcceleration) {
            com.tacz.guns.compat.ar.ARCompat.disableAcceleration();
        }
        try {
            for (List<BedrockPart> ocularPath : ocularNodePaths) {
                renderTempPart(poseStack, transformType, renderType, light, overlay, ocularPath);
            }
        } finally {
            if (restoreAcceleration) {
                com.tacz.guns.compat.ar.ARCompat.resetAcceleration();
            }
        }
    }
}
