package io.github.mousemeya.withme.gym.agent;

import io.github.mousemeya.withme.gym.action.proto.McAction;
import io.github.mousemeya.withme.gym.action.EntityAgentController;
import io.github.mousemeya.withme.gym.observation.proto.McObservation;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * 智能体控制状态，作为 NeoForge 数据附件存储在 Mob 实体上。
 * <p>
 * 保存了智能体的运行时状态，包括：动作队列、最新观测、目标位置、
 * 攻击目标、控制模式、回合信息等。每次 tick 时 {@link AgentGoal}
 * 会读取 pendingAction 并通过 {@link io.github.mousemeya.withme.gym.action.EntityAgentController} 执行。
 */
public class AgentControlState {
    /** 控制模式：OBSERVE 仅观察不控制，OWN_FLAGS 完全接管 AI */
    public enum ControlMode { OBSERVE, OWN_FLAGS }

    /** 智能体唯一标识符 */
    public String agentId;
    /** 当前控制模式 */
    public ControlMode controlMode = ControlMode.OWN_FLAGS;
    /** 环境类型注册表 ID */
    public String envType = "withme:navigation";
    /** 导航目标位置，为 null 表示无目标 */
    public Vec3 moveTarget;
    /** 攻击目标实体的 UUID */
    public UUID attackTargetUuid;
    /** 待执行的动作，由 AgentGoal.tick() 消费 */
    public McAction pendingAction;
    /** 当前环境的动作控制器，用于消费 pendingAction */
    public EntityAgentController controller;
    /** 最近一次构建的观测数据 */
    public McObservation latestObservation;
    /** 当前回合（episode）编号 */
    public long episodeId;
    /** 上一步（step）执行时的游戏刻 */
    public long lastStepTick;
    /** 智能体是否处于活跃状态 */
    public boolean active;
    /** 智能体挂载时的游戏刻 */
    public long attachTick;
}
