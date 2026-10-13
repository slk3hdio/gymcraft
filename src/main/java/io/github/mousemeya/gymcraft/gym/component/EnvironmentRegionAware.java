package io.github.mousemeya.gymcraft.gym.component;

import javax.annotation.Nullable;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * 动作与观测共用的区域初始化契约。
 * 环境仅传入默认区域；边界转换、过滤和越界处理由组件自行实现，不增加运行时硬边界。
 */
public interface EnvironmentRegionAware {
    /**
     * 在环境构造期设置区域默认空间；随后由具体环境覆盖配置，reset 不调用此方法。
     * 同一区域重复调用产生相同边界；调用方负责初始化顺序，不记录配置来源。
     * @param region 环境基线区域；null 时保留组件原有默认空间
     */
    void initializeDefaultRegion(@Nullable BoundingBox region);
}
