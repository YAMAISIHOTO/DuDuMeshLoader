package com.example.dudumeshloader.mixin;

import com.example.dudumeshloader.deco.BlockDisplayOptions;
import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.tacz.guns.client.resource.pojo.display.block.BlockDisplay;

/**
 * 给 TACZ 的 {@link BlockDisplay}（{@code display/blocks/*.json} 反序列化出的对象）注入本模组
 * 扩展的渲染选项字段，使其随 display 文件一并被 Gson 自动填充。
 *
 * <p>字段名与 JSON key 完全一致（{@code animation} / {@code isdoubleface} / {@code hide_on_close}），
 * 并加 {@link SerializedName} 双保险。这三个都是纯客户端渲染表现，与 {@code model}/{@code texture}
 * 同属 display 的职责；{@code mount_bones}/{@code attachment_bones} 是服务端实体槽位数所需的
 * 数据，必须留在 index。</p>
 *
 * <p>Mixin 注解处理器无法用 {@code @Accessor} 读取被注入的字段（只能解析目标原有成员），
 * 且 mixin 类内禁止出现非 private 的 static 方法（会被校验器拒绝）。因此这里只在
 * {@code init()}（TACZ 反序列化后必调用）把字段 push 进普通工具类 {@link BlockDisplayOptions}，
 * 由它持有静态快照并以静态方法暴露给渲染器。</p>
 */
@Mixin(value = BlockDisplay.class, remap = false)
public class BlockDisplayMixin {
    @SerializedName("animation")
    private ResourceLocation animation;

    @SerializedName("isdoubleface")
    private boolean isdoubleface;

    @SerializedName("hide_on_close")
    private boolean hide_on_close;

    @Inject(method = "init", at = @At("HEAD"))
    private void meshyloader$capture(CallbackInfo ci) {
        BlockDisplayOptions.capture((BlockDisplay) (Object) this, this.animation, this.isdoubleface, this.hide_on_close);
    }
}
