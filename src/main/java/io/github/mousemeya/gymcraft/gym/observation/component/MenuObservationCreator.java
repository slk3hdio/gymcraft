package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.Map;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import io.github.mousemeya.gymcraft.gym.menu.session.LogicalMenuSession;
import io.github.mousemeya.gymcraft.gym.menu.session.LogicalMenuSessions;
import io.github.mousemeya.gymcraft.gym.menu.adapter.MenuAdapter;
import io.github.mousemeya.gymcraft.gym.menu.adapter.MenuAdapters;
import io.github.mousemeya.gymcraft.gym.menu.adapter.MenuButtonView;
import io.github.mousemeya.gymcraft.gym.menu.adapter.MenuPropertyView;
import io.github.mousemeya.gymcraft.gym.menu.MenuTypeUtil;
import io.github.mousemeya.gymcraft.gym.menu.session.SessionSlot;
import io.github.mousemeya.gymcraft.gym.observation.AbstractObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentFactory;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoItemStackView;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMenuButton;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMenuObservation;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMenuProperty;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoSlotView;
import io.github.mousemeya.gymcraft.gym.space.BooleanSpace;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.SequenceSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;

/**
 * 菜单观测组件 —— 构建 {@link ProtoMenuObservation}，反映 Mob 当前逻辑菜单会话状态。
 * <p>
 * 只读取 Mob 上的菜单会话附件（职责边界：不依赖任何 action 的执行过程）。
 * 无会话时返回 {@code open=false}，其余集合为空，不省略整个 component，
 * 保证 observation schema 稳定。
 * </p>
 * <p>
 * 有会话时先执行会话 refresh（含失效关闭清理：菜单因非 Agent 操作失效时
 * 直接返回 {@code open=false}，不再从旧菜单构造槽位观测），随后输出
 * session_id/menu_type/title 与槽位列表——菜单打开时槽位列表连带 Agent
 * 物品栏槽位（复用背包 slot_id 与 category；synthetic slot 的 x/y 为 0）。
 * properties/buttons 由菜单适配器提供（{@link MenuAdapters} 查找），无适配器时为空。
 * 观测成功构造后提交会话的 {@code lastObservedSnapshot} 基线。
 * </p>
 */
public class MenuObservationCreator extends AbstractObservationComponentCreator<ProtoMenuObservation> {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.ofEntries(
        Map.entry("open", new BooleanSpace()),
        Map.entry("session_id", new BoxSpace(0, Long.MAX_VALUE, 1)),
        Map.entry("menu_type", new TextSpace()),
        Map.entry("title", new TextSpace()),
        Map.entry("slots", new SequenceSpace<>(new TextSpace(), 256)),
        Map.entry("properties", new SequenceSpace<>(new TextSpace(), 64)),
        Map.entry("buttons", new SequenceSpace<>(new TextSpace(), 64))
    )); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间

    public MenuObservationCreator() {
    }

    @Override
    public Class<ProtoMenuObservation> protoType() {
        return ProtoMenuObservation.class;
    }

    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return DEFAULT_SPACE;
    }

    @Override
    public boolean contains(ProtoMenuObservation component) {
        return component != null;
    }

    @Override
    public ProtoMenuObservation create(Mob mob) {
        var builder = ProtoMenuObservation.newBuilder();
        LogicalMenuSession session = LogicalMenuSessions.current(mob);
        if (session == null || !session.refresh()) {
            // 无会话，或 refresh 发现菜单已失效并完成关闭清理
            return builder.setOpen(false).build();
        }
        builder.setOpen(true).setSessionId(session.sessionId());
        if (!session.isSelfMenu()) {
            // self 背包菜单只填 open/session_id/slots，menu_type/title 保持默认空值
            builder.setMenuType(MenuTypeUtil.idOf(session.menu()));
            builder.setTitle(session.title());
        }
        for (SessionSlot slot : session.slots()) {
            builder.addSlots(buildSlotView(slot, mob.registryAccess()));
        }
        // properties/buttons 由菜单适配器提供（计划 11 节）；无适配器时保持空集合
        MenuAdapter<AbstractContainerMenu> adapter = MenuAdapters.find(session.menu());
        if (adapter != null) {
            for (MenuPropertyView property : adapter.properties(session.menu())) {
                builder.addProperties(ProtoMenuProperty.newBuilder()
                    .setDataSlot(property.dataSlot())
                    .setName(property.name())
                    .setValue(property.value()));
            }
            for (MenuButtonView button : adapter.buttons(session.menu(), mob)) {
                builder.addButtons(ProtoMenuButton.newBuilder()
                    .setButtonId(button.buttonId())
                    .setName(button.name())
                    .setEnabled(button.enabled()));
            }
        }
        // 观测成功构造、即将返回：提交本次 currentSnapshot 为新的观测基线
        session.commitObservedBaseline();
        return builder.build();
    }

    /** 构建槽位视图：空槽不设置 item；synthetic slot 与背包包装菜单槽的 x/y 为 0。 */
    private static ProtoSlotView buildSlotView(SessionSlot sessionSlot, HolderLookup.Provider registries) {
        var builder = ProtoSlotView.newBuilder()
            .setSlotId(sessionSlot.slotId())
            .setCategory(sessionSlot.category())
            .setX(sessionSlot.slot().x)
            .setY(sessionSlot.slot().y);
        ItemStack item = sessionSlot.item();
        if (!item.isEmpty()) {
            builder.setItem(buildItemView(item, registries));
        }
        return builder.build();
    }

    /**
     * 构建纯物品视图：nbt 为组件补丁的规范化 SNBT 表达（与快照比较的可观察标签状态同源），
     * 没有标签时为空字符串。
     * <p>
     * 同为物品内容观测的 {@code NearbyItemsObservationCreator} 直接复用本方法，
     * 保证两处物品视图字段语义一致。
     * </p>
     */
    public static ProtoItemStackView buildItemView(ItemStack stack, HolderLookup.Provider registries) {
        var builder = ProtoItemStackView.newBuilder()
            .setItemId(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
            .setCount(stack.getCount());
        DataComponentPatch patch = stack.getComponentsPatch();
        if (!patch.isEmpty()) {
            DataComponentPatch.CODEC
                .encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), patch)
                .resultOrPartial(error -> LOGGER.warn("Failed to encode item components to SNBT: {}", error))
                .ifPresent(tag -> builder.setNbt(tag.toString()));
        }
        return builder.build();
    }

    /**
     * 观测工厂 —— 注册表引用该内部轻量 {@link ObservationComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ObservationComponentFactory<ProtoMenuObservation, MenuObservationCreator> {
        @Override
        public MenuObservationCreator create(Mob mob) {
            return new MenuObservationCreator();
        }

        /**
         * 返回该工厂创建的具体观测生成器类型。
         *
         * @return MenuObservationCreator 的运行时类型
         */
        @Override
        public Class<MenuObservationCreator> componentType() {
            return MenuObservationCreator.class;
        }
    }
}
