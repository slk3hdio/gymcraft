package io.github.mousemeya.withme.registry;

import io.github.mousemeya.withme.WithMe;
import io.github.mousemeya.withme.gym.env.CombatEnv;
import io.github.mousemeya.withme.gym.env.McEnvFactory;
import io.github.mousemeya.withme.gym.env.NavigationEnv;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 环境工厂注册入口 —— 通过 {@link DeferredRegister} 将所有 {@link McEnvFactory} 实现
 * 挂载到 {@link RegistryKeys#ENV_FACTORIES} 注册表上。
 * <p>
 * 每个环境类型通过注册表 ID（如 {@code withme:navigation}）唯一标识。
 * {@link io.github.mousemeya.withme.gym.env.McEnvFactories#create(String, java.util.UUID)}
 * 通过此注册表查找工厂并创建环境实例。
 * </p>
 * <p>
 * 新增环境类型的步骤：
 * <ol>
 *   <li>创建继承 {@code EntityMcEnv} 的环境实现类</li>
 *   <li>实现 {@code McEnvFactory} 接口或其方法引用（如 {@code NavigationEnv::new}）</li>
 *   <li>在此类中新增一个 {@code DeferredHolder} 字段</li>
 * </ol>
 * </p>
 */
public final class EnvFactories {
    public static final DeferredRegister<McEnvFactory> REGISTRY = DeferredRegister.create(
        RegistryKeys.ENV_FACTORIES,
        WithMe.MODID
    );

    public static final DeferredHolder<McEnvFactory, McEnvFactory> NAVIGATION = REGISTRY.register(
        "navigation",
        () -> NavigationEnv::new
    );
    public static final DeferredHolder<McEnvFactory, McEnvFactory> COMBAT = REGISTRY.register(
        "combat",
        () -> CombatEnv::new
    );

    private EnvFactories() {
    }
}
