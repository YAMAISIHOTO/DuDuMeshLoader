package com.example.dudumeshloader.compat;

import com.example.dudumeshloader.core.PolyMesh;
import com.example.dudumeshloader.core.PolyMeshModel;
import com.tacz.guns.client.model.bedrock.BedrockCubeBox;
import com.tacz.guns.client.model.bedrock.BedrockModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.model.bedrock.BedrockPolygon;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 让 tacz_charms（挂饰 mod）对 poly_mesh 模型兼容的桥接层。
 *
 * <p>charm 的挂饰系统依赖 {@code BedrockPart.cubes}（cube 列表）来判定锚点、提取碰撞盒、
 * 提供 GUI 选点。但纯 poly_mesh 骨骼在 geo.json 上没有 cubes，导致 charm 无法工作。</p>
 *
 * <p>本类在模型加载后，为每个「只有 poly_mesh、没有 cube」的骨骼，按<b>相对规模</b>（该骨骼
 * 占整把枪的体积占比 × 顶点占比）自适应地把其 mesh 顶点沿最长轴二分聚类成 <b>1~20 个簇</b>，
 * 每簇生成一个虚拟 {@link BedrockCubeBox} 注入 {@code cubes}，并清空其 polygons（避免 TaCZ
 * 渲染出白盒）。charm 对每个小 AABB 单独做碰撞——多个沿长轴排布的小 AABB 比单一 AABB 能
 * 把细长部件（枪管、枪托、导轨）在宽度方向上的判定范围显著收紧。</p>
 *
 * <p>完全不改动 charm 的任何文件；若 charm 未安装则直接跳过。</p>
 */
public final class CharmCompat {
    private static final Logger LOG = LoggerFactory.getLogger("MeshyLoader");
    private static final String CHARM_MOD_ID = "tacz_charms";
    private static final boolean CHARM_PRESENT = ModList.get().isLoaded(CHARM_MOD_ID);

    /** 聚类输入顶点数上限：足够多让聚类有代表性，又不至于让加载阶段开销失控。 */
    private static final int MAX_CLUSTER_INPUT_VERTICES = 1024;

    /** 每个簇至少需要这么多顶点才会继续二分；低于此值不再细分，避免退化出碎簇。 */
    private static final int MIN_VERTICES_PER_CLUSTER = 8;

    /** 单个骨骼最多拆成的簇数上限（相对规模最大的部件）。 */
    private static final int MAX_CLUSTERS = 20;

    /**
     * 横截面方向收缩系数：AABB 的「水平宽度轴」（X/Z 平面内、垂直于长度轴的方向）向中心缩到
     * 该比例，缓解轴对齐包围盒对圆柱、斜面等部件在宽度方向上的撑大。长度轴与高度轴（Y）都不缩，
     * 保证碰撞盒在长度、高度方向完整覆盖模型表面。
     */
    private static final float WIDTH_SCALE = 0.85f;

    private static Field polygonsField;

    private CharmCompat() {
    }

    /** 骨骼的相对规模统计（顶点数 + AABB 体积）。 */
    private static final class BoneStats {
        final int vertices;
        final double volume;

        BoneStats(int vertices, double volume) {
            this.vertices = vertices;
            this.volume = volume;
        }
    }

    /**
     * 为纯 poly_mesh 骨骼注入虚拟 cube。
     *
     * @param model     枪模（用于遍历 BedrockPart 树）
     * @param polyModel 已解析的 poly_mesh 模型（用于拿骨骼顶点）
     */
    public static void injectVirtualCubes(BedrockModel model, PolyMeshModel polyModel) {
        if (!CHARM_PRESENT || model == null || polyModel == null) {
            return;
        }
        try {
            // 第一遍：统计每个骨骼的顶点数与体积，汇总模型总量（用于算相对占比）。
            Map<BedrockPart, BoneStats> stats = new HashMap<>();
            int[] totalVertices = {0};
            double[] totalVolume = {0};
            for (BedrockPart root : model.getShouldRender()) {
                collectStats(root, polyModel, stats, totalVertices, totalVolume);
            }
            // 第二遍：注入虚拟 cube。
            for (BedrockPart root : model.getShouldRender()) {
                injectForPart(root, polyModel, stats, totalVertices[0], totalVolume[0]);
            }
        } catch (Throwable t) {
            LOG.warn("[MeshyLoader][CharmCompat] Virtual cube injection aborted", t);
        }
    }

