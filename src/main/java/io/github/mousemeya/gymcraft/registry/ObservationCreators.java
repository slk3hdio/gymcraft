package io.github.mousemeya.gymcraft.registry;

import java.util.Optional;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.observation.component.InventoryObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.component.NearbyBlocksObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.component.NearbyEntitiesObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.component.SelfStateObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.component.WorldStateObservationCreator;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 观测组件注册入口 —— 通过 {@link DeferredRegister} 将所有 {@link ObservationComponentCreator} 实现
 * 挂载到 {@link RegistryKeys#OBSERVATION_COMPONENT_CREATORS} 注册表上。
 * <p>
 * 所有观测组件基于注册表 ID（如 {@code GymCraft:self}）在运行时唯一标识。
 * 环境构造时引用这些 {@link DeferredHolder} 来获取组件实例并组合成观测空间。
 * </p>
 */
public final class ObservationCreators {
    public static final DeferredRegister<ObservationComponentCreator<?>> REGISTRY = DeferredRegister.create(
        RegistryKeys.OBSERVATION_COMPONENT_CREATORS,
        GymCraft.MODID
    );

    public static final DeferredHolder<ObservationComponentCreator<?>, SelfStateObservationCreator> SELF = REGISTRY.register(
        "self",
        () -> new SelfStateObservationCreator(Optional.empty())
    );
    public static final DeferredHolder<ObservationComponentCreator<?>, NearbyEntitiesObservationCreator> NEARBY_ENTITIES = REGISTRY.register(
        "nearby_entities",
        () -> new NearbyEntitiesObservationCreator(Optional.empty())
    );
    public static final DeferredHolder<ObservationComponentCreator<?>, NearbyBlocksObservationCreator> NEARBY_BLOCKS = REGISTRY.register(
        "nearby_blocks",
        () -> new NearbyBlocksObservationCreator(Optional.empty())
    );
    public static final DeferredHolder<ObservationComponentCreator<?>, InventoryObservationCreator> INVENTORY = REGISTRY.register(
        "inventory",
        () -> new InventoryObservationCreator(Optional.empty())
    );
    public static final DeferredHolder<ObservationComponentCreator<?>, WorldStateObservationCreator> WORLD = REGISTRY.register(
        "world",
        () -> new WorldStateObservationCreator(Optional.empty())
    );

    private ObservationCreators() {
    }
}
