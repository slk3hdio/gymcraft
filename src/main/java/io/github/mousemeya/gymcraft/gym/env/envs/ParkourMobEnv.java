package io.github.mousemeya.gymcraft.gym.env.envs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.env.McEnvFactory;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMcObservation;
import io.github.mousemeya.gymcraft.gym.state.EnvStateScope;
import io.github.mousemeya.gymcraft.gym.state.RegionBlockSnapshot;
import io.github.mousemeya.gymcraft.registry.ActionComponents;
import io.github.mousemeya.gymcraft.registry.ObservationCreators;

/**
 * 跳跃搭方块环境：Mob 在受限水平区域中跳跃并将手持方块放到脚下，逐层到达目标高度。
 * <p>
 * 首个 reset 按布局选项（{@code block}/{@code block_count}/{@code target_height}）生成起点
 * 平台并发放资源，随后冻结状态基线（含平台层与训练柱区域的原世界捕获，close 时恢复）；
 * 此后 reset 由 {@code gym/state} 状态基线恢复训练柱——布局选项只在首个 reset 生效，
 * 更换布局需重建环境。区域内其他实体不进入基线（blocksOnly）。
 * </p>
 */
public class ParkourMobEnv extends AbstractMcEnv {
    private static final String BLOCK_OPTION = "block";
    private static final String BLOCK_COUNT_OPTION = "block_count";
    private static final String TARGET_HEIGHT_OPTION = "target_height";
    private static final String MAX_STEPS_OPTION = "max_steps";
    private static final int DEFAULT_BLOCK_COUNT = 32;
    private static final int DEFAULT_TARGET_HEIGHT = 8;
    private static final int DEFAULT_MAX_STEPS = 256;
    private static final int MOVE_RADIUS = 3;

    /** 首个 reset 才确定（依赖当次 Mob 位置与布局选项），此前为 null。 */
    private BlockPos origin;
    private double baseY;
    private int targetHeight = DEFAULT_TARGET_HEIGHT;
    private int maxSteps = DEFAULT_MAX_STEPS;
    private int steps;
    private int previousCount;
    private double previousY;
    private boolean success;
    private boolean outOfBounds;
    private boolean resourceExhausted;

    /** 创建跳搭环境并暴露跳跃、放置、移动和空操作组件。 */
    public ParkourMobEnv(Identifier envTypeId, Mob mob) {
        super(envTypeId, mob,
            List.of(ActionComponents.NOOP.get(), ActionComponents.JUMP.get(),
                ActionComponents.SET_BLOCK.get(), ActionComponents.STEP_MOVE.get()),
            List.of(ObservationCreators.SELF.get(), ObservationCreators.NEARBY_BLOCKS.get(),
                ObservationCreators.WORLD.get()));

        observationComponent(ObservationCreators.NEARBY_BLOCKS.get()).setMaxBlocks(64);
    }

    /**
     * 环境状态范围：平台层与训练柱（7×7，脚底下一格到目标高度上方两格）。
     * 首个 reset 前原点未定，返回空范围（不做世界快照）。
     */
    @Override
    protected EnvStateScope stateScope() {
        if (this.origin == null) {
            return EnvStateScope.empty();
        }
        return EnvStateScope.blocksOnly(new BoundingBox(
            this.origin.getX() - MOVE_RADIUS,
            this.origin.getY() - 1,
            this.origin.getZ() - MOVE_RADIUS,
            this.origin.getX() + MOVE_RADIUS,
            this.origin.getY() + this.targetHeight + 2,
            this.origin.getZ() + MOVE_RADIUS));
    }

    /** 首个 reset 搭建平台并发放资源（冻结基线），每回合重置计数器。 */
    @Override
    protected void resetMob(Mob mob, Integer seed, Map<String, Object> options) {
        super.resetMob(mob, seed, options);
        this.maxSteps = positiveInt(options, MAX_STEPS_OPTION, DEFAULT_MAX_STEPS);
        if (!this.isStateBaselineFrozen()) {
            // 布局选项仅首个 reset 生效并冻结进基线；后续 reset 忽略布局选项
            this.targetHeight = positiveInt(options, TARGET_HEIGHT_OPTION, DEFAULT_TARGET_HEIGHT);
            int count = positiveInt(options, BLOCK_COUNT_OPTION, DEFAULT_BLOCK_COUNT);
            String blockId = stringOption(options, BLOCK_OPTION, "minecraft:stone");
            Block block = BuiltInRegistries.BLOCK.get(Identifier.parse(blockId))
                .map(reference -> reference.value()).orElse(Blocks.AIR);
            if (block == Blocks.AIR || block.asItem() == null) {
                throw new IllegalArgumentException("Invalid parkour block: " + blockId);
            }
            this.origin = BlockPos.containing(mob.position());
            this.baseY = this.origin.getY();
            ServerLevel level = (ServerLevel) mob.level();
            this.captureOriginalWorld(level);
            clearTrainingColumn(level);
            for (int dx = -MOVE_RADIUS; dx <= MOVE_RADIUS; dx++) {
                for (int dz = -MOVE_RADIUS; dz <= MOVE_RADIUS; dz++) {
                    RegionBlockSnapshot.replaceWithoutDrops(
                        level, this.origin.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
                }
            }
            mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(block.asItem(), count));
            this.previousCount = count;
        } else {
            this.previousCount = mob.getMainHandItem().getCount();
        }
        this.steps = 0;
        this.previousY = mob.getY();
        this.success = false;
        this.outOfBounds = false;
        this.resourceExhausted = false;
    }

