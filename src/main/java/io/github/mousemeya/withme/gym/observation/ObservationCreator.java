package io.github.mousemeya.withme.gym.observation;

import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.protobuf.Any;
import com.google.protobuf.Message;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.withme.registry.RegistryKeys;
import io.github.mousemeya.withme.gym.observation.proto.ProtoMcObservation;


/**
 * 观测构建组合器 —— 不包含任何具体观测逻辑的通用构建器。
 * <p>
 * 在 create() 时依次调用每个组件的 create() 方法，
 * 将返回的 Protobuf 消息通过 Any 打包后放入最终的 McObservation。
 * </p>
 */
public class ObservationCreator {
    private static final Logger LOGGER = LoggerFactory.getLogger(ObservationCreator.class);
    private final List<ObservationComponentCreator<? extends Message>> components;

    ObservationCreator(Collection<ObservationComponentCreator<?>> components) {
        this.components = List.copyOf(components);
    }

    public ProtoMcObservation create(Mob mob) {
        return ProtoMcObservation.newBuilder().build();
    }
}
