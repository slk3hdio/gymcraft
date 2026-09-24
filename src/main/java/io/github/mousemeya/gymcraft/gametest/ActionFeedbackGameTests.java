package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;

import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.action.component.AttackOnceController;
import io.github.mousemeya.gymcraft.gym.action.component.SetAttackTargetController;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoAttackOnce;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoSetAttackTarget;

/**
 * 动作失败反馈回归测试 —— 验证攻击动作按实际失败阶段返回可区分的 description。
 */
public final class ActionFeedbackGameTests {
    /** 禁止实例化仅包含静态 GameTest 的工具类。 */
    private ActionFeedbackGameTests() {
    }

    /**
     * 验证 attack_once 区分缺少目标、目标未加载与目标超出近战范围。
     *
     * @param helper GameTest 辅助对象
     */
    public static void attackOnceReportsSpecificFailures(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(1, 1, 1));
        var target = spawnAgent(helper, EntityType.PIG, new BlockPos(7, 1, 1));
        var controller = new AttackOnceController(mob);

        var noTarget = controller.apply(ProtoAttackOnce.getDefaultInstance()).initialState();
        assertEquals(helper, ActionStatus.FAILED, noTarget.status(), "missing target should fail");
        assertEquals(helper, "no attack target is set", noTarget.description(), "missing target description");

        var missing = controller.apply(ProtoAttackOnce.newBuilder()
            .setTargetEntityId(Integer.MAX_VALUE).build()).initialState();
        assertEquals(helper, "attack target entity was not found or is not loaded", missing.description(),
            "missing entity description");

        var far = controller.apply(ProtoAttackOnce.newBuilder()
            .setTargetEntityId(target.getId()).build()).initialState();
        assertEquals(helper, "attack target is outside melee range", far.description(),
            "out-of-range description");
        helper.succeed();
    }

    /**
     * 验证 set_attack_target 区分主动清除、目标未加载与禁止把自身设为目标。
     *
     * @param helper GameTest 辅助对象
     */
    public static void setAttackTargetReportsSpecificFailures(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(1, 1, 1));
        var controller = new SetAttackTargetController(mob);

        var cleared = controller.apply(ProtoSetAttackTarget.getDefaultInstance()).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, cleared.status(), "explicit clear should complete");
        assertEquals(helper, "attack target cleared", cleared.description(), "clear description");

        var missing = controller.apply(ProtoSetAttackTarget.newBuilder()
            .setTargetEntityId(Integer.MAX_VALUE).build()).initialState();
        assertEquals(helper, ActionStatus.FAILED, missing.status(), "missing target should fail");
        assertEquals(helper, "attack target entity was not found or is not loaded", missing.description(),
            "missing target description");

        var self = controller.apply(ProtoSetAttackTarget.newBuilder()
            .setTargetEntityId(mob.getId()).build()).initialState();
        assertTrue(helper, self.status() == ActionStatus.FAILED && "agent cannot target itself".equals(self.description()),
            "self target should return its specific failure: " + self);
        helper.succeed();
    }
}
