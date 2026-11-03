package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.action.component.LookAtController;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoLookAt;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoLookAtBlockTarget;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoLookAtEntityTarget;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoLookAtSlotTarget;

/** 注视动作 GameTest —— 验证实体、掉落物、方块和槽位目标及其描述反馈。 */
public final class LookAtGameTests {
    /** 禁止实例化纯测试工具类。 */
    private LookAtGameTests() {
    }

    /** 普通实体目标应把视线对准眼睛并反馈实体描述。 */
    public static void lookAtLivingEntityEyes(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var target = spawnAgent(helper, EntityType.COW, new BlockPos(5, 1, 3));
        var controller = new LookAtController(mob);
        var payload = ProtoLookAt.newBuilder()
            .setEntity(ProtoLookAtEntityTarget.newBuilder().setEntityId(target.getId()))
            .build();

        var state = controller.apply(payload).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "look_at entity should complete instantly");
        assertTrue(helper, state.description().contains("minecraft:cow"), "entity description should contain entity id");
        assertLookingAt(helper, mob, EntityAnchorArgument.Anchor.EYES.apply(target));
        helper.succeed();
    }

    /** 掉落物应作为 entity 目标注视包围盒中心并反馈物品描述。 */
    public static void lookAtItemEntityAsEntity(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        Vec3 itemPos = Vec3.atCenterOf(helper.absolutePos(new BlockPos(5, 2, 2)));
        var item = new ItemEntity(helper.getLevel(), itemPos.x, itemPos.y, itemPos.z, new ItemStack(Items.IRON_INGOT, 3));
        helper.getLevel().addFreshEntity(item);
        var payload = ProtoLookAt.newBuilder()
            .setEntity(ProtoLookAtEntityTarget.newBuilder().setEntityId(item.getId()))
            .build();

        var state = new LookAtController(mob).apply(payload).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "item entity should be a valid entity target");
        assertTrue(helper, state.description().contains("minecraft:iron_ingot"), "item description should contain item id");
        assertLookingAt(helper, mob, item.getBoundingBox().getCenter());
        helper.succeed();
    }

    /** 槽位查询应描述附魔物品与空槽、支持截断且不改变视线。 */
    public static void lookAtSlotDescribesItemAndEmpty(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var sword = new ItemStack(Items.DIAMOND_SWORD);
        var enchantments = helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        sword.enchant(enchantments.getOrThrow(Enchantments.SHARPNESS), 1);
        mob.setItemSlot(EquipmentSlot.MAINHAND, sword);
        mob.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        var controller = new LookAtController(mob);
        float yaw = mob.getYRot();
        float pitch = mob.getXRot();

        var state = controller.apply(slotPayload(EquipmentSlot.MAINHAND.getId())).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "occupied slot query should complete");
        assertTrue(helper, state.description().contains("minecraft:diamond_sword"), "slot description should contain item id");
        assertTrue(helper, state.description().contains("Sharpness"), "slot description should contain enchantment tooltip");
        assertEquals(helper, yaw, mob.getYRot(), "slot query must not change yaw");
        assertEquals(helper, pitch, mob.getXRot(), "slot query must not change pitch");

        controller.setDescriptionMaxLines(1);
        state = controller.apply(slotPayload(EquipmentSlot.MAINHAND.getId())).initialState();
        assertTrue(helper, state.description().endsWith("..."), "truncated description should end with ellipsis");

        controller.setDescriptionMaxLines(8);
        state = controller.apply(slotPayload(EquipmentSlot.OFFHAND.getId())).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "empty slot query should complete");
        assertTrue(helper, state.description().contains("empty"), "empty slot description should contain empty");
        helper.succeed();
    }

    /** 方块目标应注视方块中心并反馈方块注册表 ID。 */
    public static void lookAtBlockDescribesState(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        BlockPos relative = new BlockPos(5, 2, 2);
        helper.setBlock(relative, Blocks.STONE);
        BlockPos absolute = helper.absolutePos(relative);
        var payload = ProtoLookAt.newBuilder()
            .setBlock(ProtoLookAtBlockTarget.newBuilder().setX(absolute.getX()).setY(absolute.getY()).setZ(absolute.getZ()))
            .build();

        var state = new LookAtController(mob).apply(payload).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "block target should complete instantly");
        assertTrue(helper, state.description().contains("minecraft:stone"), "block description should contain block id");
        assertLookingAt(helper, mob, Vec3.atCenterOf(absolute));
        helper.succeed();
    }

    private static ProtoLookAt slotPayload(int slotId) {
        return ProtoLookAt.newBuilder().setSlot(ProtoLookAtSlotTarget.newBuilder().setSlotId(slotId)).build();
    }

    /** 断言 Agent 当前视线与目标方向几乎重合。 */
    private static void assertLookingAt(GameTestHelper helper, Mob mob, Vec3 targetPos) {
        Vec3 expected = targetPos.subtract(mob.getEyePosition()).normalize();
        double alignment = mob.getLookAngle().normalize().dot(expected);
        assertTrue(helper, alignment > 0.99999, "look direction should align with target, dot=" + alignment);
    }
}
