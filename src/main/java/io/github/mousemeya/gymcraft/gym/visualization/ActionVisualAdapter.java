package io.github.mousemeya.gymcraft.gym.visualization;

import com.google.protobuf.Any;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import java.util.List;
import net.minecraft.world.entity.Mob;

/**
 * 动作图形扩展接口。
 *
 * <p>实现契约：只读世界与给定执行结果，禁止推进动作、修改实体、加载区块或产生
 * 会话副作用；返回的几何会在发送前统一按距离与字节预算截断。实现抛出异常是
 * 安全的——目录会记录告警并当作空图形处理。</p>
 */
@FunctionalInterface
public interface ActionVisualAdapter {
    /**
     * 由动作负载构建候选几何。
     *
     * @param mob     受控实体
     * @param payload 动作负载（{@link Any} 包装的 protobuf 消息）
     * @param state   已有执行状态；瞬时动作的终态会复用 apply 前几何，一般无需读取
     * @return 世界坐标图形列表，可为空
     */
    List<VisualElement> create(Mob mob, Any payload, ActionState state) throws Exception;
}
