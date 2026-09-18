package com.example.dudumeshloader.core;

import com.example.dudumeshloader.compat.ar.ARCompat;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.FastColor;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL15;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TaCZ 坐标系下的 Bedrock {@code poly_mesh} 绘制单元。
 *
 * <p>网格读取、三角化、逐角点法线与零法线回退语义复刻自
 * SimpleBedrockModel 2.5.1。与其不同的只有坐标适配：这里保留 TaCZMeshLoader
 * 已有的 {@code (+X, -Y, +Z)} 轴向，避免现有枪模被镜像。</p>
 */
public class PolyMesh {

    private static final float INV_BLOCK = 1.0f / 16.0f;
    private static final boolean FLIP_MODEL_X = false;
    private static final boolean FLIP_MODEL_Y = true;
    private static final boolean FLIP_UV_V = true;
    private static final float POSITION_EPSILON = 1.0E-6f;
    private static final float NORMAL_EPSILON_SQUARED = 1.0E-12f;
    private static final float[][] EMPTY_NORMALS = new float[0][0];

    /** 模型构造时的客户端配置快照；运行中不会逐顶点读取配置。 */
    private final boolean smoothNormals;

    private static final org.apache.logging.log4j.Logger MESH_PERF_LOG =
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoaderPerf");

    // 旧 VBO 路径继续保留；主渲染路径会优先使用 AR 缓存，失败时走 VertexConsumer。
    private final Map<Integer, VertexBuffer> vboCache = new LinkedHashMap<Integer, VertexBuffer>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, VertexBuffer> eldest) {
            if (size() > 8) {
                if (eldest.getValue() != null) {
                    eldest.getValue().close();
                }
                return true;
            }
            return false;
        }
    };

    // 三角化后按 a、b、c 顺序展开的局部顶点数据。
    private final float[] bakedX;
    private final float[] bakedY;
    private final float[] bakedZ;
    private final float[] bakedNX;
    private final float[] bakedNY;
    private final float[] bakedNZ;
    private final float[] bakedU;
    private final float[] bakedV;
    private final int vertexCount;

    /** 顶点去重后的局部顶点数据（索引化的 VBO 用），及其索引表。 */
    private float[] indexedX;
    private float[] indexedY;
    private float[] indexedZ;
    private float[] indexedNX;
    private float[] indexedNY;
    private float[] indexedNZ;
    private float[] indexedU;
    private float[] indexedV;
    private int[] indices;
    private int indexedVertexCount;

    /** 索引化 VBO：复用 MC VertexBuffer 的 VAO/attribute，另挂一个自定义 IBO。 */
    private VertexBuffer indexedVbo;
    private int indexedIboId = -1;

    /** AR 模组未加载时保持 null；资源若早于 AR 初始化，可在首次绘制时延迟创建。 */
    private Object acceleratedRenderer;

    public PolyMesh(JsonObject meshObj, float texWidth, float texHeight, float[] absPivot) {
        this(meshObj, texWidth, texHeight, absPivot, true);
    }

    public PolyMesh(JsonObject meshObj, float texWidth, float texHeight, float[] absPivot,
                    boolean smoothNormals) {
        this(meshObj, texWidth, texHeight, absPivot, smoothNormals, -1f);
    }

    /**
     * @param smoothingAngleDeg 自定义平滑角（度）。{@code < 0} 表示未设置，保持现状
     *                          （smoothNormals 开启时读导出的 normals，关闭时用面法线）；
     *                          {@code >= 0} 时忽略导出法线，按角度阈值在运行时重算逐顶点法线，
     *                          等价于 Blockbench 导出插件里的 smoothing angle。
     */
    public PolyMesh(JsonObject meshObj, float texWidth, float texHeight, float[] absPivot,
                    boolean smoothNormals, float smoothingAngleDeg) {
        this.smoothNormals = smoothNormals;
        float pivotX = absPivot[0];
        float pivotY = absPivot[1];
        float pivotZ = absPivot[2];
        boolean normalizedUvs = meshObj.has("normalized_uvs")
                && meshObj.get("normalized_uvs").getAsBoolean();

        float[][] positions = parse2DArray(meshObj.get("positions"), 3);
        // 分面模式完全不访问导出法线 JSON；面法线稍后由三角形位置统一计算。
        float[][] normals = smoothNormals
                ? parse2DArray(meshObj.get("normals"), 3)
                : EMPTY_NORMALS;
        float[][] uvs = parse2DArray(meshObj.get("uvs"), 2);
        JsonElement polys = meshObj.get("polys");

        // TRIANGLES 渲染 + 退化 quad 检测：Bedrock 导出的"四边形"绝大多数是
        // 三角形补成的退化 quad（第 4 角 == 第 1 角）。识别后退化 quad 只输出
        // 3 顶点（真实三角形），真四边形才拆成 2 个三角形。对 Blockbench 默认
        // 导出的纯三角形枪模，顶点数比 QUADS 再省 25%（4 顶点/面 → 3 顶点/面）。
        List<Vertex> triangles = new ArrayList<>();
        if (positions.length > 0 && polys != null && !polys.isJsonNull()) {
            bakePolys(normalizedUvs, texWidth, texHeight, pivotX, pivotY, pivotZ,
                    positions, normals, uvs, polys, triangles);
        }

        // 自定义平滑角：以角度阈值重算逐顶点法线（覆盖导出的 normals）。
        if (smoothingAngleDeg >= 0f) {
            recomputeSmoothNormals(triangles, smoothingAngleDeg);
        }

        this.vertexCount = triangles.size();
        this.bakedX = new float[vertexCount];
        this.bakedY = new float[vertexCount];
        this.bakedZ = new float[vertexCount];
        this.bakedNX = new float[vertexCount];
        this.bakedNY = new float[vertexCount];
        this.bakedNZ = new float[vertexCount];
        this.bakedU = new float[vertexCount];
        this.bakedV = new float[vertexCount];

        int index = 0;
        for (Vertex vertex : triangles) {
            bakedX[index] = vertex.x;
            bakedY[index] = vertex.y;
            bakedZ[index] = vertex.z;
            bakedNX[index] = vertex.nx;
            bakedNY[index] = vertex.ny;
            bakedNZ[index] = vertex.nz;
            bakedU[index] = vertex.u;
            bakedV[index] = vertex.v;
            index++;
        }

        this.acceleratedRenderer = ARCompat.createRenderer(this);

        // 顶点去重：为索引化 VBO 建立去重顶点数组 + 索引表。
        buildIndexedData();
    }

    /**
     * 对烘焙后的三角形顶点按 (position, uv, normal) 精确去重，建立索引表。
     *
     * <p>smooth normals 下，相邻面在共享边上的顶点拥有相同的 position/uv/normal，
     * 可被合并。去重后 GPU 顶点着色器只处理 unique 顶点，draw 数量由索引表维持，
     * 从而显著降低顶点变换开销（实测约省 2/3）。</p>
     */
    private void buildIndexedData() {
        Map<VertexKey, Integer> dedup = new HashMap<>(vertexCount);
        float[] ix = new float[vertexCount];
        float[] iy = new float[vertexCount];
        float[] iz = new float[vertexCount];
        float[] inx = new float[vertexCount];
        float[] iny = new float[vertexCount];
        float[] inz = new float[vertexCount];
        float[] iu = new float[vertexCount];
        float[] iv = new float[vertexCount];
        int[] idx = new int[vertexCount];
        int unique = 0;
        for (int i = 0; i < vertexCount; i++) {
            VertexKey key = new VertexKey(
                    Float.floatToIntBits(bakedX[i]),
                    Float.floatToIntBits(bakedY[i]),
                    Float.floatToIntBits(bakedZ[i]),
                    Float.floatToIntBits(bakedU[i]),
                    Float.floatToIntBits(bakedV[i]),
                    Float.floatToIntBits(bakedNX[i]),
                    Float.floatToIntBits(bakedNY[i]),
                    Float.floatToIntBits(bakedNZ[i]));
            Integer existing = dedup.get(key);
            if (existing == null) {
                existing = unique;
                dedup.put(key, existing);
                ix[unique] = bakedX[i];
                iy[unique] = bakedY[i];
                iz[unique] = bakedZ[i];
                inx[unique] = bakedNX[i];
                iny[unique] = bakedNY[i];
                inz[unique] = bakedNZ[i];
                iu[unique] = bakedU[i];
                iv[unique] = bakedV[i];
                unique++;
            }
            idx[i] = existing;
        }
        this.indexedVertexCount = unique;
        this.indexedX = java.util.Arrays.copyOf(ix, unique);
        this.indexedY = java.util.Arrays.copyOf(iy, unique);
        this.indexedZ = java.util.Arrays.copyOf(iz, unique);
        this.indexedNX = java.util.Arrays.copyOf(inx, unique);
        this.indexedNY = java.util.Arrays.copyOf(iny, unique);
        this.indexedNZ = java.util.Arrays.copyOf(inz, unique);
        this.indexedU = java.util.Arrays.copyOf(iu, unique);
        this.indexedV = java.util.Arrays.copyOf(iv, unique);
        this.indices = idx;

        if (unique < vertexCount) {
            MESH_PERF_LOG.info("[MeshyPerf] Indexed dedup: {} -> {} vertices ({} indices)",
                    vertexCount, unique, vertexCount);
        }
    }

    /** 顶点去重键：按 8 个分量（position+uv+normal）的精确位模式比较。 */
    private record VertexKey(int x, int y, int z, int u, int v, int nx, int ny, int nz) {
    }

    // =========================================================================
    // SimpleBedrockModel 2.5.1 poly_mesh 读取与三角化语义
    // =========================================================================

    private void bakePolys(boolean normalizedUvs, float texWidth, float texHeight,
                           float pivotX, float pivotY, float pivotZ,
                           float[][] positions, float[][] normals, float[][] uvs,
                           JsonElement polys, List<Vertex> triangles) {
        if (polys.isJsonPrimitive()) {
            JsonPrimitive primitive = polys.getAsJsonPrimitive();
            if (!primitive.isString()) {
                return;
            }
            String mode = primitive.getAsString();
            if ("tri_list".equals(mode)) {
                bakeListMode(normalizedUvs, texWidth, texHeight, pivotX, pivotY, pivotZ,
                        positions, normals, uvs, triangles, 3);
            } else if ("quad_list".equals(mode)) {
                bakeListMode(normalizedUvs, texWidth, texHeight, pivotX, pivotY, pivotZ,
                        positions, normals, uvs, triangles, 4);
            }
            return;
        }

        if (!polys.isJsonArray()) {
            return;
        }
        for (JsonElement polygonElement : polys.getAsJsonArray()) {
            if (polygonElement != null && polygonElement.isJsonArray()) {
                bakeIndexedPolygon(normalizedUvs, texWidth, texHeight, pivotX, pivotY, pivotZ,
                        positions, normals, uvs, polygonElement.getAsJsonArray(), triangles);
            }
        }
    }

    private void bakeListMode(boolean normalizedUvs, float texWidth, float texHeight,
                              float pivotX, float pivotY, float pivotZ,
                              float[][] positions, float[][] normals, float[][] uvs,
                              List<Vertex> triangles, int stride) {
        for (int i = 0; i + stride - 1 < positions.length; i += stride) {
            List<Vertex> polygon = new ArrayList<>(stride);
            for (int j = 0; j < stride; j++) {
                int index = i + j;
                int normalIndex = smoothNormals ? index : -1;
                polygon.add(createVertex(normalizedUvs, texWidth, texHeight,
                        pivotX, pivotY, pivotZ, positions, normals, uvs,
                        index, normalIndex, index));
            }
            bakePolygon(polygon, triangles);
        }
    }

    private void bakeIndexedPolygon(boolean normalizedUvs, float texWidth, float texHeight,
                                    float pivotX, float pivotY, float pivotZ,
                                    float[][] positions, float[][] normals, float[][] uvs,
                                    JsonArray polygonArray, List<Vertex> triangles) {
        List<Vertex> polygon = new ArrayList<>(polygonArray.size());
        for (JsonElement vertexElement : polygonArray) {
            if (vertexElement == null || !vertexElement.isJsonArray()) {
                continue;
            }
            JsonArray indices = vertexElement.getAsJsonArray();
            if (indices.isEmpty()) {
                continue;
            }
            int positionIndex = indices.get(0).getAsInt();
            // 关闭平滑时不读取槽位 1；UV 仍固定使用槽位 2，不能前移。
            int normalIndex = smoothNormals
                    ? (indices.size() > 1 ? indices.get(1).getAsInt() : positionIndex)
                    : -1;
            int uvIndex = indices.size() > 2 ? indices.get(2).getAsInt() : positionIndex;
            polygon.add(createVertex(normalizedUvs, texWidth, texHeight,
                    pivotX, pivotY, pivotZ, positions, normals, uvs,
                    positionIndex, normalIndex, uvIndex));
        }

        // Bedrock 导出器可能在末尾再次写入首点，用坐标而不是索引判断闭合点。
        if (polygon.size() > 1 && samePosition(polygon.get(0), polygon.get(polygon.size() - 1))) {
            polygon.remove(polygon.size() - 1);
        }
        bakePolygon(polygon, triangles);
    }

    private Vertex createVertex(boolean normalizedUvs, float texWidth, float texHeight,
                                float pivotX, float pivotY, float pivotZ,
                                float[][] positions, float[][] normals, float[][] uvs,
                                int positionIndex, int normalIndex, int uvIndex) {
        float[] position = getArray(positions, positionIndex);
        float x = transformX(get(position, 0), pivotX);
        float y = transformY(get(position, 1), pivotY);
        float z = transformZ(get(position, 2), pivotZ);

        float nx = 0.0f;
        float ny = 0.0f;
        float nz = 0.0f;
        if (normalIndex >= 0 && normalIndex < normals.length) {
            float[] normal = normals[normalIndex];
            nx = FLIP_MODEL_X ? -get(normal, 0) : get(normal, 0);
            ny = FLIP_MODEL_Y ? -get(normal, 1) : get(normal, 1);
            nz = get(normal, 2);
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length > POSITION_EPSILON) {
                nx /= length;
                ny /= length;
                nz /= length;
            }
        }

        float u = 0.0f;
        float v = 0.0f;
        if (uvIndex >= 0 && uvIndex < uvs.length) {
            float[] uv = uvs[uvIndex];
            u = get(uv, 0);
            v = get(uv, 1);
            if (!normalizedUvs) {
                u = texWidth == 0.0f ? 0.0f : u / texWidth;
                v = texHeight == 0.0f ? 0.0f : v / texHeight;
            }
            if (FLIP_UV_V) {
                v = 1.0f - v;
            }
        }
        return new Vertex(x, y, z, u, v, nx, ny, nz);
    }

    /**
     * 按自定义平滑角重算逐顶点法线（运行时版 smoothing angle）。
     *
     * <p>逻辑与 Blockbench 导出插件一致：以当前三角面的面法线为基准，只累加与它夹角
     * 在阈值内的共享该位置的相邻面法线，再归一化。角度越小 → 能参与混合的面越少 →
     * 硬边越多；{@code 0} 度等价于完全分面（flat shading）。</p>
     *
     * <p>按量化后的位置判定顶点共享，容忍浮点误差。法线由 {@link Vertex#withNormal}
     * 生成新记录写入，不破坏已有的去重/索引化流程。</p>
     */
    private static void recomputeSmoothNormals(List<Vertex> triangles, float angleDeg) {
        int count = triangles.size();
        int triCount = count / 3;
        if (triCount == 0) return;

        // 1. 每个三角形的面法线
        float[] fx = new float[triCount];
        float[] fy = new float[triCount];
        float[] fz = new float[triCount];
        for (int t = 0; t < triCount; t++) {
            Vertex a = triangles.get(t * 3);
            Vertex b = triangles.get(t * 3 + 1);
            Vertex c = triangles.get(t * 3 + 2);
            float ux = b.x - a.x, uy = b.y - a.y, uz = b.z - a.z;
            float vx = c.x - a.x, vy = c.y - a.y, vz = c.z - a.z;
            float nx = uy * vz - uz * vy;
            float ny = uz * vx - ux * vz;
            float nz = ux * vy - uy * vx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1e-8f) {
                fx[t] = nx / len;
                fy[t] = ny / len;
                fz[t] = nz / len;
            }
        }

        // 2. 位置 -> 共享该位置的三角形下标
        Map<String, List<Integer>> posToTris = new HashMap<>();
        for (int i = 0; i < count; i++) {
            Vertex v = triangles.get(i);
            posToTris.computeIfAbsent(vertexPosKey(v.x, v.y, v.z), k -> new ArrayList<>()).add(i / 3);
        }

        // 3. 逐角点混合
        float cosThreshold = (float) Math.cos(Math.toRadians(angleDeg));
        for (int i = 0; i < count; i++) {
            Vertex v = triangles.get(i);
            int base = i / 3;
            float bx = fx[base], by = fy[base], bz = fz[base];
            List<Integer> neighbours = posToTris.get(vertexPosKey(v.x, v.y, v.z));
            float sx = 0f, sy = 0f, sz = 0f;
            if (neighbours != null) {
                for (int t : neighbours) {
                    if (fx[t] * bx + fy[t] * by + fz[t] * bz < cosThreshold) continue;
                    sx += fx[t];
                    sy += fy[t];
                    sz += fz[t];
                }
            }
            float len = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
            Vector3f n = len > 1e-8f
                    ? new Vector3f(sx / len, sy / len, sz / len)
                    : new Vector3f(bx, by, bz);
            triangles.set(i, v.withNormal(n));
        }
    }

    /** 量化后的顶点位置键，用于判定顶点共享（容忍浮点误差）。 */
    private static String vertexPosKey(float x, float y, float z) {
        return Math.round(x * 10000) + "|" + Math.round(y * 10000) + "|" + Math.round(z * 10000);
    }

    private void bakePolygon(List<Vertex> polygon, List<Vertex> triangles) {
        if (polygon.size() < 3) {
            return;
        }
        if (polygon.size() == 3) {
            bakeTriangle(polygon.get(0), polygon.get(1), polygon.get(2), triangles);
            return;
        }
        if (polygon.size() == 4) {
            // 退化 quad 检测：第 4 角与第 1 角位置相同 → 实为三角形（Bedrock 导出的
            // 纯三角形网格都会补成 4 角）。识别后只输出 3 顶点，避免 QUADS 的 25%
            // 冗余；真四边形才拆成 2 个三角形。
            if (samePosition(polygon.get(0), polygon.get(3))) {
                bakeTriangle(polygon.get(0), polygon.get(1), polygon.get(2), triangles);
            } else {
                bakeTriangle(polygon.get(0), polygon.get(1), polygon.get(2), triangles);
                bakeTriangle(polygon.get(2), polygon.get(3), polygon.get(0), triangles);
            }
            return;
        }
        // 多边形（>4 边）：扇形三角化
        for (int i = 1; i + 1 < polygon.size(); i++) {
            bakeTriangle(polygon.get(0), polygon.get(i), polygon.get(i + 1), triangles);
        }
    }

    private void bakeTriangle(Vertex a, Vertex b, Vertex c, List<Vertex> triangles) {
        if (samePosition(a, b) || samePosition(b, c) || samePosition(c, a)) {
            return;
        }
        Vector3f fallbackNormal = computeNormal(a, b, c);
        triangles.add(applyConfiguredNormal(a, fallbackNormal));
        triangles.add(applyConfiguredNormal(b, fallbackNormal));
        triangles.add(applyConfiguredNormal(c, fallbackNormal));
    }

    /** 开启时保留有效导出法线；关闭时强制使用该三角形统一的面法线。 */
    private Vertex applyConfiguredNormal(Vertex vertex, Vector3f faceNormal) {
        return smoothNormals
                ? vertex.withFallbackNormal(faceNormal)
                : vertex.withNormal(faceNormal);
    }

    private static Vector3f computeNormal(Vertex a, Vertex b, Vertex c) {
        Vector3f edge1 = new Vector3f(b.x - a.x, b.y - a.y, b.z - a.z);
        Vector3f edge2 = new Vector3f(c.x - a.x, c.y - a.y, c.z - a.z);
        Vector3f normal = edge1.cross(edge2, new Vector3f());
        if (normal.lengthSquared() < NORMAL_EPSILON_SQUARED) {
            normal.set(0.0f, 1.0f, 0.0f);
        } else {
            normal.normalize();
        }
        return normal;
    }

    private static boolean samePosition(Vertex first, Vertex second) {
        return Math.abs(first.x - second.x) < POSITION_EPSILON
                && Math.abs(first.y - second.y) < POSITION_EPSILON
                && Math.abs(first.z - second.z) < POSITION_EPSILON;
    }

    // =========================================================================
    // AR / VertexConsumer 主路径
    // =========================================================================

    public void compileConsumer(PoseStack.Pose pose, VertexConsumer consumer,
                                int lightmap, int overlay,
                                float red, float green, float blue, float alpha) {
        if (tryCompileConsumerAccelerated(
                pose, consumer, lightmap, overlay, red, green, blue, alpha)) {
            return;
        }
        compileConsumerDirect(pose, consumer, lightmap, overlay, red, green, blue, alpha);
    }

    /**
     * 只尝试把当前 mesh 登记到 AR，不进行同步 VertexConsumer 回退。
     *
     * <p>TaCZ 的倍镜 AR layer 会把 stencil before 回调延迟到真正绘制前执行；
     * 因此收集 {@code scope_body} 时若 AR 拒绝，调用方不能在当前位置直接写顶点，
     * 否则会在模板尚未建立时画出镜体。普通调用仍应使用
     * {@link #compileConsumer}，其既有自动回退语义保持不变。</p>
     *
     * @return 空 mesh 或成功交给 AR 时返回 true；AR 不可用或拒绝 consumer 时返回 false
     */
    public boolean tryCompileConsumerAccelerated(PoseStack.Pose pose, VertexConsumer consumer,
                                                  int lightmap, int overlay,
                                                  float red, float green, float blue, float alpha) {
        if (vertexCount == 0) {
            return true;
        }
        if (!ARCompat.isLoaded()) {
            return false;
        }
        if (acceleratedRenderer == null) {
            acceleratedRenderer = ARCompat.createRenderer(this);
        }
        int color = FastColor.ARGB32.color(
                (int) (alpha * 255.0f),
                (int) (red * 255.0f),
                (int) (green * 255.0f),
                (int) (blue * 255.0f)
        );
        return ARCompat.render(
                acceleratedRenderer, consumer, pose.pose(), pose.normal(),
                lightmap, overlay, color);
    }

    /**
     * 不进入 AR 判断的三角形写入路径。AR 首次建立局部网格缓存时也调用这里，
     * 避免 {@link #compileConsumer} 再次进入 AR 而递归。
     */
    public void compileConsumerDirect(PoseStack.Pose pose, VertexConsumer consumer,
                                      int lightmap, int overlay,
                                      float red, float green, float blue, float alpha) {
        compileConsumerDirect(pose, consumer, lightmap, overlay, red, green, blue, alpha, false);
    }

    /**
     * 仅供 TaCZ FunctionalRenderer / 倍镜 renderTempPart 已提供 QUADS consumer 的兼容入口。
     * 主体 poly_mesh 数据是三角形（3 顶点/面），此入口把每个三角形补成 a/b/c/c，
     * 以匹配 QUADS 图层，保留 additional_magazine 的既有直接绘制语义。
     */
    public void compileConsumerQuadsDirect(PoseStack.Pose pose, VertexConsumer consumer,
                                           int lightmap, int overlay,
                                           float red, float green, float blue, float alpha) {
        compileConsumerDirect(pose, consumer, lightmap, overlay, red, green, blue, alpha, true);
    }

    private void compileConsumerDirect(PoseStack.Pose pose, VertexConsumer consumer,
                                       int lightmap, int overlay,
                                       float red, float green, float blue, float alpha,
                                       boolean padTrianglesToQuads) {
        if (vertexCount == 0) {
            return;
        }
        Matrix4f positionMatrix = pose.pose();
        Matrix3f normalMatrix = pose.normal();
        // PoseStack 的模型矩阵属于仿射变换；使用 transformPosition 只计算 xyz，
        // 避免通用四维入口每个顶点都额外计算不会被消费的 w。局部对象不跨调用共享，
        // 保持重入安全。
        Vector3f transformedPosition = new Vector3f();
        if (!smoothNormals) {
            compileFlatConsumerDirect(
                    consumer, positionMatrix, normalMatrix, transformedPosition,
                    lightmap, overlay, red, green, blue, alpha, padTrianglesToQuads);
            return;
        }
        if (padTrianglesToQuads) {
            // TaCZ FunctionalRenderer / 倍镜 renderTempPart 的 QUADS consumer：
            // 每个三角形补成 a/b/c/c（第 4 顶点复用第 3 顶点）。
            for (int i = 0; i < vertexCount; i += 3) {
                emitTransformed(consumer, positionMatrix, normalMatrix, transformedPosition, i,
                        lightmap, overlay, red, green, blue, alpha);
                emitTransformed(consumer, positionMatrix, normalMatrix, transformedPosition, i + 1,
                        lightmap, overlay, red, green, blue, alpha);
                emitTransformed(consumer, positionMatrix, normalMatrix, transformedPosition, i + 2,
                        lightmap, overlay, red, green, blue, alpha);
                emitTransformed(consumer, positionMatrix, normalMatrix, transformedPosition, i + 2,
                        lightmap, overlay, red, green, blue, alpha);
            }
            return;
        }
        // smooth：数据已是三角形（3 顶点/面），逐顶点直写
        for (int i = 0; i < vertexCount; i++) {
            emitTransformed(consumer, positionMatrix, normalMatrix, transformedPosition, i,
                    lightmap, overlay, red, green, blue, alpha);
        }
    }

    /**
     * 分面法线专用直写：同一三角形的三个角点共享面法线，因此每面只做一次
     * normal matrix 变换与归一化（数据已是三角形，3 顶点/面）。
     */
    private void compileFlatConsumerDirect(VertexConsumer consumer,
                                           Matrix4f positionMatrix, Matrix3f normalMatrix,
                                           Vector3f transformedPosition,
                                           int lightmap, int overlay,
                                           float red, float green, float blue, float alpha,
                                           boolean padTrianglesToQuads) {
        for (int i = 0; i < vertexCount; i += 3) {
            float nx = normalMatrix.m00() * bakedNX[i]
                    + normalMatrix.m10() * bakedNY[i]
                    + normalMatrix.m20() * bakedNZ[i];
            float ny = normalMatrix.m01() * bakedNX[i]
                    + normalMatrix.m11() * bakedNY[i]
                    + normalMatrix.m21() * bakedNZ[i];
            float nz = normalMatrix.m02() * bakedNX[i]
                    + normalMatrix.m12() * bakedNY[i]
                    + normalMatrix.m22() * bakedNZ[i];
            float lengthSquared = nx * nx + ny * ny + nz * nz;
            if (lengthSquared > NORMAL_EPSILON_SQUARED) {
                float inverseLength = (float) (1.0 / Math.sqrt(lengthSquared));
                nx *= inverseLength;
                ny *= inverseLength;
                nz *= inverseLength;
            }

            emitFlatTransformedPosition(
                    consumer, positionMatrix, transformedPosition, i,
                    lightmap, overlay, red, green, blue, alpha, nx, ny, nz);
            emitFlatTransformedPosition(
                    consumer, positionMatrix, transformedPosition, i + 1,
                    lightmap, overlay, red, green, blue, alpha, nx, ny, nz);
            emitFlatTransformedPosition(
                    consumer, positionMatrix, transformedPosition, i + 2,
                    lightmap, overlay, red, green, blue, alpha, nx, ny, nz);
            if (padTrianglesToQuads) {
                // QUADS 图层：补第 4 顶点（复用第 3 顶点位置，transformedPosition 已是 i+2 变换结果）
                emitFlatVertex(
                        consumer, transformedPosition, i + 2,
                        lightmap, overlay, red, green, blue, alpha, nx, ny, nz);
            }
        }
    }

    private void emitFlatTransformedPosition(VertexConsumer consumer, Matrix4f positionMatrix,
                                             Vector3f transformedPosition, int index,
                                             int lightmap, int overlay,
                                             float red, float green, float blue, float alpha,
                                             float nx, float ny, float nz) {
        positionMatrix.transformPosition(
                bakedX[index], bakedY[index], bakedZ[index],
                transformedPosition
        );
        emitFlatVertex(
                consumer, transformedPosition, index,
                lightmap, overlay, red, green, blue, alpha, nx, ny, nz);
    }

    private void emitFlatVertex(VertexConsumer consumer, Vector3f transformedPosition, int index,
                                int lightmap, int overlay,
                                float red, float green, float blue, float alpha,
                                float nx, float ny, float nz) {
        consumer.vertex(
                transformedPosition.x(), transformedPosition.y(), transformedPosition.z(),
                red, green, blue, alpha,
                bakedU[index], bakedV[index],
                overlay, lightmap,
                nx, ny, nz
        );
    }

    private void emitTransformed(VertexConsumer consumer, Matrix4f positionMatrix, Matrix3f normalMatrix,
                                  Vector3f transformedPosition,
                                 int index, int lightmap, int overlay,
                                 float red, float green, float blue, float alpha) {
        // 模型矩阵不包含投影，transformPosition 与原先 w=1 的结果等价；
        // 目标对象仍在整次 mesh 写入内复用，避免逐顶点分配。
        positionMatrix.transformPosition(
                bakedX[index], bakedY[index], bakedZ[index],
                transformedPosition
        );

        float nx = normalMatrix.m00() * bakedNX[index]
                + normalMatrix.m10() * bakedNY[index]
                + normalMatrix.m20() * bakedNZ[index];
        float ny = normalMatrix.m01() * bakedNX[index]
                + normalMatrix.m11() * bakedNY[index]
                + normalMatrix.m21() * bakedNZ[index];
        float nz = normalMatrix.m02() * bakedNX[index]
                + normalMatrix.m12() * bakedNY[index]
                + normalMatrix.m22() * bakedNZ[index];
        float lengthSquared = nx * nx + ny * ny + nz * nz;
        if (lengthSquared > NORMAL_EPSILON_SQUARED) {
            float inverseLength = (float) (1.0 / Math.sqrt(lengthSquared));
            nx *= inverseLength;
            ny *= inverseLength;
            nz *= inverseLength;
        }

        // 使用 Minecraft 的完整顶点入口：BufferBuilder 可直接命中 NEW_ENTITY
        // fastFormat；未重写该入口的包装 consumer 会自动退回接口默认的逐字段语义。
        consumer.vertex(
                transformedPosition.x(), transformedPosition.y(), transformedPosition.z(),
                red, green, blue, alpha,
                bakedU[index], bakedV[index],
                overlay, lightmap,
                nx, ny, nz
        );
    }

    public void compile(PoseStack.Pose pose, VertexConsumer consumer,
                        int lightmap, int overlay,
                        float red, float green, float blue, float alpha) {
        compileConsumer(pose, consumer, lightmap, overlay, red, green, blue, alpha);
    }

    // =========================================================================
    // 旧 VBO 管理（当前默认关闭，但保持格式与三角管线一致）
    // =========================================================================

    public void ensureUploaded(int packedLight) {
        if (vertexCount == 0 || vboCache.containsKey(packedLight)) {
            return;
        }
        long start = System.nanoTime();
        VertexBuffer vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        BufferBuilder builder = new BufferBuilder(vertexCount * DefaultVertexFormat.NEW_ENTITY.getVertexSize());
        builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);

        for (int i = 0; i < vertexCount; i++) {
            builder.vertex(bakedX[i], bakedY[i], bakedZ[i])
                    .color(1.0f, 1.0f, 1.0f, 1.0f)
                    .uv(bakedU[i], bakedV[i])
                    .overlayCoords(OverlayTexture.NO_OVERLAY)
                    .uv2(packedLight)
                    .normal(bakedNX[i], bakedNY[i], bakedNZ[i])
                    .endVertex();
        }

        vertexBuffer.bind();
        vertexBuffer.upload(builder.end());
        VertexBuffer.unbind();
        vboCache.put(packedLight, vertexBuffer);

        double milliseconds = (System.nanoTime() - start) / 1_000_000.0;
        MESH_PERF_LOG.info("[MeshyPerf] VBO upload: light={} vertexCount={} cacheSizeAfter={} tookMs={} screenOpen={}",
                packedLight, vertexCount, vboCache.size(), String.format("%.3f", milliseconds),
                net.minecraft.client.Minecraft.getInstance().screen != null);
    }

    public void drawVBO(Matrix4f posePose, int packedLight) {
        VertexBuffer vbo = vboCache.get(packedLight);
        if (vbo == null) {
            return;
        }
        vbo.bind();
        vbo.drawWithShader(posePose, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
        VertexBuffer.unbind();
    }

    // =========================================================================
    // 索引化 VBO（顶点去重 + 自定义 IBO + glDrawElements）
    // =========================================================================

    /** 反射句柄：向 MC VertexBuffer 注入自定义索引缓冲。生产环境（reobf 后）字段名是 srg。 */
    private static final Field FIELD_INDEX_BUFFER_ID = reflectField("indexBufferId", "f_166860_");
    private static final Field FIELD_SEQUENTIAL_INDICES = reflectField("sequentialIndices", "f_166865_");
    private static final Field FIELD_INDEX_COUNT = reflectField("indexCount", "f_166863_");
    private static final Field FIELD_INDEX_TYPE = reflectField("indexType", "f_166861_");

    /**
     * 先尝试生产环境（reobf 后）的 srg 名（{@code ObfuscationReflectionHelper.findField} 在当前
     * ForgeGradle 不会自动映射 Mojmap 字符串），失败回退 dev（runClient，Mojmap）名。
     */
    private static Field reflectField(String mojmapName, String srgName) {
        try {
            return net.minecraftforge.fml.util.ObfuscationReflectionHelper.findField(
                    VertexBuffer.class, srgName);
        } catch (Exception srgFailed) {
            return net.minecraftforge.fml.util.ObfuscationReflectionHelper.findField(
                    VertexBuffer.class, mojmapName);
        }
    }

    private static void setFieldInt(Field field, Object target, int value) {
        try {
            field.setInt(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to set int field " + field.getName(), e);
        }
    }

    private static void setFieldObject(Field field, Object target, Object value) {
        try {
            field.set(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to set field " + field.getName(), e);
        }
    }

    /**
     * 上传索引化网格：去重顶点进 VBO，自定义索引进 IBO。
     *
     * <p>复用 MC 的 VertexBuffer（VAO + {@code VertexFormat.setupBufferState()} 的
     * attribute 布局），因此 Iris 的光影 shader 仍按相同 attribute index 正确映射，
     * 无需额外兼容层。上传后反射注入 {@code indexBufferId}/{@code sequentialIndices=null}
     * 使 {@code draw()} 走 {@code glDrawElements} 而非顺序索引。</p>
     */
    public void ensureIndexedUploaded(int packedLight) {
        if (indexedVertexCount == 0 || indexedVbo != null || indexedVertexCount >= vertexCount) {
            return;
        }
        long start = System.nanoTime();
        try {
            VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
            BufferBuilder builder = new BufferBuilder(
                    indexedVertexCount * DefaultVertexFormat.NEW_ENTITY.getVertexSize());
            builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
            for (int i = 0; i < indexedVertexCount; i++) {
                builder.vertex(indexedX[i], indexedY[i], indexedZ[i])
                        .color(1.0f, 1.0f, 1.0f, 1.0f)
                        .uv(indexedU[i], indexedV[i])
                        .overlayCoords(OverlayTexture.NO_OVERLAY)
                        .uv2(packedLight)
                        .normal(indexedNX[i], indexedNY[i], indexedNZ[i])
                        .endVertex();
            }
            // 关键：upload 内部的 setupBufferState 会把 attribute pointer 写入
            // 当前 VAO，但 upload 本身不会绑 VAO——所以必须先 bind()，否则
            // attribute pointer 会写到默认 VAO(=0) 而不是 arrayObjectId，渲染时找不到顶点。
            vbo.bind();
            vbo.upload(builder.end());

            // 绑定 VAO 后挂载自定义 IBO（ELEMENT_ARRAY_BUFFER 绑定属于 VAO 状态）。
            int ibo = GlStateManager._glGenBuffers();
            GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ibo);
            ByteBuffer indexBuffer = ByteBuffer.allocateDirect(indices.length * Integer.BYTES)
                    .order(ByteOrder.nativeOrder());
            for (int idx : indices) {
                indexBuffer.putInt(idx);
            }
            indexBuffer.flip();
            GlStateManager._glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer,
                    GL15.GL_STATIC_DRAW);
            VertexBuffer.unbind();

            // 反射注入：让 draw() 使用自定义索引而非共享顺序索引。
            setFieldInt(FIELD_INDEX_BUFFER_ID, vbo, ibo);
            setFieldObject(FIELD_SEQUENTIAL_INDICES, vbo, null);
            setFieldInt(FIELD_INDEX_COUNT, vbo, indices.length);
            setFieldObject(FIELD_INDEX_TYPE, vbo, VertexFormat.IndexType.INT);

            this.indexedVbo = vbo;
            this.indexedIboId = ibo;

            double millis = (System.nanoTime() - start) / 1_000_000.0;
            MESH_PERF_LOG.info("[MeshyPerf] Indexed VBO upload: light={} uniqueVerts={} indices={} tookMs={}",
                    packedLight, indexedVertexCount, indices.length, String.format("%.3f", millis));
        } catch (RuntimeException | LinkageError e) {
            MESH_PERF_LOG.error("[MeshyPerf] Indexed VBO upload failed, falling back to non-indexed", e);
            this.indexedVbo = null;
            this.indexedIboId = -1;
        }
    }

    public void drawIndexed(Matrix4f posePose) {
        VertexBuffer vbo = this.indexedVbo;
        if (vbo == null) {
            return;
        }
        vbo.bind();
        vbo.drawWithShader(posePose, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
        VertexBuffer.unbind();
    }

    public boolean isIndexedReady() {
        return indexedVbo != null;
    }

    public int getIndexedVertexCount() {
        return indexedVertexCount;
    }

    public boolean isVboReady(int packedLight) {
        return vboCache.containsKey(packedLight);
    }

    public int getVertexCount() {
        return vertexCount;
    }

    /**
     * 计算本网格所有顶点的像素空间 AABB（相对骨骼 pivot）。
     *
     * <p>bakedX/Y/Z 存的是模型坐标（{@code (pos - pivot) / 16}），这里乘 16 还原为
     * 像素坐标，与 TaCZ {@code BedrockCubeBox} 的 min/max 语义一致（单位像素、相对 pivot）。</p>
     *
     * @param outMin 长度为 3 的数组，接收 (minX, minY, minZ)
     * @param outMax 长度为 3 的数组，接收 (maxX, maxY, maxZ)
     * @return 是否有顶点参与计算
     */
    public boolean computePixelBounds(float[] outMin, float[] outMax) {
        if (vertexCount == 0) {
            return false;
        }
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < vertexCount; i++) {
            float px = bakedX[i] * 16.0f;
            float py = bakedY[i] * 16.0f;
            float pz = bakedZ[i] * 16.0f;
            if (px < minX) minX = px;
            if (py < minY) minY = py;
            if (pz < minZ) minZ = pz;
            if (px > maxX) maxX = px;
            if (py > maxY) maxY = py;
            if (pz > maxZ) maxZ = pz;
        }
        outMin[0] = minX;
        outMin[1] = minY;
        outMin[2] = minZ;
        outMax[0] = maxX;
        outMax[1] = maxY;
        outMax[2] = maxZ;
        return true;
    }

    /**
     * 把本网格所有顶点（相对骨骼 pivot 的本地方块坐标）经骨骼矩阵变换后，扩展传入的
     * 模型空间 AABB（min/max）。用于 {@code PolyMeshModel} 在构造时一次性算出整模型包围盒，
     * 支撑掉落物的视锥剔除。
     *
     * @param boneMatrix 该骨骼相对模型根的变换矩阵（含 pivot/旋转/缩放累积）
     * @param min        长度为 3 的数组，接收 (minX, minY, minZ)，调用前应初始化为 +INF
     * @param max        长度为 3 的数组，接收 (maxX, maxY, maxZ)，调用前应初始化为 -INF
     */
    public void expandBounds(Matrix4f boneMatrix, float[] min, float[] max) {
        if (vertexCount == 0) {
            return;
        }
        Vector4f v = new Vector4f();
        for (int i = 0; i < vertexCount; i++) {
            v.set(bakedX[i], bakedY[i], bakedZ[i], 1.0f);
            boneMatrix.transform(v);
            float x = v.x(), y = v.y(), z = v.z();
            if (x < min[0]) min[0] = x;
            if (y < min[1]) min[1] = y;
            if (z < min[2]) min[2] = z;
            if (x > max[0]) max[0] = x;
            if (y > max[1]) max[1] = y;
            if (z > max[2]) max[2] = z;
        }
    }

    /**
     * 均匀抽样本网格的顶点（像素坐标、相对骨骼 pivot），用于构造比 AABB 更贴合模型
     * 的碰撞形状（供 charm 挂饰的凸包碰撞使用）。
     *
     * @param maxCount 最多抽样多少个顶点
     * @return 长度为 min(vertexCount, maxCount) 的顶点数组，每个元素是 [x, y, z]（像素坐标）
     */
    public float[][] samplePixelVertices(int maxCount) {
        if (vertexCount == 0 || maxCount <= 0) {
            return new float[0][];
        }
        int n = Math.min(vertexCount, maxCount);
        float[][] out = new float[n][];
        for (int i = 0; i < n; i++) {
            int idx = (int) ((long) i * vertexCount / n);
            out[i] = new float[]{bakedX[idx] * 16.0f, bakedY[idx] * 16.0f, bakedZ[idx] * 16.0f};
        }
        return out;
    }


    /** 同时失效旧 VBO 与 AR 的局部网格缓存。 */
    public void invalidateVboCache() {
        int hadEntries = vboCache.size();
        for (VertexBuffer vbo : vboCache.values()) {
            if (vbo != null) {
                vbo.close();
            }
        }
        vboCache.clear();
        ARCompat.invalidate(acceleratedRenderer);
        if (hadEntries > 0) {
            MESH_PERF_LOG.info("[MeshyPerf] VBO cache invalidated: entriesCleared={} screenOpen={}",
                    hadEntries, net.minecraft.client.Minecraft.getInstance().screen != null);
        }
    }

    public void close() {
        for (VertexBuffer vbo : vboCache.values()) {
            if (vbo != null) {
                vbo.close();
            }
        }
        vboCache.clear();
        releaseIndexedVbo();
        ARCompat.invalidate(acceleratedRenderer);
        acceleratedRenderer = null;
    }

    /** 释放索引化 VBO；close 会连带删除反射注入的 IBO（indexBufferId）。 */
    private void releaseIndexedVbo() {
        if (indexedVbo != null) {
            indexedVbo.close();
            indexedVbo = null;
        }
        indexedIboId = -1;
    }

    // =========================================================================
    // 基础数据读取
    // =========================================================================

    private static float transformX(float value, float pivot) {
        float local = value - pivot;
        return (FLIP_MODEL_X ? -local : local) * INV_BLOCK;
    }

    private static float transformY(float value, float pivot) {
        float local = value - pivot;
        return (FLIP_MODEL_Y ? -local : local) * INV_BLOCK;
    }

    private static float transformZ(float value, float pivot) {
        return (value - pivot) * INV_BLOCK;
    }

    private static float[] getArray(float[][] values, int index) {
        if (values == null || index < 0 || index >= values.length || values[index] == null) {
            return new float[0];
        }
        return values[index];
    }

    private static float get(float[] values, int index) {
        return values != null && index >= 0 && index < values.length ? values[index] : 0.0f;
    }

    private static float[][] parse2DArray(JsonElement element, int dimension) {
        if (element == null || !element.isJsonArray()) {
            return new float[0][0];
        }
        JsonArray array = element.getAsJsonArray();
        float[][] result = new float[array.size()][dimension];
        for (int i = 0; i < array.size(); i++) {
            JsonElement rowElement = array.get(i);
            if (rowElement == null || !rowElement.isJsonArray()) {
                continue;
            }
            JsonArray row = rowElement.getAsJsonArray();
            for (int j = 0; j < Math.min(dimension, row.size()); j++) {
                result[i][j] = row.get(j).getAsFloat();
            }
        }
        return result;
    }

    private record Vertex(float x, float y, float z,
                          float u, float v,
                          float nx, float ny, float nz) {
        private Vertex withFallbackNormal(Vector3f normal) {
            if (nx * nx + ny * ny + nz * nz > NORMAL_EPSILON_SQUARED) {
                return this;
            }
            return new Vertex(x, y, z, u, v, normal.x, normal.y, normal.z);
        }

        private Vertex withNormal(Vector3f normal) {
            return new Vertex(x, y, z, u, v, normal.x, normal.y, normal.z);
        }
    }
}
