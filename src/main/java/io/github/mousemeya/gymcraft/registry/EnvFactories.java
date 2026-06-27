package io.github.mousemeya.gymcraft.registry;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.env.McEnvFactory;
import io.github.mousemeya.gymcraft.gym.env.envs.SimpleMobEnvFactory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 环境工厂注册入口 —— 通过 {@link DeferredRegister} 将所有 {@link McEnvFactory} 实现
 * 挂载到 {@link RegistryKeys#ENV_FACTORIES} 注册表上。
 * <p>
 * 每个环境类型通过注册表 ID（如 {@code GymCraft:navigation}）唯一标识。
 * </p>
 */
public final class EnvFactories {
    public static final DeferredRegister<McEnvFactory> REGISTRY = DeferredRegister.create(
        RegistryKeys.ENV_FACTORIES,
        GymCraft.MODID
    );

    public static final DeferredHolder<McEnvFactory, SimpleMobEnvFactory> SIMPLE_MOB = REGISTRY.register(
        "simple_mob",
        SimpleMobEnvFactory::new
    );

    private EnvFactories() {
    }
}
