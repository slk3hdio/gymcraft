package io.github.mousemeya.withme.registry;

import io.github.mousemeya.withme.WithMe;
import io.github.mousemeya.withme.gym.observation.ObservationComponentCreator;
import io.github.mousemeya.withme.gym.observation.component.InventoryObservationComponent;
import io.github.mousemeya.withme.gym.observation.component.NearbyBlocksObservationComponent;
import io.github.mousemeya.withme.gym.observation.component.NearbyEntitiesObservationComponent;
import io.github.mousemeya.withme.gym.observation.component.SelfStateObservationComponent;
import io.github.mousemeya.withme.gym.observation.component.WorldStateObservationComponent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 观测组件注册入口 —— 通过 {@link DeferredRegister} 将所有 {@link ObservationComponentCreator} 实现
 * 挂载到 {@link RegistryKeys#OBSERVATION_COMPONENTS} 注册表上。
 * <p>
 * 所有观测组件基于注册表 ID（如 {@code withme:self}）在运行时唯一标识。
 * 环境构造时引用这些 {@link DeferredHolder} 来获取组件实例并组合成观测空间。
 * </p>
 */
public final class ObservationComponents {
    public static final DeferredRegister<ObservationComponentCreator<?>> REGISTRY = DeferredRegister.create(
        RegistryKeys.OBSERVATION_COMPONENTS,
        WithMe.MODID
    );

    public static final DeferredHolder<ObservationComponentCreator<?>, SelfStateObservationComponent> SELF = REGISTRY.register(
        "self",
        SelfStateObservationComponent::new
    );
    public static final DeferredHolder<ObservationComponentCreator<?>, NearbyEntitiesObservationComponent> NEARBY_ENTITIES = REGISTRY.register(
        "nearby_entities",
        NearbyEntitiesObservationComponent::new
    );
    public static final DeferredHolder<ObservationComponentCreator<?>, NearbyBlocksObservationComponent> NEARBY_BLOCKS = REGISTRY.register(
        "nearby_blocks",
        NearbyBlocksObservationComponent::new
    );
    public static final DeferredHolder<ObservationComponentCreator<?>, InventoryObservationComponent> INVENTORY = REGISTRY.register(
        "inventory",
        InventoryObservationComponent::new
    );
    public static final DeferredHolder<ObservationComponentCreator<?>, WorldStateObservationComponent> WORLD = REGISTRY.register(
        "world",
        WorldStateObservationComponent::new
    );

    private ObservationComponents() {
    }
}
