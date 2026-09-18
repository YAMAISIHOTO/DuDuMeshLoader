package com.example.dudumeshloader.deco;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import org.joml.Quaternionf;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.model.BedrockAnimatedModel;
import com.tacz.guns.client.model.bedrock.BedrockModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.resource.ClientAssetsManager;
import com.tacz.guns.client.resource.index.ClientBlockIndex;
import com.tacz.guns.client.resource.pojo.display.block.BlockDisplay;
import com.tacz.guns.client.resource.pojo.model.BonesItem;
import com.tacz.guns.config.client.RenderConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import com.example.dudumeshloader.deco.BlockDisplayOptions;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 渲染 {@link DecoWeaponRackEntity} 指向的 TACZ 方块 index 模型，并按模型内挂载骨骼摆放武器与配件。
 *
 * <p>渲染流程：
 * <ol>
 *   <li>与 {@code DecoBlockRenderer} 相同的变换帧（translate + ZN180 + 绕 Y 按朝向旋转）绘制 rack 模型；
 *   <li>若 index 配置了 {@code animation} + {@code display}，则重建一个 {@link BedrockAnimatedModel}
 *       并由 {@link DecoRackAnimationState} 驱动其动画（随实体 {@code open} 状态机切换）；
 *       否则回退到静态 {@link BedrockModel} 渲染；
 *   <li>在<b>同一变换帧</b>内，分别按 index 的 {@code mount_bones}（武器→{@code weapon_mount} 系）与
 *       {@code attachment_bones}（配件→{@code attachment_mount} 系）逐槽位读取对应骨骼；
 *   <li>沿骨骼链累加其动画后的 {@code pivot}/{@code rotation}（geo 单位，÷16 转方块单位）作为物品位置与朝向，
 *       物品从而跟随架子的开合动画移动；
 *   <li>缩放后用 {@code ItemRenderer.renderStatic(FIXED)} 绘制手持的枪/配件。</li>
 * </ol>
 *
 * <p>geo 坐标约定（与 TACZ 方块模型一致）：1 个 geo 单位 = 1/16 方块，且模型原点经
 * translate(0.5, 1.5, 0.5) 落在方块上。骨骼 pivot 累加后直接作为同帧平移即可对齐模型。</p>
 */
@OnlyIn(Dist.CLIENT)
public class DecoWeaponRackRenderer implements BlockEntityRenderer<DecoWeaponRackEntity> {
    /** 资源重载时自增，用于使各实例缓存失效（模型/动画可能被重新加载）。 */
    private static volatile long REVISION = 0;

    private long lastRevision = -1;
    private final Map<BlockPos, DecoRackAnimationState> animCache = new HashMap<>();
    /** 一次性诊断开关（排查动画/位置）。 */
    private static boolean RACK_DEBUG = false;

    public DecoWeaponRackRenderer(BlockEntityRendererProvider.Context context) {
    }

    /** 由客户端资源重载监听器调用，使所有实例的动画缓存失效。 */
    public static void onResourceReload() {
        REVISION++;
    }

    private Optional<ClientBlockIndex> getIndex(DecoWeaponRackEntity be) {
        ResourceLocation id = be.getId();
        if (id == null || id.equals(DefaultAssets.EMPTY_BLOCK_ID)) {
            id = DecoRegistries.RACK_BLOCK_ID;
        }
        return TimelessAPI.getClientBlockIndex(id);
    }

