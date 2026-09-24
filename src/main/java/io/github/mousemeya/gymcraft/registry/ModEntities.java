package io.github.mousemeya.gymcraft.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.entity.PlayerSimEntity;

/**
 * 实体类型注册入口 —— 通过 {@link DeferredRegister} 注册 GymCraft 自定义实体。
 * <p>
 * 当前仅 {@code gymcraft:player_sim}（玩家外观受控 Agent）。属性表经
 * {@link #registerAttributes(EntityAttributeCreationEvent)} 在 mod 总线装配；
 * 客户端渲染器在 {@code GymCraftClient} 注册。
 * </p>
 */
public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> REGISTRY = DeferredRegister.create(
        Registries.ENTITY_TYPE, GymCraft.MODID);

    /** 玩家模拟实体：玩家体型/属性，供任意环境挂载为受控 Agent。 */
    public static final DeferredHolder<EntityType<?>, EntityType<PlayerSimEntity>> PLAYER_SIM = REGISTRY.register(
        "player_sim",
        () -> EntityType.Builder.of(PlayerSimEntity::new, MobCategory.MISC)
            .sized(0.6F, 1.8F)
            .eyeHeight(1.62F)
            .clientTrackingRange(10)
            .build(GymCraft.MODID + ":player_sim")
    );

    private ModEntities() {
    }

    /**
     * 装配实体属性表（mod 总线 {@link EntityAttributeCreationEvent}）。
     *
     * @param event 属性注册事件
     */
    public static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(PLAYER_SIM.get(), PlayerSimEntity.createAttributes().build());
    }
}
