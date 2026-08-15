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
import java.util.List;
import java.util.Map;

import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;

/**
 * 动作分发器 —— 解析 {@link ProtoMcAction} 中每个动作组件，按注册表 ID 分发给对应的
 * {@link ActionComponentController} 执行。
 * <p>
 * 校验流程：类型匹配 → 参数合法性 → 执行；任意步骤失败仅跳过，不影响其他组件。
 * 分发器持有每个动作类型在当前环境中创建的独立 controller 实例（id → 实例，
 * {@link LinkedHashMap} 保持工厂声明顺序）。
 * </p>
 * <p>
 * 执行顺序约定：apply/tick/onInterrupt/getState 一律按环境声明顺序
 * （{@link #components} 的迭代顺序）处理 action 中实际出现的组件，
 * 不依赖 protobuf map 的迭代顺序；每个组件的 {@link ActionState} 单独收集，
 * 总体状态经 {@link ActionState#aggregate} 按组件聚合契约生成。
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
            var controller = factory.create(mob);
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

    /**
     * 将 ProtoMcAction 中的组件按环境声明顺序依次分发执行，并聚合组件返回的控制策略与状态。
     * <p>
     * proto 中出现但未注册的组件不产生执行副作用，以 failed 状态按 proto map 顺序并入聚合。
     * </p>
     */
    public ActionApplyResult apply(Mob mob, ProtoMcAction action) {
        if (action == null) {
            return ActionApplyResult.none(ActionState.failed("action is null"));
        }
        if (action.getComponentsCount() == 0) {
            return ActionApplyResult.none(ActionState.failed("action has no components"));
        }

        var policy = ActionControlPolicy.none();
        boolean appliedAny = false;
        List<Map.Entry<String, ActionState>> componentStates = new ArrayList<>();
        // 按环境声明顺序执行 action 中实际出现的组件
        for (var entry : this.components.entrySet()) {
            var any = action.getComponentsMap().get(entry.getKey());
            if (any == null) {
                continue;
            }
            var result = applyComponent(entry.getValue(), mob, any, entry.getKey());
            policy = policy.merge(result.policy());
            appliedAny |= result.appliedAnyComponent();
            componentStates.add(Map.entry(entry.getKey(), result.initialState()));
        }
        // proto 中出现但未注册的组件：以 failed 状态并入聚合
        for (var entry : action.getComponentsMap().entrySet()) {
            if (this.components.containsKey(entry.getKey())) {
                continue;
            }
            LOGGER.debug("No action component controller for key: {}", entry.getKey());
            componentStates.add(Map.entry(entry.getKey(),
                ActionState.failed("unknown action component", Map.of("key", entry.getKey()))));
        }
        return new ActionApplyResult(policy, appliedAny, ActionState.aggregate(componentStates));
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

    /** 将 RUNNING 中动作的所有组件按环境声明顺序逐 tick 分发到对应控制器的 {@code tick} 回调。 */
    public void tick(Mob mob, ProtoMcAction action) {
        if (action == null) {
            return;
        }
        for (var entry : this.components.entrySet()) {
            var any = action.getComponentsMap().get(entry.getKey());
            if (any == null) {
                continue;
            }
            dispatchComponentCallback(entry.getValue(), mob, any, entry.getKey(), true);
        }
    }

    /** RUNNING 中的动作被打断时，按环境声明顺序将中断事件分发到各组件控制器做状态清理。 */
    public void onInterrupt(Mob mob, ProtoMcAction action) {
        if (action == null) {
            return;
        }
        for (var entry : this.components.entrySet()) {
            var any = action.getComponentsMap().get(entry.getKey());
            if (any == null) {
                continue;
            }
            dispatchComponentCallback(entry.getValue(), mob, any, entry.getKey(), false);
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

    /**
     * 按环境声明顺序查询 action 中各组件的当前状态，并经 {@link ActionState#aggregate} 聚合。
     */
    public ActionState getState(Mob mob, ProtoMcAction action) {
        if (action == null || action.getComponentsCount() == 0) {
            return ActionState.completed("no action components");
        }

        List<Map.Entry<String, ActionState>> componentStates = new ArrayList<>();
        for (var entry : this.components.entrySet()) {
            var any = action.getComponentsMap().get(entry.getKey());
            if (any == null) {
                continue;
            }
            componentStates.add(Map.entry(entry.getKey(), getComponentState(entry.getValue(), mob, any, entry.getKey())));
        }
        for (var key : action.getComponentsMap().keySet()) {
            if (!this.components.containsKey(key)) {
                LOGGER.warn("No action component for key: {}", key);
            }
        }
        return ActionState.aggregate(componentStates);
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
}
