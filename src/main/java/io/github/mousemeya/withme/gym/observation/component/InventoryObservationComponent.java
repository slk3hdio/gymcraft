package io.github.mousemeya.withme.gym.observation.component;

import io.github.mousemeya.withme.gym.observation.ObservationComponentCreator;
import io.github.mousemeya.withme.gym.observation.proto.ItemStackView;
import io.github.mousemeya.withme.gym.observation.proto.ProtoInventory;
import io.github.mousemeya.withme.gym.space.DictSpace;
import io.github.mousemeya.withme.gym.space.McSpace;
import io.github.mousemeya.withme.gym.space.SequenceSpace;
import io.github.mousemeya.withme.gym.space.TextSpace;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

/**
 * 装备与手持物品观测组件 —— 读取 Mob 所有盔甲槽位和手持物品。
 * <p>
 * 仅遍历非空物品槽位，最多输出 8 个槽位。
 * 每个 ItemStackView 记录：物品 ID、数量、槽位索引、是否为空。
 * </p>
 */
public class InventoryObservationComponent implements ObservationComponentCreator<ProtoInventory> {
    private static final McSpace<?> SPACE = new DictSpace(Map.of(
        "slots", new SequenceSpace<>(new TextSpace(), 8)
    ));

    @Override
    public Class<ProtoInventory> protoType() {
        return ProtoInventory.class;
    }

    @Override
    public McSpace<?> space() {
        return SPACE;
    }

    @Override
    public ProtoInventory sample() {
        return ProtoInventory.getDefaultInstance();
    }

    @Override
    public boolean contains(ProtoInventory component) {
        return component != null && component.getSlotsCount() <= 8;
    }

    /** 遍历 EquipmentSlot 构建非空物品槽位的观测。 */
    @Override
    public ProtoInventory build(Mob mob, AgentControlState state, ObservationContext context) {
        var builder = ProtoInventory.newBuilder();
        int slot = 0;
        for (var equipmentSlot : EquipmentSlot.VALUES) {
            if (!equipmentSlot.isArmor() && equipmentSlot != EquipmentSlot.MAINHAND && equipmentSlot != EquipmentSlot.OFFHAND) continue;
            var stack = mob.getItemBySlot(equipmentSlot);
            if (!stack.isEmpty()) builder.addSlots(buildItemView(stack, slot));
            slot++;
        }
        return builder.build();
    }

    private static ItemStackView buildItemView(ItemStack stack, int slot) {
        return ItemStackView.newBuilder()
            .setItemId(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
            .setCount(stack.getCount())
            .setSlot(slot)
            .setEmpty(stack.isEmpty())
            .build();
    }
}
