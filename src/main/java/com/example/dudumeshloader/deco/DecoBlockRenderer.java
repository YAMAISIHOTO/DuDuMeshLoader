package com.example.dudumeshloader.deco;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.model.bedrock.BedrockModel;
import com.tacz.guns.client.resource.index.ClientBlockIndex;
import com.tacz.guns.config.client.RenderConfig;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Optional;

/**
 * 渲染任意 {@link DecoBlockEntity} 指向的 TACZ 方块 index 模型。
 *
 * <p>与 TACZ 的 {@code GunSmithTableRenderer} 类似，但不再限定
 * {@code AbstractGunSmithTableBlock}，可渲染任意 {@code ClientBlockIndex} 的模型
 * （含 {@code TaczPolyMeshBlockModel} 注入的 poly_mesh 层）。
 * 仅当处于根块（双块结构的 FOOT / LOWER）时才绘制。</p>
 */
@OnlyIn(Dist.CLIENT)
public class DecoBlockRenderer implements BlockEntityRenderer<DecoBlockEntity> {
    public DecoBlockRenderer(BlockEntityRendererProvider.Context context) {
    }

    public Optional<ClientBlockIndex> getIndex(DecoBlockEntity blockEntity) {
        ResourceLocation id = blockEntity.getId();
        if (id == null || id.equals(DefaultAssets.EMPTY_BLOCK_ID)) {
            id = DecoRegistries.DECO_BLOCK_ID;
        }
        return TimelessAPI.getClientBlockIndex(id);
    }

    @Override
    public void render(DecoBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferIn, int combinedLightIn, int combinedOverlayIn) {
        getIndex(blockEntity).ifPresent(index -> {
            BedrockModel model = index.getModel();
            ResourceLocation texture = index.getTexture();
            if (model == null) {
                return;
            }
            BlockState blockState = blockEntity.getBlockState();
            if (blockState.getBlock() instanceof DecoBlock block) {
                if (!block.isRoot(blockState)) {
                    return;
                }
                Direction facing = blockState.getValue(DecoBlock.FACING);
                poseStack.pushPose();
                poseStack.translate(0.5, 1.5, 0.5);
                poseStack.mulPose(Axis.ZN.rotationDegrees(180));
                poseStack.mulPose(Axis.YN.rotationDegrees(block.getRotation(blockState)));
                // 与 TACZ 工作台一致：半透明开关走 BLOCK_ENTITY_TRANSLUCENT
                RenderType renderType = RenderConfig.BLOCK_ENTITY_TRANSLUCENT.get()
                        ? RenderType.entityTranslucent(texture)
                        : RenderType.entityCutout(texture);
                model.render(poseStack, ItemDisplayContext.NONE, renderType, combinedLightIn, combinedOverlayIn);
                poseStack.popPose();
            }
        });
    }

    @Override
    public boolean shouldRenderOffScreen(DecoBlockEntity blockEntity) {
        return true;
    }
}
