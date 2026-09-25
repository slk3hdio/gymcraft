package io.github.mousemeya.gymcraft.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import io.github.mousemeya.gymcraft.gym.entity.PlayerSimEntity;

/**
 * 扩展 1.21.1 原版实体阵营判定，使真实/FakePlayer 与 PlayerSim 双向视为友军。
 * <p>
 * 1.21.1 尚无新版的 {@code considersEntityAsAlly} 双向扩展点，因此仅在这一版本
 * 对 {@code Entity#isAlliedTo(Entity)} 做最小前置补充，其余关系仍由原版处理。
 * </p>
 */
@Mixin(Entity.class)
public abstract class EntityAllianceMixin {
    /**
     * 在原版队伍判定前补充玩家阵营关系。
     *
     * @param other 待判断关系的另一实体
     * @param cir 阵营判定返回值回调
     */
    @Inject(method = "isAlliedTo(Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"), cancellable = true)
    private void gymcraft$playerSimAlliance(Entity other, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (self instanceof Player && other instanceof PlayerSimEntity
            || self instanceof PlayerSimEntity && other instanceof Player) {
            cir.setReturnValue(true);
        }
    }
}
