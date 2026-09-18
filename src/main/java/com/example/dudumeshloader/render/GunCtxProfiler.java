package com.example.dudumeshloader.render;

import com.example.dudumeshloader.DuDuMeshLoaderMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 按 {@code ItemDisplayContext} 拆分枪身 poly_mesh 渲染耗时的轻量诊断器。
 *
 * <p>与 {@link PerformanceDiagnostics} 不同，本类<b>始终启用</b>（无需 perfdiag 版本或
 * JVM 参数），仅在渲染线程对每次枪身渲染打 2 次 {@code nanoTime}，开销可忽略。
 * 每 5 秒汇总一次各上下文（FIRST_PERSON / THIRD_PERSON / FIXED 展示架 / GROUND 掉落物）
 * 的调用次数、总耗时、每帧耗时与单次最大耗时，并附首次捕获的渲染路径描述，
 * 用于定位「展示架帧数比第一人称低」这类按场景出现的性能差异。</p>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GunCtxProfiler {

    private static final Logger LOG = LogManager.getLogger("MeshyGunCtx");
    private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

    /** 各上下文的枪身渲染根计时（含 TaCZ 原生枪体 + 本模组 poly_mesh 阶段）。 */
    private static final Map<String, Timer> ROOT = new LinkedHashMap<>();
    /** 各上下文的 TaCZ 原生 super.render 阶段耗时（cube 枪体 + 动画，不含本模组 poly）。 */
    private static final Map<String, Timer> NATIVE = new LinkedHashMap<>();
    /** 各上下文的「附件解析 / scope 渲染」阶段耗时（BedrockGunModel.render 进入 cube 绘制之前）。 */
    private static final Map<String, Timer> ATTACH = new LinkedHashMap<>();
    /** 各上下文的「cube 枪体绘制」阶段耗时（即 BedrockAnimatedModel.render 本体，含本模组 poly 注入）。 */
    private static final Map<String, Timer> CUBE = new LinkedHashMap<>();
    /** 各上下文首次捕获的渲染路径描述（AR 是否激活 / 是否走 VBO / 是否刚性）。 */
    private static final Map<String, String> PATH = new LinkedHashMap<>();

    private static final Timer ZERO = new Timer();
    private static long windowStartNs = 0L;
    private static long frames = 0L;

    private GunCtxProfiler() {}

    /** 渲染入口调用，返回起始时间戳（始终打点）。 */
    public static long begin() {
        return System.nanoTime();
    }

    /** 渲染出口调用，按上下文累计耗时。 */
    public static void endRoot(long startNs, String ctx) {
        if (startNs == 0L) return;
        ROOT.computeIfAbsent(ctx, k -> new Timer()).add(System.nanoTime() - startNs);
    }

    /** 记录各上下文首次的渲染路径描述（后续调用同一上下文不覆盖）。 */
    public static void notePath(String ctx, String path) {
        PATH.putIfAbsent(ctx, path);
    }

    /** 记录各上下文的 TaCZ 原生 super.render 阶段耗时（cube 枪体 + 动画，不含本模组 poly）。 */
    public static void recordNative(String ctx, long ns) {
        if (ns <= 0L) return;
        NATIVE.computeIfAbsent(ctx, k -> new Timer()).add(ns);
    }

    /** 记录各上下文的「附件解析 / scope 渲染」阶段耗时（cube 绘制之前）。 */
    public static void recordAttachPhase(String ctx, long ns) {
        if (ns <= 0L) return;
        ATTACH.computeIfAbsent(ctx, k -> new Timer()).add(ns);
    }

    /** 记录各上下文的「cube 枪体绘制」阶段耗时（BedrockAnimatedModel.render 本体）。 */
    public static void recordCubePhase(String ctx, long ns) {
        if (ns <= 0L) return;
        CUBE.computeIfAbsent(ctx, k -> new Timer()).add(ns);
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        long now = System.nanoTime();
        if (windowStartNs == 0L) windowStartNs = now;
        frames++;
        if (now - windowStartNs < REPORT_INTERVAL_NS) return;

        if (!ROOT.isEmpty()) {
            double sec = (now - windowStartNs) / 1_000_000_000.0;
            LOG.info("[MeshyGunCtx] window={}s frames={} (~{} fps)",
                    round(sec), frames, round(frames / sec));
            for (Map.Entry<String, Timer> entry : ROOT.entrySet()) {
                Timer t = entry.getValue();
                Timer nat = NATIVE.get(entry.getKey());
                Timer at = ATTACH.get(entry.getKey());
                Timer cu = CUBE.get(entry.getKey());
                LOG.info(
                        "[MeshyGunCtx] ctx={} calls={} total={}ms avg={}us max={}ms/frame={}ms native={}us attach={}us cube={}us | path={}",
                        entry.getKey(), t.calls,
                        round(t.totalNs / 1_000_000.0),
                        round(t.totalNs / 1_000.0 / t.calls),
                        round(t.maxNs / 1_000_000.0),
                        round(t.totalNs / 1_000_000.0 / frames),
                        nat != null ? round(nat.totalNs / 1_000.0 / t.calls) : 0.0,
                        at != null ? round(at.totalNs / 1_000.0 / t.calls) : 0.0,
                        cu != null ? round(cu.totalNs / 1_000.0 / t.calls) : 0.0,
                        PATH.getOrDefault(entry.getKey(), "<unset>")
                );
            }
        }
        reset(now);
    }

    private static void reset(long now) {
        for (Timer t : ROOT.values()) t.reset();
        for (Timer t : NATIVE.values()) t.reset();
        for (Timer t : ATTACH.values()) t.reset();
        for (Timer t : CUBE.values()) t.reset();
        frames = 0L;
        windowStartNs = now;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static final class Timer {
        private long calls;
        private long totalNs;
        private long maxNs;

        private void add(long elapsedNs) {
            calls++;
            totalNs += elapsedNs;
            if (elapsedNs > maxNs) maxNs = elapsedNs;
        }

        private void reset() {
            calls = 0L;
            totalNs = 0L;
            maxNs = 0L;
        }
    }
}
