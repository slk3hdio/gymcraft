package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoPlaceBlock;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;

/**
 * 放置方块动作组件 —— 让 Mob 把主手持有的方块物品放置到指定位置。
 * <p>
 * 放置语义:方块落点为 {@code (x, y, z)},{@code face} 为模拟点击的相邻方块面
 * （影响楼梯、熔炉等可朝向方块的朝向）。完整复用原版链路:
 * {@code ItemStack#useOn} → NeoForge {@code onPlaceItemIntoWorld} →
 * {@code BlockItem#place}（含放置朝向计算、支撑判定、声音、GameEvent 与物品消耗）,
 * 执行者是通过 {@link MobHandSimulator} 同步了 Mob 状态的 {@link FakePlayer}。
 * </p>
 * <p>
 * 要求生物具备手持能力（{@link Mob#canHoldItem}）且主手持有 {@link BlockItem}。
 * 该动作为瞬时动作,应用后立即返回终态。
 * </p>
 */
public class PlaceBlockController implements ActionComponentController<ProtoPlaceBlock> {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlaceBlockController.class);

    /** 原版生存模式默认方块交互距离 ({@code Attributes.BLOCK_INTERACTION_RANGE} 默认值)。 */
    private static final double REACH_DISTANCE = 4.5;

    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "x", new BoxSpace(-30_000_000, 30_000_000, 1),
        "y", new BoxSpace(-2048, 2048, 1),
        "z", new BoxSpace(-30_000_000, 30_000_000, 1),
        "face", new BoxSpace(0, 5, 1)
    ));
    private final Collection<Class<?>> SUPPORTED_ENTITIES = List.of(Mob.class);

    public PlaceBlockController() {
    }

    @Override
    public boolean supportEntity(Class<?> entityType) {
        for (var supported : SUPPORTED_ENTITIES) {
            if (supported.isAssignableFrom(entityType)) return true;
        }
        return false;
    }

    @Override
    public boolean supports(Mob mob) {
        // 必须具备手持功能:空手视为可持有,手中有物品时按原版规则确认该 Mob 能持有它
        ItemStack held = mob.getMainHandItem();
        return this.supportEntity(mob.getClass()) && (held.isEmpty() || mob.canHoldItem(held));
    }

    @Override
    public Collection<Class<?>> getSupportedEntities() {
        return SUPPORTED_ENTITIES;
    }

    @Override
    public Class<ProtoPlaceBlock> protoType() {
        return ProtoPlaceBlock.class;
    }

    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return DEFAULT_SPACE;
    }

    @Override
    public ProtoPlaceBlock sample() {
        return ProtoPlaceBlock.getDefaultInstance();
    }

    @Override
    public boolean contains(ProtoPlaceBlock component, McSpace<Map<String, Object>> space) {
        return component != null && space.contains(Map.of(
            "x", component.getX(),
            "y", component.getY(),
            "z", component.getZ(),
            "face", component.getFace()
        ));
    }

    @Override
    public ActionApplyResult apply(Mob mob, ProtoPlaceBlock component) {
        if (!(mob.level() instanceof ServerLevel level)) {
            return ActionApplyResult.none(ActionState.failed("not in a server level"));
        }
        ItemStack held = mob.getMainHandItem();
        BlockPos pos = new BlockPos(component.getX(), component.getY(), component.getZ());
        Direction face = Direction.from3DDataValue(component.getFace());

        ActionState validationError = validateTarget(mob, level, pos, held);
        if (validationError != null) {
            return ActionApplyResult.none(validationError);
        }

        // 点击位置:优先取 face 反方向的相邻实心方块（等价于玩家对着该面右键）;
        // 相邻格也可替换时退化为直接点击目标格（如雪层叠加、替换草丛）
        BlockPos clickedPos = pos.relative(face.getOpposite());
        if (level.getBlockState(clickedPos).canBeReplaced()) {
            clickedPos = pos;
        }
        BlockHitResult hitResult = new BlockHitResult(Vec3.atCenterOf(clickedPos), face, clickedPos, false);

        FakePlayer fakePlayer = MobHandSimulator.syncFromMob(mob, level);
        InteractionResult result = held.useOn(new UseOnContext(fakePlayer, InteractionHand.MAIN_HAND, hitResult));
        MobHandSimulator.syncBackToMob(mob, fakePlayer);

        boolean placed = result.consumesAction();
        if (placed) {
            mob.swing(InteractionHand.MAIN_HAND);
        }
        LOGGER.info(
            "GymCraft PlaceBlock apply entity={} pos={} face={} item={} result={}",
            mob.getUUID(), pos.toShortString(), face, held.getItem(), result
        );
        ActionState state = placed
            ? ActionState.completed("block placed", Map.of(
                "pos", pos.toShortString(),
                "face", face.name(),
                "item", held.getItem().toString(),
                "interaction_result", result.toString()))
            : ActionState.failed("placement rejected", Map.of(
                "pos", pos.toShortString(),
                "face", face.name(),
                "item", held.getItem().toString(),
                "interaction_result", result.toString()));
        return ActionApplyResult.applied(ActionControlPolicy.none(), state);
    }

    @Override
    public ActionState getState(Mob mob, ProtoPlaceBlock component) {
        return ActionState.completed("placement applied");
    }

    /** 目标合法性校验,返回 null 表示可放置。 */
    private static ActionState validateTarget(Mob mob, ServerLevel level, BlockPos pos, ItemStack held) {
        if (!(held.getItem() instanceof BlockItem)) {
            return ActionState.failed("held item is not a block item", Map.of(
                "item", held.isEmpty() ? "empty" : held.getItem().toString()
            ));
        }
        if (!level.hasChunkAt(pos)) {
            return ActionState.failed("target chunk is not loaded", Map.of("pos", pos.toShortString()));
        }
        if (!level.getBlockState(pos).canBeReplaced()) {
            return ActionState.failed("target position is occupied", Map.of(
                "pos", pos.toShortString(),
                "block", level.getBlockState(pos).getBlock().toString()
            ));
        }
        double distance = mob.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
        if (distance > REACH_DISTANCE) {
            return ActionState.failed("target out of reach", Map.of(
                "pos", pos.toShortString(),
                "distance", distance,
                "reach_distance", REACH_DISTANCE
            ));
        }
        return null;
    }
}
