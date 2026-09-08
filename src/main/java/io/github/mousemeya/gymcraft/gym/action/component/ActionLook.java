package io.github.mousemeya.gymcraft.gym.action.component;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/** 动作共用的瞬时转向逻辑；仅在服务端线程使用，不负责目标合法性校验。 */
final class ActionLook {
    /** 禁止实例化工具类。 */
    private ActionLook() { }

    /**
     * @param mob 受控生物
     * @param point 世界坐标中的注视点
     */
    static void apply(Mob mob, Vec3 point) {
        mob.getLookControl().setLookAt(point.x, point.y, point.z, 360.0F, 180.0F);
        mob.lookAt(EntityAnchorArgument.Anchor.EYES, point);
        mob.setYBodyRot(mob.getYRot());
    }
}
