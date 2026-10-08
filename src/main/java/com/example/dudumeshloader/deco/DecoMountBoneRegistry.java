package com.example.dudumeshloader.deco;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 存放方块 index → 挂载骨骼映射。武器与配件分两套独立映射，互不影响：
 * <ul>
 *   <li><b>武器槽位</b>（默认 {@link #WEAPON_DEFAULT} = {@code weapon_mount}）：摆放 {@code IGun}，
 *       每槽可指定接受的枪类型（缺省表示任意）；</li>
 *   <li><b>配件骨骼</b>（默认 {@link #ATTACHMENT_DEFAULT} = {@code attachment_mount}）：摆放
 *       {@code IAttachment}，不做类型约束。</li>
 * </ul>
 * 每槽可指定容纳物品的模型缩放 {@code scale}（默认 {@link #DEFAULT_SCALE} = 0.5，与 TACZ statue 一致）。
 *
 * <p>index JSON 写法：
 * <pre>
 *   "weapon_mounts": [
 *     { "bone": "weapon_mount",      "type": "rifle",           "scale": 0.5 },
 *     { "bone": "weapon_mount_pist", "type": ["pistol", "smg"], "scale": 0.6 }
 *   ],
 *   "attachment_bones": [
 *     { "bone": "attachment_mount", "scale": 0.5 }
 *   ]
 * </pre>
 * {@code type} 可写单个字符串或字符串数组，缺省 / 空字符串 / 空数组均表示任意类型；{@code scale} 需
 * &gt;0。兼容旧写法 {@code mount_bones}（字符串数组）与单值 {@code mount_bone}，缺失则回退默认骨骼。
 * 注意 Blockbench 骨骼名必须唯一，多个挂载点需用不同名。</p>
 *
 * <p>由 {@code BlockIndexCollisionMixin} 在 TACZ 加载 index JSON 时填充，键为 index 的
 * <b>文件路径 key</b>（{@code <namespace>:<文件名>}，即物品 {@code BlockId}），与
 * {@link DecoCollisionRegistry} 一致；同时按 JSON 内的 {@code id} 字段兼容存一份。</p>
 */
public final class DecoMountBoneRegistry {
    public static final String WEAPON_DEFAULT = "weapon_mount";
    public static final String ATTACHMENT_DEFAULT = "attachment_mount";
    /** 物品默认缩放（与 TACZ statue 一致），槽位未指定 scale 时使用。 */
    public static final float DEFAULT_SCALE = 0.5F;

    /** 一个武器挂载槽：骨骼名 + 接受的枪类型列表（空列表 = 任意类型）+ 物品缩放比例。 */
    public static final class WeaponMountSlot {
        public final String bone;
        public final List<String> types;
        public final float scale;

        public WeaponMountSlot(String bone, List<String> types) {
            this(bone, types, DEFAULT_SCALE);
        }

        public WeaponMountSlot(String bone, List<String> types, float scale) {
            this.bone = (bone == null || bone.isEmpty()) ? WEAPON_DEFAULT : bone;
            this.types = (types == null || types.isEmpty())
                    ? List.of()
                    : types.stream()
                        .map(t -> (t == null ? "" : t.trim().toLowerCase(Locale.ROOT)))
                        .filter(t -> !t.isEmpty())
                        .collect(Collectors.toList());
            this.scale = (scale > 0) ? scale : DEFAULT_SCALE;
        }

        /** 该槽是否接受给定枪类型（空类型约束 = 任意）。大小写不敏感。支持多类型。 */
        public boolean accepts(String gunType) {
            if (types.isEmpty()) {
                return true;
            }
            return types.contains(gunType == null ? "" : gunType.toLowerCase(Locale.ROOT));
        }
    }

    /** 一个配件挂载槽：骨骼名 + 物品缩放比例（不做类型约束）。 */
    public static final class AttachmentMountSlot {
        public final String bone;
        public final float scale;

        public AttachmentMountSlot(String bone, float scale) {
            this.bone = (bone == null || bone.isEmpty()) ? ATTACHMENT_DEFAULT : bone;
            this.scale = (scale > 0) ? scale : DEFAULT_SCALE;
        }
    }

    private static final Map<ResourceLocation, List<WeaponMountSlot>> WEAPON_REGISTRY = new HashMap<>();
    private static final Map<ResourceLocation, List<AttachmentMountSlot>> ATTACHMENT_REGISTRY = new HashMap<>();

    private DecoMountBoneRegistry() {
    }

    /** 武器槽位：对象数组写法（多槽位武器架，每槽可带类型与缩放）。空/全空列表回退单槽默认骨骼。 */
    public static void putWeapon(ResourceLocation key, List<WeaponMountSlot> slots) {
        WEAPON_REGISTRY.put(key, cleanWeaponSlots(slots));
    }

    /** 配件槽位：对象数组写法（多槽位配件架，每槽可带缩放）。空/全空列表回退 {@link #ATTACHMENT_DEFAULT}。 */
    public static void putAttachment(ResourceLocation key, List<AttachmentMountSlot> slots) {
        ATTACHMENT_REGISTRY.put(key, cleanAttachmentSlots(slots));
    }

    /** 兼容入口：以字符串骨骼名列表注册配件槽（缩放一律取默认值）。 */
    public static void putAttachmentBones(ResourceLocation key, List<String> bones) {
        putAttachment(key, cleanBones(bones).stream()
                .map(b -> new AttachmentMountSlot(b, DEFAULT_SCALE))
                .collect(Collectors.toList()));
    }

    /** 返回该 index 的有序武器槽位定义（含骨骼名、类型约束与缩放），缺失则返回单槽默认列表。 */
    public static List<WeaponMountSlot> getWeaponSlots(ResourceLocation key) {
        List<WeaponMountSlot> v = key == null ? null : WEAPON_REGISTRY.get(key);
        return v != null ? v : List.of(new WeaponMountSlot(WEAPON_DEFAULT, List.of()));
    }

    /** 返回该 index 的有序武器骨骼名（按槽位顺序），供渲染器摆放；缺失则返回单元素默认列表。 */
    public static List<String> getWeaponBones(ResourceLocation key) {
        return getWeaponSlots(key).stream().map(s -> s.bone).collect(Collectors.toList());
    }

    /**
     * 返回该 index 的有序配件槽位定义（含骨骼名与缩放），缺失则返回单槽默认列表。
     */
    public static List<AttachmentMountSlot> getAttachmentSlots(ResourceLocation key) {
        List<AttachmentMountSlot> v = key == null ? null : ATTACHMENT_REGISTRY.get(key);
        return v != null ? v : List.of(new AttachmentMountSlot(ATTACHMENT_DEFAULT, DEFAULT_SCALE));
    }

    /** 返回该 index 的有序配件骨骼名列表，缺失则返回单槽默认列表。 */
    public static List<String> getAttachmentBones(ResourceLocation key) {
        return getAttachmentSlots(key).stream().map(s -> s.bone).collect(Collectors.toList());
    }

    private static List<WeaponMountSlot> cleanWeaponSlots(List<WeaponMountSlot> slots) {
        if (slots == null) {
            return new ArrayList<>(List.of(new WeaponMountSlot(WEAPON_DEFAULT, List.of())));
        }
        List<WeaponMountSlot> cleaned = slots.stream()
                .filter(s -> s != null && s.bone != null && !s.bone.isEmpty())
                .collect(Collectors.toList());
        if (cleaned.isEmpty()) {
            cleaned.add(new WeaponMountSlot(WEAPON_DEFAULT, List.of()));
        }
        return cleaned;
    }

    private static List<AttachmentMountSlot> cleanAttachmentSlots(List<AttachmentMountSlot> slots) {
        if (slots == null) {
            return new ArrayList<>(List.of(new AttachmentMountSlot(ATTACHMENT_DEFAULT, DEFAULT_SCALE)));
        }
        List<AttachmentMountSlot> cleaned = slots.stream()
                .filter(s -> s != null && s.bone != null && !s.bone.isEmpty())
                .collect(Collectors.toList());
        if (cleaned.isEmpty()) {
            cleaned.add(new AttachmentMountSlot(ATTACHMENT_DEFAULT, DEFAULT_SCALE));
        }
        return cleaned;
    }

    private static List<String> cleanBones(List<String> bones) {
        if (bones == null) {
            return new ArrayList<>(List.of(ATTACHMENT_DEFAULT));
        }
        List<String> cleaned = bones.stream()
                .filter(b -> b != null && !b.isEmpty())
                .collect(Collectors.toList());
        if (cleaned.isEmpty()) {
            cleaned.add(ATTACHMENT_DEFAULT);
        }
        return cleaned;
    }
}
