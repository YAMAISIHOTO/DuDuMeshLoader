package com.example.dudumeshloader.deco;

import com.tacz.guns.client.resource.pojo.display.block.BlockDisplay;
import net.minecraft.resources.ResourceLocation;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 承载来自 {@link BlockDisplayMixin}（display 文件扩展选项）的静态快照。
 *
 * <p>之所以单独抽成普通类而不是放在 mixin 里：Mixin 校验器禁止 mixin 类内出现
 * 「非 private 的 static 方法」（会被误判为试图 shadow 目标静态方法或产生歧义），
 * 否则整个 mixin 在 {@code MAIN Applicator Phase} 直接被拒绝、导致崩溃。
 * 把读取逻辑放到这里（普通类，不受 Mixin 规则约束），mixin 只保留注入字段 +
 * 一个 {@code @Inject(init)} 把数据 push 进来即可。</p>
 *
 * <p>以 {@link BlockDisplay} 实例为键（IdentityHashMap，按对象身份而非 equals），
 * 在 TACZ 反序列化后由 mixin 的 init 注入点填充。崩溃安全：未捕获时返回默认值
 * （animation=null / isDoubleFace=false / isHideOnClose=false），等价于旧行为。</p>
 */
public final class BlockDisplayOptions {
    private static final Map<BlockDisplay, Options> OPTIONS = new IdentityHashMap<>();

    private static final class Options {
        final ResourceLocation animation;
        final boolean isDoubleFace;
        final boolean hideOnClose;

        Options(ResourceLocation animation, boolean isDoubleFace, boolean hideOnClose) {
            this.animation = animation;
            this.isDoubleFace = isDoubleFace;
            this.hideOnClose = hideOnClose;
        }
    }

    /** 由 {@code BlockDisplayMixin} 的 init 注入点调用，快照当前 display 的扩展选项。 */
    public static void capture(BlockDisplay display, ResourceLocation animation, boolean isDoubleFace, boolean hideOnClose) {
        OPTIONS.put(display, new Options(animation, isDoubleFace, hideOnClose));
    }

    public static ResourceLocation getAnimation(BlockDisplay display) {
        Options o = OPTIONS.get(display);
        return o != null ? o.animation : null;
    }

    public static boolean isDoubleFace(BlockDisplay display) {
        Options o = OPTIONS.get(display);
        return o != null && o.isDoubleFace;
    }

    public static boolean isHideOnClose(BlockDisplay display) {
        Options o = OPTIONS.get(display);
        return o != null && o.hideOnClose;
    }
}
