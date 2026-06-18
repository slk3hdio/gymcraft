package io.github.mousemeya.withme.gym.action;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.github.mousemeya.withme.gym.action.proto.McAction;
import io.github.mousemeya.withme.gym.space.McSpace;
import io.github.mousemeya.withme.registry.RegistryKeys;
import net.minecraft.resources.Identifier;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 动作空间 —— 对 {@link McAction} 的组合空间封装，实现 {@link McSpace} 接口。
 * <p>
 * 所有组件的 schema、校验、采样逻辑完全委托给各个 {@link ActionComponent} 实现。
 * 核心层不需要知道任何具体动作类型。
 * </p>
 */
public class McActionSpace implements McSpace<McAction> {
    private final Map<String, ActionComponent<?>> components;

    /** @param components 该空间包含的所有动作组件实例 */
    public McActionSpace(Collection<ActionComponent<?>> components) {
        var map = new LinkedHashMap<String, ActionComponent<?>>();
        for (var component : components) {
            map.put(componentId(component), component);
        }
        this.components = Collections.unmodifiableMap(map);
    }

    @Override
    public McAction sample() {
        return McAction.getDefaultInstance();
    }

    /** 校验 McAction 中的所有组件是否都在该空间中且参数合法。 */
    @Override
    public boolean contains(McAction value) {
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
            "type", "mc_action",
            "components", serialized
        );
    }

    public Map<String, ActionComponent<?>> components() {
        return components;
    }

    /** 从 NeoForge 注册表中获取动作组件的正式注册表 ID 字符串。 */
    public static String componentId(ActionComponent<?> component) {
        Identifier key = RegistryKeys.ACTION_COMPONENTS.getKey(component);
        if (key == null) {
            throw new IllegalStateException("Action component is not registered: " + component);
        }
        return key.toString();
    }

    /** 解包 Any 后委托组件自身的 contains() 做参数校验。 */
    private static <T extends Message> boolean containsComponent(ActionComponent<T> component, Any any) {
        if (!any.is(component.protoType())) return false;
        try {
            return component.contains(any.unpack(component.protoType()));
        } catch (InvalidProtocolBufferException e) {
            return false;
        }
    }
}
