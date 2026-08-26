package io.github.mousemeya.gymcraft.gym.menu.bridge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;

import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.inventory.AgentSlot;
import io.github.mousemeya.gymcraft.gym.inventory.LogicalSlotIdentity;

/**
 * Agent 物品栏桥接 —— 把 Agent 统一物品栏（{@link AgentInventoryLayout}）映射到
 * 会话独占 FakePlayer 的玩家物品栏，并在每次菜单操作后写回 Mob。
 * <p>
 * 1.26 的玩家 {@link Inventory} 是单一 36 格 {@code items} 列表加
 * {@code EntityEquipment}（槽号 36–42 映射到装备槽），{@code getContainerSize()} 为 43；
 * 绝大多数菜单只绑定 0–35 的主物品栏槽位，因此映射容量阈值取 36，不按 43 计算。
 * </p>
 * <p>
 * 固定映射表（实现常量，不随菜单变化；slot_id == {@code EquipmentSlot#getId()}）：
 * <pre>
 * slot_id 0 (MAINHAND) -&gt; 普通格 0（{@link #MAINHAND_INVENTORY_SLOT}，与 FakePlayer 默认选中格一致）
 * slot_id 1 (FEET)     -&gt; 装备槽 36
 * slot_id 2 (LEGS)     -&gt; 装备槽 37
 * slot_id 3 (CHEST)    -&gt; 装备槽 38
 * slot_id 4 (HEAD)     -&gt; 装备槽 39
 * slot_id 5 (OFFHAND)  -&gt; 装备槽 40（{@code Inventory.SLOT_OFFHAND}）
 * slot_id 6 (BODY)     -&gt; 预留普通格 35（{@link #RESERVED_SLOT_BODY}）
 * slot_id 7 (SADDLE)   -&gt; 预留普通格 34（{@link #RESERVED_SLOT_SADDLE}）
 * slot_id 8+k (容器槽) -&gt; 普通格 1+k（{@link #NATIVE_CONTAINER_BASE_SLOT} 起，最多 {@link #MAX_NATIVE_CONTAINER_SLOTS} 格）
 * </pre>
 * 八个 Mob 装备槽优先复制到 FakePlayer 对应装备位置；BODY/SADDLE 没有参与菜单绑定的
 * 对应位置，复制到预留固定普通格。预留格只用于会话桥接，不构成新的持久 Agent 背包，
 * 也不获得第二个 slot_id。
 * </p>
 * <p>
 * 首版约束：扣除预留后超出可安全映射容量时拒绝建立桥接（抛 {@link MenuBridgeException}），
 * 不能隐藏额外槽位。未映射的 FakePlayer 槽位必须保持为空；写回时发现未映射槽非空
 * 视为边界异常，记 warning 并按清算规则处理，绝不静默删除。
 * </p>
 * <p>
 * 所有方法仅允许在服务端 tick 线程调用。
 * </p>
 */
public final class AgentInventoryBridge {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Mob 主手桥接到的固定普通格（FakePlayer 默认 selected=0，与主手语义一致）。 */
    public static final int MAINHAND_INVENTORY_SLOT = 0;
    /** 预留固定普通格：Mob {@code EquipmentSlot.BODY} 的桥接位置。 */
    public static final int RESERVED_SLOT_BODY = 35;
    /** 预留固定普通格：Mob {@code EquipmentSlot.SADDLE} 的桥接位置。 */
    public static final int RESERVED_SLOT_SADDLE = 34;
    /** Mob 自带容器槽映射的普通格起始位置。 */
    public static final int NATIVE_CONTAINER_BASE_SLOT = 1;
    /**
     * Mob 自带容器可映射的最大槽数：36 格普通物品栏扣除主手格（1）与两个预留格（2）。
     * 映射容量阈值按计划取 36（菜单只绑定 0–35），不按 43 计算。
     */
    public static final int MAX_NATIVE_CONTAINER_SLOTS = 33;

    private final Mob mob;
    private final FakePlayer player;
    private final AgentInventoryLayout layout;
    private final List<SlotMapping> mappings;
    private final Set<Integer> mappedInventoryIndices;

