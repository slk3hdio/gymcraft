package io.github.mousemeya.gymcraft.registry;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentFactory;
import io.github.mousemeya.gymcraft.gym.observation.component.InventoryObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.component.NearbyBlocksObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.component.NearbyEntitiesObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.component.SelfStateObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.component.WorldStateObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoInventory;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoNearbyBlocks;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoNearbyEntities;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoSelfState;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoWorldState;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 观测组件注册入口 —— 通过 {@link DeferredRegister} 将所有 {@link ObservationComponentFactory} 实现
 * 挂载到 {@link RegistryKeys#OBSERVATION_COMPONENT_FACTORIES} 注册表上。
 * <p>
 * 与 action 体系一致：注册对象为各 creator 类内定义并实现的轻量 {@code Factory} 类；
 * 环境构造时通过 {@code factory.create()} 为每个环境创建独立的
 * {@link ObservationComponentCreator} 实例。
 * </p>
 */
public final class ObservationCreators {
    public static final DeferredRegister<ObservationComponentFactory<?>> REGISTRY = DeferredRegister.create(
        RegistryKeys.OBSERVATION_COMPONENT_FACTORIES,
        GymCraft.MODID
    );

    public static final DeferredHolder<ObservationComponentFactory<?>, ObservationComponentFactory<ProtoSelfState>> SELF = REGISTRY.register(
        "self",
        SelfStateObservationCreator.Factory::new
    );
    public static final DeferredHolder<ObservationComponentFactory<?>, ObservationComponentFactory<ProtoNearbyEntities>> NEARBY_ENTITIES = REGISTRY.register(
        "nearby_entities",
        NearbyEntitiesObservationCreator.Factory::new
    );
    public static final DeferredHolder<ObservationComponentFactory<?>, ObservationComponentFactory<ProtoNearbyBlocks>> NEARBY_BLOCKS = REGISTRY.register(
        "nearby_blocks",
        NearbyBlocksObservationCreator.Factory::new
    );
    public static final DeferredHolder<ObservationComponentFactory<?>, ObservationComponentFactory<ProtoInventory>> INVENTORY = REGISTRY.register(
        "inventory",
        InventoryObservationCreator.Factory::new
    );
    public static final DeferredHolder<ObservationComponentFactory<?>, ObservationComponentFactory<ProtoWorldState>> WORLD = REGISTRY.register(
        "world",
        WorldStateObservationCreator.Factory::new
    );

    private ObservationCreators() {
    }
}
