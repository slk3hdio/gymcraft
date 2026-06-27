package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.Map;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoInventory;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoItemStackView;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.SequenceSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;

/**
 * 装备与手持物品观测组件 —— 读取 Mob 所有盔甲槽位和手持物品。
 * <p>
 * 仅遍历非空物品槽位，最多输出 8 个槽位。
 * 每个物品视图记录：物品 ID、数量、槽位索引、是否为空。
 * </p>
 */
public class InventoryObservationCreator implements ObservationComponentCreator<ProtoInventory> {
    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "slots", new SequenceSpace<>(new TextSpace(), 8)
    )); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间
    private final McSpace<Map<String, Object>> space;

    public InventoryObservationCreator(Optional<McSpace<Map<String, Object>>> space) {
        this.space = space.orElse(DEFAULT_SPACE);
    }

    @Override
    public Class<ProtoInventory> protoType() {
        return ProtoInventory.class;
    }

    @Override
    public McSpace<Map<String, Object>> space() {
        return space;
    }

    @Override
    public ProtoInventory sample() {
        return ProtoInventory.getDefaultInstance();
    }

    @Override
    public boolean contains(ProtoInventory component) {
        return component != null && component.getSlotsCount() <= 8;
    }

    @Override
    public ProtoInventory create(Mob mob) {
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

    private static ProtoItemStackView buildItemView(ItemStack stack, int slot) {
        return ProtoItemStackView.newBuilder()
            .setItemId(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
            .setCount(stack.getCount())
            .setSlot(slot)
            .setEmpty(stack.isEmpty())
            .build();
    }
}
