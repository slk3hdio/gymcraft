package io.github.mousemeya.gymcraft.client.visualization;

import io.github.mousemeya.gymcraft.gym.visualization.VisualElement;
import io.github.mousemeya.gymcraft.gym.visualization.VisualFrame;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** 客户端 1.21.1 世界渲染后端；每帧重建图形，既不修改实体也不维护持久化世界标记。 */
public final class VisualizationRenderer {
    private static com.mojang.blaze3d.vertex.PoseStack pose;
    private static com.mojang.blaze3d.vertex.VertexConsumer vertices;
    private static Vec3 camera;

    /** 合并同形同位置的图形，仅保留最高优先级颜色并合并标签。 */
    private record Drawing(VisualElement element, Vec3 from, Vec3 to, int color, int priority, String label) { }
    /** 精确世界坐标键，避免方块观测与动作目标发生叠线闪烁。 */
    private record GeometryKey(VisualElement.Shape shape, Vec3 from, Vec3 to) { }

    /** 禁止实例化事件工具类。 */
    private VisualizationRenderer() { }

    /**
     * 每帧从缓存重建全部图形并提交给原生线框与字体渲染管线。
     *
     * <p>收集顺序（后写覆盖先写）：环境边界 → 全量观测 → 淡出中的终态反馈 → 运行中动作；
     * 标签数量与准星距离受限，避免文字过载。收集完成后观测图形按观察者距离淡出，
     * 观测方框按配置改画角框，避免大量图形淹没画面。渲染不做任何持久化，帧间无状态。</p>
     *
     * @param event 世界渲染状态提取事件；只有此阶段允许读取客户端实体
     */
    public static void extract(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        var state = VisualizationClientState.INSTANCE;
        if (!VisualizationConfig.ENABLED.get() || state.agent() == null
            || !level.dimension().location().toString().equals(state.dimension())) return;
        long now = System.nanoTime();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Map<GeometryKey, Drawing> drawings = new LinkedHashMap<>();
        // 环境边界垫底：优先级最低、不参与淡出，作为全部图形的空间参照。
        collect(drawings, state.boundary(), 1, partialTick);
        collect(drawings, state.observation(), 1, partialTick);
        // 短暂终态反馈按剩余寿命淡出。
        for (var feedback : state.feedback(now)) {
            float age = (float) (now - feedback.receivedNanos()) / VisualizationClientState.FEEDBACK_NANOS;
            collect(drawings, feedback.frame(), Math.min(1, (1 - age) * 3), partialTick);
        }
        collect(drawings, state.active(), 1, partialTick);
        int labels = 0;
        var minecraft = Minecraft.getInstance();
        fadeObservations(drawings, minecraft, partialTick);
        Drawing focused = null;
        double focusDistance = 2.25;
        // 准星附近的观测元素优先显示标签；其余观测图形只画几何不画文字。
        if (minecraft.hitResult != null) {
            for (Drawing drawing : drawings.values()) {
                if (!drawing.element().observation() || isBoundary(drawing.element()) || drawing.label().isBlank()) continue;
                double distance = minecraft.hitResult.getLocation().distanceToSqr(drawing.from().add(drawing.to()).scale(.5));
                if (distance < focusDistance) { focusDistance = distance; focused = drawing; }
            }
        }
        // 每帧收集后由原生渲染管线负责深度与穿墙两个阶段。
        {
            pose = event.getPoseStack();
            camera = event.getCamera().getPosition();
            var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
            var renderType = VisualizationRenderTypes.lines(VisualizationConfig.THROUGH_WALLS.get());
            vertices = buffers.getBuffer(renderType);
            for (Drawing drawing : drawings.values()) {
                draw(drawing);
                Vec3 center = drawing.from().add(drawing.to()).scale(.5);
                if (VisualizationConfig.LABELS.get() && !drawing.label().isBlank() && labels < 24
                    && (!drawing.element().observation() || drawing == focused || isBoundary(drawing.element()))) {
                    Vec3 position = drawing.element().shape() == VisualElement.Shape.BOX
                        ? new Vec3(center.x, Math.max(drawing.from().y, drawing.to().y) + .15, center.z)
                        : drawing.to().add(0, .2, 0);
                    label(VisualElement.shorten(drawing.label(), 128), position, drawing.color(), buffers);
                    vertices = buffers.getBuffer(renderType);
                    labels++;
                }
            }
            buffers.endBatch();
            pose = null;
            vertices = null;
        }
    }

