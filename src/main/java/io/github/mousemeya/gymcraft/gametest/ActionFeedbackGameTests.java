package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;

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
     * 验证 attack_distance 同时扩展 attack_once 与原版近战 Goal 的攻击范围。
     *
     * @param helper GameTest 辅助对象
     */
    public static void attackDistanceOptionOverridesMeleeRange(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(1, 1, 1));
        var target = spawnAgent(helper, EntityType.PIG, new BlockPos(7, 1, 1));
        var attackOnce = new AttackOnceController(mob);

        var defaultResult = attackOnce.apply(ProtoAttackOnce.newBuilder()
            .setTargetEntityId(target.getId()).build()).initialState();
        assertEquals(helper, ActionStatus.FAILED, defaultResult.status(),
            "default melee range should reject a far target");

        attackOnce.applyOptions(Map.of("attack_distance", 8.0));
        assertEquals(helper, 8.0, mob.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE),
            "attack_once attack_distance should update the vanilla entity interaction range attribute");
        var extendedResult = attackOnce.apply(ProtoAttackOnce.newBuilder()
            .setTargetEntityId(target.getId()).build()).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, extendedResult.status(),
            "attack_distance should extend attack_once range");

        var setAttackTarget = new SetAttackTargetController(mob);
        setAttackTarget.applyOptions(Map.of("attack_distance", 8.0));
        assertEquals(helper, 8.0, mob.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE),
            "attack_distance should update the vanilla entity interaction range attribute");
        var targetResult = setAttackTarget.apply(ProtoSetAttackTarget.newBuilder()
            .setTargetEntityId(target.getId()).build());
        assertEquals(helper, ActionStatus.RUNNING, targetResult.initialState().status(),
            "set_attack_target should still accept a distant target");
        assertTrue(helper, mob.isWithinMeleeAttackRange(target),
            "attack_distance should extend the mob's shared melee range");

        var replacement = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(1, 1, 3));
        setAttackTarget.setMob(replacement);
        setAttackTarget.apply(ProtoSetAttackTarget.newBuilder()
            .setTargetEntityId(target.getId()).build());
        assertEquals(helper, 8.0, replacement.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE),
            "attack_distance should be reapplied to the replacement mob's attribute");
        assertTrue(helper, replacement.isWithinMeleeAttackRange(target),
            "attack_distance should be reapplied after the mob is replaced");
        helper.succeed();
    }
}
