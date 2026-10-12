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
 * 通用 LLM 控制环境，装配全部通用动作与观测并管理所需附件。
 * 受控 Mob 必须位于服务端世界；未提供外部场景时仅捕获 agent，不维护世界区域。
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

    /**
     * 根据显式场景创建环境；未提供场景时仅挂载当前 Mob。
     * @param envTypeId 环境类型注册 ID
     * @param mob 服务端世界中的受控 Mob
     * @param scenario 完整场景；null 表示仅捕获 agent，状态范围不含方块及周围实体
     */
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
            List.of(MobAttachments.AGENT_BACKPACK, MobAttachments.DAMAGE_LOG),
            // 状态区域仅来自显式场景；挂载现有 Mob 时只捕获 agent。
            scenario
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
