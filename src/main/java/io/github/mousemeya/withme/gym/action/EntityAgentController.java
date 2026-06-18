package io.github.mousemeya.withme.gym.action;

import com.mojang.logging.LogUtils;
import io.github.mousemeya.withme.gym.action.proto.McAction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实体智能体控制器，负责将 Protobuf 格式的 {@link McAction} 分发给对应的 {@link ActionHandler}。
 * <p>
 * 一个 McAction 可以包含多个动作组件（components），控制器按注册顺序依次查找并执行
 * 对应的处理器。支持的默认动作包括：
 * <ul>
 *   <li>{@code gym.move_to} - 寻路移动到指定坐标</li>
 *   <li>{@code gym.step_move} - 低级移动控制（前后/左右/跳跃/视角）</li>
 *   <li>{@code gym.set_attack_target} - 设置攻击目标</li>
 *   <li>{@code gym.attack_once} - 执行一次近战攻击</li>
 *   <li>{@code gym.noop} - 无操作</li>
 * </ul>
 */
public class EntityAgentController {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Map<String, ActionHandler> handlers = new LinkedHashMap<>();  // 动作键 -> 处理器映射

    public EntityAgentController(List<ActionHandler> handlers) {
        for (var h : handlers) {
            this.handlers.put(h.actionKey(), h);
        }
    }

    private static EntityAgentController DEFAULT;  // 懒加载的默认控制器单例

    /** 获取包含所有默认动作处理器的控制器实例（懒加载单例） */
    public static EntityAgentController defaultController() {
        if (DEFAULT == null) {
            DEFAULT = new EntityAgentController(List.of(
                new MoveToHandler(),
                new StepMoveHandler(),
                new SetAttackTargetHandler(),
                new AttackOnceHandler(),
                new NoopHandler()
            ));
        }
        return DEFAULT;
    }

    /** 将 McAction 中的所有动作组件依次分发给对应的处理器执行 */
    public void apply(Mob mob, McAction action) {
        if (action.getComponentsCount() == 0) return;

        for (var entry : action.getComponentsMap().entrySet()) {
            var key = entry.getKey();
            var params = entry.getValue();
            var handler = handlers.get(key);
            if (handler == null) {
                LOGGER.debug("No handler for action key: {}", key);
                continue;
            }
            if (!handler.canHandle(mob)) {
                LOGGER.debug("Handler {} cannot handle mob type {}", key, BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()));
                continue;
            }
            try {
                handler.handle(mob, params);
            } catch (Exception e) {
                LOGGER.warn("Error applying action {}: {}", key, e.getMessage());
            }
        }
    }
}
