package io.github.mousemeya.gymcraft.gym.menu.session;

import net.minecraft.core.BlockPos;

/**
 * 打开菜单的目标（对应 {@code ProtoOpenMenu} 的 oneof target）。
 * <p>
 * 阶段 4 的 OpenMenuController 从 proto 构造本类型；resolver 只消费本模型。
 * </p>
 */
public sealed interface OpenMenuTarget {

    /** 方块目标：经 {@code BlockState#getMenuProvider} 解析（保留双箱合并、阻挡检查等原版链路）。 */
    record Block(BlockPos pos) implements OpenMenuTarget {
    }

    /** 实体目标：按网络实体 ID 解析（菜单实体 / 商人 / 马）。 */
    record Entity(int entityId) implements OpenMenuTarget {
    }

    /** 自身目标：打开 Agent 统一物品栏的专用背包包装菜单（slot_id 0..N）。 */
    record Self() implements OpenMenuTarget {
    }
}
