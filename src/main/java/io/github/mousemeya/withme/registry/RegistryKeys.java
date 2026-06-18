package io.github.mousemeya.withme.registry;

import io.github.mousemeya.withme.WithMe;
import io.github.mousemeya.withme.gym.action.ActionComponent;
import io.github.mousemeya.withme.gym.env.McEnvFactory;
import io.github.mousemeya.withme.gym.obs.ObservationComponent;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.registries.NewRegistryEvent;
import net.neoforged.neoforge.registries.RegistryBuilder;

/**
 * 自定义注册表定义 —— 使用 NeoForge 官方 {@link RegistryBuilder} + {@link NewRegistryEvent} 流程。
 * <p>
 * 定义了三个 RL 框架所需的自定义注册表：
 * <ul>
 *   <li>{@code action_components} —— 注册所有 {@link ActionComponent} 实现</li>
 *   <li>{@code observation_components} —— 注册所有 {@link ObservationComponent} 实现</li>
 *   <li>{@code env_factories} —— 注册所有 {@link McEnvFactory} 实现</li>
 * </ul>
 * 通过 {@link #register(NewRegistryEvent)} 挂载到 mod 事件总线，
 * 确保在 mod 构造阶段完成注册表本身的创建。
 * </p>
 * <p>
 * 注册表使用示例（在 WithMe.java 的构造函数中）：
 * {@code modEventBus.addListener(RegistryKeys::register);}
 * </p>
 */
public final class RegistryKeys {
    public static final ResourceKey<Registry<ActionComponent<?>>> ACTION_COMPONENTS_KEY = ResourceKey.createRegistryKey(
        Identifier.fromNamespaceAndPath(WithMe.MODID, "action_components")
    );

    public static final ResourceKey<Registry<ObservationComponent<?>>> OBSERVATION_COMPONENTS_KEY = ResourceKey.createRegistryKey(
        Identifier.fromNamespaceAndPath(WithMe.MODID, "observation_components")
    );

    public static final ResourceKey<Registry<McEnvFactory>> ENV_FACTORIES_KEY = ResourceKey.createRegistryKey(
        Identifier.fromNamespaceAndPath(WithMe.MODID, "env_factories")
    );

    public static final Registry<ActionComponent<?>> ACTION_COMPONENTS = new RegistryBuilder<>(ACTION_COMPONENTS_KEY).create();
    public static final Registry<ObservationComponent<?>> OBSERVATION_COMPONENTS = new RegistryBuilder<>(OBSERVATION_COMPONENTS_KEY).create();
    public static final Registry<McEnvFactory> ENV_FACTORIES = new RegistryBuilder<>(ENV_FACTORIES_KEY).create();

    private RegistryKeys() {
    }

    public static void register(NewRegistryEvent event) {
        event.register(ACTION_COMPONENTS);
        event.register(OBSERVATION_COMPONENTS);
        event.register(ENV_FACTORIES);
    }
}