    /** 递归统计每个骨骼的顶点数与 AABB 体积（像素³），并累加进模型总量。 */
    private static void collectStats(BedrockPart part, PolyMeshModel polyModel,
                                     Map<BedrockPart, BoneStats> stats,
                                     int[] totalVertices, double[] totalVolume) {
        if (part == null) {
            return;
        }
        if (part.name != null && !part.name.isEmpty() && part.cubes.isEmpty()) {
            List<PolyMesh> meshes = polyModel.getMeshes(part.name);
            if (meshes != null && !meshes.isEmpty()) {
                int verts = 0;
                float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
                float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
                boolean any = false;
                for (PolyMesh m : meshes) {
                    verts += m.getVertexCount();
                    float[] mn = new float[3];
                    float[] mx = new float[3];
                    if (m.computePixelBounds(mn, mx)) {
                        any = true;
                        for (int i = 0; i < 3; i++) {
                            min[i] = Math.min(min[i], mn[i]);
                            max[i] = Math.max(max[i], mx[i]);
                        }
                    }
                }
                double vol = any
                        ? (double) (max[0] - min[0]) * (max[1] - min[1]) * (max[2] - min[2])
                        : 0.0;
                stats.put(part, new BoneStats(verts, Math.max(0.0, vol)));
                totalVertices[0] += verts;
                totalVolume[0] += Math.max(0.0, vol);
            }
        }
        for (BedrockPart child : part.children) {
            collectStats(child, polyModel, stats, totalVertices, totalVolume);
        }
    }

    private static void injectForPart(BedrockPart part, PolyMeshModel polyModel,
                                      Map<BedrockPart, BoneStats> stats,
                                      int totalVertices, double totalVolume) {
        if (part == null) {
            return;
        }
        // 只对「有名字、且原本没有 cube」的骨骼注入，避免与混合模型的原版 cube 冲突。
        if (part.name != null && !part.name.isEmpty() && part.cubes.isEmpty()) {
            BoneStats s = stats.get(part);
            if (s != null && s.vertices >= MIN_VERTICES_PER_CLUSTER) {
                List<PolyMesh> meshes = polyModel.getMeshes(part.name);
                if (meshes != null && !meshes.isEmpty()) {
                    injectClusters(part, meshes, s, totalVertices, totalVolume);
                }
            }
        }
        for (BedrockPart child : part.children) {
            injectForPart(child, polyModel, stats, totalVertices, totalVolume);
        }
    }

    /**
     * 核心：按骨骼的相对规模（占整把枪的体积/顶点比例）拆成 1~20 个簇，每簇注入一个 AABB cube。
     */
    private static void injectClusters(BedrockPart part, List<PolyMesh> meshes, BoneStats s,
                                       int totalVertices, double totalVolume) {
        List<float[]> all = collectVertices(meshes, MAX_CLUSTER_INPUT_VERTICES);
        if (all.size() < MIN_VERTICES_PER_CLUSTER) {
            return;
        }
        int clusterCount = decideClusterCount(s.vertices, totalVertices, s.volume, totalVolume);
        List<List<float[]>> clusters = splitInto(all, clusterCount);
        // 用整个骨骼的水平长度轴决定「宽度轴」：长度与高度（Y）都不缩，只缩水平宽度。
        int widthAxis = horizontalWidthAxis(all);
        int injected = 0;
        for (List<float[]> cluster : clusters) {
            if (cluster.size() < MIN_VERTICES_PER_CLUSTER) {
                continue;
            }
            float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
            float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
            for (float[] v : cluster) {
                for (int i = 0; i < 3; i++) {
                    min[i] = Math.min(min[i], v[i]);
                    max[i] = Math.max(max[i], v[i]);
                }
            }
            shrinkWidthAxis(min, max, widthAxis);
            BedrockCubeBox cube = newBedrockCubeBox(min, max);
            if (cube == null) {
                continue;
            }
            clearPolygons(cube);
            part.cubes.add(cube);
            injected++;
        }
        if (injected > 0) {
            double vertPct = totalVertices > 0 ? 100.0 * s.vertices / totalVertices : 0.0;
            double volPct = totalVolume > 0 ? 100.0 * s.volume / totalVolume : 0.0;
            LOG.info("[MeshyLoader][CharmCompat] bone={} vertices={} vertPct={}% volPct={}% clusters={}",
                    part.name, s.vertices,
                    String.format("%.1f", vertPct), String.format("%.1f", volPct), injected);
        }
    }

