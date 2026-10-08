package com.example.dudumeshloader.mixin;

import com.example.dudumeshloader.deco.DecoCollisionRegistry;
import com.example.dudumeshloader.deco.DecoCollisionType;
import com.example.dudumeshloader.deco.DecoDisplayRegistry;
import com.example.dudumeshloader.deco.DecoMountBoneRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tacz.guns.resource.manager.JsonDataManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 在 TACZ 加载任意 index JSON 时抓取其中的方块装饰配置，写入对应注册表：
 * <ul>
 *   <li>{@code collision} → {@link DecoCollisionRegistry}；</li>
 *   <li>{@code weapon_mounts}（对象数组，每元素含 {@code bone} + 可选 {@code type}）→
 *       {@link DecoMountBoneRegistry#putWeapon}；兼容旧 {@code mount_bones} / {@code mount_bone}；</li>
 *   <li>{@code attachment_bones} / {@code attachment_bone} → {@link DecoMountBoneRegistry#putAttachment}；</li>
 *   <li>{@code display} → {@link DecoDisplayRegistry}。</li>
 * </ul>
 *
 * <p>注册表必须以 TACZ 的<b>资源文件路径 key</b>（{@code index/blocks/<文件名>.json} →
 * {@code <namespace>:<文件名>}）索引：{@link JsonDataManager#apply} 用它做 map 的 key，
 * {@code GunSmithTableItem} 也用它作为物品的 {@code BlockId}，用别的键放置时反查不到。
 * 同时额外按 JSON 内的 {@code id} 字段存一份做兼容。</p>
 *
 * <p>{@code apply} 在所有数据类型重载时都会调用，这里只对带相关字段的 JSON 生效。
 * 纯渲染表现选项（{@code animation} / {@code isdoubleface} / {@code hide_on_close}）不在此处 ——
 * 它们随 {@code display/blocks/*.json} 由 {@code BlockDisplayMixin} 注入到
 * {@link com.tacz.guns.client.resource.pojo.display.block.BlockDisplay}。</p>
 */
@Mixin(value = JsonDataManager.class, remap = false)
public class BlockIndexCollisionMixin {

    @Inject(method = "apply", at = @At("HEAD"))
    private void meshyloader$captureCollision(Map<ResourceLocation, JsonElement> pObject, ResourceManager pResourceManager, ProfilerFiller pProfiler, CallbackInfo ci) {
        try {
            for (Map.Entry<ResourceLocation, JsonElement> entry : pObject.entrySet()) {
                JsonElement json = entry.getValue();
                if (json == null || !json.isJsonObject()) {
                    continue;
                }
                JsonObject obj = json.getAsJsonObject();
                ResourceLocation fileKey = entry.getKey();

                // collision 选项
                JsonElement colElem = obj.get("collision");
                DecoCollisionType type = null;
                if (colElem != null && colElem.isJsonPrimitive()) {
                    type = DecoCollisionType.parse(colElem.getAsString());
                    DecoCollisionRegistry.put(fileKey, type);
                }

                // 武器挂载槽位：优先 weapon_mounts 对象数组（每元素含 bone + 可选 type），
                // 缺失回退旧 mount_bones 字符串数组（视为无类型），再缺失回退单 mount_bone（默认 weapon_mount）。
                List<DecoMountBoneRegistry.WeaponMountSlot> slots = new ArrayList<>();
                JsonElement wmArr = obj.get("weapon_mounts");
                if (wmArr != null && wmArr.isJsonArray()) {
                    for (JsonElement e : wmArr.getAsJsonArray()) {
                        if (e.isJsonObject()) {
                            JsonObject o = e.getAsJsonObject();
                            String bone = o.has("bone") && o.get("bone").isJsonPrimitive()
                                    ? o.get("bone").getAsString() : DecoMountBoneRegistry.WEAPON_DEFAULT;
                            // type 兼容单字符串与字符串数组：["rifle","pistol"] 或 "rifle"
                            List<String> wtypes = new ArrayList<>();
                            JsonElement tElem = o.get("type");
                            if (tElem != null) {
                                if (tElem.isJsonArray()) {
                                    for (JsonElement te : tElem.getAsJsonArray()) {
                                        if (te.isJsonPrimitive()) {
                                            wtypes.add(te.getAsString());
                                        }
                                    }
                                } else if (tElem.isJsonPrimitive()) {
                                    wtypes.add(tElem.getAsString());
                                }
                            }
                            slots.add(new DecoMountBoneRegistry.WeaponMountSlot(bone, wtypes, parseScale(o)));
                        }
                    }
                }
                if (slots.isEmpty()) {
                    List<String> legacy = new ArrayList<>();
                    JsonElement mbArr = obj.get("mount_bones");
                    if (mbArr != null && mbArr.isJsonArray()) {
                        for (JsonElement e : mbArr.getAsJsonArray()) {
                            if (e.isJsonPrimitive()) {
                                legacy.add(e.getAsString());
                            }
                        }
                    }
                    if (legacy.isEmpty()) {
                        JsonElement mbElem = obj.get("mount_bone");
                        String mb = (mbElem != null && mbElem.isJsonPrimitive())
                                ? mbElem.getAsString() : DecoMountBoneRegistry.WEAPON_DEFAULT;
                        legacy.add(mb);
                    }
                    for (String b : legacy) {
                        slots.add(new DecoMountBoneRegistry.WeaponMountSlot(b, List.of()));
                    }
                }
                DecoMountBoneRegistry.putWeapon(fileKey, slots);

                // 配件挂载骨骼：attachment_bones 数组（对象数组可带 scale；旧字符串数组取默认缩放），
                // 缺失回退单 attachment_bone
                List<DecoMountBoneRegistry.AttachmentMountSlot> attachSlots = new ArrayList<>();
                JsonElement abArr = obj.get("attachment_bones");
                if (abArr != null && abArr.isJsonArray()) {
                    for (JsonElement e : abArr.getAsJsonArray()) {
                        if (e.isJsonObject()) {
                            JsonObject o = e.getAsJsonObject();
                            String bone = o.has("bone") && o.get("bone").isJsonPrimitive()
                                    ? o.get("bone").getAsString() : DecoMountBoneRegistry.ATTACHMENT_DEFAULT;
                            attachSlots.add(new DecoMountBoneRegistry.AttachmentMountSlot(bone, parseScale(o)));
                        } else if (e.isJsonPrimitive()) {
                            attachSlots.add(new DecoMountBoneRegistry.AttachmentMountSlot(
                                    e.getAsString(), DecoMountBoneRegistry.DEFAULT_SCALE));
                        }
                    }
                }
                if (attachSlots.isEmpty()) {
                    JsonElement abElem = obj.get("attachment_bone");
                    String ab = (abElem != null && abElem.isJsonPrimitive()) ? abElem.getAsString() : DecoMountBoneRegistry.ATTACHMENT_DEFAULT;
                    attachSlots.add(new DecoMountBoneRegistry.AttachmentMountSlot(ab, DecoMountBoneRegistry.DEFAULT_SCALE));
                }
                DecoMountBoneRegistry.putAttachment(fileKey, attachSlots);

                // display 选项（指向 display/blocks/<path>.json，用于解析几何体与读取渲染选项）
                ResourceLocation disp = null;
                JsonElement dispElem = obj.get("display");
                if (dispElem != null && dispElem.isJsonPrimitive()) {
                    disp = ResourceLocation.tryParse(dispElem.getAsString());
                    if (disp != null) {
                        DecoDisplayRegistry.put(fileKey, disp);
                    }
                }

                // 兼容 key：JSON 内的 id 字段
                JsonElement idElem = obj.get("id");
                if (idElem != null && idElem.isJsonPrimitive()) {
                    ResourceLocation id = ResourceLocation.tryParse(idElem.getAsString());
                    if (id != null) {
                        if (type != null) {
                            DecoCollisionRegistry.put(id, type);
                        }
                        DecoMountBoneRegistry.putWeapon(id, slots);
                        DecoMountBoneRegistry.putAttachment(id, attachSlots);
                        if (disp != null) {
                            DecoDisplayRegistry.put(id, disp);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 解析失败时静默忽略，回退到默认档位
        }
    }

    /** 读取槽位的 {@code scale} 字段（&gt;0 才有效），非法/缺失返回默认缩放。 */
    private static float parseScale(JsonObject o) {
        JsonElement s = o.get("scale");
        if (s != null && s.isJsonPrimitive()) {
            try {
                float v = s.getAsFloat();
                if (v > 0) {
                    return v;
                }
            } catch (NumberFormatException ignored) {
                // 非法值回退默认
            }
        }
        return DecoMountBoneRegistry.DEFAULT_SCALE;
    }
}
