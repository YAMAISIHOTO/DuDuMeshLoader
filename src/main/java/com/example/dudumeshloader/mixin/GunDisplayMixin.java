package com.example.dudumeshloader.mixin;

import com.example.dudumeshloader.api.IPolyMeshSmoothingConfig;
import com.tacz.guns.client.resource.pojo.display.gun.GunDisplay;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 给 TaCZ 的 {@link GunDisplay} 追加可选的自定义平滑角字段，枪包里写作
 * {@code "poly_mesh_smoothing_angle": 30}（TaCZ 的 display JSON 由 Gson 按下划线命名策略解析）。
 *
 * <p>刻意用 {@link Float} 包装类型：未配置时保持 {@code null}，才能与显式配置 0 度（完全分面）
 * 区分开 —— 基本类型在 Gson 走 Unsafe 分配时默认 0，会被误判成要求分面。该字段 TaCZ 自己不读，
 * 仅作为本模组的扩展配置项存在。</p>
 */
@Mixin(value = GunDisplay.class, remap = false)
public class GunDisplayMixin implements IPolyMeshSmoothingConfig {

    private Float polyMeshSmoothingAngle;

    @Override
    public Float dudumeshloader$getPolyMeshSmoothingAngle() {
        return polyMeshSmoothingAngle;
    }
}