    /** 计算高度增量、放置奖励、成功奖励及失败惩罚。 */
    @Override
    protected double computeReward(ProtoMcObservation observation) {
        Mob mob = mob();
        double currentY = mob.getY();
        double reward = currentY - this.previousY;
        if (currentY - this.baseY >= this.targetHeight) {
            reward += 0.2 * Math.max(0, Math.floor(currentY - this.previousY));
            if (!this.success) {
                reward += 10.0;
                this.success = true;
            }
        }
        int count = mob.getMainHandItem().getCount();
        if (count < this.previousCount) {
            reward += 0.1;
        }
        this.previousCount = count;
        this.previousY = currentY;
        return reward;
    }

    /** 判断是否达到相对目标高度。 */
    @Override
    protected boolean isTerminated(ProtoMcObservation observation) {
        return this.success || super.isTerminated(observation);
    }

    /** 判断是否越界、耗尽资源或超过步数上限。 */
    @Override
    protected boolean isTruncated(ProtoMcObservation observation) {
        Mob mob = mob();
        this.steps++;
        this.outOfBounds = this.origin != null
            && (Math.abs(mob.getX() - (this.origin.getX() + 0.5)) > MOVE_RADIUS
                || Math.abs(mob.getZ() - (this.origin.getZ() + 0.5)) > MOVE_RADIUS);
        this.resourceExhausted = mob.getMainHandItem().isEmpty();
        return !this.success && (this.outOfBounds || this.resourceExhausted || this.steps >= this.maxSteps);
    }

    /** 将环境专属运行状态加入 step 信息，供 Python demo 读取。 */
    @Override
    protected Map<String, Object> createStepInfo(ProtoMcObservation observation) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("height", mob().getY() - this.baseY);
        info.put("target_height", this.targetHeight);
        info.put("remaining_blocks", mob().getMainHandItem().getCount());
        info.put("steps", this.steps);
        info.put("success", this.success);
        info.put("out_of_bounds", this.outOfBounds);
        info.put("resource_exhausted", this.resourceExhausted);
        return info;
    }

    /**
     * 清空移动范围内从起始脚底到目标上方的训练柱体（仅首个 reset 的场景搭建分支调用）。
     *
     * @param level 训练环境所在的服务端世界
     */
    private void clearTrainingColumn(ServerLevel level) {
        int topY = this.origin.getY() + this.targetHeight + 2;
        for (int dx = -MOVE_RADIUS; dx <= MOVE_RADIUS; dx++) {
            for (int dz = -MOVE_RADIUS; dz <= MOVE_RADIUS; dz++) {
                for (int y = this.origin.getY(); y <= topY; y++) {
                    BlockPos pos = new BlockPos(this.origin.getX() + dx, y, this.origin.getZ() + dz);
                    if (!level.getBlockState(pos).isAir()) {
                        level.removeBlock(pos, false);
                    }
                }
            }
        }
    }

    /** 读取正整数 reset option。 */
    private static int positiveInt(Map<String, Object> options, String key, int fallback) {
        Object value = options.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number) || number.intValue() <= 0) {
            throw new IllegalArgumentException("Reset option '" + key + "' must be a positive integer");
        }
        return number.intValue();
    }

    /** 读取字符串 reset option。 */
    private static String stringOption(Map<String, Object> options, String key, String fallback) {
        Object value = options.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException("Reset option '" + key + "' must be a non-empty string");
        }
        return string;
    }

    /** 注册表使用的环境工厂。 */
    public static final class Factory implements McEnvFactory {
        /** 创建指定 Mob 的跳搭环境。 */
        @Override
        public ParkourMobEnv create(Identifier envTypeId, Mob mob) {
            return new ParkourMobEnv(envTypeId, mob);
        }
    }
}
