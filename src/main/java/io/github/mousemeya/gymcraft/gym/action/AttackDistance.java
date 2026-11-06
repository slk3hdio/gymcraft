package io.github.mousemeya.gymcraft.gym.action;

import javax.annotation.Nullable;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.component.AttackRange;

import io.github.mousemeya.gymcraft.GymCraft;

/**
 * 攻击距离工具 —— 通过原版 {@link Attributes#ENTITY_INTERACTION_RANGE} 属性承载攻击距离。
 * <p>
 * 原版没有独立的“攻击距离”属性；{@link AttackRange#defaultFor(LivingEntity)} 会读取
 * {@code ENTITY_INTERACTION_RANGE} 生成攻击范围。本工具在 {@code applyOptions} 时修改该属性
 * 的基础值，并附加一个零值标记 modifier，供 {@code MobAttackDistanceMixin} 判断是否启用覆盖。
 * 未调用 {@link #apply(Mob, double)} 时完全保留原版行为。
 * </p>
 */
public final class AttackDistance {

    /** 原版 {@code ENTITY_INTERACTION_RANGE} 属性的最大值。 */
    public static final double MAX_DISTANCE = 64.0;

    /** 覆盖标记 modifier 的稳定 ID。 */
    private static final Identifier MODIFIER_ID = Identifier.fromNamespaceAndPath(GymCraft.MODID, "attack_distance");

    /** 零值标记 modifier；只用于识别属性是否被本工具覆盖，不改变属性数值。 */
    private static final AttributeModifier MARKER = new AttributeModifier(
        MODIFIER_ID,
        0.0,
        AttributeModifier.Operation.ADD_VALUE
    );

    /** 禁止实例化工具类。 */
    private AttackDistance() {
    }

    /**
     * 校验攻击距离。
     *
     * @param distance 待校验距离
     * @throws IllegalArgumentException 距离非有限、非正或超过原版属性上限
     */
    public static void requireValid(double distance) {
        if (!Double.isFinite(distance) || distance <= 0 || distance > MAX_DISTANCE) {
            throw new IllegalArgumentException(
                "attack_distance must be a positive finite number no greater than " + MAX_DISTANCE + ", got: " + distance
            );
        }
    }

    /**
     * 将攻击距离写入 Mob 的原版实体交互距离属性，并打上覆盖标记。
     *
     * @param mob 目标 Mob
     * @param distance 有限正数，且不超过 {@link #MAX_DISTANCE}
     * @throws IllegalArgumentException 距离非法
     * @throws IllegalStateException Mob 缺少 {@code ENTITY_INTERACTION_RANGE} 属性
     */
    public static void apply(Mob mob, double distance) {
        requireValid(distance);
        AttributeInstance attribute = mob.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
        if (attribute == null) {
            throw new IllegalStateException("Mob does not have the entity interaction range attribute");
        }
        // 已由本工具覆盖且数值一致时直接复用，避免每 tick 重复写属性触发同步。
        if (attribute.hasModifier(MODIFIER_ID) && Double.compare(attribute.getBaseValue(), distance) == 0) {
            return;
        }
        attribute.setBaseValue(distance);
        if (!attribute.hasModifier(MODIFIER_ID)) {
            attribute.addOrReplacePermanentModifier(MARKER);
        }
    }

    /**
     * 判断 Mob 是否已启用攻击距离覆盖。
     *
     * @param mob 目标 Mob
     * @return 已附加覆盖标记时为 true
     */
    public static boolean hasOverride(Mob mob) {
        AttributeInstance attribute = mob.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
        return attribute != null && attribute.hasModifier(MODIFIER_ID);
    }

    /**
     * 读取当前覆盖距离。
     *
     * @param mob 目标 Mob
     * @return 覆盖后的属性值；未启用覆盖时为 null
     */
    @Nullable
    public static Double overrideValue(Mob mob) {
        return hasOverride(mob) ? mob.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE) : null;
    }

    /**
     * 使用原版 {@link AttackRange#defaultFor(LivingEntity)} 判断目标是否在攻击距离内。
     *
     * @param mob 攻击者
     * @param target 候选目标
     * @return 目标 hitbox 与攻击者视线的距离不超过属性值时为 true
     */
    public static boolean isWithin(Mob mob, LivingEntity target) {
        return AttackRange.defaultFor(mob).isInRange(mob, target.getHitbox(), 0.0);
    }
}
