package com.example.dudumeshloader.render;

import com.example.dudumeshloader.DuDuMeshLoaderMod;
import com.tacz.guns.compat.oculus.OculusCompat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 临时性能诊断聚合器。
 *
 * <p>只有版本号包含 {@code perfdiag}，或 JVM 参数显式设置
 * {@code -Ddudumeshloader.perfdiag=true} 时才启用。正式版构建会直接走无计时分支，
 * 不改变现有渲染顺序、缓冲提交或 stencil 状态。</p>
 *
 * <p>当前所有记录点都位于客户端渲染线程，故使用普通 long 以降低探针自身开销。
 * 每五秒只输出一次汇总，不在逐帧路径中创建 Map、lambda 或临时计时对象。</p>
 */
@Mod.EventBusSubscriber(
        modid = DuDuMeshLoaderMod.MOD_ID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class PerformanceDiagnostics {

    private static final Logger LOG = LogManager.getLogger("MeshyLoaderPerf");
    private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

    private static final int FLUSH_ORIGIN_NONE = 0;
    private static final int FLUSH_ORIGIN_GUN = 1;
    private static final int FLUSH_ORIGIN_ATTACHMENT = 2;

    private static final boolean ENABLED = detectEnabled();

    private static final Timer GUN_ROOT = new Timer();
    private static final Timer GUN_POLY_AR = new Timer();
    private static final Timer GUN_POLY_DIRECT = new Timer();
    /** 按 ItemDisplayContext 分桶的枪根耗时，用于对比 FP / FIXED / GROUND 场景渲染成本。 */
    private static final Map<String, Timer> GUN_ROOT_BY_CONTEXT = new LinkedHashMap<>();
    /** 按 ItemDisplayContext 分桶的 poly_mesh 阶段耗时（区分 AR/DIRECT）。 */
    private static final Map<String, Timer> GUN_POLY_BY_CONTEXT = new LinkedHashMap<>();
    private static final Timer ATTACHMENT_ROOT = new Timer();
    private static final Timer ATTACHMENT_NORMAL = new Timer();
    private static final Timer ATTACHMENT_SPECIAL = new Timer();
    private static final Timer ATTACHMENT_IDLE_SIGHT = new Timer();

    /** perfdiag.2：倍镜特殊直写的细分阶段。 */
    private static final Timer ATTACHMENT_SPECIAL_PRECHECK = new Timer();
    private static long attachmentSpecialPrecheckHits;
    private static final Timer ATTACHMENT_SPECIAL_GET_BUFFER = new Timer();
    private static final Timer ATTACHMENT_SPECIAL_BONE_PASS = new Timer();
    private static final Timer ATTACHMENT_SPECIAL_VERTEX_WRITE = new Timer();
    private static final Timer ATTACHMENT_SPECIAL_TYPED_FLUSH = new Timer();
    private static long attachmentSpecialVertices;
    private static final Map<String, SpecialRootStats> ATTACHMENT_SPECIAL_ROOTS =
            new LinkedHashMap<>();

    private static final Timer AR_BUILD = new Timer();
    private static final Timer AR_WRITE = new Timer();
    private static long arBuildVertices;
    private static long arCacheHits;
    private static long arCacheHitVertices;
    private static long arWriteVertices;
    private static long arInvalidations;
    private static long arInvalidatedEntries;
    private static long gunArStageAcceptedConsumers;
    private static long gunArStageFallbackConsumers;
    private static long nativeScopeArAttempts;
    private static long nativeScopeArAccepted;
    private static long nativeScopeArRejected;
    private static long nativeScopeArCircuitTrips;
    private static long nativeScopeArCircuitBypasses;

    private static final Timer TACZ_COMPAT_CALL = new Timer();
    private static final Timer TACZ_COMPAT_FULL_FLUSH = new Timer();
    private static final Timer GUN_COMPAT_CALL = new Timer();
    private static final Timer ATTACHMENT_COMPAT_CALL = new Timer();
    private static long gunCompatFullFlushes;
    private static long attachmentCompatFullFlushes;
    private static final Timer TYPED_FLUSH = new Timer();

    private static long windowStartNs;
    private static long frames;
    private static boolean announced;
    private static int flushOrigin;

    private PerformanceDiagnostics() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    /** 禁用诊断时返回 0，避免正式版调用 System.nanoTime。 */
    public static long begin() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void endGunRoot(long startNs) {
        record(GUN_ROOT, startNs);
    }

    /** 按 ItemDisplayContext 分桶记录枪根耗时，定位 FP / FIXED / GROUND 各自的渲染成本。 */
    public static void endGunRootByContext(String contextName, long startNs) {
        addToContextMap(GUN_ROOT_BY_CONTEXT, contextName, startNs);
    }

    public static void endGunPolyStage(long startNs, boolean accelerated) {
        record(accelerated ? GUN_POLY_AR : GUN_POLY_DIRECT, startNs);
    }

    /** 按 ItemDisplayContext 分桶记录 poly_mesh 阶段耗时。 */
    public static void endGunPolyStageByContext(String contextName, long startNs, boolean accelerated) {
        addToContextMap(GUN_POLY_BY_CONTEXT, (accelerated ? "AR:" : "DIRECT:") + contextName, startNs);
    }

    private static void addToContextMap(Map<String, Timer> map, String contextName, long startNs) {
        if (!ENABLED) return;
        String name = contextName == null || contextName.isEmpty() ? "NULL" : contextName;
        Timer timer = map.get(name);
        if (timer == null) {
            timer = new Timer();
            map.put(name, timer);
        }
        timer.add(System.nanoTime() - startNs);
    }

    public static void endAttachmentRoot(long startNs) {
        record(ATTACHMENT_ROOT, startNs);
    }

    public static void endAttachmentNormal(long startNs) {
        record(ATTACHMENT_NORMAL, startNs);
    }

    public static void endAttachmentSpecial(String rootBoneName, long startNs) {
        if (!ENABLED) return;
        long elapsed = System.nanoTime() - startNs;
        ATTACHMENT_SPECIAL.add(elapsed);
        specialRoot(rootBoneName).outer.add(elapsed);
    }

    public static void endAttachmentIdleSight(long startNs) {
        record(ATTACHMENT_IDLE_SIGHT, startNs);
    }

    /** 记录特殊节点实际绘制前的 hasMeshInSubtree 扫描；不改变旧 special 计时起点。 */
    public static void endAttachmentSpecialPrecheck(long startNs, boolean hasMesh) {
        if (!ENABLED) return;
        ATTACHMENT_SPECIAL_PRECHECK.add(System.nanoTime() - startNs);
        if (hasMesh) attachmentSpecialPrecheckHits++;
    }

    /** getBuffer 可能隐式切换上一批次，因此单独记录渲染线程墙钟时间。 */
    public static void endAttachmentSpecialGetBuffer(String rootBoneName, long startNs) {
        if (!ENABLED) return;
        long elapsed = System.nanoTime() - startNs;
        ATTACHMENT_SPECIAL_GET_BUFFER.add(elapsed);
        specialRoot(rootBoneName).getBuffer.add(elapsed);
    }

    /** 包含 findBone、骨骼递归、PoseStack 和 mesh emit；vertexTransformWrite 是其子集。 */
    public static void endAttachmentSpecialBonePass(String rootBoneName, long startNs) {
        if (!ENABLED) return;
        long elapsed = System.nanoTime() - startNs;
        ATTACHMENT_SPECIAL_BONE_PASS.add(elapsed);
        specialRoot(rootBoneName).bonePass.add(elapsed);
    }

    /**
     * 记录单个 PolyMesh 的逐顶点矩阵变换与 VertexConsumer 写入。
     * 两者在 emitTransformed 中交织，诊断版不逐顶点拆分，以免探针反过来污染结果。
     */
    public static void endAttachmentSpecialVertexWrite(String rootBoneName, long startNs,
                                                        int vertices) {
        if (!ENABLED) return;
        long elapsed = System.nanoTime() - startNs;
        ATTACHMENT_SPECIAL_VERTEX_WRITE.add(elapsed);
        attachmentSpecialVertices += vertices;
        SpecialRootStats stats = specialRoot(rootBoneName);
        stats.vertexWrite.add(elapsed);
        stats.vertices += vertices;
    }

    /** 枪根作用域内的 TaCZ 原生刷新也归入枪侧；附件根进入时会临时覆盖该标签。 */
    public static int enterGunFlushScope() {
        return enterFlushScope(FLUSH_ORIGIN_GUN);
    }

    /** 附件根作用域覆盖外层枪标签，从而包含 renderTempPart 内的 TaCZ 原生刷新。 */
    public static int enterAttachmentFlushScope() {
        return enterFlushScope(FLUSH_ORIGIN_ATTACHMENT);
    }

    public static void leaveFlushScope(int previousOrigin) {
        if (!ENABLED) return;
        flushOrigin = previousOrigin;
    }

    private static int enterFlushScope(int origin) {
        if (!ENABLED) return FLUSH_ORIGIN_NONE;
        int previousOrigin = flushOrigin;
        flushOrigin = origin;
        return previousOrigin;
    }

    public static void endArBuild(long startNs, int vertices) {
        if (!ENABLED) return;
        AR_BUILD.add(System.nanoTime() - startNs);
        arBuildVertices += vertices;
    }

    public static void arCacheHit(int vertices) {
        if (!ENABLED) return;
        arCacheHits++;
        arCacheHitVertices += vertices;
    }

    public static void endArWrite(long startNs, int vertices) {
        if (!ENABLED) return;
        AR_WRITE.add(System.nanoTime() - startNs);
        arWriteVertices += vertices;
    }

    public static void arCacheInvalidated(int clearedEntries) {
        if (!ENABLED) return;
        arInvalidations++;
        arInvalidatedEntries += clearedEntries;
    }

    /** 记录 TaCZ AR 阶段拿到的 consumer 是否真的支持本模组 AR 管线。 */
    public static void gunArStageConsumer(boolean accelerated) {
        if (!ENABLED) return;
        if (accelerated) {
            gunArStageAcceptedConsumers++;
        } else {
            gunArStageFallbackConsumers++;
        }
    }

    /** 第一人称 scope_body 已满足原生 -942 AR 候选条件。 */
    public static void nativeScopeArAttempt() {
        if (ENABLED) nativeScopeArAttempts++;
    }

    /** scope_body 整棵 poly_mesh 已成功登记到 TaCZ 原生 AR layer。 */
    public static void nativeScopeArAccepted() {
        if (ENABLED) nativeScopeArAccepted++;
    }

    /** AR 提交被 consumer 拒绝或抛出异常；本帧不提前直绘，后续帧熔断回旧路径。 */
    public static void nativeScopeArRejected() {
        if (ENABLED) nativeScopeArRejected++;
    }

    /** AR 拒绝或异常首次把本模型切换到稳定同步路径。 */
    public static void nativeScopeArCircuitTrip() {
        if (ENABLED) nativeScopeArCircuitTrips++;
    }

    /** 已打开的熔断在本次候选渲染中直接绕过原生 scope AR。 */
    public static void nativeScopeArCircuitBypass() {
        if (ENABLED) nativeScopeArCircuitBypasses++;
    }

    /**
     * 只给本项目枪身代码打来源标签；实际计时由 OculusCompat Mixin 统一完成。
     */
    public static boolean endGunOculusBatch(MultiBufferSource.BufferSource source) {
        return endTmlOculusBatch(source, FLUSH_ORIGIN_GUN);
    }

    /**
     * 只给本项目附件代码打来源标签；TaCZ 随后的原生刷新仍归入总计。
     */
    public static boolean endAttachmentOculusBatch(MultiBufferSource.BufferSource source) {
        return endTmlOculusBatch(source, FLUSH_ORIGIN_ATTACHMENT);
    }

    private static boolean endTmlOculusBatch(MultiBufferSource.BufferSource source, int origin) {
        if (!ENABLED) {
            return OculusCompat.endBatch(source);
        }
        int previousOrigin = flushOrigin;
        flushOrigin = origin;
        try {
            return OculusCompat.endBatch(source);
        } finally {
            flushOrigin = previousOrigin;
        }
    }

    /** 由 OculusCompat Mixin 在 Function.apply 返回前记录。 */
    public static void endTaczCompatCall(long startNs, boolean fullFlush) {
        if (!ENABLED) return;
        long elapsed = System.nanoTime() - startNs;
        TACZ_COMPAT_CALL.add(elapsed);
        if (fullFlush) {
            TACZ_COMPAT_FULL_FLUSH.add(elapsed);
        }
        if (flushOrigin == FLUSH_ORIGIN_GUN) {
            GUN_COMPAT_CALL.add(elapsed);
            if (fullFlush) gunCompatFullFlushes++;
        } else if (flushOrigin == FLUSH_ORIGIN_ATTACHMENT) {
            ATTACHMENT_COMPAT_CALL.add(elapsed);
            if (fullFlush) attachmentCompatFullFlushes++;
        }
    }

    /** 统计 TML 自己发起的指定 RenderType 刷新；Oculus FullyBuffered 下通常接近空操作。 */
    public static void endTypedBatch(MultiBufferSource.BufferSource source, RenderType renderType) {
        if (!ENABLED) {
            source.endBatch(renderType);
            return;
        }
        long startNs = System.nanoTime();
        try {
            source.endBatch(renderType);
        } finally {
            TYPED_FLUSH.add(System.nanoTime() - startNs);
        }
    }

    /**
     * 特殊倍镜路径的指定类型提交。只执行一次真实 endBatch，并把同一份 elapsed
     * 同时归入全局 typed 与特殊路径子项，避免嵌套探针把自身开销算进去。
     */
    public static void endAttachmentSpecialTypedBatch(String rootBoneName,
                                                       MultiBufferSource.BufferSource source,
                                                       RenderType renderType) {
        if (!ENABLED) {
            source.endBatch(renderType);
            return;
        }
        long startNs = System.nanoTime();
        try {
            source.endBatch(renderType);
        } finally {
            long elapsed = System.nanoTime() - startNs;
            TYPED_FLUSH.add(elapsed);
            ATTACHMENT_SPECIAL_TYPED_FLUSH.add(elapsed);
            specialRoot(rootBoneName).typedFlush.add(elapsed);
        }
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;

        long now = System.nanoTime();
        if (!announced) {
            announced = true;
            LOG.info("[MeshyPerfDiag] enabled; aggregate interval=5s; root timings are inclusive");
        }
        if (windowStartNs == 0L) {
            windowStartNs = now;
        }
        frames++;
        long windowNs = now - windowStartNs;
        if (windowNs < REPORT_INTERVAL_NS) return;

        logReport(windowNs);
        // 排除本次四行日志格式化/写盘耗时，避免污染下一个五秒窗口。
        resetWindow(System.nanoTime());
    }

    private static void logReport(long windowNs) {
        double windowSeconds = windowNs / 1_000_000_000.0;
        double measuredFps = frames / windowSeconds;

        LOG.info(
                "[MeshyPerfDiag] window={}s frames={} (~{} fps) | ROOT(inclusive) gun={} | attachment={}",
                round(windowSeconds), frames, round(measuredFps), describe(GUN_ROOT), describe(ATTACHMENT_ROOT)
        );
        for (Map.Entry<String, Timer> entry : GUN_ROOT_BY_CONTEXT.entrySet()) {
            LOG.info("[MeshyPerfDiag] GUN_ROOT_CTX {}={}", entry.getKey(), describe(entry.getValue()));
        }
        for (Map.Entry<String, Timer> entry : GUN_POLY_BY_CONTEXT.entrySet()) {
            LOG.info("[MeshyPerfDiag] GUN_POLY_CTX {}={}", entry.getKey(), describe(entry.getValue()));
        }
        LOG.info(
                "[MeshyPerfDiag] STAGE gunPolyAtTaczArStage={} | gunPolyAtTaczNormalStage={} | "
                        + "arStageAccepted={} fallback={} | attachmentNormal={} | "
                        + "attachmentSpecial={} | idleSight={}",
                describe(GUN_POLY_AR), describe(GUN_POLY_DIRECT),
                describeCount(gunArStageAcceptedConsumers), describeCount(gunArStageFallbackConsumers),
                describe(ATTACHMENT_NORMAL),
                describe(ATTACHMENT_SPECIAL), describe(ATTACHMENT_IDLE_SIGHT)
        );
        LOG.info(
                "[MeshyPerfDiag] AR build={} vertices={} | hits={} vertices={} | write={} vertices={} | "
                        + "invalidateCalls={} clearedEntries={}",
                describe(AR_BUILD), arBuildVertices, arCacheHits, arCacheHitVertices,
                describe(AR_WRITE), arWriteVertices, arInvalidations, arInvalidatedEntries
        );
        LOG.info(
                "[MeshyPerfDiag] NATIVE_SCOPE_AR attempt={} accepted={} rejected={} "
                        + "circuitTrips={} circuitBypass={}",
                describeCount(nativeScopeArAttempts), describeCount(nativeScopeArAccepted),
                describeCount(nativeScopeArRejected), describeCount(nativeScopeArCircuitTrips),
                describeCount(nativeScopeArCircuitBypasses)
        );
        LOG.info(
                "[MeshyPerfDiag] FLUSH taczCompat={} fullyBufferedCalls={} | "
                        + "withinGunRoot={} fullyBufferedCalls={} | "
                        + "withinAttachmentRoot={} fullyBufferedCalls={} | typed={}",
                describe(TACZ_COMPAT_CALL), describe(TACZ_COMPAT_FULL_FLUSH),
                describe(GUN_COMPAT_CALL), describeCount(gunCompatFullFlushes),
                describe(ATTACHMENT_COMPAT_CALL), describeCount(attachmentCompatFullFlushes),
                describe(TYPED_FLUSH)
        );
        LOG.info(
                "[MeshyPerfDiag] SPECIAL_DETAIL precheck={} hits={} | getBuffer={} | "
                        + "bonePassInclusive={} | vertexTransformWrite={} meshes={} vertices={} | "
                        + "boneTraversalExclusive={} | typedEndBatch={} | outerRemainder={}",
                describe(ATTACHMENT_SPECIAL_PRECHECK),
                describeCount(attachmentSpecialPrecheckHits),
                describe(ATTACHMENT_SPECIAL_GET_BUFFER),
                describe(ATTACHMENT_SPECIAL_BONE_PASS),
                describe(ATTACHMENT_SPECIAL_VERTEX_WRITE),
                ATTACHMENT_SPECIAL_VERTEX_WRITE.calls,
                attachmentSpecialVertices,
                describeDerived(ATTACHMENT_SPECIAL_BONE_PASS.totalNs
                        - ATTACHMENT_SPECIAL_VERTEX_WRITE.totalNs),
                describe(ATTACHMENT_SPECIAL_TYPED_FLUSH),
                describeDerived(ATTACHMENT_SPECIAL.totalNs
                        - ATTACHMENT_SPECIAL_GET_BUFFER.totalNs
                        - ATTACHMENT_SPECIAL_BONE_PASS.totalNs
                        - ATTACHMENT_SPECIAL_TYPED_FLUSH.totalNs)
        );

        for (Map.Entry<String, SpecialRootStats> entry : ATTACHMENT_SPECIAL_ROOTS.entrySet()) {
            SpecialRootStats stats = entry.getValue();
            if (stats.outer.calls == 0L) continue;
            LOG.info(
                    "[MeshyPerfDiag] SPECIAL_ROOT bone={} outer={} | getBuffer={} | "
                            + "bonePassInclusive={} | vertexTransformWrite={} meshes={} vertices={} | "
                            + "boneTraversalExclusive={} | typedEndBatch={} | outerRemainder={}",
                    entry.getKey(), describe(stats.outer), describe(stats.getBuffer),
                    describe(stats.bonePass), describe(stats.vertexWrite),
                    stats.vertexWrite.calls, stats.vertices,
                    describeDerived(stats.bonePass.totalNs - stats.vertexWrite.totalNs),
                    describe(stats.typedFlush),
                    describeDerived(stats.outer.totalNs
                            - stats.getBuffer.totalNs
                            - stats.bonePass.totalNs
                            - stats.typedFlush.totalNs)
            );
        }
    }

    private static String describe(Timer timer) {
        if (timer.calls == 0L) return "0";
        return String.format(
                Locale.ROOT,
                "%d/%.3fms(%.2f calls/f,%.3fms/f,avg %.2fus,max %.3fms)",
                timer.calls,
                timer.totalNs / 1_000_000.0,
                timer.calls / (double) frames,
                timer.totalNs / 1_000_000.0 / frames,
                timer.totalNs / 1_000.0 / timer.calls,
                timer.maxNs / 1_000_000.0
        );
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String describeCount(long count) {
        return String.format(Locale.ROOT, "%d(%.2f/f)", count, count / (double) frames);
    }

    /** 派生的 exclusive/remainder 没有独立调用次数，只报告窗口总量与每帧值。 */
    private static String describeDerived(long elapsedNs) {
        long nonNegativeNs = Math.max(0L, elapsedNs);
        return String.format(
                Locale.ROOT,
                "%.3fms(%.3fms/f)",
                nonNegativeNs / 1_000_000.0,
                nonNegativeNs / 1_000_000.0 / frames
        );
    }

    private static void record(Timer timer, long startNs) {
        if (!ENABLED) return;
        timer.add(System.nanoTime() - startNs);
    }

    private static SpecialRootStats specialRoot(String rootBoneName) {
        String safeName = rootBoneName == null || rootBoneName.isEmpty()
                ? "<unnamed>"
                : rootBoneName;
        SpecialRootStats stats = ATTACHMENT_SPECIAL_ROOTS.get(safeName);
        if (stats == null) {
            stats = new SpecialRootStats();
            ATTACHMENT_SPECIAL_ROOTS.put(safeName, stats);
        }
        return stats;
    }

    private static void resetWindow(long now) {
        GUN_ROOT.reset();
        GUN_POLY_AR.reset();
        GUN_POLY_DIRECT.reset();
        for (Timer timer : GUN_ROOT_BY_CONTEXT.values()) {
            timer.reset();
        }
        for (Timer timer : GUN_POLY_BY_CONTEXT.values()) {
            timer.reset();
        }
        ATTACHMENT_ROOT.reset();
        ATTACHMENT_NORMAL.reset();
        ATTACHMENT_SPECIAL.reset();
        ATTACHMENT_IDLE_SIGHT.reset();
        ATTACHMENT_SPECIAL_PRECHECK.reset();
        ATTACHMENT_SPECIAL_GET_BUFFER.reset();
        ATTACHMENT_SPECIAL_BONE_PASS.reset();
        ATTACHMENT_SPECIAL_VERTEX_WRITE.reset();
        ATTACHMENT_SPECIAL_TYPED_FLUSH.reset();
        for (SpecialRootStats stats : ATTACHMENT_SPECIAL_ROOTS.values()) {
            stats.reset();
        }
        AR_BUILD.reset();
        AR_WRITE.reset();
        TACZ_COMPAT_CALL.reset();
        TACZ_COMPAT_FULL_FLUSH.reset();
        GUN_COMPAT_CALL.reset();
        ATTACHMENT_COMPAT_CALL.reset();
        TYPED_FLUSH.reset();

        arBuildVertices = 0L;
        arCacheHits = 0L;
        arCacheHitVertices = 0L;
        arWriteVertices = 0L;
        arInvalidations = 0L;
        arInvalidatedEntries = 0L;
        gunArStageAcceptedConsumers = 0L;
        gunArStageFallbackConsumers = 0L;
        nativeScopeArAttempts = 0L;
        nativeScopeArAccepted = 0L;
        nativeScopeArRejected = 0L;
        nativeScopeArCircuitTrips = 0L;
        nativeScopeArCircuitBypasses = 0L;
        attachmentSpecialPrecheckHits = 0L;
        attachmentSpecialVertices = 0L;
        gunCompatFullFlushes = 0L;
        attachmentCompatFullFlushes = 0L;
        frames = 0L;
        windowStartNs = now;
    }

    private static boolean detectEnabled() {
        if (Boolean.getBoolean("dudumeshloader.perfdiag")) {
            return true;
        }

        Package ownerPackage = PerformanceDiagnostics.class.getPackage();
        String implementationVersion = ownerPackage == null ? null : ownerPackage.getImplementationVersion();
        if (containsPerfDiag(implementationVersion)) {
            return true;
        }

        try {
            return ModList.get().getModContainerById(DuDuMeshLoaderMod.MOD_ID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .map(PerformanceDiagnostics::containsPerfDiag)
                    .orElse(false);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static boolean containsPerfDiag(String version) {
        return version != null && version.toLowerCase(Locale.ROOT).contains("perfdiag");
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

    /** 按 renderTempPart 根骨骼复用的固定统计槽；首次遇到名称时才创建一次。 */
    private static final class SpecialRootStats {
        private final Timer outer = new Timer();
        private final Timer getBuffer = new Timer();
        private final Timer bonePass = new Timer();
        private final Timer vertexWrite = new Timer();
        private final Timer typedFlush = new Timer();
        private long vertices;

        private void reset() {
            outer.reset();
            getBuffer.reset();
            bonePass.reset();
            vertexWrite.reset();
            typedFlush.reset();
            vertices = 0L;
        }
    }
}
