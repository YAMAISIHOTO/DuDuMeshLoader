package com.example.dudumeshloader.deco;

import com.tacz.guns.api.client.animation.Animations;
import com.tacz.guns.api.client.animation.AnimationController;
import com.tacz.guns.api.client.animation.ObjectAnimation;
import com.tacz.guns.api.client.animation.ObjectAnimationRunner;
import com.tacz.guns.client.model.BedrockAnimatedModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.resource.ClientAssetsManager;
import com.tacz.guns.client.resource.pojo.animation.bedrock.BedrockAnimation;
import com.tacz.guns.client.resource.pojo.animation.bedrock.BedrockAnimationFile;
import com.tacz.guns.client.resource.pojo.display.block.BlockDisplay;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 单个武器架实例的动画运行态（客户端，按 BlockPos 缓存）。
 *
 * <p>构建流程：用 {@code display} RL 解析出几何体 POJO，重建一个 {@link BedrockAnimatedModel}
 * （与 TACZ 方块 index 共用同一套几何体，但可被子弹般驱动动画），再用 {@code animation} RL
 * 读取的 {@link BedrockAnimationFile} 创建 {@link AnimationController}。</p>
 *
 * <p>状态机（随方块实体的 {@code open} 切换）：
 * <pre>
 *   idle_close (默认循环)
 *       潜行右键 → open (PLAY_ONCE) → 完成后 → idle_open (循环)
 *       再次潜行右键 → close (PLAY_ONCE) → 完成后 → idle_close (循环)
 * </pre>
 * 动画名约定：{@code idle_close} / {@code open} / {@code idle_open} / {@code close}。
 * 缺失某个动画时优雅降级（如缺 idle 则停留绑定姿态）。</p>
 */
@OnlyIn(Dist.CLIENT)
public class DecoRackAnimationState {
    private enum Phase { CLOSED, OPENING, OPEN, CLOSING }

    private static final String IDLE_CLOSE = "idle_close";
    private static final String OPEN = "open";
    private static final String IDLE_OPEN = "idle_open";
    private static final String CLOSE = "close";

    /** 单次会话仅打一次日志，确认动画是否成功加载（用于排查 animation RL 解析）。 */
    private static boolean LOG_DONE = false;

    @Nullable
    private final BedrockAnimatedModel model;
    @Nullable
    private final AnimationController controller;
    @Nullable
    private final BedrockAnimationFile animFile;
    private Phase phase = Phase.CLOSED;
    private boolean hadOpen = false;
    @Nullable
    private String currentLoop = null;
    private Phase lastLoggedPhase = null;
    private static final org.apache.logging.log4j.Logger STATE_LOG =
            org.apache.logging.log4j.LogManager.getLogger(DecoRackAnimationState.class);

    public DecoRackAnimationState(ResourceLocation animRl, ResourceLocation displayRl) {
        this.animFile = resolveAnimations(animRl);
        BedrockAnimationFile animFile = this.animFile;
        BedrockAnimatedModel built = null;
        if (animFile != null) {
            ResourceLocation geoRl = resolveGeometry(displayRl);
            BedrockModelPOJO pojo = (geoRl != null) ? ClientAssetsManager.INSTANCE.getBedrockModelPOJO(geoRl) : null;
            if (pojo != null) {
                BedrockVersion ver = BedrockVersion.isNewVersion(pojo) ? BedrockVersion.NEW : BedrockVersion.LEGACY;
                built = new BedrockAnimatedModel(pojo, ver);
            }
        }
        this.model = built;
        this.controller = (animFile != null && built != null)
                ? Animations.createControllerFromBedrock(animFile, built)
                : null;
        if (!LOG_DONE) {
            LOG_DONE = true;
            org.apache.logging.log4j.LogManager.getLogger(DecoRackAnimationState.class)
                    .info("[DecoRack] animRl={} loaded={} controller={}", animRl, animFile != null, controller != null);
        }
    }

    /**
     * 解析动画文件，兼容两种 RL key 写法：
     * <ul>
     *   <li>{@code duyupack:weapon_box}（TACZ 注册时剥离 {@code .animation.json}）；</li>
     *   <li>{@code duyupack:weapon_box.animation}（仅剥离 {@code .json} 保留 {@code .animation}）。</li>
     * </ul>
     * 优先按原值取，取不到再尝试追加 {@code .animation} 后缀。
     */
    @Nullable
    private static BedrockAnimationFile resolveAnimations(ResourceLocation animRl) {
        if (animRl == null) {
            return null;
        }
        BedrockAnimationFile f = ClientAssetsManager.INSTANCE.getBedrockAnimations(animRl);
        if (f != null) {
            return f;
        }
        ResourceLocation suffixed = new ResourceLocation(animRl.getNamespace(), animRl.getPath() + ".animation");
        return ClientAssetsManager.INSTANCE.getBedrockAnimations(suffixed);
    }

