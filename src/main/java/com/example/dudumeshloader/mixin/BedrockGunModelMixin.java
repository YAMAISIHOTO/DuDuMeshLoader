package com.example.dudumeshloader.mixin;

import com.example.dudumeshloader.tacz.TaczPolyMeshGunModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.model.BedrockGunModel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把枪身 poly_mesh 接入 TaCZ 原生的普通/AR 枪身渲染阶段。
 *
 * <p>目标调用必须锁定生产字节码中的直接父类 BedrockAnimatedModel；这样普通路径
 * 能复用尚未清除的 stencil，AR 路径能复用尚未重置的 -940 图层回调。</p>
 */
@Mixin(value = BedrockGunModel.class, remap = false)
public abstract class BedrockGunModelMixin {

    private static final String GUN_RENDER_DESC =
            "(Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/world/item/ItemStack;"
                    + "Lnet/minecraft/world/item/ItemDisplayContext;"
                    + "Lnet/minecraft/client/renderer/RenderType;II)V";

    private static final String NATIVE_GUN_BODY_RENDER =
            "Lcom/tacz/guns/client/model/BedrockAnimatedModel;render("
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/world/item/ItemDisplayContext;"
                    + "Lnet/minecraft/client/renderer/RenderType;II)V";

    @Inject(
            method = "render" + GUN_RENDER_DESC,
            at = @At(
                    value = "INVOKE",
                    target = NATIVE_GUN_BODY_RENDER,
                    opcode = Opcodes.INVOKESPECIAL,
                    ordinal = 0,
                    shift = At.Shift.AFTER,
                    remap = false
            ),
            require = 1
    )
    private void taczMeshLoader$renderNormalPolyMesh(PoseStack poseStack, ItemStack stack,
                                                      ItemDisplayContext transformType,
                                                      RenderType renderType, int light, int overlay,
                                                      CallbackInfo ci) {
        if ((Object) this instanceof TaczPolyMeshGunModel model) {
            model.renderPolyMeshAtNativeStage(poseStack, light, overlay, false);
        }
    }

    @Inject(
            method = "renderAccelerated" + GUN_RENDER_DESC,
            at = @At(
                    value = "INVOKE",
                    target = NATIVE_GUN_BODY_RENDER,
                    opcode = Opcodes.INVOKESPECIAL,
                    ordinal = 0,
                    shift = At.Shift.AFTER,
                    remap = false
            ),
            require = 1
    )
    private void taczMeshLoader$renderAcceleratedPolyMesh(PoseStack poseStack, ItemStack stack,
                                                           ItemDisplayContext transformType,
                                                           RenderType renderType, int light, int overlay,
                                                           CallbackInfo ci) {
        if ((Object) this instanceof TaczPolyMeshGunModel model) {
            model.renderPolyMeshAtNativeStage(poseStack, light, overlay, true);
        }
    }
}
