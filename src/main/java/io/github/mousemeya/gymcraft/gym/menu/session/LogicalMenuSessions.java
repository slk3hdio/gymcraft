package io.github.mousemeya.gymcraft.gym.menu.session;

import io.github.mousemeya.gymcraft.gym.fakeplayer.AgentInventoryBridge;
import io.github.mousemeya.gymcraft.gym.menu.adapter.MenuAdapter;
import io.github.mousemeya.gymcraft.gym.menu.adapter.MenuAdapters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.trading.Merchant;
import net.neoforged.neoforge.common.util.FakePlayer;

import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.inventory.AgentSlot;
import io.github.mousemeya.gymcraft.registry.ModAttachments;

/**
 * 逻辑菜单会话入口 —— 菜单打开（resolver + 候选验证 + 会话替换）与关闭的统一入口。
 * <p>
 * 打开流程（计划 9.3 节）：候选菜单用独立候选 FakePlayer 构造，完成容量、槽位规范化、
 * bridge 与初始有效性验证后才替换旧会话；验证失败或抛异常时对候选菜单恰好调用一次
 * {@code removed(candidateFakePlayer)}（对称清理构造器已产生的 {@code startOpen}、
 * merchant trading player 等副作用），旧会话保持打开。候选验证成功后自动关闭旧会话，
 * 重新同步 Agent 物品栏到候选 bridge 并二次检查有效性，再走打开适配；
 * 二次检查或打开适配失败则清理候选并保持无菜单状态。
 * </p>
 * <p>
 * 所有方法仅允许在服务端 tick 线程调用。
 * </p>
 */
public final class LogicalMenuSessions {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** GymCraft 会话 ID 分配器（单调递增，关闭重开必不同，旧 ID 不能命中新会话）。 */
    private static final AtomicLong NEXT_SESSION_ID = new AtomicLong();

    /** self 背包菜单的固定标题（协议观测与 open_menu 结果共用）。 */
    private static final String SELF_MENU_TITLE = "Agent Inventory";

    private LogicalMenuSessions() {
    }

    /** 打开结果：成功携带会话；失败携带可读的失败原因（供动作 description 使用）。 */
    public record OpenResult(boolean success, @Nullable LogicalMenuSession session, @Nullable String failureReason) {
        static OpenResult ok(LogicalMenuSession session) {
            return new OpenResult(true, session, null);
        }

        static OpenResult failed(String reason) {
            return new OpenResult(false, null, reason);
        }
    }

    /** @return Mob 当前打开的菜单会话；未打开返回 null（附件读取经 hasData 守卫） */
    @Nullable
    public static LogicalMenuSession current(Mob mob) {
        var type = ModAttachments.MENU_SESSION.get();
        return mob.hasData(type) ? mob.getData(type) : null;
    }

    /**
     * 关闭 Mob 的当前菜单会话（若有）。
     *
     * @return 是否实际关闭了会话
     */
    public static boolean closeCurrent(Mob mob, String reason) {
        LogicalMenuSession session = current(mob);
        return session != null && session.closeOnce(reason);
    }

