package io.github.mousemeya.gymcraft.gym.action.navigation;

import javax.annotation.Nullable;

import net.minecraft.world.level.pathfinder.Path;

/**
 * 寻路类动作 controller 的当前路径只读视图。
 * <p>
 * 由持有 {@link MobNavigationTask} 的动作 controller（如 move_to/swim_to/pick_up_item）
 * 实现，供可视化层在动作运行期间采样实际规划路径；返回的 {@link Path} 是导航内部
 * 对象，调用方只允许读取节点坐标，不得修改。
 * </p>
 */
public interface NavigationPathProvider {
    /**
     * 返回当前正在跟随的规划路径。
     *
     * @return 跟随中的路径；动作未运行、末段直行、驻留等待或路径已释放时为 null
     */
    @Nullable
    Path currentPath();
}
