package io.github.mousemeya.withme.gym.obs;

import com.google.protobuf.Any;
import io.github.mousemeya.withme.gym.agent.AgentRegistry;
import io.github.mousemeya.withme.gym.observation.proto.McObservation;
import io.github.mousemeya.withme.gym.observation.proto.ObservationHeader;
import io.github.mousemeya.withme.registry.RegistryKeys;
import net.minecraft.world.entity.Mob;

import java.util.Collection;
import java.util.List;

/**
 * 观测构建组合器 —— 不包含任何具体观测逻辑的通用构建器。
 * <p>
 * 接收一组 {@link ObservationComponent}，在 {@link #build(Mob, String)} 时依次调用
 * 每个组件的 build() 方法，将返回的 Protobuf 消息通过 Any 打包后放入最终的
 * {@link McObservation}。新增观测维度只需新增一个 ObservationComponent 实现，
 * 并在对应环境的构造函数中传入即可。
 * </p>
 */
public class EntityObservationBuilder {
    private final List<ObservationComponent<?>> components;

    public EntityObservationBuilder(Collection<ObservationComponent<?>> components) {
        this.components = List.copyOf(components);
    }

    public McObservation build(Mob mob, String agentId) {
        var level = mob.level();
        long gameTick = level.getGameTime();
        var context = new ObservationContext(agentId, gameTick);

        var header = ObservationHeader.newBuilder()
            .setSchemaVersion(1)
            .setGameTick(gameTick)
            .setDimension(level.dimension().identifier().toString())
            .setAgentId(agentId)
            .build();

        var state = AgentRegistry.getState(mob);
        var builder = McObservation.newBuilder().setHeader(header);
        for (var component : components) {
            builder.putComponents(componentId(component), Any.pack(buildComponent(component, mob, state, context)));
        }
        return builder.build();
    }

    private static String componentId(ObservationComponent<?> component) {
        var key = RegistryKeys.OBSERVATION_COMPONENTS.getKey(component);
        if (key == null) {
            throw new IllegalStateException("Observation component is not registered: " + component);
        }
        return key.toString();
    }

    private static <T extends com.google.protobuf.Message> T buildComponent(
        ObservationComponent<T> component,
        Mob mob,
        io.github.mousemeya.withme.gym.agent.AgentControlState state,
        ObservationContext context
    ) {
        return component.build(mob, state, context);
    }
}
