package io.github.mousemeya.withme.gym.env;

import io.github.mousemeya.withme.registry.RegistryKeys;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * 环境工厂查询工具 —— 封装环境类型 ID 到工厂实例的查找逻辑。
 * <p>
 * 通过 {@link RegistryKeys#ENV_FACTORIES} 注册表查询对应环境的工厂。
 * ID 必须为完整注册表 ID 格式（如 {@code withme:navigation}）。
 * </p>
 */
public final class McEnvFactories {
    private McEnvFactories() {
    }

    /** 从注册表中查找 envType 对应的工厂并创建环境实例。 */
    public static AbstractMcEnv create(String envType, UUID entityUuid) {
        var id = parseId(envType);
        var factory = RegistryKeys.ENV_FACTORIES.getValue(id);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown environment type: " + id);
        }
        return factory.create(entityUuid);
    }

    private static Identifier parseId(String envType) {
        return Identifier.parse(envType);
    }
}
