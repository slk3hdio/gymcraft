package io.github.mousemeya.withme.gym.observation;

import com.google.protobuf.Any;
import io.github.mousemeya.withme.gym.agent.AgentRegistry;
import io.github.mousemeya.withme.gym.observationervation.proto.McObservation;
import io.github.mousemeya.withme.gym.observationervation.proto.ObservationHeader;
import io.github.mousemeya.withme.registry.RegistryKeys;
import net.minecraft.world.entity.Mob;

import java.util.Collection;
import java.util.List;

/**
 * 观测构建组合器 —— 不包含任何具体观测逻辑的通用构建器。
 * <p>
 * 在 create() 时依次调用每个组件的 create() 方法，
 * 将返回的 Protobuf 消息通过 Any 打包后放入最终的 McObservation。
 * </p>
 */
public class ObservationCreator {
    private final List<ObservationComponentCreator<?>> components;

    public ObservationCreator(Collection<ObservationComponentCreator<?>> components) {
        this.components = List.copyOf(components);
    }

    /** 构建完整的 McObservation 消息，包含 header 和所有注册的观测组件。 */
    public McObservation create(Mob mob, String agentId) {
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
            builder.putComponents(componentId(component), Any.pack(createComponent(component, mob, state, context)));
        }
        return builder.build();
    }

    private static String componentId(ObservationComponentCreator<?> component) {
        var key = RegistryKeys.OBSERVATION_COMPONENTS.getKey(component);
        if (key == null) {
            throw new IllegalStateException("Observation component is not registered: " + component);
        }
        return key.toString();
    }

    private static <T extends com.google.protobuf.Message> T createComponent(
        ObservationComponentCreator<T> component,
        Mob mob,
        io.github.mousemeya.withme.gym.agent.AgentControlState state,
        ObservationContext context
    ) {
        return component.create(mob, state, context);
    }
}
