package com.example.dudumeshloader.mixin;

import com.example.dudumeshloader.api.IPolyMeshSmoothingConfig;
import com.tacz.guns.client.resource.pojo.display.gun.GunDisplay;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 给 TaCZ 的 {@link GunDisplay} 追加一个可选的自定义平滑角字段。
 *
 * <p>TaCZ 的 display JSON 由 Gson 按下划线命名策略解析（如 {@code model_type}、
 * {@code ammo_count_style}），所以枪包里应写作：</p>
 *
 * <pre>{@code
 * "poly_mesh_smoothing_angle": 30
 * }</pre>
 *
 * <p>字段刻意使用 {@link Float} 包装类型而非 {@code float}：未配置时保持 {@code null}，
 * 从而与「显式配置 0 度（完全分面）」区分开。若用基本类型，Gson 走 Unsafe 分配时
 * 默认值 0 会被误判成用户主动要求的分面渲染。</p>
 *
 * <p>该字段不会被 TaCZ 自己读取，仅作为本模组的扩展配置项存在。</p>
 */
@Mixin(value = GunDisplay.class, remap = false)
public class GunDisplayMixin implements IPolyMeshSmoothingConfig {

    private Float polyMeshSmoothingAngle;

    @Override
    public Float dudumeshloader$getPolyMeshSmoothingAngle() {
        return polyMeshSmoothingAngle;
    }
}
