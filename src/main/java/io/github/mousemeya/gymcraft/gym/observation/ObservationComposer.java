package io.github.mousemeya.gymcraft.gym.observation;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.protobuf.Any;
import com.google.protobuf.Message;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoActionFeedback;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoObservationHeader;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMcObservation;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.space.ComponentSpaceSet;
import io.github.mousemeya.gymcraft.gym.space.McSpace;


/**
 * 观测构建组合器 —— 不包含任何具体观测逻辑的通用构建器。
 * <p>
 * 在 create() 时依次调用每个组件的 create() 方法，
 * 将返回的 Protobuf 消息通过 Any 打包后放入最终的 McObservation。
 * 组合器持有每个观测类型在当前环境中创建的独立 creator 实例（id → 实例）。
 * </p>
 */
public class ObservationComposer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ObservationComposer.class);
    private final Map<String, ObservationComponentCreator<?>> components;

    public ObservationComposer(Mob mob, Collection<? extends ObservationComponentFactory<?, ?>> factories) {
        var map = new LinkedHashMap<String, ObservationComponentCreator<?>>();
        for (var factory : factories) {
            map.put(factory.getRegisterId(), factory.create(mob));
        }
        this.components = map;
    }

    /**
     * 按注册顺序对全部观测组件执行操作（如环境构造期推送区域）。
     *
     * @param action 逐组件执行的操作
     */
    public void forEachComponent(java.util.function.Consumer<ObservationComponentCreator<?>> action) {
        this.components.values().forEach(action);
    }

    /**
     * 按工厂注册 id 获取当前环境的组件 creator 实例，并通过工厂类型令牌校验具体类型。
     *
     * @param factory 目标观测组件工厂
     * @param <T> protobuf 消息类型
     * @param <C> 具体观测生成器类型
     * @return 当前环境中的具体观测生成器实例
     */
    public <T extends Message, C extends ObservationComponentCreator<T>> C getComponent(
        ObservationComponentFactory<T, C> factory
    ) {
        ObservationComponentCreator<?> creator = this.components.get(factory.getRegisterId());
        if (creator == null) {
            throw new IllegalArgumentException("Unknown observation component: " + factory.getRegisterId());
        }
        return factory.componentType().cast(creator);
    }

    /**
     * 按组件注册 ID 查找 creator 实例（只读）。
     *
     * @param componentId 组件注册 ID
     * @return creator 实例；未注册时为 null
     */
    @Nullable
    public ObservationComponentCreator<?> findComponent(String componentId) {
        return this.components.get(componentId);
    }

    /**
     * 组装一次完整观测。
     *
     * <p>该观测同时用于 gRPC 返回与只读可视化：末尾把这份不可变快照交给可视化
     * 挂钩，绝不重复调用 creator（部分 creator 带有会话副作用）。</p>
     *
     * @param mob             采样实体
     * @param lastActionState 上一动作的执行结果
     * @return 同时用于 RL 返回与只读可视化的真实观测
     */
    public ProtoMcObservation create(Mob mob, ActionState lastActionState) {
        try (var composeZone = Profiler.get().zone("observation_compose")) {
            ProtoObservationHeader.Builder headerBuilder;
            try (var headerZone = Profiler.get().zone("obs_header")) {
                headerBuilder = ProtoObservationHeader.newBuilder()
                    .setSchemaVersion(1)
                    .setGameTick(mob.level().getGameTime())
                    // .setDimension(mob.level().dimension().identifier().toString())
                    .setAgentId(mob.getUUID().toString());
                if (lastActionState != null) {
                    headerBuilder.setLastActionStatus(lastActionState.status().name());
                    headerBuilder.setLastActionDescription(lastActionState.description());
                    // 批次状态 details["actions"] 携带逐项明细（ActionBatchExecutor.batchState），
                    // 聚合到 header.last_actions，使逐动作反馈成为观测的一部分而非仅存在于 info
                    for (ProtoActionFeedback feedback : expandActionFeedback(lastActionState)) {
                        headerBuilder.addLastActions(feedback);
                    }
                }
            }

            var builder = ProtoMcObservation.newBuilder()
                .setHeader(headerBuilder.build());

            for (var entry : this.components.entrySet()) {
                try (var zone = Profiler.get().zone("obs_" + entry.getKey())) {
                    builder.putComponents(entry.getKey(), Any.pack(entry.getValue().create(mob)));
                } catch (RuntimeException e) {
                    LOGGER.warn("Failed to create observation component {}: {}", entry.getKey(), e.getMessage());
                }
            }
            ProtoMcObservation observation = builder.build();
            // 可视化只消费本次真实观测，不能重复调用有会话副作用的 creator。
            try (var visualizationZone = Profiler.get().zone("obs_visualization")) {
                io.github.mousemeya.gymcraft.gym.visualization.VisualizationService.observation(mob, observation);
            }
            return observation;
        }
    }

    /**
     * 把批次状态 {@code details["actions"]} 中的逐项明细展开为 header 反馈列表。
     * <p>
     * details 是面向 info JSON 的弱类型映射，这里做防御性解析：缺键或元素类型不符时
     * 跳过对应项，返回空列表也不影响摘要字段。status 统一大写，与
     * {@code last_action_status} 的 {@code ActionStatus.name()} 口径一致。
     * </p>
     *
     * @param lastActionState 上一批次的终态
     * @return 逐动作反馈列表（无明细时为空）
     */
    private static List<ProtoActionFeedback> expandActionFeedback(ActionState lastActionState) {
        Object actions = lastActionState.details().get("actions");
        if (!(actions instanceof List<?> list)) {
            return List.of();
        }
        var result = new java.util.ArrayList<ProtoActionFeedback>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Object index = map.get("index");
            result.add(ProtoActionFeedback.newBuilder()
                .setIndex(index instanceof Number number ? number.intValue() : result.size())
                .setComponentId(asString(map.get("component_id")))
                .setStatus(asString(map.get("status")).toUpperCase(Locale.ROOT))
                .setDescription(asString(map.get("description")))
                .build());
        }
        return result;
    }

    /** @return 弱类型明细值的字符串形式，缺失时为 "" */
    private static String asString(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** @return 以组件 ID 为键的有序观测空间目录 */
    public ComponentSpaceSet space() {
        var spaces = new LinkedHashMap<String, McSpace<?>>();
        for (var entry : this.components.entrySet()) {
            spaces.put(entry.getKey(), entry.getValue().space());
        }
        return new ComponentSpaceSet(spaces);
    }

    /**
     * 设置指定组件的观测空间（descriptor 与组件 proto 类型不一致时由 creator 抛错）。
     *
     * @param componentId 组件 ID
     * @param space 新空间
     */
    public void setComponentSpace(String componentId, McSpace<? extends Message> space) {
        ObservationComponentCreator<?> creator = this.components.get(componentId);
        if (creator == null) {
            throw new IllegalArgumentException("Unknown observation component: " + componentId);
        }
        setSpaceUnchecked(creator, space);
    }

    /** @param componentId 组件 ID @return 组件空间 */
    public McSpace<? extends Message> getComponentSpace(String componentId) {
        ObservationComponentCreator<?> creator = this.components.get(componentId);
        if (creator == null) {
            throw new IllegalArgumentException("Unknown observation component: " + componentId);
        }
        return creator.space();
    }

    /** 通配捕获桥接：descriptor 一致性由 {@code creator.setSpace} 校验。 */
    @SuppressWarnings("unchecked")
    private static <T extends Message> void setSpaceUnchecked(
        ObservationComponentCreator<T> creator,
        McSpace<? extends Message> space
    ) {
        creator.setSpace((McSpace<T>) space);
    }
}
