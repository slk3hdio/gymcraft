package io.github.mousemeya.gymcraft.gym.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

import io.github.mousemeya.gymcraft.gym.attachment.MobAttachmentAccessScope;
import io.github.mousemeya.gymcraft.gym.attachment.MobAttachmentService;
import io.github.mousemeya.gymcraft.gym.attachment.MobAttachments;

/**
 * Agent 统一物品栏布局 —— 由 Mob 结构确定性导出的无状态 slot_id 映射快照。
 * <p>
 * 编号规则（GymCraft 自定义索引，非 Minecraft 全局槽位编号）：
 * <ul>
 *   <li>{@code 0..6} —— 装备槽（按 {@link #equipmentSlotId} 固定顺序：MAINHAND/FEET/LEGS/CHEST/HEAD/OFFHAND/BODY）</li>
 *   <li>{@code 7..N} —— Mob 自带 {@link Container} 或 env 授权专属背包的局部槽位</li>
 * </ul>
 * Mob 自带容器的发现顺序：
 * <ol>
 *   <li>{@link InventoryCarrier#getInventory()}（村民系、猪灵、悦灵、掠夺者等）</li>
 *   <li>{@link AbstractHorse#getInventory()}（马系；NeoForge 扩展方法）</li>
 * </ol>
 * 布局对象只保存槽位引用与读写策略，不复制物品；底层容器可能被替换（如马装备箱子），
 * 因此 {@link #resolve(Mob)} 无状态，每次操作前应重新解析。
 * 菜单会话打开时，菜单中的 Agent 物品栏槽位必须复用本布局的 slot_id，不得另配编号。
 * </p>
 */
public final class AgentInventoryLayout {
    /** 装备槽段大小：slot_id 0..6 固定对应七个 {@link EquipmentSlot}。 */
    public static final int EQUIPMENT_SLOT_COUNT = EquipmentSlot.values().length;

    private final List<AgentSlot> slots;

    private AgentInventoryLayout(List<AgentSlot> slots) {
        this.slots = List.copyOf(slots);
    }

    /**
     * 固定装备槽 slot_id 映射（1.21.1 的 EquipmentSlot 无 getId，改由本表定义，
     * 与 FakePlayer 桥接的映射表保持一致）。
     *
     * @param slot 装备槽枚举
     * @return 统一布局中的固定 slot_id
     */
    public static int equipmentSlotId(EquipmentSlot slot) {
        return switch (slot) {
            case MAINHAND -> 0;
            case FEET -> 1;
            case LEGS -> 2;
            case CHEST -> 3;
            case HEAD -> 4;
            case OFFHAND -> 5;
            case BODY -> 6;
        };
    }

    /**
     * 解析 Mob 当前的统一物品栏布局。
     *
     * @param mob 目标实体
     * @return 按 slot_id 升序排列的布局快照
     */
    public static AgentInventoryLayout resolve(Mob mob) {
        List<AgentSlot> slots = new ArrayList<>();
        // 装备槽按 equipmentSlotId 升序（沿用 26.1 版 slot_id 布局：MAINHAND=0, FEET..HEAD=1..4,
        // OFFHAND=5, BODY=6），保证 slots 列表顺序 == slot_id 顺序，slot(slotId) 直取成立
        for (var equipmentSlot : java.util.Arrays.stream(EquipmentSlot.values())
            .sorted(java.util.Comparator.comparingInt(AgentInventoryLayout::equipmentSlotId))
            .toList()) {
            slots.add(new EquipmentAgentSlot(mob, equipmentSlot));
        }
        var nativeContainer = findNativeContainer(mob);
        if (nativeContainer != null) {
            for (int i = 0; i < nativeContainer.container().getContainerSize(); i++) {
                slots.add(new NativeContainerAgentSlot(mob, nativeContainer, i));
            }
        } else if (MobAttachmentAccessScope.canAccess(mob, MobAttachments.AGENT_BACKPACK)) {
            ItemStackHandler backpack = MobAttachmentService
                .getIfPresent(mob, MobAttachments.AGENT_BACKPACK)
                .orElseThrow(() -> new IllegalStateException("Backpack access granted without attachment: " + mob.getUUID()));
            for (int i = 0; i < backpack.getSlots(); i++) {
                slots.add(new BackpackAgentSlot(mob, backpack, i));
            }
        }
        return new AgentInventoryLayout(slots);
    }

