package io.github.mousemeya.withme.gym.agent;

import io.github.mousemeya.withme.gym.action.proto.McAction;
import io.github.mousemeya.withme.gym.observation.proto.McObservation;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public class AgentControlState {
    public enum ControlMode { OBSERVE, OWN_FLAGS }

    public String agentId;
    public ControlMode controlMode = ControlMode.OWN_FLAGS;
    public String envType = "navigation";
    public Vec3 moveTarget;
    public UUID attackTargetUuid;
    public McAction pendingAction;
    public McObservation latestObservation;
    public long episodeId;
    public long lastStepTick;
    public boolean active;
    public long attachTick;
}
