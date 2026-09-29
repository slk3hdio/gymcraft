package io.github.mousemeya.gymcraft.client.visualization;

import io.github.mousemeya.gymcraft.gym.visualization.VisualSnapshotCache;

/** 客户端专属缓存实例；缓存算法不依赖 Minecraft 客户端，可在无头测试中验证。 */
public final class VisualizationClientState {
    /** 全客户端唯一缓存实例；HUD 与渲染器都从这里读取。 */
    public static final VisualSnapshotCache INSTANCE = new VisualSnapshotCache();
    /** 透传终态显示时长，供渲染层计算淡出进度。 */
    public static final long FEEDBACK_NANOS = VisualSnapshotCache.FEEDBACK_NANOS;

    /** 禁止实例化缓存持有者。 */
    private VisualizationClientState() { }
}