    /**
     * 把 Mob 统一物品栏（装备槽 + 自带容器）的全部物品掉落在 Mob 位置并清空槽位。
     * <p>
     * 供"实体即将被移除"的路径调用（如 reset 用快照替换受控实体前）：
     * 旧实体身上的物品属于世界状态，必须真正落到某处，任何路径都不允许静默删除。
     * </p>
     *
     * @param mob 目标实体
     * @return 掉落明细（用于日志）；无物品或非服务端 level 时为空
     */
    public static List<ItemStack> dropAllItems(Mob mob) {
        if (!(mob.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return List.of();
        }
        var dropped = new ArrayList<ItemStack>();
        for (AgentSlot slot : resolve(mob).slots()) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            slot.setItem(ItemStack.EMPTY);
            mob.spawnAtLocation(stack);
            dropped.add(stack);
        }
        return List.copyOf(dropped);
    }

    /**
     * 清空 Mob 统一物品栏（装备槽 + 自带容器）的全部物品。
     * <p>
     * reset 语义：旧实体即将被快照还原的实体替换，其携带的物品直接删除，不在世界掉落。
     * 仅供 reset 等"物品不再需要保留"的路径使用；若需保留即为 {@link #dropAllItems}。
     * 注意这会静默删除物品（不再掉落），调用方需自行确认符合业务语义。
     * </p>
     *
     * @param mob 目标实体
     */
    public static void clearAllItems(Mob mob) {
        for (AgentSlot slot : resolve(mob).slots()) {
            slot.setItem(ItemStack.EMPTY);
        }
    }

    /** Mob 自带容器及其发现来源。 */
    private record NativeContainer(Container container, LogicalSlotIdentity.NativeContainerSource source) {
    }

    /** 按计划发现顺序查找 Mob 自带容器；没有自带容器时返回 null。 */
    @Nullable
    private static NativeContainer findNativeContainer(Mob mob) {
        if (mob instanceof InventoryCarrier carrier) {
            return new NativeContainer(carrier.getInventory(), LogicalSlotIdentity.NativeContainerSource.INVENTORY_CARRIER);
        }
        if (mob instanceof AbstractHorse horse) {
            return new NativeContainer(horse.getInventory(), LogicalSlotIdentity.NativeContainerSource.HORSE);
        }
        return null;
    }

    /** @return 统一物品栏槽位总数（装备槽 7 个 + 容器槽个数） */
    public int size() {
        return this.slots.size();
    }

    /** @return Agent 存储槽个数（原生容器或专属背包；0 表示没有存储槽） */
    public int storageSlotCount() {
        return this.slots.size() - EQUIPMENT_SLOT_COUNT;
    }

    /**
     * @return Mob 自带容器槽个数；保留供旧调用方兼容，新增代码应使用 {@link #storageSlotCount()}
     * @deprecated 统一布局现在也可包含专属背包，名称无法准确表达语义
     */
    @Deprecated
    public int nativeContainerSlotCount() {
        return this.storageSlotCount();
    }

    /** @return 全部槽位，按 slot_id 升序（不可变） */
    public List<AgentSlot> slots() {
        return this.slots;
    }

    /**
     * 按 slot_id 取槽位。
     *
     * @throws IndexOutOfBoundsException slot_id 越界时抛出
     */
    public AgentSlot slot(int slotId) {
        return this.slots.get(slotId);
    }

    /**
     * 装备槽实现 —— 读取经 {@link Mob#getItemBySlot}，写入经 {@link Mob#setItemSlot}。
     * <p>
     * 放入检查遵循 {@code Mob#canUseSlot}；非手持槽额外要求
     * {@code Mob#isEquippableInSlot}。容量取 {@link EquipmentSlot#countLimit}
     * 与物品最大堆叠数的较小值；{@code countLimit == 0} 表示没有装备槽额外限制，
     * 实际上限仍受物品自身最大堆叠数约束。
     * </p>
     */
    private static final class EquipmentAgentSlot implements AgentSlot {
        private final Mob mob;
        private final EquipmentSlot equipmentSlot;

        private EquipmentAgentSlot(Mob mob, EquipmentSlot equipmentSlot) {
            this.mob = mob;
            this.equipmentSlot = equipmentSlot;
        }

        @Override
        public int slotId() {
            return equipmentSlotId(this.equipmentSlot);
        }

        @Override
        public LogicalSlotIdentity identity() {
            return new LogicalSlotIdentity.MobEquipment(this.mob.getUUID(), this.equipmentSlot);
        }

        @Override
        public String category() {
            return this.equipmentSlot.getName();
        }

