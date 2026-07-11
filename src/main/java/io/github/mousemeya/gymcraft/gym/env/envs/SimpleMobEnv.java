package io.github.mousemeya.gymcraft.gym.env.envs;

import java.util.List;
import java.util.UUID;

import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.registry.ActionComponents;
import io.github.mousemeya.gymcraft.registry.ObservationCreators;
import io.github.mousemeya.gymcraft.registry.RegistryKeys;


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
                // ObservationCreators.NEARBY_BLOCKS.get(),
                ObservationCreators.INVENTORY.get()
            )
        );
    }

    @Override
    public String getRegisterId() {
        return RegistryKeys.ENV_FACTORIES.getKey(SimpleMobEnv::new).toString();
    }
}
