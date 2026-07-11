package io.github.mousemeya.gymcraft.gym.env;

import net.minecraft.world.entity.Mob;

/**
 * 环境工厂接口 —— 定义了创建环境实例的方法。
 * <p>
 * </p>
 */
@FunctionalInterface
public interface McEnvFactory {
    McEnv create(Mob mob);
}
