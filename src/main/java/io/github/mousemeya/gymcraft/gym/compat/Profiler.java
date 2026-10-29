package io.github.mousemeya.gymcraft.gym.compat;

import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** 将 1.3.0 的自动关闭采样区间接到 1.21.1 的 push/pop 分析器；仅在服务端线程采样。 */
public final class Profiler {
    private final ProfilerFiller filler;

    /** 保存当前分析器；参数 filler 为本次采样目标。 */
    private Profiler(ProfilerFiller filler) { this.filler = filler; }

    /** 返回当前服务端线程分析器；服务器不可用或不在主线程时返回空分析器。 */
    public static Profiler get() {
        var server = ServerLifecycleHooks.getCurrentServer();
        return new Profiler(server != null && server.isSameThread() ? server.getProfiler() : InactiveProfiler.INSTANCE);
    }

    /** 开始命名区间；参数 name 为采样名，返回必须关闭的区间。 */
    public Zone zone(String name) {
        this.filler.push(name);
        return new Zone(this.filler);
    }

    /** 自动关闭的采样区间；必须与创建时的 push 在同一线程配对。 */
    public static final class Zone implements AutoCloseable {
        private final ProfilerFiller filler;

        /** 保存区间分析器；参数 filler 为已执行 push 的分析器。 */
        private Zone(ProfilerFiller filler) { this.filler = filler; }

        /** 结束区间，无参数和返回值。 */
        @Override public void close() { this.filler.pop(); }
    }
}
