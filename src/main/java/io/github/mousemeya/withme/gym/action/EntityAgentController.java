package io.github.mousemeya.withme.gym.action;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.mojang.logging.LogUtils;
import io.github.mousemeya.withme.gym.action.proto.McAction;
import io.github.mousemeya.withme.registry.RegistryKeys;
import net.minecraft.world.entity.Mob;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 动作分发器 —— 解析 {@link McAction} 中每个动作组件，按注册表 ID 分发给对应的
 * {@link ActionComponent} 执行。
 * <p>
 * 分发流程：
 * <ol>
 *   <li>遍历 McAction.components 中的每个 entry</li>
 *   <li>以 entry 的 key（注册表 ID，如 {@code withme:move_to}）查找本地组件映射</li>
 *   <li>用组件的 {@code protoType()} 校验 Any 消息类型是否匹配</li>
 *   <li>解包后调用组件的 {@code contains()} 参数校验</li>
 *   <li>校验通过后调用组件的 {@code apply()} 执行实际动作</li>
 * </ol>
 * 任意步骤失败时仅打印 debug 日志并跳过该组件，不影响其他组件的执行。
 * </p>
 */
public class EntityAgentController {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Map<String, ActionComponent<?>> components;

    public EntityAgentController(Collection<ActionComponent<?>> components) {
        var map = new LinkedHashMap<String, ActionComponent<?>>();
        for (var component : components) {
            map.put(componentId(component), component);
        }
        this.components = Map.copyOf(map);
    }

    public void apply(Mob mob, McAction action) {
        if (action == null || action.getComponentsCount() == 0) return;

        for (var entry : action.getComponentsMap().entrySet()) {
            var component = components.get(entry.getKey());
            if (component == null) {
                LOGGER.debug("No action component for key: {}", entry.getKey());
                continue;
            }
            applyComponent(component, mob, entry.getValue(), entry.getKey());
        }
    }

    private static String componentId(ActionComponent<?> component) {
        var key = RegistryKeys.ACTION_COMPONENTS.getKey(component);
        if (key == null) {
            throw new IllegalStateException("Action component is not registered: " + component);
        }
        return key.toString();
    }

    private static <T extends Message> void applyComponent(ActionComponent<T> component, Mob mob, Any any, String key) {
        if (!any.is(component.protoType())) {
            LOGGER.debug("Action component {} has unexpected payload type", key);
            return;
        }
        try {
            var payload = any.unpack(component.protoType());
            if (!component.contains(payload)) {
                LOGGER.debug("Action component {} payload failed validation", key);
                return;
            }
            component.apply(mob, payload);
        } catch (InvalidProtocolBufferException e) {
            LOGGER.warn("Failed to unpack action component {}: {}", key, e.getMessage());
        } catch (Exception e) {
            LOGGER.warn("Error applying action component {}: {}", key, e.getMessage());
        }
    }
}
