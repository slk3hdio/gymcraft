package io.github.mousemeya.gymcraft.gym.env;

import javax.annotation.Nullable;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.scenario.ScenarioStore;
import io.github.mousemeya.gymcraft.gym.state.AttachScope;

/**
 * 环境工厂接口 —— 定义了创建环境实例的方法。
 * <p>
 * 仅由 EnvManager 的两个创建入口消费；非空场景表示从文件创建完整环境
 * （区域与捕获开关由文件决定，scope 被忽略），null 表示向已有 Mob 挂载，
 * 区域与捕获开关来自 scope。禁止工厂隐式加载默认场景。
 * </p>
 */
@FunctionalInterface
public interface McEnvFactory {
    /**
     * 构造指定类型的环境，场景是否存在由调用方明确决定。
     * @param envTypeId 环境注册 ID
     * @param mob 受控 Mob
     * @param scenario 显式完整场景；null 表示已有 Mob 挂载
     * @param scope 挂载作用域；scenario 非空时被忽略
     * @return 环境实例，由 EnvManager 注册并管理生命周期
     */
    McEnv create(Identifier envTypeId, Mob mob, @Nullable ScenarioStore.LoadedScenario scenario, AttachScope scope);
}
