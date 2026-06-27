package io.github.mousemeya.withme.item;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.github.mousemeya.withme.gym.EnvManager;
import io.github.mousemeya.withme.registry.RegistryKeys;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * 环境工具物品。
 */
public class EnvToolItem extends Item {
    private static final String ENV_TYPE_TAG = "withme_env_type";
    private static final String DEFAULT_ENV_TYPE = "withme:simple_mob";

    public EnvToolItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof Mob mob)) {
            return InteractionResult.PASS;
        }

        if (player.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        if (player.isShiftKeyDown()) {
            boolean removed = EnvManager.close(mob.getUUID());
            player.sendSystemMessage(Component.literal(removed ? "Removed environment from " + mob.getUUID() : "No environment on " + mob.getUUID()));
            return InteractionResult.SUCCESS;
        }

        String envType = getSelectedEnvType(stack);
        EnvManager.create(envType, mob);
        player.sendSystemMessage(Component.literal("Created environment " + envType + " for " + mob.getUUID()));
        return InteractionResult.SUCCESS;
    }

    public static String getSelectedEnvType(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null || !customData.contains(ENV_TYPE_TAG)) {
            return DEFAULT_ENV_TYPE;
        }
        return customData.copyTag().getString(ENV_TYPE_TAG).orElse(DEFAULT_ENV_TYPE);
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
        for (Identifier id : RegistryKeys.ENV_FACTORIES.keySet()) {
            envTypes.add(id.toString());
        }
        envTypes.sort(Comparator.naturalOrder());
        return envTypes;
    }

    public static void setSelectedEnvType(ItemStack stack, String envType) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(ENV_TYPE_TAG, envType));
    }
}
