package io.github.mousemeya.gymcraft.gym.env.envs;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.env.McEnv;
import io.github.mousemeya.gymcraft.gym.env.McEnvFactory;
import io.github.mousemeya.gymcraft.gym.scenario.ScenarioStore;
import io.github.mousemeya.gymcraft.gym.attachment.MobAttachments;
import io.github.mousemeya.gymcraft.gym.state.AttachScope;
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
        this(envTypeId, mob, null);
    }

    /**
     * 根据显式场景创建环境；未提供场景时仅挂载当前 Mob。
     * @param envTypeId 环境类型注册 ID
     * @param mob 服务端世界中的受控 Mob
     * @param scenario 完整场景；null 表示仅捕获 agent，状态范围不含方块及周围实体
     */
    public SimpleMobEnv(Identifier envTypeId, Mob mob, @Nullable ScenarioStore.LoadedScenario scenario) {
        this(envTypeId, mob, scenario, new AttachScope(null, false, false));
    }

    /**
     * 根据显式场景或挂载作用域创建环境；场景非空时忽略作用域（区域与捕获开关由文件决定）。
     * @param envTypeId 环境类型注册 ID
     * @param mob 服务端世界中的受控 Mob
     * @param scenario 完整场景；null 表示挂载已有 Mob
     * @param scope 挂载作用域（区域与方块/实体捕获开关）
     */
    public SimpleMobEnv(Identifier envTypeId, Mob mob, @Nullable ScenarioStore.LoadedScenario scenario, AttachScope scope) {
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
                ActionComponents.DROP_ITEM.get(),
                ActionComponents.USE_ITEM.get(),
                ActionComponents.UPDATE_INTERESTING_BLOCKS.get(),
                ActionComponents.SEND_CHAT.get()
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
            // 状态区域来自显式场景或挂载作用域；两者都为空时只捕获 agent。
            scenario,
            scope
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
        public SimpleMobEnv create(Identifier envTypeId, Mob mob, @Nullable ScenarioStore.LoadedScenario scenario, AttachScope scope) {
            return new SimpleMobEnv(envTypeId, mob, scenario, scope);
        }
    }
}
