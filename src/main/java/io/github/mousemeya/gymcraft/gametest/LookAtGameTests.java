package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.action.component.LookAtController;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoLookAt;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoLookAtBlockTarget;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoLookAtEntityTarget;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoLookAtItemTarget;

/**
 * 注视动作 GameTest —— 分别验证普通实体眼睛、掉落物包围盒中心和方块中心目标。
 * <p>
 * 测试直接驱动 {@link LookAtController}，并以 Agent 眼睛到目标点的单位向量与
 * {@link Mob#getLookAngle()} 点积验证最终视线方向。
 * </p>
 */
public final class LookAtGameTests {
    /** 禁止实例化纯测试工具类。 */
    private LookAtGameTests() {
    }

    /**
     * 普通实体目标应把视线对准目标眼睛位置。
     *
     * @param helper GameTest 辅助对象
     */
    public static void lookAtLivingEntityEyes(GameTestHelper helper) {
        // 将 Agent 与目标分开放置，避免零长度方向向量。
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var target = spawnAgent(helper, EntityType.COW, new BlockPos(5, 1, 3));
        var controller = new LookAtController(mob);
        var payload = ProtoLookAt.newBuilder()
            .setEntity(ProtoLookAtEntityTarget.newBuilder().setEntityId(target.getId()))
            .build();

        var state = controller.apply(payload).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "look_at entity should complete instantly");
        assertLookingAt(helper, mob, EntityAnchorArgument.Anchor.EYES.apply(target));
        helper.succeed();
    }

    /**
     * 断言 Agent 当前视线与目标方向几乎重合。
     *
     * @param helper GameTest 辅助对象
     * @param mob 受控 Agent
     * @param targetPos 精确目标点
     */
    private static void assertLookingAt(GameTestHelper helper, Mob mob, Vec3 targetPos) {
        Vec3 expected = targetPos.subtract(mob.getEyePosition()).normalize();
        double alignment = mob.getLookAngle().normalize().dot(expected);
        assertTrue(helper, alignment > 0.99999, "look direction should align with target, dot=" + alignment);
    }
}
