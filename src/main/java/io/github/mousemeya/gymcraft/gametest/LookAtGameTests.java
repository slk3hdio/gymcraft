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
     * 掉落物目标应把视线对准其包围盒中心。
     *
     * @param helper GameTest 辅助对象
     */
    public static void lookAtItemCenter(GameTestHelper helper) {
        // 掉落物不经 spawnAgent 创建，保留 ItemEntity 的实际包围盒尺寸。
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        Vec3 itemPos = helper.absolutePos(new BlockPos(4, 2, 5)).getCenter();
        var item = new ItemEntity(helper.getLevel(), itemPos.x, itemPos.y, itemPos.z, new ItemStack(Items.APPLE));
        helper.getLevel().addFreshEntity(item);
        var controller = new LookAtController(mob);
        var payload = ProtoLookAt.newBuilder()
            .setItem(ProtoLookAtItemTarget.newBuilder().setEntityId(item.getId()))
            .build();

        var state = controller.apply(payload).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "look_at item should complete instantly");
        assertLookingAt(helper, mob, item.getBoundingBox().getCenter());
        helper.succeed();
    }

    /**
     * 方块目标应把视线对准非空气方块中心。
     *
     * @param helper GameTest 辅助对象
     */
    public static void lookAtBlockCenter(GameTestHelper helper) {
        // proto 使用绝对世界坐标，因此先把测试结构相对位置转换为绝对位置。
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        BlockPos targetPos = helper.absolutePos(new BlockPos(5, 3, 4));
        helper.setBlock(new BlockPos(5, 3, 4), Blocks.STONE);
        var controller = new LookAtController(mob);
        var payload = ProtoLookAt.newBuilder()
            .setBlock(ProtoLookAtBlockTarget.newBuilder()
                .setX(targetPos.getX())
                .setY(targetPos.getY())
                .setZ(targetPos.getZ()))
            .build();

        var state = controller.apply(payload).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "look_at block should complete instantly");
        assertLookingAt(helper, mob, Vec3.atCenterOf(targetPos));
        helper.succeed();
    }

    /**
     * 空气方块坐标也应作为合法目标，并把视线对准其中心。
     *
     * @param helper GameTest 辅助对象
     */
    public static void lookAtAirBlockCenter(GameTestHelper helper) {
        // 测试结构中的目标位置保持为空气，只验证坐标区块已加载。
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        BlockPos targetPos = helper.absolutePos(new BlockPos(5, 5, 4));
        var controller = new LookAtController(mob);
        var payload = ProtoLookAt.newBuilder()
            .setBlock(ProtoLookAtBlockTarget.newBuilder()
                .setX(targetPos.getX())
                .setY(targetPos.getY())
                .setZ(targetPos.getZ()))
            .build();

        var state = controller.apply(payload).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "look_at air should complete instantly");
        assertEquals(helper, "minecraft:air", state.details().get("block"), "details should report the air block");
        assertLookingAt(helper, mob, Vec3.atCenterOf(targetPos));
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
