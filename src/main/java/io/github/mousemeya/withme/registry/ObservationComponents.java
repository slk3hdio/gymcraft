package io.github.mousemeya.withme.registry;

import io.github.mousemeya.withme.WithMe;
import io.github.mousemeya.withme.gym.obs.ObservationComponent;
import io.github.mousemeya.withme.gym.obs.component.InventoryObservationComponent;
import io.github.mousemeya.withme.gym.obs.component.NearbyBlocksObservationComponent;
import io.github.mousemeya.withme.gym.obs.component.NearbyEntitiesObservationComponent;
import io.github.mousemeya.withme.gym.obs.component.SelfStateObservationComponent;
import io.github.mousemeya.withme.gym.obs.component.WorldStateObservationComponent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 观测组件注册入口 —— 通过 {@link DeferredRegister} 将所有 {@link ObservationComponent} 实现
 * 挂载到 {@link RegistryKeys#OBSERVATION_COMPONENTS} 注册表上。
 * <p>
 * 所有观测组件基于注册表 ID（如 {@code withme:self}）在运行时唯一标识。
 * 环境构造时引用这些 {@link DeferredHolder} 来获取组件实例并组合成观测空间。
 * </p>
 */
public final class ObservationComponents {
    public static final DeferredRegister<ObservationComponent<?>> REGISTRY = DeferredRegister.create(
        RegistryKeys.OBSERVATION_COMPONENTS,
        WithMe.MODID
    );

    public static final DeferredHolder<ObservationComponent<?>, SelfStateObservationComponent> SELF = REGISTRY.register(
        "self",
        SelfStateObservationComponent::new
    );
    public static final DeferredHolder<ObservationComponent<?>, NearbyEntitiesObservationComponent> NEARBY_ENTITIES = REGISTRY.register(
        "nearby_entities",
        NearbyEntitiesObservationComponent::new
    );
    public static final DeferredHolder<ObservationComponent<?>, NearbyBlocksObservationComponent> NEARBY_BLOCKS = REGISTRY.register(
        "nearby_blocks",
        NearbyBlocksObservationComponent::new
    );
    public static final DeferredHolder<ObservationComponent<?>, InventoryObservationComponent> INVENTORY = REGISTRY.register(
        "inventory",
        InventoryObservationComponent::new
    );
    public static final DeferredHolder<ObservationComponent<?>, WorldStateObservationComponent> WORLD = REGISTRY.register(
        "world",
        WorldStateObservationComponent::new
    );

    private ObservationComponents() {
    }
}