    private AgentInventoryBridge(Mob mob, FakePlayer player, AgentInventoryLayout layout, List<SlotMapping> mappings) {
        this.mob = mob;
        this.player = player;
        this.layout = layout;
        this.mappings = List.copyOf(mappings);
        var indices = new HashSet<Integer>();
        for (var mapping : mappings) {
            indices.add(mapping.inventoryIndex());
        }
        this.mappedInventoryIndices = Set.copyOf(indices);
    }

    /** 单个 Agent 槽位到 FakePlayer 物品栏槽号的映射。 */
    public record SlotMapping(AgentSlot agentSlot, int inventoryIndex) {
    }

    /** 清算去向：{@code mainhand}（空主手）、{@code container}（Mob 自带容器）、{@code dropped}（世界掉落）。 */
    public record RelocatedItem(ItemStack stack, String destination) {
    }

    /** 写回结果：清算与掉落明细（供 ActionState details / 生命周期 warning 使用）。 */
    public record WriteBackResult(List<RelocatedItem> relocated, List<RelocatedItem> dropped) {
        public boolean isEmpty() {
            return this.relocated.isEmpty() && this.dropped.isEmpty();
        }
    }

    /**
     * 建立桥接：解析 Mob 当前统一物品栏布局并同步到 FakePlayer。
     *
     * @throws MenuBridgeException Mob 自带容器超出可映射容量（{@link #MAX_NATIVE_CONTAINER_SLOTS}）时抛出
     */
    public static AgentInventoryBridge establish(Mob mob, FakePlayer player) {
        AgentInventoryLayout layout = AgentInventoryLayout.resolve(mob);
        if (layout.nativeContainerSlotCount() > MAX_NATIVE_CONTAINER_SLOTS) {
            throw new MenuBridgeException(
                "Mob native container has " + layout.nativeContainerSlotCount()
                    + " slots, exceeding the bridge capacity of " + MAX_NATIVE_CONTAINER_SLOTS
                    + " (mob=" + mob.getUUID() + ")");
        }
        List<SlotMapping> mappings = new ArrayList<>();
        for (var slot : layout.slots()) {
            mappings.add(new SlotMapping(slot, inventoryIndexFor(slot)));
        }
        var bridge = new AgentInventoryBridge(mob, player, layout, mappings);
        bridge.syncToFakePlayer();
        return bridge;
    }

    /** 按固定映射表计算 Agent 槽位对应的 FakePlayer 物品栏槽号。 */
    private static int inventoryIndexFor(AgentSlot slot) {
        return switch (slot.identity()) {
            case LogicalSlotIdentity.MobEquipment equipment -> switch (equipment.slot()) {
                case MAINHAND -> MAINHAND_INVENTORY_SLOT;
                case OFFHAND -> Inventory.SLOT_OFFHAND;
                case FEET, LEGS, CHEST, HEAD -> equipment.slot().getIndex(Inventory.INVENTORY_SIZE);
                case BODY -> RESERVED_SLOT_BODY;
                case SADDLE -> RESERVED_SLOT_SADDLE;
            };
            case LogicalSlotIdentity.MobNativeContainer container ->
                NATIVE_CONTAINER_BASE_SLOT + container.localIndex();
            // 物品栏布局只产生 MobEquipment/MobNativeContainer 身份；
            // FakeBridge/MenuOwned 是会话阶段的规范化身份，不会出现在布局中
            default -> throw new IllegalStateException("Unexpected agent slot identity: " + slot.identity());
        };
    }

    /** @return 建立桥接时的布局快照 */
    public AgentInventoryLayout layout() {
        return this.layout;
    }

    /** @return 全部槽位映射（Agent 槽位 → FakePlayer 物品栏槽号） */
    public List<SlotMapping> mappings() {
        return this.mappings;
    }

    /** @return FakePlayer 物品栏槽号映射到的 Agent slot_id；未映射返回 null */
    @Nullable
    public Integer agentSlotIdAt(int inventoryIndex) {
        for (var mapping : this.mappings) {
            if (mapping.inventoryIndex() == inventoryIndex) {
                return mapping.agentSlot().slotId();
            }
        }
        return null;
    }

