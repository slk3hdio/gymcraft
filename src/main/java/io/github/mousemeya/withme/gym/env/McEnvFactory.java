package io.github.mousemeya.withme.gym.env;

import java.util.UUID;

/**
 * 环境工厂接口 —— 作为 NeoForge 自定义注册表 {@code env_factories} 的注册对象。
 * <p>
 * 每个环境类型对应一个工厂实现，工厂负责创建具体的 {@link EntityMcEnv} 子类实例。
 * 新增环境类型只需实现此接口并注册。
 * </p>
 */
public interface McEnvFactory {
    /** @param entityUuid 绑定的 Mob 实体 UUID */
    EntityMcEnv create(UUID entityUuid);
}
