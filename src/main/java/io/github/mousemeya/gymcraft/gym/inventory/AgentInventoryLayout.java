package io.github.mousemeya.gymcraft.gym.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.item.ItemStack;

/**
 * Agent 统一物品栏布局 —— 由 Mob 结构确定性导出的无状态 slot_id 映射快照。
 * <p>
 * 编号规则（GymCraft 自定义索引，非 Minecraft 全局槽位编号）：
 * <ul>
 *   <li>{@code 0..7} —— {@link EquipmentSlot#getId()} 对应的八个装备槽</li>
 *   <li>{@code 8..N} —— Mob 自带 {@link Container} 的局部槽位</li>
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
    /** 装备槽段大小：slot_id 0..7 固定对应八个 {@link EquipmentSlot}。 */
    public static final int EQUIPMENT_SLOT_COUNT = EquipmentSlot.VALUES.size();

    private final List<AgentSlot> slots;

    private AgentInventoryLayout(List<AgentSlot> slots) {
        this.slots = List.copyOf(slots);
    }

    /**
     * 解析 Mob 当前的统一物品栏布局。
     *
     * @param mob 目标实体
     * @return 按 slot_id 升序排列的布局快照
     */
    public static AgentInventoryLayout resolve(Mob mob) {
        List<AgentSlot> slots = new ArrayList<>();
        // 装备槽按 EquipmentSlot.getId() 升序（枚举声明顺序与 id 不一致：OFFHAND id=5，
        // FEET..HEAD id=1..4），保证 slots 列表顺序 == slot_id 顺序，slot(slotId) 直取成立
        for (var equipmentSlot : EquipmentSlot.VALUES.stream()
            .sorted(java.util.Comparator.comparingInt(EquipmentSlot::getId))
            .toList()) {
            slots.add(new EquipmentAgentSlot(mob, equipmentSlot));
        }
        var nativeContainer = findNativeContainer(mob);
        if (nativeContainer != null) {
            for (int i = 0; i < nativeContainer.container().getContainerSize(); i++) {
                slots.add(new NativeContainerAgentSlot(mob, nativeContainer, i));
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
            mob.spawnAtLocation(level, stack);
            dropped.add(stack);
        }
        return List.copyOf(dropped);
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

    /** @return 统一物品栏槽位总数（装备槽 8 个 + 容器槽个数） */
    public int size() {
        return this.slots.size();
    }

    /** @return Mob 自带容器槽个数（0 表示该 Mob 没有自带容器） */
    public int nativeContainerSlotCount() {
        return this.slots.size() - EQUIPMENT_SLOT_COUNT;
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
            return this.equipmentSlot.getId();
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
            return this.mob.isEquippableInSlot(stack, this.equipmentSlot);
        }

        @Override
        public int maxStackSize(ItemStack stack) {
            int limit = this.equipmentSlot.countLimit > 0
                ? this.equipmentSlot.countLimit
                : stack.getMaxStackSize();
            return Math.min(limit, stack.getMaxStackSize());
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
}