    /**
     * 按骨骼占整把枪的<b>相对规模</b>决定簇数（1~{@value MAX_CLUSTERS}），而不是绝对顶点数——
     * 这样无论模型是几千顶点还是六万顶点，判定都一致。综合两个占比的加权和，顶点占比权重更大
     * （几何细节才是碰撞贴合的关键）：
     *
     * <pre>scale = 0.7 * vertexRatio + 0.3 * volumeRatio</pre>
     *
     * <ul>
     *   <li>{@code vertexRatio}：骨骼顶点数 / 模型总顶点数（几何信息占比）</li>
     *   <li>{@code volumeRatio}：骨骼 AABB 体积 / 模型各骨骼 AABB 体积之和（尺寸占比）</li>
     * </ul>
     *
     * 占比越高（又大又精细）拆越细；极小零件（scale &lt; 1%）仍是 1 簇，避免无谓拆分。
     */
    private static int decideClusterCount(int boneVertices, int totalVertices,
                                          double boneVolume, double totalVolume) {
        double vertexRatio = totalVertices > 0 ? (double) boneVertices / totalVertices : 0.0;
        double volumeRatio = totalVolume > 0 ? boneVolume / totalVolume : 0.0;
        double scale = 0.7 * vertexRatio + 0.3 * volumeRatio;
        if (scale < 0.005) {
            return 1;
        }
        if (scale < 0.01) {
            return 6;
        }
        if (scale < 0.02) {
            return 10;
        }
        if (scale < 0.04) {
            return 14;
        }
        if (scale < 0.08) {
            return 18;
        }
        return MAX_CLUSTERS;
    }

    /** 从骨骼的多个 mesh 里均匀抽样顶点（像素坐标），总数量压到 maxTotal 以内。 */
    private static List<float[]> collectVertices(List<PolyMesh> meshes, int maxTotal) {
        int perMesh = Math.max(16, maxTotal / Math.max(1, meshes.size()));
        List<float[]> all = new ArrayList<>();
        for (PolyMesh mesh : meshes) {
            for (float[] v : mesh.samplePixelVertices(perMesh)) {
                all.add(v);
            }
        }
        if (all.size() > maxTotal) {
            List<float[]> trimmed = new ArrayList<>(maxTotal);
            for (int i = 0; i < maxTotal; i++) {
                int idx = (int) ((long) i * all.size() / maxTotal);
                trimmed.add(all.get(idx));
            }
            all = trimmed;
        }
        return all;
    }

    /**
     * 沿最长轴递归二分，把顶点拆成约 k 个空间连续、顶点数均匀的簇。确定性算法（无随机），
     * 对枪管、枪托这类细长部件会自然地沿长轴切成若干段，每段一个小 AABB。
     */
    private static List<List<float[]>> splitInto(List<float[]> verts, int k) {
        List<List<float[]>> result = new ArrayList<>();
        if (k <= 1 || verts.size() <= MIN_VERTICES_PER_CLUSTER * 2) {
            result.add(verts);
            return result;
        }
        int axis = longestAxis(verts);
        verts.sort(Comparator.comparingDouble(v -> v[axis]));
        int mid = verts.size() / 2;
        List<float[]> left = new ArrayList<>(verts.subList(0, mid));
        List<float[]> right = new ArrayList<>(verts.subList(mid, verts.size()));
        int kLeft = k / 2;
        int kRight = k - kLeft;
        result.addAll(splitInto(left, kLeft));
        result.addAll(splitInto(right, kRight));
        return result;
    }

