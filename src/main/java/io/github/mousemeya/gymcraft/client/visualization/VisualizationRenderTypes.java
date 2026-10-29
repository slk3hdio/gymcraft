package io.github.mousemeya.gymcraft.client.visualization;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/** 1.21.1 世界线框渲染状态；穿墙模式只写颜色，不污染世界深度缓冲。 */
public final class VisualizationRenderTypes extends RenderType {
    private static final RenderType THROUGH_WALLS = create("gymcraft_visual_lines",
        DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, 1536,
        CompositeState.builder().setShaderState(RENDERTYPE_LINES_SHADER)
            .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.empty()))
            .setLayeringState(VIEW_OFFSET_Z_LAYERING).setTransparencyState(TRANSLUCENT_TRANSPARENCY)
            .setDepthTestState(NO_DEPTH_TEST).setWriteMaskState(COLOR_WRITE).setCullState(NO_CULL)
            .createCompositeState(false));

    /** 禁止创建静态渲染类型工具实例。 */
    private VisualizationRenderTypes() {
        super("gymcraft_visual", DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES,
            1536, false, false, () -> {}, () -> {});
    }

    /** 返回当前线框状态；参数 throughWalls 控制是否忽略场景深度。 */
    public static RenderType lines(boolean throughWalls) { return throughWalls ? THROUGH_WALLS : RenderType.lines(); }
}
