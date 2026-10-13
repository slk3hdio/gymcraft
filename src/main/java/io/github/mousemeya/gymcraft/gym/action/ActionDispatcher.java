package io.github.mousemeya.gymcraft.gym.action;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.mojang.logging.LogUtils;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单组件动作分发器。
 * <p>
 * 每个 {@link ProtoMcAction} 只包含一个组件注册 ID 和一个 protobuf 负载。
 * 本类负责查找 controller、校验负载并转发生命周期；多动作串行调度由运行时处理。
 * </p>
 */
public class ActionDispatcher {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Map<String, ActionComponentController<?>> components;

    /**
     * 为指定实体创建动作控制器集合。
     *
     * @param mob 受控实体
     * @param factories 当前环境允许的组件工厂
     */
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
     * 按工厂注册 ID 获取 controller 实例。
     *
     * @param factory 目标组件工厂
     * @param <T> protobuf 消息类型
     * @param <C> controller 类型
     * @return controller 实例
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

    /**
     * 按组件注册 ID 查找 controller 实例（只读）。
     *
     * @param componentId 组件注册 ID
     * @return controller 实例；未注册时为 null
     */
    @Nullable
    public ActionComponentController<?> findComponent(String componentId) {
        return this.components.get(componentId);
    }

    /** @param mob reset 后的新受控实体 */
    public void setMob(Mob mob) {
        for (var controller : this.components.values()) {
            controller.setMob(mob);
        }
    }

    /** @param region 环境默认区域；只经共享接口初始化，不解释组件的具体边界策略 */
    public void initializeDefaultRegion(@Nullable net.minecraft.world.level.levelgen.structure.BoundingBox region) {
        for (var controller : this.components.values()) {
            if (controller instanceof io.github.mousemeya.gymcraft.gym.component.EnvironmentRegionAware spatial) {
                spatial.initializeDefaultRegion(region);
            }
        }
    }

    /** @return 以组件 ID 为键的可选动作空间目录 */
    public McSpace<Map<String, Object>> space() {
        var spaces = new LinkedHashMap<String, McSpace<?>>();
        for (var entry : this.components.entrySet()) {
            spaces.put(entry.getKey(), entry.getValue().space());
        }
        return new DictSpace(spaces);
    }

    /**
     * @param componentId 组件 ID
     * @param space 新空间
     */
    public void setComponentSpace(String componentId, McSpace<Map<String, Object>> space) {
        ActionComponentController<?> controller = this.components.get(componentId);
        if (controller == null) {
            throw new IllegalArgumentException("Unknown action component: " + componentId);
        }
        controller.setSpace(space);
    }

    /**
     * @param componentId 组件 ID
     * @return 组件空间
     */
    public DictSpace getComponentSpace(String componentId) {
        ActionComponentController<?> controller = this.components.get(componentId);
        if (controller == null) {
            throw new IllegalArgumentException("Unknown action component: " + componentId);
        }
        return controller.space();
    }

    /**
     * 校验并应用单组件动作。
     *
     * @param action 单组件动作
     * @return 控制策略与初始状态
     */
    public ActionApplyResult apply(ProtoMcAction action) {
        if (action == null) {
            return ActionApplyResult.none(ActionState.failed("action is null"));
        }
        String componentId = action.getComponentId();
        if (componentId.isBlank()) {
            return ActionApplyResult.none(ActionState.failed("action component_id is empty"));
        }
        if (!action.hasPayload()) {
            return ActionApplyResult.none(ActionState.failed(
                "action payload is missing", Map.of("component_id", componentId)
            ));
        }
        ActionComponentController<?> controller = this.components.get(componentId);
        if (controller == null) {
            return ActionApplyResult.none(ActionState.failed(
                "unknown action component", Map.of("component_id", componentId)
            ));
        }
        return applyComponent(controller, action.getPayload(), componentId);
    }

