package com.example.dudumeshloader.render;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 客户端每渲染帧自增的单调计数器。
 *
 * <p>用于让 {@code PolyMeshModel} 的「骨骼世界矩阵帧级缓存」判断何时失效：
 * 一次渲染里同一把枪会被多次提交（VR 双目、阴影、倍镜叠加层等），每遍都重走整棵
 * 骨骼树是浪费。缓存每帧只算一次，跨遍复用。计数由
 * {@link ShaderStateTracker#onRenderTick} 在 {@code RenderTickEvent(Phase.START)}
 * 中推进，保证一次游戏帧内所有渲染遍看到同一个帧号。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class FrameCounter {
    private static volatile long frame = 0;

    private FrameCounter() {}

    /** 当前帧号（每游戏渲染帧自增 1）。 */
    public static long frame() {
        return frame;
    }

    /** 由渲染帧事件调用，标记进入新的一帧。 */
    public static void nextFrame() {
        frame++;
    }
}