    /**
     * 只把 AABB 的「水平宽度轴」向中心收缩到 {@link #WIDTH_SCALE}。长度轴与高度轴（Y）
     * 都不缩，保证碰撞盒在长度、高度方向完整覆盖模型表面。
     */
    private static void shrinkWidthAxis(float[] min, float[] max, int widthAxis) {
        float center = (min[widthAxis] + max[widthAxis]) * 0.5f;
        float half = (max[widthAxis] - min[widthAxis]) * WIDTH_SCALE * 0.5f;
        min[widthAxis] = center - half;
        max[widthAxis] = center + half;
    }

    /**
     * 返回「水平宽度轴」：X/Z 平面内 span 较小的那个轴（span 较大的是长度轴，保持不变）。
     * 返回 0=X、2=Z；高度轴（Y）不参与，永不收缩。
     */
    private static int horizontalWidthAxis(List<float[]> verts) {
        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (float[] v : verts) {
            minX = Math.min(minX, v[0]);
            maxX = Math.max(maxX, v[0]);
            minZ = Math.min(minZ, v[2]);
            maxZ = Math.max(maxZ, v[2]);
        }
        float spanX = maxX - minX;
        float spanZ = maxZ - minZ;
        // X 跨度大 → X 是长度，宽度是 Z；否则宽度是 X。
        return spanX >= spanZ ? 2 : 0;
    }

    /** 找出顶点集 AABB 中跨度最大的轴（0=X, 1=Y, 2=Z），作为二分切分方向。 */
    private static int longestAxis(List<float[]> verts) {
        float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (float[] v : verts) {
            for (int i = 0; i < 3; i++) {
                min[i] = Math.min(min[i], v[i]);
                max[i] = Math.max(max[i], v[i]);
            }
        }
        int axis = 0;
        float span = max[0] - min[0];
        for (int i = 1; i < 3; i++) {
            float s = max[i] - min[i];
            if (s > span) {
                span = s;
                axis = i;
            }
        }
        return axis;
    }

    /**
     * 用 AABB 构造虚拟 cube。构造器 12 个参数中，前两个是 TaCZ 历史遗留的废弃参数（字节码里
     * 从未读取），真正参与 AABB 的是第 3-8 个参数（minX/minY/minZ + sizeX/sizeY/sizeZ），
     * 第 9 个是 inflate（传 0），第 10 个是 mirror，最后两个是纹理尺寸。
     */
    private static BedrockCubeBox newBedrockCubeBox(float[] min, float[] max) {
        try {
            return new BedrockCubeBox(
                    0.0f, 0.0f,
                    min[0], min[1], min[2],
                    max[0] - min[0], max[1] - min[1], max[2] - min[2],
                    0.0f, false, 16.0f, 16.0f);
        } catch (Throwable t) {
            LOG.warn("[MeshyLoader][CharmCompat] Failed to construct virtual cube", t);
            return null;
        }
    }

    /**
     * 清空 polygons，让 TaCZ 不渲染任何面（不出现白盒），charm 的 BedrockCubeGeometry 遇空
     * polygons 会 fallback 到 AABB（8 顶点 + 12 边）做碰撞盒。
     */
    private static void clearPolygons(BedrockCubeBox cube) {
        try {
            if (polygonsField == null) {
                polygonsField = BedrockCubeBox.class.getDeclaredField("polygons");
                polygonsField.setAccessible(true);
            }
            polygonsField.set(cube, new BedrockPolygon[0]);
        } catch (ReflectiveOperationException e) {
            LOG.warn("[MeshyLoader][CharmCompat] Failed to clear cube polygons", e);
        }
    }
}