    /**
     * 为 Mob 打开逻辑菜单（阶段 4 的 OpenMenuController 直接调用本入口）。
     *
     * @param mob    Agent 实体
     * @param target 菜单目标（方块 / 实体 / 自身背包）
     * @return 打开结果；失败时旧会话（若有）保持打开
     */
    public static OpenResult open(Mob mob, OpenMenuTarget target) {
        if (!(mob.level() instanceof ServerLevel level)) {
            return OpenResult.failed("mob is not in a server level");
        }
        MenuAgentPlayer candidate = MenuAgentPlayer.create(mob, level);
        AbstractContainerMenu menu = null;
        AgentInventoryBridge bridge;
        ResolvedMenu resolved = null;
        try {
            // 容量校验：扣除预留后超出可安全映射容量时拒绝打开
            bridge = AgentInventoryBridge.establish(mob, candidate.player());
            resolved = resolve(mob, level, target, candidate, bridge);
            if (resolved == null) {
                return OpenResult.failed("target does not provide a menu");
            }
            resolved.beforeCreateMenu().accept(candidate.player());
            menu = resolved.menuFactory().create(candidate);
            if (menu == null) {
                throw new IllegalStateException("menu provider returned null");
            }
            // 初始有效性验证（距离、目标存活、trading player 等）
            candidate.syncToMob(mob);
            if (!menu.stillValid(candidate.player())) {
                throw new IllegalStateException("menu is not valid for agent");
            }
            long sessionId = NEXT_SESSION_ID.incrementAndGet();
            List<SessionSlot> slots = buildSessionSlots(sessionId, menu, bridge, resolved.normalizer());

            // 候选验证成功：自动关闭旧会话（不要求显式 close），清算后重新同步候选 bridge
            closeCurrent(mob, "replaced by new menu session");
            bridge.syncToFakePlayer();
            candidate.syncToMob(mob);
            if (!menu.stillValid(candidate.player())) {
                menu.removed(candidate.player());
                return OpenResult.failed("menu invalidated while replacing old session");
            }
            LogicalMenuHandle handle = LogicalMenuHandle.open(candidate, menu, bridge);
            var session = new LogicalMenuSession(
                sessionId, mob, level, candidate, handle, bridge, slots,
                resolved.targetValidity(), resolved.title(), resolved.selfMenu()
            );
            mob.setData(ModAttachments.MENU_SESSION.get(), session);
            MenuSessionHooks.register(mob, session);
            session.captureCurrentSnapshots();
            LOGGER.info("Menu session {} opened for mob {} target={}", sessionId, mob.getUUID(), target);
            return OpenResult.ok(session);
        } catch (Exception e) {
            // 候选失败：菜单已构造则恰好 removed 一次（对称清理 startOpen/trading player），
            // 未构造则执行目标级失败清理（如还原 merchant trading player）；旧会话保持打开
            if (menu != null) {
                menu.removed(candidate.player());
            } else if (resolved != null) {
                resolved.afterFailedCreate().accept(candidate.player());
            }
            LOGGER.debug("Failed to open menu for mob {} target={}: {}", mob.getUUID(), target, e.getMessage());
            return OpenResult.failed(String.valueOf(e.getMessage()));
        }
    }

    /**
     * 菜单槽规范化结果（计划 7.3/7.4 节）。
     */
    private sealed interface NormalizedMenuSlot {
        /** 复用 Agent 背包 slot_id（FakeBridge 桥接格或 MobEquipment/MobNativeContainer 包装槽）。 */
        record AgentClaim(int agentSlotId) implements NormalizedMenuSlot {
        }

        /** 菜单自有槽：从 N+1 起分配会话 slot_id（MenuOwned 身份）。 */
        enum MenuOwnedSlot implements NormalizedMenuSlot {
            INSTANCE
        }

        /** 不暴露的槽位（未映射的桥接普通格：必须保持为空，不分配 slot_id）。 */
        enum Hidden implements NormalizedMenuSlot {
            INSTANCE
        }
    }

    /** 菜单槽规范化器：把原版 menu_slot 归一化为 Agent 背包槽认领 / 菜单自有槽 / 不暴露。 */
    @FunctionalInterface
    private interface SlotNormalizer {
        NormalizedMenuSlot normalize(Slot menuSlot);
    }

    /** 菜单构造工厂：在候选 FakePlayer 上按目标语义构造菜单（containerId 由候选玩家分配）。 */
    @FunctionalInterface
    private interface MenuFactory {
        @Nullable
        AbstractContainerMenu create(MenuAgentPlayer candidate);
    }

    /** 保留菜单自有槽的原版索引，供适配器在分配 slot_id 时添加语义。 */
    private record MenuOwnedCandidate(int menuSlotIndex, Slot slot) {
    }

    /** 目标解析结果：菜单构造、槽位规范化、目标有效性复查与构造前/失败后副作用处理。 */
    private record ResolvedMenu(
        String title,
        boolean selfMenu,
        SlotNormalizer normalizer,
        BooleanSupplier targetValidity,
        Consumer<FakePlayer> beforeCreateMenu,
        Consumer<FakePlayer> afterFailedCreate,
        MenuFactory menuFactory
    ) {
    }