    @Override
    public void render(DecoWeaponRackEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferIn, int combinedLightIn, int combinedOverlayIn) {
        getIndex(blockEntity).ifPresent(index -> {
            if (lastRevision != REVISION) {
                animCache.clear();
                lastRevision = REVISION;
            }
            ResourceLocation id = blockEntity.getId();
            ResourceLocation lookup = (id != null) ? id : DecoRegistries.RACK_BLOCK_ID;
            ResourceLocation displayRl = DecoDisplayRegistry.get(lookup);
            // 纯渲染选项（animation / isdoubleface / hide_on_close）已随 display 文件注入 BlockDisplay，
            // 经 displayRl 取出 display 对象后由 BlockDisplayOptions 暴露的静态方法读取。
            BlockDisplay bd = (displayRl != null) ? ClientAssetsManager.INSTANCE.getBlockDisplay(displayRl) : null;
            ResourceLocation animRl = (bd != null) ? BlockDisplayOptions.getAnimation(bd) : null;

            BedrockModel baseModel = index.getModel();

            // 动画：始终渲染静态 index 模型（保底可见）。仅把动画模型算出的、
            // 受动画影响的骨骼（door/gear/plate）变换拷到静态模型的对应骨骼上，
            // 渲染结束后还原。直接渲染独立 BedrockAnimatedModel 会导致整模型不可见。
            List<Runnable> boneRestore = new ArrayList<>();
            DecoRackAnimationState state = null;
            BedrockAnimatedModel animatedModel = null;

            if (animRl != null && displayRl != null) {
                state = animCache.get(blockEntity.getBlockPos());
                if (state == null) {
                    try {
                        state = new DecoRackAnimationState(animRl, displayRl);
                    } catch (Exception ignored) {
                        state = null;
                    }
                    animCache.put(blockEntity.getBlockPos(), state);
                }
                if (state != null && state.isValid()) {
                    state.update(blockEntity.isOpen());
                    animatedModel = state.getModel();
                    if (animatedModel != null) {
                        for (String bone : state.getAnimatedBoneNames()) {
                            BedrockPart src = animatedModel.getNode(bone);
                            BedrockPart dst = baseModel.getNode(bone);
                            if (src != null && dst != null) {
                                boneRestore.add(copyBoneTransform(dst, src));
                            }
                        }
                        if (!RACK_DEBUG) {
                            RACK_DEBUG = true;
                            BedrockPart dsrc = animatedModel.getNode("door");
                            BedrockPart ddst = baseModel.getNode("door");
                            Quaternionf q = dsrc != null ? dsrc.additionalQuaternion : null;
                            org.apache.logging.log4j.LogManager.getLogger(DecoWeaponRackRenderer.class).info(
                                    "[DecoRack] open={} animBones={} doorSrcFound={} doorDstFound={} doorQuat={}",
                                    blockEntity.isOpen(), state.getAnimatedBoneNames(),
                                    dsrc != null, ddst != null, q != null ? (q.x + "," + q.y + "," + q.z + "," + q.w) : "?");
                        }
                    }
                }
            }

            if (baseModel == null) {
                return;
            }

            BlockState blockState = blockEntity.getBlockState();
            if (!(blockState.getBlock() instanceof DecoWeaponRackBlock)) {
                return;
            }
            // 双块武器架（b/c）只由根块绘制模型，配对块（HEAD/UPPER）不重复渲染
            if (!DecoWeaponRackBlock.isRoot(blockState)) {
                return;
            }
            Direction facing = blockState.getValue(DecoWeaponRackBlock.FACING);

            // 所有 rack 类型的 FACING 均指向玩家（模型正面面朝玩家），
            // 双块（DOUBLE_B）的第二格在玩家视角右侧（FACING 逆时针 90°），模型直接跟 FACING 走。
            Direction modelFacing = facing;

            poseStack.pushPose();
            poseStack.translate(0.5, 1.5, 0.5);
            poseStack.mulPose(Axis.ZN.rotationDegrees(180));
            poseStack.mulPose(Axis.YN.rotationDegrees(90.0F * (3 - modelFacing.get2DDataValue()) - 90));

            ResourceLocation texture = index.getTexture();
            // isdoubleface：true 时模型双面显示（关闭背面剔除），覆盖默认单面 cutout（来自 display 选项）
            boolean doubleFace = bd != null && BlockDisplayOptions.isDoubleFace(bd);
            RenderType renderType;
            if (RenderConfig.BLOCK_ENTITY_TRANSLUCENT.get()) {
                renderType = RenderType.entityTranslucent(texture);
            } else {
                renderType = doubleFace ? RenderType.entityCutoutNoCull(texture) : RenderType.entityCutout(texture);
            }
            try {
            baseModel.render(poseStack, ItemDisplayContext.NONE, renderType, combinedLightIn, combinedOverlayIn);

            // 在模型变换帧内，分别摆放武器与配件（跟随动画后骨骼）
            Level level = blockEntity.getLevel();
            if (level != null) {
                // hide_on_close（display 选项）：仅处于「完全关闭」(idle_close / CLOSED) 状态时才隐藏武器；
                // 播放 open 动画、idle_open、以及播放 close 动画期间（OPENING/OPEN/CLOSING）都显示武器。
                // 用动画状态机的 Phase 而非方块实体的 open 字段：close 动画一开始方块实体已切为 false，
                // 若用 isOpen() 判定则武器会在 close 动画播放期间就提前消失。
                boolean hideOnClose = bd != null && BlockDisplayOptions.isHideOnClose(bd);
                boolean showItems = !hideOnClose
                        || (state == null ? blockEntity.isOpen() : !state.isClosed());
                if (showItems) {
                    placeItems(blockEntity, lookup, animatedModel, baseModel, poseStack, bufferIn, level);
                }
            }
            } finally {
                for (Runnable r : boneRestore) {
                    r.run();
                }
                poseStack.popPose();
            }
        });
    }