    /** 对单组件执行类型校验、参数校验和应用。 */
    private static <T extends Message> ActionApplyResult applyComponent(
        ActionComponentController<T> controller,
        Any payload,
        String componentId
    ) {
        if (!payload.is(controller.protoType())) {
            return ActionApplyResult.none(ActionState.failed("unexpected payload type", Map.of(
                "component_id", componentId,
                "expected", controller.protoType().getName(),
                "actual", payload.getTypeUrl()
            )));
        }
        try {
            T unpacked = payload.unpack(controller.protoType());
            if (!controller.contains(unpacked)) {
                return ActionApplyResult.none(ActionState.failed(
                    "payload failed validation", Map.of("component_id", componentId)
                ));
            }
            ActionApplyResult result = controller.apply(unpacked);
            return result == null ? ActionApplyResult.none() : result;
        } catch (InvalidProtocolBufferException e) {
            LOGGER.warn("Failed to unpack action component {}: {}", componentId, e.getMessage());
            return ActionApplyResult.none(ActionState.failed(
                "unpack error: " + e.getMessage(), Map.of("component_id", componentId)
            ));
        } catch (Exception e) {
            LOGGER.warn("Error applying action component {}: {}", componentId, e.getMessage());
            return ActionApplyResult.none(ActionState.failed(
                "apply error: " + e.getMessage(), Map.of("component_id", componentId)
            ));
        }
    }

    /**
     * 校验并推进单组件动作一个 tick。
     *
     * @param action 当前动作
     * @return 推进后的动作状态；负载非法时返回 FAILED 状态
     */
    public ActionState tick(ProtoMcAction action) {
        if (action == null || !action.hasPayload()) {
            return ActionState.failed("invalid action");
        }
        String componentId = action.getComponentId();
        ActionComponentController<?> controller = this.components.get(componentId);
        if (controller == null) {
            return ActionState.failed("unknown action component", Map.of("component_id", componentId));
        }
        return dispatchTick(controller, action.getPayload(), componentId);
    }

    /**
     * 逐组件转发释放回调（reset/环境关闭时由运行时调用）；
     * 单个组件释放异常只记录日志，不阻断其余组件。
     *
     * @param reason 释放原因（reset / clear，用于日志）
     */
    public void releaseComponents(String reason) {
        for (var controller : this.components.values()) {
            try {
                controller.release(reason);
            } catch (Exception e) {
                LOGGER.warn("Error in release callback of action component: {}", e.getMessage());
            }
        }
    }

    /** @param action 需要中断的当前动作 */
    public void onInterrupt(ProtoMcAction action) {
        if (action == null || !action.hasPayload()) {
            return;
        }
        ActionComponentController<?> controller = this.components.get(action.getComponentId());
        if (controller != null) {
            dispatchInterrupt(controller, action.getPayload(), action.getComponentId());
        }
    }

    /** 解包负载并推进 controller 一个 tick，返回当前状态。 */
    private static <T extends Message> ActionState dispatchTick(
        ActionComponentController<T> controller,
        Any payload,
        String componentId
    ) {
        if (!payload.is(controller.protoType())) {
            return ActionState.failed("unexpected payload type", Map.of("component_id", componentId));
        }
        try {
            return controller.tick(payload.unpack(controller.protoType()));
        } catch (InvalidProtocolBufferException e) {
            LOGGER.warn("Failed to unpack action component {}: {}", componentId, e.getMessage());
            return ActionState.failed("unpack error: " + e.getMessage(), Map.of("component_id", componentId));
        } catch (Exception e) {
            LOGGER.warn("Error in tick of action component {}: {}", componentId, e.getMessage());
            return ActionState.failed("tick error: " + e.getMessage(), Map.of("component_id", componentId));
        }
    }

    /** 解包负载并转发 onInterrupt 回调。 */
    private static <T extends Message> void dispatchInterrupt(
        ActionComponentController<T> controller,
        Any payload,
        String componentId
    ) {
        if (!payload.is(controller.protoType())) {
            return;
        }
        try {
            controller.onInterrupt(payload.unpack(controller.protoType()));
        } catch (Exception e) {
            LOGGER.warn("Error in onInterrupt callback of action component {}: {}", componentId, e.getMessage());
        }
    }
}