    /** 默认规范化：FakePlayer 物品栏槽按 bridge 映射认领 Agent slot_id，其余为菜单自有槽。 */
    private static SlotNormalizer defaultNormalizer(FakePlayer player, AgentInventoryBridge bridge) {
        return menuSlot -> {
            if (menuSlot.container == player.getInventory()) {
                Integer agentSlotId = bridge.agentSlotIdAt(menuSlot.getContainerSlot());
                return agentSlotId != null
                    ? new NormalizedMenuSlot.AgentClaim(agentSlotId)
                    : NormalizedMenuSlot.Hidden.INSTANCE;
            }
            return NormalizedMenuSlot.MenuOwnedSlot.INSTANCE;
        };
    }

    /**
     * 马菜单规范化（计划 7.3 节）：1.21.1 的鞍槽位于
     * {@code horse.getInventory()}，马铠槽位于独立的 {@code horse.getBodyArmorAccess()}。
     * 马铠槽复用 BODY 的 slot_id 6；鞍槽和货物槽按容器局部索引复用
     * slot_id 7+localIndex，最后为玩家物品栏。
     */
    private static SlotNormalizer horseNormalizer(FakePlayer player, AgentInventoryBridge bridge, AbstractHorse horse) {
        SlotNormalizer fallback = defaultNormalizer(player, bridge);
        return menuSlot -> {
            if (menuSlot.container == player.getInventory()) {
                return fallback.normalize(menuSlot);
            }
            if (menuSlot.container == horse.getBodyArmorAccess()) {
                return new NormalizedMenuSlot.AgentClaim(
                    AgentInventoryLayout.equipmentSlotId(EquipmentSlot.BODY));
            }
            if (menuSlot.container == horse.getInventory()) {
                int agentSlotId = AgentInventoryLayout.EQUIPMENT_SLOT_COUNT + menuSlot.getContainerSlot();
                return agentSlotId < bridge.layout().size()
                    ? new NormalizedMenuSlot.AgentClaim(agentSlotId)
                    : NormalizedMenuSlot.MenuOwnedSlot.INSTANCE;
            }
            return NormalizedMenuSlot.MenuOwnedSlot.INSTANCE;
        };
    }

    /** 解析菜单目标（计划 9 节）；不支持的目标返回 null。 */
    @Nullable
    private static ResolvedMenu resolve(
        Mob mob,
        ServerLevel level,
        OpenMenuTarget target,
        MenuAgentPlayer candidate,
        AgentInventoryBridge bridge
    ) {
        SlotNormalizer defaultNormalizer = defaultNormalizer(candidate.player(), bridge);
        return switch (target) {
            case OpenMenuTarget.Self ignored -> new ResolvedMenu(
                SELF_MENU_TITLE,
                true,
                defaultNormalizer,
                () -> true,
                player -> {
                },
                player -> {
                },
                // 直接复用原版 InventoryMenu（active=true 启用 2x2 合成区）；
                // 其 stillValid 恒为 true，与 self 会话语义一致
                c -> new InventoryMenu(c.player().getInventory(), true, c.player())
            );
            case OpenMenuTarget.Block block -> {
                var state = level.getBlockState(block.pos());
                // 必须经 BlockState#getMenuProvider：保留双箱合并、箱子阻挡检查与
                // 无容器 BlockEntity 的 SimpleMenuProvider，不能只强转 BlockEntity
                MenuProvider provider = state.getMenuProvider(level, block.pos());
                if (provider == null) {
                    yield null;
                }
                yield new ResolvedMenu(
                    provider.getDisplayName().getString(),
                    false,
                    defaultNormalizer,
                    () -> level.getBlockState(block.pos()).getMenuProvider(level, block.pos()) != null,
                    player -> {
                    },
                    player -> {
                    },
                    c -> provider.createMenu(c.nextContainerId(), c.player().getInventory(), c.player())
                );
            }
            case OpenMenuTarget.Entity entityTarget -> {
                Entity entity = level.getEntity(entityTarget.entityId());
                if (entity == null || !entity.isAlive()) {
                    yield null;
                }
                BooleanSupplier validity = () -> entity.isAlive() && !entity.isRemoved();
                // 解析顺序（计划 9.2 节）：MenuProvider 实体 → Merchant 专用 → AbstractHorse 专用
                if (entity instanceof MenuProvider provider) {
                    yield new ResolvedMenu(
                        provider.getDisplayName().getString(),
                        false,
                        defaultNormalizer,
                        validity,
                        player -> {
                        },
                        player -> {
                        },
                        c -> provider.createMenu(c.nextContainerId(), c.player().getInventory(), c.player())
                    );
                }
                if (entity instanceof Merchant merchant) {
                    // MerchantMenu 构造前必须先 setTradingPlayer：
                    // AbstractVillager#stillValid 要求 getTradingPlayer() == player
                    yield new ResolvedMenu(
                        entity.getDisplayName().getString(),
                        false,
                        defaultNormalizer,
                        validity,
                        merchant::setTradingPlayer,
                        player -> merchant.setTradingPlayer(null),
                        c -> new MerchantMenu(c.nextContainerId(), c.player().getInventory(), merchant)
                    );
                }
                if (entity instanceof AbstractHorse horse) {
                    yield new ResolvedMenu(
                        entity.getDisplayName().getString(),
                        false,
                        horseNormalizer(candidate.player(), bridge, horse),
                        validity,
                        player -> {
                        },
                        player -> {
                        },
                        c -> new HorseInventoryMenu(
                            c.nextContainerId(), c.player().getInventory(),
                            horse.getInventory(), horse, horse.getInventoryColumns()
                        )
                    );
                }
                yield null;
            }
        };
    }