        @Override
        public ItemStack getItem() {
            return this.mob.getItemBySlot(this.equipmentSlot);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            if (!this.mob.canUseSlot(this.equipmentSlot)) {
                return false;
            }
            if (this.equipmentSlot.getType() == EquipmentSlot.Type.HAND) {
                return true;
            }
            // 1.21.1 无 Mob#isEquippableInSlot：非手持槽要求物品声明的装备槽与目标槽一致
            return this.mob.getEquipmentSlotForItem(stack) == this.equipmentSlot;
        }

        @Override
        public int maxStackSize(ItemStack stack) {
            // 1.21.1 的 EquipmentSlot#countLimit 为 private：手持槽无额外限制，盔甲类槽位为 1
            if (this.equipmentSlot.getType() == EquipmentSlot.Type.HAND) {
                return stack.getMaxStackSize();
            }
            return Math.min(1, stack.getMaxStackSize());
        }

        @Override
        public void setItem(ItemStack stack) {
            this.mob.setItemSlot(this.equipmentSlot, stack);
        }
    }

    /**
     * Mob 自带容器槽实现 —— 读写直接作用于底层 {@link Container} 局部槽位。
     * <p>
     * 放入检查遵循 {@link Container#canPlaceItem}，容量受容器与物品最大堆叠数
     * 共同约束，写入后执行 {@link Container#setChanged}。
     * </p>
     */
    private static final class NativeContainerAgentSlot implements AgentSlot {
        private final Mob mob;
        private final NativeContainer nativeContainer;
        private final int localIndex;

        private NativeContainerAgentSlot(Mob mob, NativeContainer nativeContainer, int localIndex) {
            this.mob = mob;
            this.nativeContainer = nativeContainer;
            this.localIndex = localIndex;
        }

        @Override
        public int slotId() {
            return EQUIPMENT_SLOT_COUNT + this.localIndex;
        }

        @Override
        public LogicalSlotIdentity identity() {
            return new LogicalSlotIdentity.MobNativeContainer(
                this.mob.getUUID(), this.nativeContainer.source(), this.localIndex);
        }

        @Override
        public String category() {
            return "container";
        }

        @Override
        public ItemStack getItem() {
            return this.nativeContainer.container().getItem(this.localIndex);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return this.nativeContainer.container().canPlaceItem(this.localIndex, stack);
        }

        @Override
        public int maxStackSize(ItemStack stack) {
            return Math.min(this.nativeContainer.container().getMaxStackSize(), stack.getMaxStackSize());
        }

        @Override
        public void setItem(ItemStack stack) {
            this.nativeContainer.container().setItem(this.localIndex, stack);
            this.nativeContainer.container().setChanged();
        }
    }

    /**
     * 专属背包槽实现：使用 NeoForge 资源处理器持久化物品，并通过直接 set 完成写回。
     */
    private static final class BackpackAgentSlot implements AgentSlot {
        private final Mob mob;
        private final ItemStackHandler backpack;
        private final int localIndex;

        /** 保存背包处理器和局部索引，所有读写直接指向持久附件。 */
        private BackpackAgentSlot(Mob mob, ItemStackHandler backpack, int localIndex) {
            this.mob = mob;
            this.backpack = backpack;
            this.localIndex = localIndex;
        }

        /** @return 统一布局中的稳定槽位 ID */
        @Override
        public int slotId() {
            return EQUIPMENT_SLOT_COUNT + this.localIndex;
        }

        /** @return 由 Mob UUID 和局部索引组成的背包槽身份 */
        @Override
        public LogicalSlotIdentity identity() {
            return new LogicalSlotIdentity.MobBackpack(this.mob.getUUID(), this.localIndex);
        }

        /** @return 与原生容器兼容的槽位类别 */
        @Override
        public String category() {
            return "container";
        }

        /** @return 当前背包堆栈的副本 */
        @Override
        public ItemStack getItem() {
            return this.backpack.getStackInSlot(this.localIndex).copy();
        }

        /** @return 非空物品是否可存入该背包槽 */
        @Override
        public boolean mayPlace(ItemStack stack) {
            return !stack.isEmpty() && this.backpack.isItemValid(this.localIndex, stack);
        }

        /** @return 背包槽位上限与物品最大堆叠数的较小值 */
        @Override
        public int maxStackSize(ItemStack stack) {
            return Math.min(this.backpack.getSlotLimit(this.localIndex), stack.getMaxStackSize());
        }

        /** 使用物品堆叠覆盖背包槽。 */
        @Override
        public void setItem(ItemStack stack) {
            this.backpack.setStackInSlot(this.localIndex, stack.copy());
        }
    }
}
