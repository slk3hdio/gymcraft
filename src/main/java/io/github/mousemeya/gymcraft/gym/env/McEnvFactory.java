package io.github.mousemeya.gymcraft.gym.env;

import javax.annotation.Nullable;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.scenario.ScenarioStore;

/**
 * 环境工厂接口 —— 定义了创建环境实例的方法。
 * <p>
 * 场景参数为手动 {@code env load} 路径的外部场景源（null = 环境按
 * {@code bundledScenarioName()} 解析内置资产或走现场快照）。
 * </p>
 */
@FunctionalInterface
public interface McEnvFactory {
    McEnv create(Identifier envTypeId, Mob mob, @Nullable ScenarioStore.LoadedScenario scenario);
}
