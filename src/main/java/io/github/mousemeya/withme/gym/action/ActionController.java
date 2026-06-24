package io.github.mousemeya.withme.gym.action;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.mojang.logging.LogUtils;
import io.github.mousemeya.withme.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.withme.registry.RegistryKeys;
import net.minecraft.world.entity.Mob;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 动作分发器 —— 解析 {@link ProtoMcAction} 中每个动作组件，按注册表 ID 分发给对应的
 * {@link ActionComponentController} 执行。
 * <p>
 * 校验流程：类型匹配 → 参数合法性 → 执行；任意步骤失败仅跳过，不影响其他组件。
 * </p>
 */
public class ActionController {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Map<String, ActionComponentController<?>> components;

    /** @param components 该控制器可处理的所有动作组件实例 */
    public ActionController(Collection<ActionComponentController<?>> components) {
        var map = new LinkedHashMap<String, ActionComponentController<?>>();
        for (var component : components) {
            map.put(componentId(component), component);
        }
        this.components = Map.copyOf(map);
    }

    /** 将 ProtoMcAction 中的所有组件依次分发执行。 */
    public void apply(Mob mob, ProtoMcAction action) {
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

    private static String componentId(ActionComponentController<?> component) {
        var key = RegistryKeys.ACTION_COMPONENTS.getKey(component);
        if (key == null) {
            throw new IllegalStateException("Action component is not registered: " + component);
        }
        return key.toString();
    }

    /** 对单个动作组件执行类型校验、参数校验和执行。 */
    private static <T extends Message> void applyComponent(ActionComponentController<T> component, Mob mob, Any any, String key) {
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

    private boolean isDone(Mob mob, ProtoMcAction action) {
        for (var entry : action.getComponentsMap().entrySet()) {
            var component = components.get(entry.getKey());
            if (component == null) {
                LOGGER.warn("No action component for key: {}", entry.getKey());
                continue;
            }
            if (!component.isDone(mob, entry.getValue())) {
                return false;
            }
        }
        return true;
    }
}
