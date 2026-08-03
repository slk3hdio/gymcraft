package io.github.mousemeya.gymcraft.gym.action;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.mojang.logging.LogUtils;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import org.slf4j.Logger;

import java.util.ArrayList;
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
 * 分发器持有每个动作类型在当前环境中创建的独立 controller 实例（id → 实例）。
 * </p>
 */
public class ActionDispatcher {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Map<String, ActionComponentController<?>> components;

    /** 为指定实体创建动作控制器集合：对每个工厂 create 独立实例并校验实体支持性。 */
    public ActionDispatcher(Mob mob, Collection<ActionComponentFactory<?>> factories) {
        var map = new LinkedHashMap<String, ActionComponentController<?>>();
        var unsupported = new ArrayList<String>();
        for (var factory : factories) {
            var controller = factory.create();
            if (!controller.supports(mob)) {
                unsupported.add(factory.getRegisterId());
            }
            map.put(factory.getRegisterId(), controller);
        }
        if (!unsupported.isEmpty()) {
            String mobType = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
            throw new IllegalArgumentException(
                "Cannot create action dispatcher for mob " + mobType + " (" + mob.getUUID()
                    + ") because unsupported actions are registered: " + unsupported
            );
        }
        this.components = map;
    }

    public McSpace<Map<String, Object>> space() {
        var spaces = new LinkedHashMap<String, McSpace<?>>();
        for (var entry : this.components.entrySet()) {
            spaces.put(entry.getKey(), entry.getValue().space());
        }
        return new DictSpace(spaces);
    }

    public void setComponentSpace(String componentId, McSpace<Map<String, Object>> space) {
        ActionComponentController<?> controller = this.components.get(componentId);
        if (controller == null) {
            throw new IllegalArgumentException("Unknown action component: " + componentId);
        }
        controller.setSpace(space);
    }

    public McSpace<Map<String, Object>> getComponentSpace(String componentId) {
        ActionComponentController<?> controller = this.components.get(componentId);
        if (controller == null) {
            throw new IllegalArgumentException("Unknown action component: " + componentId);
        }
        return controller.space();
    }

    /** 将 ProtoMcAction 中的所有组件依次分发执行，并聚合组件返回的控制策略。 */
    public ActionApplyResult apply(Mob mob, ProtoMcAction action) {
        if (action == null) {
            return ActionApplyResult.none(ActionState.failed("action is null"));
        }
        if (action.getComponentsCount() == 0) {
            return ActionApplyResult.none(ActionState.failed("action has no components"));
        }

        var result = ActionApplyResult.none();
        for (var entry : action.getComponentsMap().entrySet()) {
            var controller = components.get(entry.getKey());
            if (controller == null) {
                LOGGER.debug("No action component controller for key: {}", entry.getKey());
                result = result.merge(ActionApplyResult.none(ActionState.failed("unknown action component", Map.of("key", entry.getKey()))));
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
            return ActionApplyResult.none(ActionState.failed("unexpected payload type", Map.of(
                "key", key,
                "expected", controller.protoType().getName(),
                "actual", any.getTypeUrl()
            )));
        }
        try {
            var payload = any.unpack(controller.protoType());
            if (!controller.contains(payload)) {
                LOGGER.debug("Action component controller {} payload failed validation", key);
                return ActionApplyResult.none(ActionState.failed("payload failed validation", Map.of("key", key)));
            }
            var result = controller.apply(mob, payload);
            return result == null ? ActionApplyResult.none() : result;
        } catch (InvalidProtocolBufferException e) {
            LOGGER.warn("Failed to unpack action component controller {}: {}", key, e.getMessage());
            return ActionApplyResult.none(ActionState.failed("unpack error: " + e.getMessage(), Map.of("key", key)));
        } catch (Exception e) {
            LOGGER.warn("Error applying action component controller {}: {}", key, e.getMessage());
            return ActionApplyResult.none(ActionState.failed("apply error: " + e.getMessage(), Map.of("key", key)));
        }
    }

    /** 将 RUNNING 中动作的所有组件逐 tick 分发到对应控制器的 {@code tick} 回调。 */
    public void tick(Mob mob, ProtoMcAction action) {
        if (action == null) {
            return;
        }
        for (var entry : action.getComponentsMap().entrySet()) {
            var controller = components.get(entry.getKey());
            if (controller == null) {
                continue;
            }
            dispatchComponentCallback(controller, mob, entry.getValue(), entry.getKey(), true);
        }
    }

    /** RUNNING 中的动作被打断时，将中断事件分发到各组件控制器做状态清理。 */
    public void onInterrupt(Mob mob, ProtoMcAction action) {
        if (action == null) {
            return;
        }
        for (var entry : action.getComponentsMap().entrySet()) {
            var controller = components.get(entry.getKey());
            if (controller == null) {
                continue;
            }
            dispatchComponentCallback(controller, mob, entry.getValue(), entry.getKey(), false);
        }
    }

    private static <T extends Message> void dispatchComponentCallback(
        ActionComponentController<T> controller,
        Mob mob,
        Any any,
        String key,
        boolean isTick
    ) {
        if (!any.is(controller.protoType())) {
            return;
        }
        try {
            var payload = any.unpack(controller.protoType());
            if (isTick) {
                controller.tick(mob, payload);
            } else {
                controller.onInterrupt(mob, payload);
            }
        } catch (Exception e) {
            LOGGER.warn("Error in {} callback of action component controller {}: {}", isTick ? "tick" : "onInterrupt", key, e.getMessage());
        }
    }

    public ActionState getState(Mob mob, ProtoMcAction action) {
        if (action == null || action.getComponentsCount() == 0) {
            return ActionState.completed("no action components");
        }

        ActionState merged = null;
        for (var entry : action.getComponentsMap().entrySet()) {
            var controller = components.get(entry.getKey());
            if (controller == null) {
                LOGGER.warn("No action component for key: {}", entry.getKey());
                continue;
            }
            ActionState state = getComponentState(controller, mob, entry.getValue(), entry.getKey());
            merged = mergeState(merged, state);
        }
        return merged == null ? ActionState.completed("no components processed") : merged;
    }

    private static <T extends Message> ActionState getComponentState(ActionComponentController<T> controller, Mob mob, Any any, String key) {
        if (!any.is(controller.protoType())) {
            LOGGER.debug("Action component controller {} has unexpected payload type", key);
            return ActionState.completed("unexpected payload type");
        }
        try {
            return controller.getState(mob, any.unpack(controller.protoType()));
        } catch (InvalidProtocolBufferException e) {
            LOGGER.warn("Failed to unpack action component controller {}: {}", key, e.getMessage());
            return ActionState.failed("unpack error: " + e.getMessage());
        }
    }

    private static ActionState mergeState(ActionState a, ActionState b) {
        if (a == null) return b;
        if (b == null) return a;
        int cmp = priority(a.status()) - priority(b.status());
        if (cmp < 0) return b;
        if (cmp > 0) return a;
        if (a.status() == ActionStatus.RUNNING) return a;
        return a;
    }

    private static int priority(ActionStatus status) {
        return switch (status) {
            case COMPLETED -> 0;
            case RUNNING -> 1;
            case INTERRUPTED -> 2;
            case FAILED -> 3;
        };
    }
}
