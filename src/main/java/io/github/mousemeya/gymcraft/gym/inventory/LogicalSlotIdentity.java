package io.github.mousemeya.gymcraft.gym.inventory;

import java.util.UUID;

import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;

/**
 * 规范化逻辑槽身份 —— 用于跨容器包装识别同一个 Mob 实际槽位。
 * <p>
 * 同一个 Mob 实际槽位可能通过不同的 {@code Container} 包装出现在菜单中，
 * 因此不能用 {@code Slot.container} 引用判断槽位是否相同；所有 slot_id 映射
 * 都应以本接口描述的规范化身份去重（计划 7.3 节）。
 * </p>
 */
public sealed interface LogicalSlotIdentity {

    /**
     * Mob 装备槽身份：由 Mob UUID 与 {@link EquipmentSlot} 唯一确定。
     * <p>
     * 例如 1.21.1 {@code HorseInventoryMenu} 的 BODY 槽由
     * {@code AbstractHorse#getBodyArmorAccess()} 提供独立 {@code Container}，但最终读写的
     * 仍是同一匹马的 BODY 装备槽，必须规范化为本身份并复用 slot_id 6。
     * </p>
     *
     * @param mobId 所属 Mob 的 UUID
     * @param slot  装备槽
     */
    record MobEquipment(UUID mobId, EquipmentSlot slot) implements LogicalSlotIdentity {
    }

    /**
     * Mob 自带容器槽身份：由 Mob UUID、容器来源与容器局部索引唯一确定。
     * <p>
     * 容器实例可能被整体替换（如 {@code AbstractChestedHorse#setChest} 重建
     * {@code SimpleContainer}），身份不含容器引用；实例级校验由会话阶段
     * 使用 {@code AbstractHorse#hasInventoryChanged(Container)} 等引用比较完成。
     * </p>
     *
     * @param mobId      所属 Mob 的 UUID
     * @param source     容器发现来源
     * @param localIndex 容器局部槽位索引
     */
    record MobNativeContainer(UUID mobId, NativeContainerSource source, int localIndex) implements LogicalSlotIdentity {
    }

    /**
     * Mob 专属背包槽身份：由 Mob UUID 与背包局部索引唯一确定。
     *
     * @param mobId 所属 Mob 的 UUID
     * @param localIndex 背包局部槽位索引
     */
    record MobBackpack(UUID mobId, int localIndex) implements LogicalSlotIdentity {
    }

    /**
     * FakePlayer 桥接格身份：菜单槽指向 FakePlayer 物品栏中映射了 Agent 槽位的格子。
     * <p>
     * 用于会话建立时的槽位规范化：命中本身份的菜单槽认领对应 Agent 背包 slot_id
     * （与 MobEquipment/MobNativeContainer/MobBackpack 身份等效，同属该 Agent 槽位）。
     * </p>
     *
     * @param agentSlotId 桥接格映射的 Agent 背包 slot_id
     */
    record FakeBridge(int agentSlotId) implements LogicalSlotIdentity {
    }

    /**
     * 菜单自有槽身份：无法规范化为 Agent 既有槽位的菜单槽。
     * <p>
     * 会话 slot_id 从 N+1 起分配，随会话失效；重开菜单不保证复用原编号。
     * 容器身份使用引用比较（{@link Container} 未重写 equals，按引用判等）。
     * </p>
     *
     * @param sessionId 所属会话 ID
     * @param container 槽位对应的底层容器（引用即身份）
     * @param localIndex 容器局部槽位索引
     */
    record MenuOwned(long sessionId, Container container, int localIndex) implements LogicalSlotIdentity {
    }

    /** Mob 自带容器的发现来源（发现顺序见 {@link AgentInventoryLayout#resolve}）。 */
    enum NativeContainerSource {
        /** {@code InventoryCarrier#getInventory()}（村民系、猪灵、悦灵、掠夺者等） */
        INVENTORY_CARRIER,
        /** {@code AbstractHorse#getInventory()}（马系，含货物槽） */
        HORSE
    }
}
