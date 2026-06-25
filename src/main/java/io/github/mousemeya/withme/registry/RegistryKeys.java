package io.github.mousemeya.withme.registry;

import io.github.mousemeya.withme.WithMe;
import io.github.mousemeya.withme.gym.action.ActionComponentController;
import io.github.mousemeya.withme.gym.env.McEnvFactory;
import io.github.mousemeya.withme.gym.observation.ObservationComponentCreator;
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
 *   <li>{@code action_components} —— 注册所有 {@link ActionComponentController} 实现</li>
 *   <li>{@code observation_components} —— 注册所有 {@link ObservationComponentCreator} 实现</li>
 *   <li>{@code env_factories} —— 注册所有 {@link McEnvFactory} 实现</li>
 * </ul>
 * </p>
 */
public final class RegistryKeys {
    public static final ResourceKey<Registry<ActionComponentController<?>>> ACTION_COMPONENT_CONTROLLERS_KEY = ResourceKey.createRegistryKey(
        Identifier.fromNamespaceAndPath(WithMe.MODID, "action_components")
    );
    public static final ResourceKey<Registry<ObservationComponentCreator<?>>> OBSERVATION_COMPONENT_CREATORS_KEY = ResourceKey.createRegistryKey(
        Identifier.fromNamespaceAndPath(WithMe.MODID, "observation_components")
    );
    public static final ResourceKey<Registry<McEnvFactory>> ENV_FACTORIES_KEY = ResourceKey.createRegistryKey(
        Identifier.fromNamespaceAndPath(WithMe.MODID, "env_factories")
    );

    /** 动作组件注册表实例 */
    public static final Registry<ActionComponentController<?>> ACTION_COMPONENT_CONTROLLERS = new RegistryBuilder<>(ACTION_COMPONENT_CONTROLLERS_KEY).create();
    /** 观测组件注册表实例 */
    public static final Registry<ObservationComponentCreator<?>> OBSERVATION_COMPONENT_CREATORS = new RegistryBuilder<>(OBSERVATION_COMPONENT_CREATORS_KEY).create();
    /** 环境工厂注册表实例 */
    public static final Registry<McEnvFactory> ENV_FACTORIES = new RegistryBuilder<>(ENV_FACTORIES_KEY).create();

    private RegistryKeys() {
    }

    /** 将三个自定义注册表注册到 NeoForge 的根注册表。在 mod 构造时的 NewRegistryEvent 中调用。 */
    public static void register(NewRegistryEvent event) {
        event.register(ACTION_COMPONENT_CONTROLLERS);
        event.register(OBSERVATION_COMPONENT_CREATORS);
        event.register(ENV_FACTORIES);
    }
}
