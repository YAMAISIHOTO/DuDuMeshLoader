package com.example.dudumeshloader.deco;

import java.util.Locale;
import net.minecraft.util.StringRepresentable;

/**
 * block_deco 的碰撞档位，对应 TACZ 工作台 + 一个无碰撞档位：
 * <ul>
 *   <li>{@link #SINGLE_A} —— 1×1×1（workbench_a 的碰撞）</li>
 *   <li>{@link #DOUBLE_B} —— 2×1×1 横向双块（workbench_b 的碰撞）</li>
 *   <li>{@link #DOUBLE_C} —— 1×2×1 纵向双块（workbench_c 的碰撞）</li>
 *   <li>{@link #SLAB}     —— 底部半砖碰撞（原版 slab 的碰撞）</li>
 *   <li>{@link #NONE}     —— 无碰撞（像原版草/花一样可穿过）</li>
 * </ul>
 *
 * <p>该枚举同时作为方块状态属性 {@code collision} 的值，可被方块状态机直接读取，
 * 无需再回查方块实体。</p>
 */
public enum DecoCollisionType implements StringRepresentable {
    SINGLE_A,
    DOUBLE_B,
    DOUBLE_C,
    SLAB,
    NONE;

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static DecoCollisionType parse(String s) {
        if (s == null) {
            return SINGLE_A;
        }
        switch (s.toLowerCase(Locale.ROOT)) {
            case "b":
            case "workbench_b":
            case "double_b":
            case "wide":
            case "horizontal":
                return DOUBLE_B;
            case "c":
            case "workbench_c":
            case "double_c":
            case "tall":
            case "vertical":
                return DOUBLE_C;
            case "slab":
            case "half":
            case "slab_half":
                return SLAB;
            case "null":
            case "none":
            case "no_collision":
            case "empty":
            case "grass":
                return NONE;
            case "a":
            case "workbench_a":
            case "single":
            default:
                return SINGLE_A;
        }
    }
}
