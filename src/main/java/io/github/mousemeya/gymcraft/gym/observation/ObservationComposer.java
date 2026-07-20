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
 * </p>
 */
public class ObservationComposer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ObservationComposer.class);
    private final Map<String, ObservationComponentBinding<?>> componentBindings;

    public ObservationComposer(Collection<ObservationComponentCreator<?>> components) {
        var map = new LinkedHashMap<String, ObservationComponentBinding<?>>();
        for (var component : components) {
            map.put(component.getRegisterId(), ObservationComponentBinding.usingDefaultSpace(component));
        }
        this.componentBindings = map;
    }

    public ObservationComposer(Collection<ObservationComponentBinding<?>> bindings, boolean ignored) {
        var map = new LinkedHashMap<String, ObservationComponentBinding<?>>();
        for (var binding : bindings) {
            map.put(binding.creator().getRegisterId(), binding);
        }
        this.componentBindings = map;
    }

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

        for (ObservationComponentBinding<?> binding : this.componentBindings.values()) {
            ObservationComponentCreator<? extends Message> component = binding.creator();
            try {
                builder.putComponents(component.getRegisterId(), Any.pack(component.create(mob)));
            } catch (RuntimeException e) {
                LOGGER.warn("Failed to create observation component {}: {}", component, e.getMessage());
            }
        }
        return builder.build();
    }

    public McSpace<Map<String, Object>> space() {
        var spaces = new LinkedHashMap<String, McSpace<?>>();
        for (var entry : this.componentBindings.entrySet()) {
            spaces.put(entry.getKey(), entry.getValue().space());
        }
        return new DictSpace(spaces);
    }

    public void setComponentSpace(String componentId, McSpace<Map<String, Object>> space) {
        ObservationComponentBinding<?> binding = this.componentBindings.get(componentId);
        if (binding == null) {
            throw new IllegalArgumentException("Unknown observation component: " + componentId);
        }
        this.componentBindings.put(componentId, replaceSpace(binding, space));
    }

    public McSpace<Map<String, Object>> getComponentSpace(String componentId) {
        ObservationComponentBinding<?> binding = this.componentBindings.get(componentId);
        if (binding == null) {
            throw new IllegalArgumentException("Unknown observation component: " + componentId);
        }
        return binding.space();
    }

    private static <T extends Message> ObservationComponentBinding<T> replaceSpace(
        ObservationComponentBinding<T> binding,
        McSpace<Map<String, Object>> space
    ) {
        return binding.withSpace(space);
    }
}
