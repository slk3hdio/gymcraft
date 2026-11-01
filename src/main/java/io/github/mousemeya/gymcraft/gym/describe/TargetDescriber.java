package io.github.mousemeya.gymcraft.gym.describe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** 注视目标描述组装器 —— 在服务端生成稳定的注册表 ID 与原版 tooltip 文本。 */
public final class TargetDescriber {

    private TargetDescriber() {
    }

    /** 描述物品堆：首行为数量与物品 ID，后续行为去掉物品名后的原版 tooltip。 */
    public static List<String> describeItemStack(Level level, ItemStack stack) {
        if (stack.isEmpty()) {
            return List.of("empty");
        }
        List<String> lines = new ArrayList<>();
        lines.add(stack.getCount() + "x " + BuiltInRegistries.ITEM.getKey(stack.getItem()));
        stack.getTooltipLines(Item.TooltipContext.of(level), null, TooltipFlag.NORMAL).stream()
            .skip(1)
            .map(Component::getString)
            .forEach(lines::add);
        return List.copyOf(lines);
    }

    /** 描述方块：首行为方块 ID，可复制为物品时追加其 tooltip。 */
    public static List<String> describeBlock(Level level, BlockPos pos, BlockState state) {
        List<String> lines = new ArrayList<>();
        lines.add(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        ItemStack picked = state.getCloneItemStack(level, pos, true);
        if (!picked.isEmpty()) {
            describeItemStack(level, picked).stream().skip(1).forEach(lines::add);
        }
        return List.copyOf(lines);
    }

    /** 描述实体；掉落物直接复用物品描述，生物追加生命、护甲、手持物与状态效果。 */
    public static List<String> describeEntity(Entity entity) {
        if (entity instanceof ItemEntity item) {
            return describeItemStack(entity.level(), item.getItem());
        }

        List<String> lines = new ArrayList<>();
        lines.add(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + " " + entity.getDisplayName().getString());
        if (entity instanceof LivingEntity living) {
            lines.add("health=" + living.getHealth() + "/" + living.getMaxHealth());
            lines.add("armor=" + living.getArmorValue());
            lines.add("absorption=" + living.getAbsorptionAmount());
            ItemStack mainHand = living.getMainHandItem();
            if (!mainHand.isEmpty()) {
                lines.add("main_hand=" + BuiltInRegistries.ITEM.getKey(mainHand.getItem()));
            }
            for (var effect : living.getActiveEffects()) {
                String effectId = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()).toString();
                String remaining = effect.isInfiniteDuration()
                    ? "infinite"
                    : String.format(Locale.ROOT, "%.1fs", effect.getDuration() / 20.0);
                lines.add("effect=" + effectId + " level=" + (effect.getAmplifier() + 1) + " remaining=" + remaining);
            }
        }
        return List.copyOf(lines);
    }

    /** 将描述限制为指定行数；截断时保留首行并以省略号收尾。 */
    public static List<String> truncate(List<String> lines, int maxLines) {
        if (maxLines < 1) {
            throw new IllegalArgumentException("maxLines must be positive, got: " + maxLines);
        }
        if (lines.size() <= maxLines) {
            return List.copyOf(lines);
        }
        if (maxLines == 1) {
            return List.of(lines.getFirst() + " ...");
        }
        List<String> truncated = new ArrayList<>(lines.subList(0, maxLines - 1));
        truncated.add("...");
        return List.copyOf(truncated);
    }

    /** 将多行描述合并为动作反馈使用的单行文本。 */
    public static String join(List<String> lines) {
        return String.join("; ", lines);
    }
}
