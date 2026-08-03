package io.github.mousemeya.gymcraft.gym.env.envs;

import java.util.List;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.env.McEnv;
import io.github.mousemeya.gymcraft.gym.env.McEnvFactory;
import io.github.mousemeya.gymcraft.registry.ActionComponents;
import io.github.mousemeya.gymcraft.registry.ObservationCreators;


/**
 * 最小 Mob 控制环境。
 * <p>
 * 使用当前已注册的全部动作组件和观测组件，奖励恒为 0，实体死亡时终止。
 * </p>
 */
public class SimpleMobEnv extends AbstractMcEnv {
    public SimpleMobEnv(Identifier envTypeId, Mob mob) {
        super(
            envTypeId,
            mob,
            List.of(
                // ActionComponents.NOOP.get(),
                // ActionComponents.STEP_MOVE.get(),
                ActionComponents.MOVE_TO.get(),
                ActionComponents.SET_ATTACK_TARGET.get(),
                ActionComponents.BREAK_BLOCK.get(),
                ActionComponents.PLACE_BLOCK.get()
                // ActionComponents.ATTACK_ONCE.get()
            ),
            List.of(
                ObservationCreators.SELF.get(),
                ObservationCreators.WORLD.get(),
                ObservationCreators.NEARBY_ENTITIES.get(),
                ObservationCreators.NEARBY_BLOCKS.get(),
                ObservationCreators.INVENTORY.get()
            )
        );
    }

    /**
     * 环境工厂 —— 注册表引用该内部轻量 {@link McEnvFactory}，而非目标类构造函数。
     */
    public static final class Factory implements McEnvFactory {
        @Override
        public SimpleMobEnv create(Identifier envTypeId, Mob mob) {
            return new SimpleMobEnv(envTypeId, mob);
        }
    }
}
