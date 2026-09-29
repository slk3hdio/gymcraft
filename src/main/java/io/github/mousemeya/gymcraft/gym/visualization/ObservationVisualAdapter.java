package io.github.mousemeya.gymcraft.gym.visualization;

import com.google.protobuf.Any;
import java.util.List;
import net.minecraft.world.entity.Mob;

/**
 * 观测图形扩展接口。
 *
 * <p>实现契约：只消费给定的真实观测负载，禁止重新扫描世界、读取实时实体位置或
 * 调用 creator（部分 creator 带会话副作用）；图形固定在采样坐标，保证与 RL 看到
 * 的数据一致。实现抛出异常是安全的——目录会记录告警并跳过该组件。</p>
 */
@FunctionalInterface
public interface ObservationVisualAdapter {
    /**
     * 由观测负载构建固定图形。
     *
     * @param mob     采样实体
     * @param payload 已生成的观测负载（{@link Any} 包装的 protobuf 消息）
     * @return 固定于采样坐标的图形列表，可为空
     */
    List<VisualElement> create(Mob mob, Any payload) throws Exception;
}
