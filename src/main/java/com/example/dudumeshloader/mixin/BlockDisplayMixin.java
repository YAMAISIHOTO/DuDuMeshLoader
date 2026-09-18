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
 * 给 TACZ 的 {@link BlockDisplay}（即 {@code display/blocks/*.json} 反序列化出的对象）注入
 * 我们扩展的渲染选项字段，使它们随 display 文件一并被 Gson 自动填充。
 *
 * <p>字段名与 JSON key 完全一致（{@code animation} / {@code isdoubleface} / {@code hide_on_close}），
 * 并额外加 {@link SerializedName} 双保险，确保无论 Gson 走字段名映射还是注解映射都能填充。</p>
 *
 * <p>为何放 display 而非 index：这三个都是纯客户端渲染表现（播哪套动画 / 模型是否双面 / 关闭时是否隐藏物品），
 * 与 {@code model}/{@code texture} 同属「怎么渲染这个方块」，本就属于 display 的职责。
 * 注意 {@code mount_bones}/{@code attachment_bones} 不在此处——它们同时是服务端实体槽位数所需的
 * 数据，必须留在 index（两端都加载）。</p>
 *
 * <p><b>跨代码读取方式</b>：Mixin 注解处理器无法用 {@code @Accessor} 读取被注入的字段（只能解析目标
 * 原有成员），接口 mixin 也会因「目标非接口」报错；且 mixin 类内禁止出现非 private 的 static 方法
 * （会被校验器在 Applicator Phase 拒绝、导致崩溃）。因此这里只在 {@code init()}（TACZ 反序列化后必调用，
 * 见 {@code DisplayManager.apply -> IDisplay.init()}）把三个字段 push 进普通工具类
 * {@link BlockDisplayOptions}，由它持有静态快照并以静态方法暴露给渲染器。</p>
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
