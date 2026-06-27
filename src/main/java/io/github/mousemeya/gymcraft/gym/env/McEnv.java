package io.github.mousemeya.gymcraft.gym.env;

import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMcObservation;
import io.github.mousemeya.gymcraft.gym.rpc.proto.ResetResponse;
import io.github.mousemeya.gymcraft.gym.rpc.proto.StepResponse;
import io.github.mousemeya.gymcraft.gym.space.McSpace;

import java.util.Map;

/**
 * 核心环境接口，对应 Gymnasium 的 Env 类。
 */
public interface McEnv {

    /**
     * 重置环境到初始状态。
     * @param seed 随机种子，用于可复现性
     * @param options 可选的配置参数
     * @return 包含初始观测和额外信息的 ResetResponse
     */
    ResetResponse reset(Integer seed, Map<String, Object> options);

    /**
     * 执行一步交互。
     * @param action 智能体选择的动作
     * @return 包含新观测、奖励、终止标志、截断标志和额外信息的 StepResponse
     */
    StepResponse step(ProtoMcAction action);

    // --- 核心属性 ---
    /** 返回动作空间 */
    McSpace<Map<String, Object>> getActionSpace();

    /** 返回观测空间 */
    McSpace<Map<String, Object>> getObservationSpace();

    /** 环境的元数据，如渲染模式 */
    Map<String, Object> getMetadata();

    /** 关闭环境，释放资源 */
    void close();
}