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
 * 需要调整组件默认值时，在构造函数中经 {@link #actionComponent}/{@link #observationComponent}
 * 取到组件实例并调用其 setter（见 {@code nearby_blocks} 等组件的 setter 说明）。
 * </p>
 */
public class SimpleMobEnv extends AbstractMcEnv {
    /**
     * 创建暴露全部通用动作与观测组件的 Mob 环境。
     *
     * @param envTypeId 环境类型注册 ID
     * @param mob 受控 Agent
     */
    public SimpleMobEnv(Identifier envTypeId, Mob mob) {
        super(
            envTypeId,
            mob,
            List.of(
                ActionComponents.NOOP.get(),
                ActionComponents.STEP_MOVE.get(),
                ActionComponents.LOOK_AT.get(),
                ActionComponents.MOVE_TO.get(),
                ActionComponents.SET_ATTACK_TARGET.get(),
                ActionComponents.BREAK_BLOCK.get(),
                ActionComponents.SET_BLOCK.get(),
                ActionComponents.ATTACK_ONCE.get(),
                ActionComponents.JUMP.get(),
                ActionComponents.OPEN_MENU.get(),
                ActionComponents.CLOSE_MENU.get(),
                ActionComponents.MOVE_MENU_ITEM.get(),
                ActionComponents.CLICK_MENU_BUTTON.get(),
                ActionComponents.PICK_UP_ITEM.get(),
                ActionComponents.DROP_ITEM.get()
            ),
            List.of(
                ObservationCreators.SELF.get(),
                ObservationCreators.WORLD.get(),
                ObservationCreators.NEARBY_ENTITIES.get(),
                ObservationCreators.NEARBY_BLOCKS.get(),
                ObservationCreators.NEARBY_ITEMS.get(),
                ObservationCreators.MENU.get()
            )
        );
    }

    /**
     * 环境工厂 —— 注册表引用该内部轻量 {@link McEnvFactory}，而非目标类构造函数。
     */
    public static final class Factory implements McEnvFactory {
        /**
         * 创建简单 Mob 环境。
         *
         * @param envTypeId 环境类型注册 ID
         * @param mob 受控 Agent
         * @return 新环境实例
         */
        @Override
        public SimpleMobEnv create(Identifier envTypeId, Mob mob) {
            return new SimpleMobEnv(envTypeId, mob);
        }
    }
}
