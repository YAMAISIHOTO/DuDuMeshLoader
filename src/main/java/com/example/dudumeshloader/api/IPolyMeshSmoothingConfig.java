package com.example.dudumeshloader.api;

/**
 * Duck interface：由 mixin 注入到 TaCZ 的 display POJO 上，供本模组读取自定义平滑角。
 *
 * <p><b>为什么用接口而不是直接引用 mixin 类</b>：被 {@code @Mixin} 标注的类绝对不能被
 * 本模组其他代码直接 import 或调用其方法——Mixin 加载时会把它当成「合并进目标类的定义」，
 * 单独引用会判 {@code is invalid} 并抛 {@code NoClassDefFoundError}。因此这里定义普通
 * 接口，由 mixin 类实现它，调用方通过强制转换访问。</p>
 */
public interface IPolyMeshSmoothingConfig {

    /**
     * 自定义平滑角（度）。
     *
     * @return {@code null} 表示枪包未配置，沿用导出时的法线；
     *         {@code >= 0} 表示按该角度运行时重算逐顶点法线（{@code 0} 为完全分面）。
     */
    Float dudumeshloader$getPolyMeshSmoothingAngle();
}
