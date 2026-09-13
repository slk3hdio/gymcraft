package io.github.mousemeya.gymcraft.item;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.github.mousemeya.gymcraft.gym.EnvManager;
import io.github.mousemeya.gymcraft.registry.EnvFactories;
import io.github.mousemeya.gymcraft.registry.RegistryKeys;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 环境工具物品，用于为 Mob 创建、切换或移除 GymCraft 环境。
 * <p>
 * 实体右键由高优先级交互事件优先转发到本物品，确保村民等会主动消费右键的实体
 * 不会在环境绑定前打开自身界面；非 Mob 目标保持原版交互行为。
 * </p>
 */
public class EnvToolItem extends Item {
    private static final String ENV_TYPE_TAG = "gymcraft_env_type";
    private static final String DEFAULT_ENV_TYPE = EnvFactories.SIMPLE_MOB.getId().toString();

    public EnvToolItem(Properties properties) {
        super(properties);
    }

    /**
     * 处理物品原生的生物交互回退入口。
     *
     * @param stack 当前环境工具物品栈
     * @param player 发起交互的玩家
     * @param target 被交互的生物
     * @param hand 使用环境工具的手
     * @return Mob 目标的环境操作结果；非 Mob 返回 PASS
     */
    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof Mob mob)) {
            return InteractionResult.PASS;
        }

        return interactWithMob(stack, player, mob);
    }

    /**
     * 在原版实体交互前优先处理环境工具，避免村民交易等行为抢先消费右键。
     *
     * @param event 玩家普通实体交互事件
     */
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getItemStack().getItem() instanceof EnvToolItem envTool)
            || !(event.getTarget() instanceof Mob mob)) {
            return;
        }

        InteractionResult result = envTool.interactWithMob(event.getItemStack(), event.getEntity(), mob);
        if (result.consumesAction()) {
            event.setCancellationResult(result);
            event.setCanceled(true);
        }
    }

    /**
     * 为目标 Mob 创建或移除环境。
     *
     * @param stack 当前环境工具物品栈
     * @param player 发起交互的玩家
     * @param mob 目标 Mob
     * @return 已处理时返回 SUCCESS
     */
    private InteractionResult interactWithMob(ItemStack stack, Player player, Mob mob) {

        if (player.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        if (player.isShiftKeyDown()) {
            boolean removed = EnvManager.close(mob.getUUID());
            player.sendSystemMessage(Component.literal(removed ? "Removed environment from " + mob.getUUID() : "No environment on " + mob.getUUID()));
            return InteractionResult.SUCCESS;
        }

        String envType = getSelectedEnvType(stack);
        try {
            EnvManager.create(envType, mob);
        } catch (IllegalArgumentException e) {
            player.sendSystemMessage(Component.literal(e.getMessage()));
            return InteractionResult.SUCCESS;
        }
        player.sendSystemMessage(Component.literal("Created environment " + envType + " for " + mob.getUUID()));
        return InteractionResult.SUCCESS;
    }

    public static String getSelectedEnvType(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null || !customData.contains(ENV_TYPE_TAG)) {
            return DEFAULT_ENV_TYPE;
        }
                // 1.21.1 的 CompoundTag#getString 直接返回 String（键缺失时为空串）
        String stored = customData.copyTag().getString(ENV_TYPE_TAG);
        return stored.isEmpty() ? DEFAULT_ENV_TYPE : stored;
    }

    public static String cycleSelectedEnvType(ItemStack stack, int direction) {
        List<String> envTypes = getRegisteredEnvTypes();
        if (envTypes.isEmpty()) {
            setSelectedEnvType(stack, DEFAULT_ENV_TYPE);
            return DEFAULT_ENV_TYPE;
        }

        String current = getSelectedEnvType(stack);
        int index = envTypes.indexOf(current);
        if (index < 0) {
            index = 0;
        } else {
            index = Math.floorMod(index + direction, envTypes.size());
        }

        String selected = envTypes.get(index);
        setSelectedEnvType(stack, selected);
        return selected;
    }

    private static List<String> getRegisteredEnvTypes() {
        List<String> envTypes = new ArrayList<>();
        for (ResourceLocation id : RegistryKeys.ENV_FACTORIES.keySet()) {
            envTypes.add(id.toString());
        }
        envTypes.sort(Comparator.naturalOrder());
        return envTypes;
    }

    public static void setSelectedEnvType(ItemStack stack, String envType) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(ENV_TYPE_TAG, envType));
    }
}
