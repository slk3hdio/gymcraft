package io.github.mousemeya.gymcraft.gym.action;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.mojang.logging.LogUtils;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import org.slf4j.Logger;

import javax.annotation.Nullable;

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
 * {@link LinkedHashMap} 保持工厂声明顺序）。每个 controller 在构造时绑定 Mob，
 * reset 重建实体后由运行时经 {@link #setMob(Mob)} 同步更新。
 * </p>
 * <p>
 * 执行顺序约定：apply/tick/onInterrupt/getState 一律按环境声明顺序
 * （{@link #components} 的迭代顺序）处理 action 中实际出现的组件，
 * 不依赖 protobuf map 的迭代顺序；每个组件的 {@link ActionState} 单独收集，
 * 总体状态经 {@link ActionState#aggregate} 按组件聚合契约生成。
 * </p>
 * <p>
 * 动作级超时：{@link ProtoMcAction#getTimeoutSeconds()} 以秒为单位（&le; 0 表示不限制），
 * 本分发器按 {@link #TICKS_PER_SECOND} tick/秒换算。{@link #apply} 绑定当前动作并清零计时，
 * {@link #tick} 逐 tick 累加；超时当 tick 不再分发组件 tick，而是按中断路径通知各组件
 * 清理跨 tick 状态，此后 {@link #getState} 对该动作直接返回 failed 终态。
 * </p>
 */
public class ActionDispatcher {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 游戏刻速率：每秒 20 tick，用于把动作级超时（秒）换算为 tick 数。 */
    private static final int TICKS_PER_SECOND = 20;

    private final Map<String, ActionComponentController<?>> components;
    /** 当前正在逐 tick 推进的动作（{@link #apply} 时绑定，中断后清除）。 */
    @Nullable
    private ProtoMcAction activeAction;
    /** {@link #activeAction} 已经过的 tick 数。 */
    private int runningTicks;
    /** 已因超时中断的动作；{@link #getState} 对其直接返回 failed 终态。 */
    @Nullable
    private ProtoMcAction timedOutAction;

    /** 为指定实体创建动作控制器集合：对每个工厂 create 独立实例并校验实体支持性。 */
    public ActionDispatcher(Mob mob, Collection<? extends ActionComponentFactory<?, ?>> factories) {
        var map = new LinkedHashMap<String, ActionComponentController<?>>();
        var unsupported = new ArrayList<String>();
        for (var factory : factories) {
            var controller = factory.create(mob);
            if (!controller.supports()) {
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

    /**
     * 按工厂注册 id 获取当前环境的组件 controller 实例，并通过工厂类型令牌校验具体类型。
     *
     * @param factory 目标动作组件工厂
     * @param <T> protobuf 消息类型
     * @param <C> 具体动作控制器类型
     * @return 当前环境中的具体动作控制器实例
     */
    public <T extends Message, C extends ActionComponentController<T>> C getComponent(
        ActionComponentFactory<T, C> factory
    ) {
        ActionComponentController<?> controller = this.components.get(factory.getRegisterId());
        if (controller == null) {
            throw new IllegalArgumentException("Unknown action component: " + factory.getRegisterId());
        }
        return factory.componentType().cast(controller);
    }

    /** 更新所有控制器绑定的 Mob（reset 重建实体后由运行时调用）。 */
    public void setMob(Mob mob) {
        for (var controller : this.components.values()) {
            controller.setMob(mob);
        }
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
    public ActionApplyResult apply(ProtoMcAction action) {
        if (action == null) {
            return ActionApplyResult.none(ActionState.failed("action is null"));
        }
        if (action.getComponentsCount() == 0) {
            return ActionApplyResult.none(ActionState.failed("action has no components"));
        }

        // 绑定当前动作并清零超时计时（新动作同时清除旧动作的超时标记）
        this.activeAction = action;
        this.runningTicks = 0;
        this.timedOutAction = null;

        var policy = ActionControlPolicy.none();
        boolean appliedAny = false;
        List<Map.Entry<String, ActionState>> componentStates = new ArrayList<>();
        // 按环境声明顺序执行 action 中实际出现的组件
        for (var entry : this.components.entrySet()) {
            var any = action.getComponentsMap().get(entry.getKey());
            if (any == null) {
                continue;
            }
            var result = applyComponent(entry.getValue(), any, entry.getKey());
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
    private static <T extends Message> ActionApplyResult applyComponent(ActionComponentController<T> controller, Any any, String key) {
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
            var result = controller.apply(payload);
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
    public void tick(ProtoMcAction action) {
        if (action == null) {
            return;
        }
        if (action != this.activeAction) {
            // 防御：未经过 apply 绑定的动作从 0 重新计时
            this.activeAction = action;
            this.runningTicks = 0;
        }
        this.runningTicks++;
        int timeoutTicks = timeoutTicks(action);
        if (timeoutTicks > 0 && this.runningTicks >= timeoutTicks && this.timedOutAction != action) {
            // 超时：本 tick 不再分发组件 tick，按中断路径通知各组件清理跨 tick 状态，
            // 之后 getState 对该动作直接返回 failed 终态
            LOGGER.info("Action timeout after {} ticks (limit {}s)", this.runningTicks, action.getTimeoutSeconds());
            this.timedOutAction = action;
            for (var entry : this.components.entrySet()) {
                var any = action.getComponentsMap().get(entry.getKey());
                if (any == null) {
                    continue;
                }
                dispatchComponentCallback(entry.getValue(), any, entry.getKey(), false);
            }
            return;
        }
        for (var entry : this.components.entrySet()) {
            var any = action.getComponentsMap().get(entry.getKey());
            if (any == null) {
                continue;
            }
            dispatchComponentCallback(entry.getValue(), any, entry.getKey(), true);
        }
    }

    /** RUNNING 中的动作被打断时，按环境声明顺序将中断事件分发到各组件控制器做状态清理。 */
    public void onInterrupt(ProtoMcAction action) {
        if (action == null) {
            return;
        }
        // 外部中断：清除超时计时与超时标记
        if (action == this.activeAction) {
            this.activeAction = null;
            this.runningTicks = 0;
        }
        if (action == this.timedOutAction) {
            this.timedOutAction = null;
        }
        for (var entry : this.components.entrySet()) {
            var any = action.getComponentsMap().get(entry.getKey());
            if (any == null) {
                continue;
            }
            dispatchComponentCallback(entry.getValue(), any, entry.getKey(), false);
        }
    }

    private static <T extends Message> void dispatchComponentCallback(
        ActionComponentController<T> controller,
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
                controller.tick(payload);
            } else {
                controller.onInterrupt(payload);
            }
        } catch (Exception e) {
            LOGGER.warn("Error in {} callback of action component controller {}: {}", isTick ? "tick" : "onInterrupt", key, e.getMessage());
        }
    }

    /**
     * 按环境声明顺序查询 action 中各组件的当前状态，并经 {@link ActionState#aggregate} 聚合。
     */
    public ActionState getState(ProtoMcAction action) {
        if (action == null || action.getComponentsCount() == 0) {
            return ActionState.completed("no action components");
        }
        // 超时动作：直接返回 failed 终态，不再聚合组件状态
        if (action == this.timedOutAction) {
            return ActionState.failed("action timeout", Map.of(
                "timeout_seconds", (double) action.getTimeoutSeconds(),
                "elapsed_ticks", this.runningTicks
            ));
        }

        List<Map.Entry<String, ActionState>> componentStates = new ArrayList<>();
        for (var entry : this.components.entrySet()) {
            var any = action.getComponentsMap().get(entry.getKey());
            if (any == null) {
                continue;
            }
            componentStates.add(Map.entry(entry.getKey(), getComponentState(entry.getValue(), any, entry.getKey())));
        }
        for (var key : action.getComponentsMap().keySet()) {
            if (!this.components.containsKey(key)) {
                LOGGER.warn("No action component for key: {}", key);
            }
        }
        return ActionState.aggregate(componentStates);
    }

    /**
     * 将动作级超时（秒）按 {@link #TICKS_PER_SECOND} 换算为 tick 数。
     *
     * @return 超时 tick 数；{@code timeout_seconds <= 0} 时返回 0 表示不超时，正数至少为 1 tick
     */
    private static int timeoutTicks(ProtoMcAction action) {
        float seconds = action.getTimeoutSeconds();
        if (seconds <= 0) {
            return 0;
        }
        return Math.max(1, Math.round(seconds * TICKS_PER_SECOND));
    }

    private static <T extends Message> ActionState getComponentState(ActionComponentController<T> controller, Any any, String key) {
        if (!any.is(controller.protoType())) {
            LOGGER.debug("Action component controller {} has unexpected payload type", key);
            return ActionState.completed("unexpected payload type");
        }
        try {
            return controller.getState(any.unpack(controller.protoType()));
        } catch (InvalidProtocolBufferException e) {
            LOGGER.warn("Failed to unpack action component controller {}: {}", key, e.getMessage());
            return ActionState.failed("unpack error: " + e.getMessage());
        }
    }
}
