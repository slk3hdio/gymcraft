package io.github.mousemeya.withme.gym.env;

import java.util.UUID;

/**
 * 环境工厂接口 —— 作为 NeoForge 自定义注册表 {@code env_factories} 的注册对象。
 * <p>
 * 每个环境类型对应一个工厂实现，工厂负责创建具体的 {@link EntityMcEnv} 子类实例。
 * 新增环境类型只需实现此接口并注册到 {@code EnvFactories.REGISTRY}。
 * 核心分发层（如 {@link io.github.mousemeya.withme.gym.env.McEnvFactories}）
 * 通过注册表 ID 定位工厂并创建环境，不硬编码任何具体环境类型。
 * </p>
 */
public interface McEnvFactory {
    EntityMcEnv create(UUID entityUuid);
}
