package io.github.mousemeya.withme.gym.env.envs;

import java.util.List;

import io.github.mousemeya.withme.gym.env.AbstractMcEnv;
import io.github.mousemeya.withme.registry.ActionComponents;
import io.github.mousemeya.withme.registry.ObservationCreators;
import net.minecraft.world.entity.Mob;

/**
 * 最小 Mob 控制环境。
 * <p>
 * 使用当前已注册的全部动作组件和观测组件，奖励恒为 0，实体死亡时终止。
 * </p>
 */
public class SimpleMobEnv extends AbstractMcEnv {
    public SimpleMobEnv(Mob mob) {
        super(
            mob,
            List.of(
                ActionComponents.NOOP.get(),
                ActionComponents.STEP_MOVE.get(),
                ActionComponents.MOVE_TO.get(),
                ActionComponents.SET_ATTACK_TARGET.get(),
                ActionComponents.ATTACK_ONCE.get()
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
}