    /**
     * 把 Agent 统一物品栏同步到 FakePlayer（复制物品，建立会话或替换会话时使用）。
     * <p>
     * 未映射的 FakePlayer 槽位强制清空；发现非空残留时记 warning 并按清算规则处理。
     * </p>
     */
    public void syncToFakePlayer() {
        Inventory inventory = this.player.getInventory();
        for (var mapping : this.mappings) {
            inventory.setItem(mapping.inventoryIndex(), mapping.agentSlot().getItem().copy());
        }
        clearUnmappedSlots();
    }

    /**
     * 把 FakePlayer 映射槽写回 Mob（每次菜单操作后调用）。
     * <p>
     * 普通容器映射槽按原容器规则写回（canPlaceItem/容量）；装备及特殊槽若仍满足原槽位
     * 要求则写回原槽，否则依次清算：Agent 空主手 → Mob 自带容器 → Mob 位置掉落。
     * 若原主手本身就是待清算槽，不用同一逻辑槽作为回退目标。任何路径都不静默删除物品。
     * </p>
     *
     * @return 清算与掉落明细；全部直接写回时为空结果
     */
    public WriteBackResult writeBackToMob() {
        List<RelocatedItem> relocated = new ArrayList<>();
        List<RelocatedItem> dropped = new ArrayList<>();
        Inventory inventory = this.player.getInventory();
        for (var mapping : this.mappings) {
            AgentSlot agentSlot = mapping.agentSlot();
            ItemStack fakeStack = inventory.getItem(mapping.inventoryIndex());
            ItemStack mobStack = agentSlot.getItem();
            if (ItemStack.matches(fakeStack, mobStack)) {
                continue;
            }
            if (fakeStack.isEmpty() || canReturnToOrigin(agentSlot, fakeStack)) {
                agentSlot.setItem(fakeStack.isEmpty() ? ItemStack.EMPTY : fakeStack.copy());
                continue;
            }
            liquidate(agentSlot, fakeStack.copy(), relocated, dropped);
        }
        // 桥接边界校验：未映射槽位必须保持为空，发现残留按清算规则处理，不静默删除
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (this.mappedInventoryIndices.contains(i)) {
                continue;
            }
            ItemStack leftover = inventory.getItem(i);
            if (leftover.isEmpty()) {
                continue;
            }
            LOGGER.warn(
                "Unmapped FakePlayer slot {} is not empty ({}); liquidating instead of deleting (mob={})",
                i, leftover, this.mob.getUUID()
            );
            inventory.setItem(i, ItemStack.EMPTY);
            liquidate(null, leftover, relocated, dropped);
        }
        return new WriteBackResult(List.copyOf(relocated), List.copyOf(dropped));
    }

    /**
     * 把指定 Agent 槽位从 Mob 侧同步到 FakePlayer（建立/替换会话外的单槽同步）。
     * <p>
     * synthetic Slot 与马货物等直写 Mob 侧的槽位在菜单操作后调用，使随后的
     * {@link #writeBackToMob()} 统一写回路径两侧一致，避免陈旧的 FakePlayer 副本
     * 被误判为变更而覆盖 Mob 侧的新内容。
     * </p>
     */
    public void syncSlotToFakePlayer(int agentSlotId) {
        for (var mapping : this.mappings) {
            if (mapping.agentSlot().slotId() == agentSlotId) {
                this.player.getInventory().setItem(mapping.inventoryIndex(), mapping.agentSlot().getItem().copy());
                return;
            }
        }
    }

    /**
     * 清算菜单操作的多余物品到 FakePlayer 桥接侧（计划 8 节去向顺序），
     * 随后由 {@link #writeBackToMob()} 统一写回 Mob 并核对边界。
     * <p>
     * 去向：Agent 空主手映射格 → Mob 自带容器映射格（先合并同类堆叠，再放空槽）→
     * Mob 位置掉落（世界操作无法经桥接延迟，立即发生）。限制检查始终按原 Mob 槽位
     * （{@link AgentSlot#mayPlace}/{@link AgentSlot#maxStackSize}）执行，不因
     * FakePlayer 一侧是普通格而绕过装备/容器要求。若原主手本身就是待清算槽
     * （{@code origin}），不再以主手作为回退目标。任何路径都不静默删除。
     * </p>
     *
     * @param stack  待清算物品（本方法不修改入参）
     * @param origin 多余物品的来源槽位（如移动动作的 source）；null 表示无明确来源
     * @return 清算与掉落明细
     */
    public WriteBackResult liquidateToBridge(ItemStack stack, @Nullable AgentSlot origin) {
        List<RelocatedItem> relocated = new ArrayList<>();
        List<RelocatedItem> dropped = new ArrayList<>();
        if (stack.isEmpty()) {
            return new WriteBackResult(List.of(), List.of());
        }
        Inventory inventory = this.player.getInventory();
        ItemStack remaining = stack.copy();
        // ① 空主手映射格（整叠放入；限制按原 Mob 主手槽规则）
        AgentSlot mainhand = this.layout.slot(EquipmentSlot.MAINHAND.getId());
        if (origin != mainhand) {
            int mainhandIndex = this.inventoryIndexOf(mainhand);
            if (inventory.getItem(mainhandIndex).isEmpty() && canReturnToOrigin(mainhand, remaining)) {
                inventory.setItem(mainhandIndex, remaining.copy());
                relocated.add(new RelocatedItem(remaining.copy(), "mainhand"));
                return new WriteBackResult(List.copyOf(relocated), List.copyOf(dropped));
            }
        }
        // ② Mob 自带容器映射格
        int beforeContainer = remaining.getCount();
        remaining = this.tryInsertIntoMappedContainerSlots(remaining);
        if (remaining.getCount() < beforeContainer) {
            relocated.add(new RelocatedItem(stack.copyWithCount(beforeContainer - remaining.getCount()), "container"));
        }
        if (remaining.isEmpty()) {
            return new WriteBackResult(List.copyOf(relocated), List.copyOf(dropped));
        }
        // ③ Mob 位置掉落
        if (this.mob.level() instanceof ServerLevel level) {
            this.mob.spawnAtLocation(level, remaining);
            dropped.add(new RelocatedItem(remaining.copy(), "dropped"));
        } else {
            // 非服务端环境无法掉落：记 error 兜底（按约定本类只在服务端 tick 线程调用）
            LOGGER.error("Cannot liquidate item outside server level; item lost: {} (mob={})", remaining, this.mob.getUUID());
        }
        return new WriteBackResult(List.copyOf(relocated), List.copyOf(dropped));
    }

    /** 按 Agent 槽位查找映射的 FakePlayer 物品栏槽号（映射由同一布局构建，必定存在）。 */
    private int inventoryIndexOf(AgentSlot slot) {
        for (var mapping : this.mappings) {
            if (mapping.agentSlot() == slot) {
                return mapping.inventoryIndex();
            }
        }
        throw new IllegalStateException("Agent slot " + slot.slotId() + " has no bridge mapping");
    }

    /** 尝试把物品插入 Mob 自带容器的 FakePlayer 映射格；返回无法插入的余量。 */
    private ItemStack tryInsertIntoMappedContainerSlots(ItemStack stack) {
        Inventory inventory = this.player.getInventory();
        ItemStack remaining = stack;
        for (var mapping : this.mappings) {
            if (remaining.isEmpty()) {
                break;
            }
            AgentSlot slot = mapping.agentSlot();
            if (!(slot.identity() instanceof LogicalSlotIdentity.MobNativeContainer)) {
                continue;
            }
            ItemStack existing = inventory.getItem(mapping.inventoryIndex());
            if (!existing.isEmpty()) {
                // 合并同类堆叠
                if (ItemStack.isSameItemSameComponents(existing, remaining) && existing.getCount() < slot.maxStackSize(existing)) {
                    int moved = Math.min(remaining.getCount(), slot.maxStackSize(existing) - existing.getCount());
                    existing.grow(moved);
                    inventory.setItem(mapping.inventoryIndex(), existing);
                    remaining.shrink(moved);
                }
                continue;
            }
            if (!slot.mayPlace(remaining)) {
                continue;
            }
            int moved = Math.min(remaining.getCount(), slot.maxStackSize(remaining));
            inventory.setItem(mapping.inventoryIndex(), remaining.copyWithCount(moved));
            remaining.shrink(moved);
        }
        return remaining;
    }

    /** 物品变化后是否仍满足原槽位要求（可写回原槽）。 */
    private static boolean canReturnToOrigin(AgentSlot agentSlot, ItemStack stack) {
        return agentSlot.mayPlace(stack) && stack.getCount() <= agentSlot.maxStackSize(stack);
    }

    /** 清空未映射的 FakePlayer 槽位；发现非空残留时记 warning 并丢弃到清算（此处仅同步期防御，理论为空）。 */
    private void clearUnmappedSlots() {
        Inventory inventory = this.player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (this.mappedInventoryIndices.contains(i)) {
                continue;
            }
            if (!inventory.getItem(i).isEmpty()) {
                LOGGER.warn("Clearing non-empty unmapped FakePlayer slot {} during sync (mob={})", i, this.mob.getUUID());
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }
    }

    /**
     * 清算单份物品：空主手 → Mob 自带容器 → Mob 位置掉落。
     *
     * @param origin 待清算的原槽位；为 null 表示无明确来源（未映射槽残留）。
     *               原主手本身待清算时不再以主手作为回退目标。
     */
    private void liquidate(@Nullable AgentSlot origin, ItemStack stack, List<RelocatedItem> relocated, List<RelocatedItem> dropped) {
        ItemStack remaining = stack;
        // ① 空主手
        AgentSlot mainhand = this.layout.slot(EquipmentSlot.MAINHAND.getId());
        if (origin != mainhand && mainhand.getItem().isEmpty() && canReturnToOrigin(mainhand, remaining)) {
            mainhand.setItem(remaining);
            relocated.add(new RelocatedItem(remaining.copy(), "mainhand"));
            return;
        }
        // ② Mob 自带容器（先合并同类堆叠，再放空槽）
        remaining = tryInsertIntoNativeContainer(remaining);
        if (remaining.isEmpty()) {
            relocated.add(new RelocatedItem(stack.copy(), "container"));
            return;
        }
        // ③ Mob 位置掉落
        if (this.mob.level() instanceof ServerLevel level) {
            this.mob.spawnAtLocation(level, remaining);
            dropped.add(new RelocatedItem(remaining.copy(), "dropped"));
        } else {
            // 非服务端环境无法掉落：记 error 兜底（按约定本类只在服务端 tick 线程调用）
            LOGGER.error("Cannot liquidate item outside server level; item lost: {} (mob={})", remaining, this.mob.getUUID());
        }
    }

    /** 尝试把物品插入 Mob 自带容器；返回无法插入的余量。 */
    private ItemStack tryInsertIntoNativeContainer(ItemStack stack) {
        ItemStack remaining = stack;
        for (var slot : this.layout.slots()) {
            if (remaining.isEmpty()) {
                break;
            }
            if (!(slot.identity() instanceof LogicalSlotIdentity.MobNativeContainer)) {
                continue;
            }
            ItemStack existing = slot.getItem();
            if (!existing.isEmpty()) {
                // 合并同类堆叠
                if (ItemStack.isSameItemSameComponents(existing, remaining) && existing.getCount() < slot.maxStackSize(existing)) {
                    int moved = Math.min(remaining.getCount(), slot.maxStackSize(existing) - existing.getCount());
                    existing.grow(moved);
                    slot.setItem(existing);
                    remaining.shrink(moved);
                }
                continue;
            }
            if (!slot.mayPlace(remaining)) {
                continue;
            }
            int moved = Math.min(remaining.getCount(), slot.maxStackSize(remaining));
            ItemStack placed = remaining.copyWithCount(moved);
            slot.setItem(placed);
            remaining.shrink(moved);
        }
        return remaining;
    }

    /** 桥接建立失败（如容量不足）时抛出。 */
    public static final class MenuBridgeException extends RuntimeException {
        public MenuBridgeException(String message) {
            super(message);
        }
    }
}
