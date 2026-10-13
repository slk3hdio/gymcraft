package io.github.mousemeya.gymcraft.gym.component;

import javax.annotation.Nullable;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * 动作与观测共用的区域初始化契约。
 * 环境仅传入默认区域；边界转换、过滤和越界处理由组件自行实现，不增加运行时硬边界。
 */
public interface EnvironmentRegionAware {
    /**
     * 用环境区域初始化组件的默认空间；显式配置优先，重复调用不重新初始化。
     * @param region 环境基线区域；null 时保留组件原有默认空间
     */
    void initializeDefaultRegion(@Nullable BoundingBox region);
}
