package io.github.mousemeya.gymcraft.gym.env;

import com.google.protobuf.Message;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.rpc.proto.ResetResponse;
import io.github.mousemeya.gymcraft.gym.rpc.proto.StepResponse;
import io.github.mousemeya.gymcraft.gym.space.ComponentSpaceSet;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * 核心环境接口，对应 Gymnasium 的 Env 类。
 */
public interface McEnv {

    String getRegisterId();

    /**
     * 重置环境到初始状态。
     * @param seed 随机种子，用于可复现性
     * @param options 可选的配置参数
     * @return 包含初始观测和额外信息的 ResetResponse
     */
    ResetResponse reset(Integer seed, Map<String, Object> options);

    /**
     * 执行一步交互。
     * @param actions 按顺序执行的动作列表
     * @param timeoutSeconds 整个 step 共享的超时秒数
     * @return 包含新观测、奖励、终止标志、截断标志和额外信息的 StepResponse
     */
    StepResponse step(List<ProtoMcAction> actions, float timeoutSeconds);

    // --- 核心属性 ---
    /** 返回动作空间目录（组件 ID → 消息空间，有序） */
    ComponentSpaceSet getActionSpace();

    /** 返回观测空间目录（组件 ID → 消息空间，有序） */
    ComponentSpaceSet getObservationSpace();

    /** 环境的元数据，如渲染模式 */
    Map<String, Object> getMetadata();

    /** 关闭环境，释放资源 */
    void close();

    // --- 通用只读查询 ---
    /**
     * 按组件注册 ID 查找动作 controller 实例（只读），供环境外扩展在运行期
     * 解析组件状态（如死亡钩子读取 respawn 配置），不得用于调用动作生命周期方法。
     * @param componentId 组件注册 ID（如 {@code gymcraft:respawn}）
     * @return 该组件的 controller 实例；环境未声明该组件时为空
     */
    Optional<ActionComponentController<?>> findActionComponent(String componentId);

    /**
     * 获取指定动作组件在当前环境中使用的参数空间。
     * @param componentId 组件注册 ID
     * @return 该组件的空间；未声明该组件时的行为由实现决定
     */
    McSpace<? extends Message> getActionComponentSpace(String componentId);

    /**
     * 获取指定观测组件在当前环境中使用的观测空间。
     * @param componentId 组件注册 ID
     * @return 该组件的空间；未声明该组件时的行为由实现决定
     */
    McSpace<? extends Message> getObservationComponentSpace(String componentId);

    /**
     * 最近一次 reset 的生效 options（场景保存回放用）。
     * @return 不可变 options 视图；未 reset 过为空 Map
     */
    Map<String, Object> lastResetOptionsView();

    /**
     * 生效状态区域的只读视图：场景环境来自场景文件，挂载环境来自挂载作用域；
     * 构造期一次性确定，此后不变。
     * @return 当前生效的环境占用区域；空区域环境（不占用世界）为 null
     */
    @Nullable
    BoundingBox stateRegion();
}
