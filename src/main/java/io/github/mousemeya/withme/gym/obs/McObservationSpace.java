package io.github.mousemeya.withme.gym.obs;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.github.mousemeya.withme.gym.observation.proto.McObservation;
import io.github.mousemeya.withme.gym.observation.proto.ObservationHeader;
import io.github.mousemeya.withme.gym.space.McSpace;
import io.github.mousemeya.withme.registry.RegistryKeys;
import net.minecraft.resources.Identifier;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 观测空间 —— 对 {@link McObservation} 的组合空间封装，实现 {@link McSpace} 接口。
 * <p>
 * 内部维护一个 {@code Map<String, ObservationComponent>} 的不可变映射，
 * 所有组件的 schema 和校验完全委托给各个 {@link ObservationComponent} 实现。
 * 核心层不需要知道任何具体观测组件类型。
 * </p>
 * <p>
 * sample() 会为每个注册的观测组件生成一个默认样本并封装为完整的 McObservation 消息。
 * serialize() 输出结构为 {@code {type: "mc_observation", components: {key: {space}, ...}}}。
 * contains() 校验时需要每个组件的 Any 消息能被正确解包并通过组件自身的验证。
 * </p>
 */
public class McObservationSpace implements McSpace<McObservation> {
    private final Map<String, ObservationComponent<?>> components;

    public McObservationSpace(Collection<ObservationComponent<?>> components) {
        var map = new LinkedHashMap<String, ObservationComponent<?>>();
        for (var component : components) {
            map.put(componentId(component), component);
        }
        this.components = Collections.unmodifiableMap(map);
    }

    @Override
    public McObservation sample() {
        var builder = McObservation.newBuilder()
            .setHeader(ObservationHeader.newBuilder().setSchemaVersion(1).build());
        for (var entry : components.entrySet()) {
            builder.putComponents(entry.getKey(), Any.pack(entry.getValue().sample()));
        }
        return builder.build();
    }

    @Override
    public boolean contains(McObservation value) {
        if (value == null) return false;
        for (var entry : value.getComponentsMap().entrySet()) {
            var component = components.get(entry.getKey());
            if (component == null || !containsComponent(component, entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Map<String, Object> serialize() {
        var serialized = new LinkedHashMap<String, Object>();
        for (var entry : components.entrySet()) {
            serialized.put(entry.getKey(), entry.getValue().space().serialize());
        }
        return Map.of(
            "type", "mc_observation",
            "components", serialized
        );
    }

    public Map<String, ObservationComponent<?>> components() {
        return components;
    }

    public static String componentId(ObservationComponent<?> component) {
        Identifier key = RegistryKeys.OBSERVATION_COMPONENTS.getKey(component);
        if (key == null) {
            throw new IllegalStateException("Observation component is not registered: " + component);
        }
        return key.toString();
    }

    private static <T extends Message> boolean containsComponent(ObservationComponent<T> component, Any any) {
        if (!any.is(component.protoType())) return false;
        try {
            return component.contains(any.unpack(component.protoType()));
        } catch (InvalidProtocolBufferException e) {
            return false;
        }
    }
}