    @Nullable
    private static ResourceLocation resolveGeometry(ResourceLocation displayRl) {
        if (displayRl == null) {
            return null;
        }
        BlockDisplay bd = ClientAssetsManager.INSTANCE.getBlockDisplay(displayRl);
        return (bd != null) ? bd.getModelLocation() : null;
    }

    @Nullable
    public BedrockAnimatedModel getModel() {
        return model;
    }

    /**
     * 动画文件中被动画化的所有骨骼名（跨全部动画去重）。
     * 渲染器只把这些骨骼的变换从动画模型拷回静态模型，其余骨骼保持静止位姿，
     * 从而既让门/齿轮/板随开合动画运动，又保证武器架主体始终可见。
     */
    public Set<String> getAnimatedBoneNames() {
        Set<String> names = new HashSet<>();
        if (animFile != null && animFile.getAnimations() != null) {
            for (Map.Entry<String, BedrockAnimation> e : animFile.getAnimations().entrySet()) {
                if (e.getValue().getBones() != null) {
                    names.addAll(e.getValue().getBones().keySet());
                }
            }
        }
        return names;
    }

    public boolean isValid() {
        return controller != null && model != null;
    }

    /**
     * 是否处于「完全关闭」状态——即 close 过渡动画已播完、停在 {@code idle_close} 循环。
     *
     * <p>用于 {@code hide_on_close} 判定：仅在完全关闭时隐藏武器；播放 open 动画
     * （{@code OPENING}）、开启中（{@code OPEN}）、以及播放 close 动画（{@code CLOSING}）期间
     * 都视为「未关闭」，武器应当可见。这能避免 close 动画一开始（此时方块实体的
     * {@code open} 已切为 false）武器就提前消失的问题。</p>
     */
    public boolean isClosed() {
        return phase == Phase.CLOSED;
    }

    /** 每帧调用：根据 open 状态推进动画状态机并 advance 控制器。 */
    public void update(boolean openNow) {
        if (controller == null || model == null) {
            return;
        }

        // 检测 open 的升降沿，触发过渡动画
        if (openNow && !hadOpen) {
            run(OPEN, ObjectAnimation.PlayType.PLAY_ONCE_HOLD);
            currentLoop = null;
            phase = Phase.OPENING;
        } else if (!openNow && hadOpen) {
            run(CLOSE, ObjectAnimation.PlayType.PLAY_ONCE_HOLD);
            currentLoop = null;
            phase = Phase.CLOSING;
        }
        hadOpen = openNow;

        switch (phase) {
            case OPENING:
                if (transitionDone()) {
                    if (!IDLE_OPEN.equals(currentLoop)) {
                        run(IDLE_OPEN, ObjectAnimation.PlayType.LOOP);
                        currentLoop = IDLE_OPEN;
                    }
                    phase = Phase.OPEN;
                }
                break;
            case CLOSING:
                if (transitionDone()) {
                    if (!IDLE_CLOSE.equals(currentLoop)) {
                        run(IDLE_CLOSE, ObjectAnimation.PlayType.LOOP);
                        currentLoop = IDLE_CLOSE;
                    }
                    phase = Phase.CLOSED;
                }
                break;
            case OPEN:
                if (!IDLE_OPEN.equals(currentLoop)) {
                    run(IDLE_OPEN, ObjectAnimation.PlayType.LOOP);
                    currentLoop = IDLE_OPEN;
                }
                break;
            case CLOSED:
                if (!IDLE_CLOSE.equals(currentLoop)) {
                    run(IDLE_CLOSE, ObjectAnimation.PlayType.LOOP);
                    currentLoop = IDLE_CLOSE;
                }
                break;
        }

        controller.update();

        if (phase != lastLoggedPhase) {
            lastLoggedPhase = phase;
            BedrockPart door = (model != null) ? model.getNode("door") : null;
            STATE_LOG.info("[DecoRack] phase={} openNow={} doorXRot={}", phase, openNow,
                    door != null ? door.xRot : "?");
        }
    }

    private void run(String name, ObjectAnimation.PlayType type) {
        if (controller.containPrototype(name)) {
            controller.runAnimation(0, name, type, 0.0F);
        }
    }

    /** 过渡动画是否已播完（PLAY_ONCE 的进度达到总时长，或已停止/被替换）。 */
    private boolean transitionDone() {
        ObjectAnimationRunner runner = controller.getAnimation(0);
        if (runner == null || runner.isStopped()) {
            return true;
        }
        ObjectAnimation anim = runner.getAnimation();
        if (anim == null) {
            return true;
        }
        long durationNs = (long) (anim.getMaxEndTimeS() * 1_000_000_000L);
        return runner.getProgressNs() >= durationNs;
    }
}
