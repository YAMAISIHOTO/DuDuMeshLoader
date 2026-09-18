package com.example.dudumeshloader.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * TacZ Mesh Loader 的客户端配置。
 *
 * <p>圆滑法线在模型构造时读取一次；修改配置后需要完整重启游戏。
 * 本模组不监听游戏内热重载，也不为该开关维护运行时切换状态。</p>
 */
public final class ClientConfig {

    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.BooleanValue SMOOTH_NORMALS;
    private static final ForgeConfigSpec.BooleanValue MESH_SINGLE_SIDED;
    private static final ForgeConfigSpec.BooleanValue MESH_SCOPE_VBO;
    private static final ForgeConfigSpec.BooleanValue MESH_DISABLE_AR_FOR_ITEM;
    private static final ForgeConfigSpec.BooleanValue MESH_SKIP_NATIVE_CUBES;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        SMOOTH_NORMALS = builder
                .comment(
                        "Whether poly_mesh uses exported per-corner normals.",
                        "When false, exported normals are not read and every triangle uses one computed face normal.",
                        "Restart the game after changing this option."
                )
                .define("smoothNormals", true);
        MESH_SINGLE_SIDED = builder
                .comment(
                        "Render poly_mesh triangles single-sided (back-face culling on).",
                        "Greatly reduces overdraw vs the old double-sided rendering.",
                        "Only disable this if your model has inconsistent triangle winding (holes when enabled).",
                        "Restart the game after changing this option."
                )
                .define("meshSingleSided", true);
        MESH_SCOPE_VBO = builder
                .comment(
                        "Render scope/sight special bones (scope_body, ocular, division...) via indexed VBO",
                        "like the gun model, instead of per-vertex CPU direct write. This removes the",
                        "per-frame CPU transform cost of large scope geometry.",
                        "Disable this if the scope looks wrong or causes rendering issues.",
                        "Restart the game after changing this option."
                )
                .define("meshScopeVbo", true);
        MESH_DISABLE_AR_FOR_ITEM = builder
                .comment(
                        "Disable AcceleratedRendering for item-frame (FIXED) and dropped-item (GROUND) contexts,",
                        "so guns in item frames / on the ground render directly like first-person instead of",
                        "going through the AR layer (which re-uploads cubes every frame for these contexts).",
                        "Restart the game after changing this option."
                )
                .define("meshDisableArForItem", true);
        MESH_SKIP_NATIVE_CUBES = builder
                .comment(
                        "Skip TaCZ's native cube rendering for guns whose every bone is covered by poly_mesh,",
                        "since the cubes are redundant low-poly copies. This skips the whole per-bone cube loop.",
                        "Only disabled if a gun shows missing parts (holes) after enabling this.",
                        "Restart the game after changing this option."
                )
                .define("meshSkipNativeCubes", true);
        SPEC = builder.build();
    }

    private ClientConfig() {
    }

    /** 返回新建模型应使用的圆滑法线状态。 */
    public static boolean smoothNormalsEnabled() {
        return SMOOTH_NORMALS.get();
    }

    /** 返回 poly_mesh 是否使用单面渲染（默认 true）。 */
    public static boolean meshSingleSided() {
        return MESH_SINGLE_SIDED.get();
    }

    /** 返回倍镜/瞄具特殊骨骼是否走索引化 VBO 直绘（默认 true）。 */
    public static boolean meshScopeVbo() {
        return MESH_SCOPE_VBO.get();
    }

    /** 返回物品展示框(FIXED)/掉落物(GROUND)是否禁用 AR（默认 true）。 */
    public static boolean meshDisableArForItem() {
        return MESH_DISABLE_AR_FOR_ITEM.get();
    }

    /** 返回是否跳过 TaCZ 原生 cube 渲染（默认 true）。 */
    public static boolean meshSkipNativeCubes() {
        return MESH_SKIP_NATIVE_CUBES.get();
    }
}