    /**
     * 把一帧的全部元素收集进合并表。
     *
     * @param drawings    合并表（按几何键去重）
     * @param frame       待收集帧；null 直接返回
     * @param alpha       透明度（终态反馈按寿命衰减）
     * @param partialTick 帧插值参数
     */
    private static void collect(Map<GeometryKey, Drawing> drawings, VisualFrame frame, float alpha, float partialTick) {
        if (frame == null) return;
        collect(drawings, frame.elements(), alpha, partialTick);
    }

    /**
     * 把一组元素收集进合并表。
     *
     * <p>按配置跳过隐藏图层；运行中的动作元素经 UUID 校验后按实体实时位置平移
     * （观测与边界元素固定不动）；同形同位的图形按优先级择色并合并标签，优先级为
     * 动作 &gt; 关注方块 &gt; 普通观测 &gt; 环境边界。</p>
     *
     * @param drawings    合并表（按几何键去重）
     * @param elements    待收集元素
     * @param alpha       透明度
     * @param partialTick 帧插值参数
     */
    private static void collect(Map<GeometryKey, Drawing> drawings, List<VisualElement> elements, float alpha, float partialTick) {
        var level = Minecraft.getInstance().level;
        for (VisualElement element : elements) {
            if (!VisualizationConfig.visible((element.observation() ? "observation/" : "action/") + element.component())) continue;
            Vec3 from = element.from();
            Vec3 to = element.to();
            if (!element.observation() && element.status().equals("RUNNING") && element.entityUuid() != null && level != null) {
                var entity = level.getEntity(element.entityId());
                if (entity != null && entity.getUUID().equals(element.entityUuid())) {
                    Vec3 offset = entity.getPosition(partialTick).subtract(element.anchor());
                    to = to.add(offset);
                    if (element.shape() == VisualElement.Shape.BOX || element.shape() == VisualElement.Shape.POINT) from = from.add(offset);
                }
            }
            int priority = isBoundary(element) ? 0
                : element.observation() ? (element.component().equals("gymcraft:interesting_blocks") ? 2 : 1) : 3;
            int color = ((int) (255 * Math.clamp(alpha, 0, 1)) << 24) | color(element);
            var key = new GeometryKey(element.shape(), from, to);
            Drawing drawing = new Drawing(element, from, to, color, priority, element.label());
            Drawing previous = drawings.get(key);
            if (previous != null) {
                Drawing selected = previous.priority() > priority ? previous : drawing;
                String label = previous.label().equals(element.label()) || element.label().isBlank()
                    ? previous.label() : previous.label() + " / " + element.label();
                drawing = new Drawing(selected.element(), selected.from(), selected.to(), selected.color(), selected.priority(), label);
            }
            drawings.put(key, drawing);
        }
    }