    /** 摆放武器（weapon_mount 系）与配件（attachment_mount 系）两套独立槽位。 */
    private void placeItems(DecoWeaponRackEntity be, ResourceLocation lookup,
                            @Nullable BedrockAnimatedModel animated, BedrockModel base,
                            PoseStack poseStack, MultiBufferSource bufferIn, Level level) {
        List<DecoMountBoneRegistry.WeaponMountSlot> wSlots = DecoMountBoneRegistry.getWeaponSlots(lookup);
        placeCategory(be.getWeaponItems(),
                wSlots.stream().map(s -> s.bone).collect(Collectors.toList()),
                wSlots.stream().map(s -> s.scale).collect(Collectors.toList()),
                animated, base, poseStack, bufferIn, level);
        List<DecoMountBoneRegistry.AttachmentMountSlot> aSlots = DecoMountBoneRegistry.getAttachmentSlots(lookup);
        placeCategory(be.getAttachmentItems(),
                aSlots.stream().map(s -> s.bone).collect(Collectors.toList()),
                aSlots.stream().map(s -> s.scale).collect(Collectors.toList()),
                animated, base, poseStack, bufferIn, level);
    }

    /**
     * 在一套槽位内逐骨骼摆放物品。
     * 优先用动画后 {@link BedrockPart}（{@code animated != null} 且该骨骼存在），否则回退静态 pivot 摆放。
     * 若模型里根本找不到该骨骼则跳过，避免物品跑到原点。
     * 每个槽位使用 index 里配置的独立缩放 {@code scales[i]}（缺省 {@link DecoMountBoneRegistry#DEFAULT_SCALE}）。
     */
    private void placeCategory(List<ItemStack> items, List<String> bones, List<Float> scales,
                               @Nullable BedrockAnimatedModel animated, BedrockModel base,
                               PoseStack poseStack, MultiBufferSource bufferIn, Level level) {
        for (int i = 0; i < bones.size(); i++) {
            if (i >= items.size()) {
                break;
            }
            ItemStack held = items.get(i);
            if (held.isEmpty()) {
                continue;
            }
            String mountBone = bones.get(i);
            boolean useAnimated = animated != null && animated.getNode(mountBone) != null;
            if (!useAnimated && base.getBone(mountBone) == null) {
                continue; // 模型里没有这个骨骼，跳过
            }
            float scale = (i < scales.size()) ? scales.get(i) : DecoMountBoneRegistry.DEFAULT_SCALE;
            poseStack.pushPose();
            if (useAnimated) {
                applyBoneChain(animated.getNode(mountBone), poseStack);
            } else {
                fallbackStatic(base, mountBone, poseStack);
            }

            // 每个槽位独立的物品缩放（默认 0.5，与 TACZ statue 一致）
            poseStack.scale(scale, scale, scale);

            Minecraft.getInstance().getItemRenderer().renderStatic(
                    held,
                    ItemDisplayContext.FIXED,
                    LightTexture.pack(15, 15),
                    OverlayTexture.NO_OVERLAY,
                    poseStack,
                    bufferIn,
                    level,
                    0
            );
            poseStack.popPose();
        }
    }

    /** 静态回退：按骨骼 pivot 链累加位置 + 应用静态 rotation。 */
    private static void fallbackStatic(BedrockModel model, String mountBone, PoseStack poseStack) {
        BonesItem bone = model.getBone(mountBone);
        if (bone == null) {
            return;
        }
        Vec3 pivot = accumulatePivot(model, mountBone);
        poseStack.translate(pivot.x / 16.0, pivot.y / 16.0, pivot.z / 16.0);
        List<Float> rot = bone.getRotation();
        if (rot != null && rot.size() >= 3) {
            poseStack.mulPose(Axis.XN.rotationDegrees(rot.get(0)));
            poseStack.mulPose(Axis.YN.rotationDegrees(rot.get(1)));
            poseStack.mulPose(Axis.ZN.rotationDegrees(rot.get(2)));
        }
    }

