package io.github.mousemeya.gymcraft.gym.env.envs;

import io.github.mousemeya.gymcraft.gym.attachment.MobAttachments;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.env.McEnvFactory;
import io.github.mousemeya.gymcraft.gym.scenario.ScenarioStore;
import io.github.mousemeya.gymcraft.registry.ActionComponents;
import io.github.mousemeya.gymcraft.registry.ObservationCreators;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;

import java.util.List;

import javax.annotation.Nullable;


/**
 * 通用 LLM 控制环境。
 */
public class GeneralEnv extends AbstractMcEnv {
    /**
     * 创建暴露全部通用动作与观测组件的 Mob 环境。
     *
     * @param envTypeId 环境类型注册 ID
     * @param mob 受控 Agent
     */
    public GeneralEnv(Identifier envTypeId, Mob mob) {
        this(envTypeId, mob, null);
    }

    /** 在普通构造之上携带外部场景源（手动 env load）。 */
    public GeneralEnv(Identifier envTypeId, Mob mob, @Nullable ScenarioStore.LoadedScenario scenario) {
        super(
            envTypeId,
            mob,
            List.of(
                ActionComponents.NOOP.get(),
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
                ActionComponents.DROP_ITEM.get(),
                ActionComponents.USE_ITEM.get(),
                ActionComponents.UPDATE_INTERESTING_BLOCKS.get(),
                ActionComponents.SEND_CHAT.get(),
                ActionComponents.SWIM_TO.get()
            ),
            List.of(
                ObservationCreators.SELF.get(),
                ObservationCreators.WORLD.get(),
                ObservationCreators.NEARBY_ENTITIES.get(),
                ObservationCreators.NEARBY_BLOCKS.get(),
                ObservationCreators.NEARBY_ITEMS.get(),
                ObservationCreators.MENU.get(),
                ObservationCreators.INTERESTING_BLOCKS.get(),
                ObservationCreators.CHAT.get(),
                ObservationCreators.HEALTH_LOG.get()
            ),
            List.of(MobAttachments.AGENT_BACKPACK, MobAttachments.DAMAGE_LOG)
        );
        this.observationComponent(ObservationCreators.NEARBY_BLOCKS.get()).setMaxBlocks(32);
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
        public GeneralEnv create(Identifier envTypeId, Mob mob, @Nullable ScenarioStore.LoadedScenario scenario) {
            return new GeneralEnv(envTypeId, mob, scenario);
        }
    }
}
