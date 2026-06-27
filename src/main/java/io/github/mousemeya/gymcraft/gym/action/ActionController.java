package io.github.mousemeya.gymcraft.gym.action;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.mojang.logging.LogUtils;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.registry.RegistryKeys;
import net.minecraft.world.entity.Mob;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;

/**
 * 动作分发器 —— 解析 {@link ProtoMcAction} 中每个动作组件，按注册表 ID 分发给对应的
 * {@link ActionComponentController} 执行。
 * <p>
 * 校验流程：类型匹配 → 参数合法性 → 执行；任意步骤失败仅跳过，不影响其他组件。
 * </p>
 */
public class ActionController {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Map<String, ActionComponentController<?>> componentControllers;

    /** @param controllers 该控制器可处理的所有动作组件实例 */
    public ActionController(Collection<ActionComponentController<?>> controllers) {
        var map = new LinkedHashMap<String, ActionComponentController<?>>();
        for (var controller : controllers) {
            map.put(controller.getRegisterId(), controller);
        }
        this.componentControllers = Map.copyOf(map);
    }

    public McSpace<Map<String, Object>> space() {
        var spaces = new LinkedHashMap<String, McSpace<?>>();
        for (var entry : this.componentControllers.entrySet()) {
            spaces.put(entry.getKey(), entry.getValue().space());
        }
        return new DictSpace(spaces);
    }

    /** 将 ProtoMcAction 中的所有组件依次分发执行，并聚合组件返回的控制策略。 */
    public ActionApplyResult apply(Mob mob, ProtoMcAction action) {
        if (action == null || action.getComponentsCount() == 0) return ActionApplyResult.none();

        var result = ActionApplyResult.none();
        for (var entry : action.getComponentsMap().entrySet()) {
            var controller = componentControllers.get(entry.getKey());
            if (controller == null) {
                LOGGER.debug("No action component controller for key: {}", entry.getKey());
                continue;
            }
            result = result.merge(applyComponent(controller, mob, entry.getValue(), entry.getKey()));
        }
        return result;
    }

    /** 对单个动作组件执行类型校验、参数校验和执行。 */
    private static <T extends Message> ActionApplyResult applyComponent(ActionComponentController<T> controller, Mob mob, Any any, String key) {
        if (!any.is(controller.protoType())) {
            LOGGER.debug("Action component controller {} has unexpected payload type", key);
            return ActionApplyResult.none();
        }
        try {
            var payload = any.unpack(controller.protoType());
            if (!controller.contains(payload)) {
                LOGGER.debug("Action component controller {} payload failed validation", key);
                return ActionApplyResult.none();
            }
            var result = controller.apply(mob, payload);
            return result == null ? ActionApplyResult.none() : result;
        } catch (InvalidProtocolBufferException e) {
            LOGGER.warn("Failed to unpack action component controller {}: {}", key, e.getMessage());
        } catch (Exception e) {
            LOGGER.warn("Error applying action component controller {}: {}", key, e.getMessage());
        }
        return ActionApplyResult.none();
    }

    public boolean isDone(Mob mob, ProtoMcAction action) {
        if (action == null || action.getComponentsCount() == 0) return true;

        for (var entry : action.getComponentsMap().entrySet()) {
            var controller = componentControllers.get(entry.getKey());
            if (controller == null) {
                LOGGER.warn("No action component for key: {}", entry.getKey());
                continue;
            }
            if (!isComponentDone(controller, mob, entry.getValue(), entry.getKey())) {
                return false;
            }
        }
        return true;
    }

    private static <T extends Message> boolean isComponentDone(ActionComponentController<T> controller, Mob mob, Any any, String key) {
        if (!any.is(controller.protoType())) {
            LOGGER.debug("Action component controller {} has unexpected payload type", key);
            return true;
        }
        try {
            return controller.isDone(mob, any.unpack(controller.protoType()));
        } catch (InvalidProtocolBufferException e) {
            LOGGER.warn("Failed to unpack action component controller {}: {}", key, e.getMessage());
            return true;
        }
    }
}