    /**
     * 构建会话 slot_id 映射（计划 7.4 节）。
     * <p>
     * 先扫描原版菜单槽：可规范化为 Agent 既有槽位的认领对应 slot_id
     * （同一身份只保留首个原版槽）；无法规范化的留作菜单自有槽。
     * 随后为未被原版菜单包含的 Agent 槽补齐 synthetic bridge Slot
     * （同一规范化逻辑身份只生成一个 SessionSlot，原版菜单槽优先），
     * 菜单自有槽从 N+1 起顺序分配会话 slot_id。
     * </p>
     */
    private static List<SessionSlot> buildSessionSlots(
        long sessionId,
        AbstractContainerMenu menu,
        AgentInventoryBridge bridge,
        SlotNormalizer normalizer
    ) {
        AgentInventoryLayout layout = bridge.layout();
        Map<Integer, Slot> claimedMenuSlots = new HashMap<>();
        List<MenuOwnedCandidate> menuOwnedSlots = new ArrayList<>();
        for (int menuSlotIndex = 0; menuSlotIndex < menu.slots.size(); menuSlotIndex++) {
            Slot menuSlot = menu.slots.get(menuSlotIndex);
            switch (normalizer.normalize(menuSlot)) {
                case NormalizedMenuSlot.AgentClaim claim -> claimedMenuSlots.putIfAbsent(claim.agentSlotId(), menuSlot);
                case NormalizedMenuSlot.MenuOwnedSlot ignored ->
                    menuOwnedSlots.add(new MenuOwnedCandidate(menuSlotIndex, menuSlot));
                case NormalizedMenuSlot.Hidden ignored -> {
                }
            }
        }
        List<SessionSlot> slots = new ArrayList<>();
        // Agent自身背包槽
        for (AgentSlot agentSlot : layout.slots()) {
            Slot backing = claimedMenuSlots.remove(agentSlot.slotId());
            slots.add(backing != null
                ? SessionSlot.menuBacked(agentSlot.slotId(), agentSlot.identity(), agentSlot.category(), backing, agentSlot)
                : SessionSlot.synthetic(agentSlot.slotId(), agentSlot));
        }
        // Menu槽位
        int nextId = layout.size();
        MenuAdapter<AbstractContainerMenu> adapter = MenuAdapters.find(menu);
        for (MenuOwnedCandidate candidate : menuOwnedSlots) {
            String category = adapter == null
                ? "menu"
                : adapter.menuSlotCategory(menu, candidate.menuSlotIndex(), candidate.slot());
            if (category == null || category.isBlank()) {
                category = "menu";
            }
            slots.add(SessionSlot.menuOwned(nextId++, sessionId, candidate.slot(), category));
        }
        return List.copyOf(slots);
    }
}