    /**
     * 按观察者距离淡出观测图形，缓解大量方框淹没游戏画面。
     *
     * <p>以观察者眼睛位置为中心，淡出截止距离（配置 {@code fadeDistance}）的半程处
     * 开始线性降低透明度，超出截止距离的观测图形直接移除。动作图形与终态反馈数量
     * 少且时效短，不参与淡出；环境边界体量巨大且是空间参照，也不参与淡出。
     * 淡出在标签选择与绘制之前完成，透明度同步作用于文字。</p>
     *
     * @param drawings    合并后的图形表；原地修改透明度并移除远距项
     * @param minecraft   客户端实例；用于读取观察者
     * @param partialTick 帧插值参数
     */
    private static void fadeObservations(Map<GeometryKey, Drawing> drawings, Minecraft minecraft, float partialTick) {
        double fade = VisualizationConfig.FADE_DISTANCE.get();
        if (fade <= 0 || minecraft.player == null) return;
        Vec3 eye = minecraft.player.getEyePosition(partialTick);
        var iterator = drawings.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Drawing drawing = entry.getValue();
            if (!drawing.element().observation() || isBoundary(drawing.element())) continue;
            double distance = eye.distanceTo(drawing.from().add(drawing.to()).scale(.5));
            float visibility = (float) Math.clamp((fade - distance) / (fade * .5), 0, 1);
            if (visibility <= .01F) {
                iterator.remove();
            } else if (visibility < 1F) {
                entry.setValue(new Drawing(drawing.element(), drawing.from(), drawing.to(),
                    (((int) ((drawing.color() >>> 24) * visibility)) << 24) | (drawing.color() & 0xFFFFFF), drawing.priority(), drawing.label()));
            }
        }
    }

    /**
     * 图形配色：动作按执行状态（绿=完成、红=失败、灰=中断、橙=运行中），
     * 观测按组件类型区分，环境边界固定琥珀色。
     *
     * @param element 图形
     * @return 不含透明度的 RGB 颜色
     */
    private static int color(VisualElement element) {
        if (!element.observation()) return switch (element.status()) {
            case "COMPLETED" -> 0x55EE77;
            case "FAILED" -> 0xFF5555;
            case "INTERRUPTED" -> 0xAAAAAA;
            default -> 0xFFAA33;
        };
        return switch (element.component()) {
            case VisualElement.ENV_BOUNDARY_COMPONENT -> 0xFFC107;
            case "gymcraft:interesting_blocks" -> 0xCC77FF;
            case "gymcraft:nearby_entities" -> 0x55DDDD;
            case "gymcraft:nearby_items" -> 0xFFFF77;
            case "gymcraft:self" -> 0xFFFFFF;
            case "gymcraft:world" -> 0xFF99CC;
            default -> 0x5599FF;
        };
    }

    /** @param element 图形 @return 是否为环境边界（专属配色，不参与淡出、角框化与准星聚焦） */
    private static boolean isBoundary(VisualElement element) {
        return element.component().equals(VisualElement.ENV_BOUNDARY_COMPONENT);
    }

    /** @param drawing 已合并图形；退化线段不提交以防原生箭头归一化产生 NaN */
    private static void draw(Drawing drawing) {
        Vec3 from = drawing.from();
        Vec3 to = drawing.to();
        switch (drawing.element().shape()) {
            // 观测方框数量大，默认改画角框降低视觉密度；边界与动作图形保持完整线框。
            case BOX -> {
                if (drawing.element().observation() && !isBoundary(drawing.element())
                    && VisualizationConfig.COMPACT_BOXES.get()) {
                    cornerFrame(from, to, drawing.color());
                } else {
                    box(new AABB(from, to).inflate(.002), drawing.color());
                }
            }
            case POINT -> box(new AABB(from, from).inflate(.08), drawing.color());
            case LINE -> { if (from.distanceToSqr(to) > 1.0E-8) segment(from, to, drawing.color()); }
            case ARROW -> { if (from.distanceToSqr(to) > 1.0E-8) arrow(from, to, drawing.color()); }
        }
    }

    /**
     * 观测方框的紧凑画法：只在八个角各画三条约 30% 棱长的短线。
     *
     * <p>相邻方格的完整线框会拼成致密网格并淹没画面，角框在保留位置与尺寸参考的
     * 同时让每条棱中段留空，视觉密度大幅下降。某维度退化为零长度时该方向的短线
     * 自动跳过。</p>
     *
     * @param from  方框最小角
     * @param to    方框最大角
     * @param color ARGB 颜色（含淡出透明度）
     */
    private static void cornerFrame(Vec3 from, Vec3 to, int color) {
        double lx = Math.abs(to.x - from.x) * .3;
        double ly = Math.abs(to.y - from.y) * .3;
        double lz = Math.abs(to.z - from.z) * .3;
        for (int ix = 0; ix <= 1; ix++) for (int iy = 0; iy <= 1; iy++) for (int iz = 0; iz <= 1; iz++) {
            Vec3 corner = new Vec3(ix == 0 ? from.x : to.x, iy == 0 ? from.y : to.y, iz == 0 ? from.z : to.z);
            segment(corner, corner.add(ix == 0 ? lx : -lx, 0, 0), color);
            segment(corner, corner.add(0, iy == 0 ? ly : -ly, 0), color);
            segment(corner, corner.add(0, 0, iz == 0 ? lz : -lz), color);
        }
    }

    /**
     * 提交一条短线段图形。
     *
     * @param from  起点
     * @param to    终点
     * @param color ARGB 颜色
     */
    private static void segment(Vec3 from, Vec3 to, int color) {
        if (from.distanceToSqr(to) <= 1.0E-8) return;
        Vec3 normal = to.subtract(from).normalize();
        Vec3 a = from.subtract(camera), b = to.subtract(camera);
        vertices.addVertex(pose.last(), (float) a.x, (float) a.y, (float) a.z).setColor(color)
            .setNormal(pose.last(), (float) normal.x, (float) normal.y, (float) normal.z);
        vertices.addVertex(pose.last(), (float) b.x, (float) b.y, (float) b.z).setColor(color)
            .setNormal(pose.last(), (float) normal.x, (float) normal.y, (float) normal.z);
    }

    /** 绘制十二条方框棱；参数 box 为世界包围盒，color 为 ARGB 颜色。 */
    private static void box(AABB box, int color) {
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) {
            segment(new Vec3(x == 0 ? box.minX : box.maxX, y == 0 ? box.minY : box.maxY, box.minZ),
                new Vec3(x == 0 ? box.minX : box.maxX, y == 0 ? box.minY : box.maxY, box.maxZ), color);
            segment(new Vec3(x == 0 ? box.minX : box.maxX, box.minY, y == 0 ? box.minZ : box.maxZ),
                new Vec3(x == 0 ? box.minX : box.maxX, box.maxY, y == 0 ? box.minZ : box.maxZ), color);
            segment(new Vec3(box.minX, x == 0 ? box.minY : box.maxY, y == 0 ? box.minZ : box.maxZ),
                new Vec3(box.maxX, x == 0 ? box.minY : box.maxY, y == 0 ? box.minZ : box.maxZ), color);
        }
    }

    /** 绘制带箭头线段；参数 from、to 为端点，color 为 ARGB 颜色。 */
    private static void arrow(Vec3 from, Vec3 to, int color) {
        segment(from, to, color);
        Vec3 direction = to.subtract(from).normalize();
        Vec3 side = direction.cross(Math.abs(direction.y) > .9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize().scale(.15);
        Vec3 back = to.subtract(direction.scale(.3));
        segment(to, back.add(side), color);
        segment(to, back.subtract(side), color);
    }

    /** 绘制朝向相机的标签；参数 text、position、color、buffers 为文字、位置、颜色和缓冲。 */
    private static void label(String text, Vec3 position, int color, net.minecraft.client.renderer.MultiBufferSource.BufferSource buffers) {
        var minecraft = Minecraft.getInstance();
        pose.pushPose();
        Vec3 relative = position.subtract(camera);
        pose.translate(relative.x, relative.y, relative.z);
        pose.mulPose(minecraft.gameRenderer.getMainCamera().rotation());
        pose.scale(.02F, -.02F, .02F);
        minecraft.font.drawInBatch(text, -minecraft.font.width(text) / 2.0F, 0, color, false,
            pose.last().pose(), buffers, VisualizationConfig.THROUGH_WALLS.get()
                ? net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH : net.minecraft.client.gui.Font.DisplayMode.NORMAL,
            0, net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
        pose.popPose();
    }
}