    /**
     * 把 src 骨骼的变换（偏移/旋转/缩放/可见性）拷到 dst，并返回还原 dst 原值的 Runnable。
     * 用于在静态模型上临时套用动画骨骼姿态，渲染后恢复，避免直接渲染独立
     * {@link BedrockAnimatedModel} 时整模型不可见的问题。
     */
    private static Runnable copyBoneTransform(BedrockPart dst, BedrockPart src) {
        float ox = dst.offsetX, oy = dst.offsetY, oz = dst.offsetZ;
        float rx = dst.xRot, ry = dst.yRot, rz = dst.zRot;
        float sx = dst.xScale, sy = dst.yScale, sz = dst.zScale;
        boolean vis = dst.visible;
        // TACZ 的 ROTATION 动画通道写进 additionalQuaternion（见 ModelRotateListener），
        // cleanAnimationTransform 也只清它；xRot/yRot/zRot 不参与动画旋转。漏拷会导致纯旋转组件不动。
        float oqw = dst.additionalQuaternion.w, oqx = dst.additionalQuaternion.x,
                oqy = dst.additionalQuaternion.y, oqz = dst.additionalQuaternion.z;
        dst.offsetX = src.offsetX; dst.offsetY = src.offsetY; dst.offsetZ = src.offsetZ;
        dst.xRot = src.xRot; dst.yRot = src.yRot; dst.zRot = src.zRot;
        dst.xScale = src.xScale; dst.yScale = src.yScale; dst.zScale = src.zScale;
        dst.visible = src.visible;
        dst.additionalQuaternion.set(src.additionalQuaternion);
        return () -> {
            dst.offsetX = ox; dst.offsetY = oy; dst.offsetZ = oz;
            dst.xRot = rx; dst.yRot = ry; dst.zRot = rz;
            dst.xScale = sx; dst.yScale = sy; dst.zScale = sz;
            dst.visible = vis;
            dst.additionalQuaternion.set(oqx, oqy, oqz, oqw);
        };
    }

    /**
     * 沿父链累加动画后骨骼的变换，得到其在模型坐标系中的世界变换。
     * 与 {@code BedrockPart.translateAndRotateAndScale} 一致（translate 用 geo/16，旋转用
     * 正轴 + 弧度，按 Z→Y→X 顺序），但不施加缩放（物品大小由外部 0.5 缩放决定）。
     */
    private static void applyBoneChain(BedrockPart node, PoseStack poseStack) {
        List<BedrockPart> chain = new ArrayList<>();
        for (BedrockPart c = node; c != null; c = c.getParent()) {
            chain.add(c);
        }
        Collections.reverse(chain); // root 在前
        for (BedrockPart p : chain) {
            if (p.offsetX != 0 || p.offsetY != 0 || p.offsetZ != 0) {
                poseStack.translate(p.offsetX, p.offsetY, p.offsetZ);
            }
            poseStack.translate(p.x / 16.0F, p.y / 16.0F, p.z / 16.0F);
            if (p.zRot != 0) {
                poseStack.mulPose(Axis.ZP.rotation(p.zRot));
            }
            if (p.yRot != 0) {
                poseStack.mulPose(Axis.YP.rotation(p.yRot));
            }
            if (p.xRot != 0) {
                poseStack.mulPose(Axis.XP.rotation(p.xRot));
            }
            if (p.additionalQuaternion != null) {
                poseStack.mulPose(p.additionalQuaternion);
            }
        }
    }

    /** 沿父链累加骨骼 pivot（geo 单位），得到其在模型坐标系中的近似世界位置。 */
    private static Vec3 accumulatePivot(BedrockModel model, String boneName) {
        double x = 0, y = 0, z = 0;
        BonesItem cur = model.getBone(boneName);
        while (cur != null) {
            List<Float> p = cur.getPivot();
            if (p != null && p.size() >= 3) {
                x += p.get(0);
                y += p.get(1);
                z += p.get(2);
            }
            String parent = cur.getParent();
            cur = (parent != null) ? model.getBone(parent) : null;
        }
        return new Vec3(x, y, z);
    }

    @Override
    public boolean shouldRenderOffScreen(DecoWeaponRackEntity blockEntity) {
        return true;
    }
}
