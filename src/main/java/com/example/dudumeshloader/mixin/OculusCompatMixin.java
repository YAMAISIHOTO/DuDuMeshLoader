package com.example.dudumeshloader.mixin;

import com.example.dudumeshloader.render.PerformanceDiagnostics;
import com.tacz.guns.compat.oculus.OculusCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Function;

/**
 * 统计经 TaCZ Oculus 兼容层发起的 FullyBuffered 全量刷新。
 *
 * <p>只包裹原有 Function.apply，不修改参数、返回值或异常传播。诊断关闭时
 * {@link PerformanceDiagnostics#begin()} 不调用 nanoTime，正式版仅保留一次轻量方法转发。</p>
 */
@Mixin(value = OculusCompat.class, remap = false)
public abstract class OculusCompatMixin {

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
            method = "endBatch(Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/function/Function;apply(Ljava/lang/Object;)Ljava/lang/Object;",
                    remap = false
            ),
            require = 1
    )
    private static Object taczMeshLoader$profileEndBatch(Function function, Object source) {
        long startNs = PerformanceDiagnostics.begin();
        boolean fullFlush = false;
        try {
            Object result = function.apply(source);
            fullFlush = Boolean.TRUE.equals(result);
            return result;
        } finally {
            PerformanceDiagnostics.endTaczCompatCall(startNs, fullFlush);
        }
    }
}
