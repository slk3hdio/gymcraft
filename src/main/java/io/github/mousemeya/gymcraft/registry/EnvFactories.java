package io.github.mousemeya.gymcraft.registry;

import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.UUID;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.env.McEnvFactory;
import io.github.mousemeya.gymcraft.gym.env.envs.SimpleMobEnv;

/**
 * 环境工厂注册入口 —— 通过 {@link DeferredRegister} 将所有 {@link McEnvFactory} 实现
 * 挂载到 {@link RegistryKeys#ENV_FACTORIES} 注册表上。
 * <p>
 * 每个环境类型通过注册表 ID（如 {@code GymCraft:simple_mob}）唯一标识；
 * 注册对象为环境类内部定义并实现的轻量 {@link McEnvFactory}。
 * </p>
 */
public final class EnvFactories {
    public static final DeferredRegister<McEnvFactory> REGISTRY = DeferredRegister.create(
        RegistryKeys.ENV_FACTORIES,
        GymCraft.MODID
    );

    public static final DeferredHolder<McEnvFactory, McEnvFactory> SIMPLE_MOB = REGISTRY.register(
        "simple_mob",
        SimpleMobEnv.Factory::new
    );

    private EnvFactories() {
    }
}
