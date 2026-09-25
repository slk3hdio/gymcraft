package io.github.mousemeya.gymcraft.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;

import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.component.ClickMenuButtonController;
import io.github.mousemeya.gymcraft.gym.action.component.CloseMenuController;
import io.github.mousemeya.gymcraft.gym.action.component.MoveMenuItemController;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoClickMenuButton;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoCloseMenu;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMoveMenuItem;
import io.github.mousemeya.gymcraft.gym.menu.session.LogicalMenuSession;
import io.github.mousemeya.gymcraft.gym.menu.session.LogicalMenuSessions;
import io.github.mousemeya.gymcraft.gym.menu.adapter.MenuAdapters;
import io.github.mousemeya.gymcraft.gym.menu.session.OpenMenuTarget;
import io.github.mousemeya.gymcraft.gym.observation.component.MenuObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMenuObservation;

/**
 * 菜单 GameTest 公共辅助 —— 生成测试 Mob、搭地板/容器、驱动会话与动作组件、断言。
 * <p>
 * 测试直接驱动 {@link LogicalMenuSessions} 与各 controller（不经 gRPC 环境），
 * {@link #observe} 模拟一次菜单 observation（refresh + 提交观测基线），
 * 是移动/按钮动作通过 stale 校验的前提。
 * </p>
 */
public final class MenuGameTestSupport {
    private MenuGameTestSupport() {
    }

    public static void assertTrue(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            helper.fail(message);
        }
    }

    public static void assertEquals(GameTestHelper helper, Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            helper.fail(message + " (expected=" + expected + " actual=" + actual + ")");
        }
    }

    /** 生成一个无自主 AI 的测试 Mob（带 3x3 石头地板，避免掉落/游荡干扰）。 */
    public static <T extends Mob> T spawnAgent(GameTestHelper helper, EntityType<T> type, BlockPos relPos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                helper.setBlock(relPos.offset(dx, 0, dz), Blocks.STONE);
            }
        }
        T mob = helper.spawnWithNoFreeWill(type, relPos.above());
        // GameTestHelper.removeFreeWill 只清 goalSelector；测试 Agent 还需清除自主索敌 Goal。
        mob.targetSelector.removeAllGoals(goal -> true);
        return mob;
    }

    /** 放置箱子并返回其 BlockEntity。 */
    public static ChestBlockEntity placeChest(GameTestHelper helper, BlockPos relPos) {
        helper.setBlock(relPos, Blocks.CHEST);
        return helper.getBlockEntity(relPos, ChestBlockEntity.class);
    }

    /** 打开方块菜单并断言成功。 */
    public static LogicalMenuSession openBlockMenu(GameTestHelper helper, Mob mob, BlockPos relPos) {
        LogicalMenuSessions.OpenResult result = openBlockMenuRaw(helper, mob, relPos);
        assertTrue(helper, result.success(), "open menu failed: " + result.failureReason());
        return result.session();
    }

    /** 打开方块菜单（不断言，供失败路径用例使用）。 */
    public static LogicalMenuSessions.OpenResult openBlockMenuRaw(GameTestHelper helper, Mob mob, BlockPos relPos) {
        return LogicalMenuSessions.open(mob, new OpenMenuTarget.Block(helper.absolutePos(relPos)));
    }

    /** 模拟一次菜单 observation：refresh 会话并提交观测基线。 */
    public static ProtoMenuObservation observe(Mob mob) {
        return new MenuObservationCreator().create(mob);
    }

    /** 菜单槽（按 {@code menu.slots} 索引）对应的会话 slot_id。 */
    public static int sessionSlotId(LogicalMenuSession session, int menuSlotIndex) {
        var ids = MenuAdapters.sessionSlotIds(session, session.menu(), menuSlotIndex);
        if (ids.size() != 1) {
            throw new IllegalStateException("menu slot " + menuSlotIndex + " maps to " + ids.size() + " session slots");
        }
        return ids.getFirst();
    }

    public static ActionState moveMenuItem(Mob mob, long sessionId, int sourceSlotId, int targetSlotId, int count) {
        return moveMenuItem(mob, sessionId, sourceSlotId, targetSlotId, count, 1);
    }

    /** 移动菜单物品（可指定 repeat 重复次数）。 */
    public static ActionState moveMenuItem(Mob mob, long sessionId, int sourceSlotId, int targetSlotId, int count, int repeat) {
        return new MoveMenuItemController(mob).apply(ProtoMoveMenuItem.newBuilder()
            .setSessionId(sessionId)
            .addMoves(io.github.mousemeya.gymcraft.gym.action.proto.Move.newBuilder()
            .setSourceSlotId(sourceSlotId)
            .setTargetSlotId(targetSlotId)
            .setCount(count)
            .setRepeat(repeat))
            .build()).initialState();
    }

    public static ActionState closeMenu(Mob mob, long sessionId) {
        return new CloseMenuController(mob).apply(ProtoCloseMenu.newBuilder()
            .setSessionId(sessionId)
            .build()).initialState();
    }

    public static ActionState clickButton(Mob mob, long sessionId, int buttonId) {
        return new ClickMenuButtonController(mob).apply(ProtoClickMenuButton.newBuilder()
            .setSessionId(sessionId)
            .setButtonId(buttonId)
            .build()).initialState();
    }

    /** 直接写菜单槽（测试布景用；触发 slotsChanged 等容器回调）。 */
    public static void setMenuSlot(LogicalMenuSession session, int menuSlotIndex, ItemStack stack) {
        AbstractContainerMenu menu = session.menu();
        menu.slots.get(menuSlotIndex).set(stack);
    }

    /** 放置讲台并放入指定页数的成书。 */
    public static LecternBlockEntity placeLecternWithBook(GameTestHelper helper, BlockPos relPos, int pageCount) {
        helper.setBlock(relPos, Blocks.LECTERN);
        LecternBlockEntity lectern = helper.getBlockEntity(relPos, LecternBlockEntity.class);
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        var pages = new java.util.ArrayList<Filterable<Component>>(pageCount);
        for (int i = 0; i < pageCount; i++) {
            pages.add(Filterable.passThrough(Component.literal("page " + i)));
        }
        book.set(DataComponents.WRITTEN_BOOK_CONTENT,
            new WrittenBookContent(Filterable.passThrough("Test Book"), "gymcraft", 0, java.util.List.copyOf(pages), true));
        lectern.setBook(book);
        // setBook 只更新 BlockEntity，HAS_BOOK 方块状态需显式设置（否则 getMenuProvider 返回 null）
        helper.setBlock(relPos, helper.getBlockState(relPos).setValue(LecternBlock.HAS_BOOK, true));
        return lectern;
    }
}
