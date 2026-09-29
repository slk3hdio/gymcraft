package io.github.mousemeya.gymcraft.gym.observation;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.protobuf.Any;
import com.google.protobuf.Message;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoObservationHeader;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMcObservation;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
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
        var headerBuilder = ProtoObservationHeader.newBuilder()
            .setSchemaVersion(1)
            .setGameTick(mob.level().getGameTime())
            // .setDimension(mob.level().dimension().identifier().toString())
            .setAgentId(mob.getUUID().toString());
        if (lastActionState != null) {
            headerBuilder.setLastActionStatus(lastActionState.status().name());
            headerBuilder.setLastActionDescription(lastActionState.description());
        }

        var builder = ProtoMcObservation.newBuilder()
            .setHeader(headerBuilder.build());

        for (var entry : this.components.entrySet()) {
            try {
                builder.putComponents(entry.getKey(), Any.pack(entry.getValue().create(mob)));
            } catch (RuntimeException e) {
                LOGGER.warn("Failed to create observation component {}: {}", entry.getKey(), e.getMessage());
            }
        }
        ProtoMcObservation observation = builder.build();
        // 可视化只消费本次真实观测，不能重复调用有会话副作用的 creator。
        io.github.mousemeya.gymcraft.gym.visualization.VisualizationService.observation(mob, observation);
        return observation;
    }

    public McSpace<Map<String, Object>> space() {
        var spaces = new LinkedHashMap<String, McSpace<?>>();
        for (var entry : this.components.entrySet()) {
            spaces.put(entry.getKey(), entry.getValue().space());
        }
        return new DictSpace(spaces);
    }

    public void setComponentSpace(String componentId, McSpace<Map<String, Object>> space) {
        ObservationComponentCreator<?> creator = this.components.get(componentId);
        if (creator == null) {
            throw new IllegalArgumentException("Unknown observation component: " + componentId);
        }
        creator.setSpace(space);
    }

    public McSpace<Map<String, Object>> getComponentSpace(String componentId) {
        ObservationComponentCreator<?> creator = this.components.get(componentId);
        if (creator == null) {
            throw new IllegalArgumentException("Unknown observation component: " + componentId);
        }
        return creator.space();
    }
}
