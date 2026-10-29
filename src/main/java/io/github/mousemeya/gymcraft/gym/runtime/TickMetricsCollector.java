package io.github.mousemeya.gymcraft.gym.runtime;

import net.minecraft.util.profiling.Profiler;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 默认关闭的服务端 tick 测评指标采集器。
 *
 * <p>采集只在服务端 tick 线程记录内存数据，停止时一次性写 CSV，避免逐 tick
 * 日志和控制台 I/O 扰动测试结果。{@code stepsStarted} 在新动作批次被消费时增加，
 * {@code stepsCompleted} 在批次结果发布时增加；多动作批次仍只计为一次交互。</p>
 */
public final class TickMetricsCollector {
    private static final String CSV_HEADER =
        "run_id,tick_index,tick_start_monotonic_ns,tick_duration_ms,steps_started,steps_completed,"
            + "active_environments,target_steps_per_second_per_env\n";

    private static boolean enabled;
    private static String runId = "";
    private static double targetStepsPerSecond;
    private static int activeEnvironments;
    private static long tickIndex;
    private static long tickStartNanos;
    private static int stepsStarted;
    private static int stepsCompleted;
    private static Path outputPath;
    private static final List<TickRecord> RECORDS = new ArrayList<>();

    private TickMetricsCollector() {
    }

    /**
     * 开始一轮采集。
     *
     * @param id 运行标识，只允许由指令调用方保证唯一性
     * @param targetHz 每环境目标步进频率，仅作元数据记录
     * @param environments 参与环境数，仅作元数据记录
     * @param output CSV 输出路径，必须不存在
     * @throws IOException 无法创建父目录时抛出
     */
    public static synchronized void start(String id, double targetHz, int environments, Path output)
            throws IOException {
        if (enabled) {
            throw new IllegalStateException("Tick metrics collection is already active");
        }
        Path absolute = output.toAbsolutePath().normalize();
        if (Files.exists(absolute)) {
            throw new IllegalArgumentException("Tick metrics output already exists: " + absolute);
        }
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        runId = id;
        targetStepsPerSecond = targetHz;
        activeEnvironments = environments;
        outputPath = absolute;
        tickIndex = 0;
        stepsStarted = 0;
        stepsCompleted = 0;
        RECORDS.clear();
        enabled = true;
    }

    /**
     * 停止采集并写出全部 tick。
     *
     * @return 写出的 CSV 路径
     * @throws IOException 写文件失败时抛出
     */
    public static synchronized Path stop() throws IOException {
        if (!enabled) {
            throw new IllegalStateException("Tick metrics collection is not active");
        }
        enabled = false;
        Path output = outputPath;
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writer.write(CSV_HEADER);
            for (TickRecord record : RECORDS) {
                writer.write(String.format(Locale.ROOT,
                    "%s,%d,%d,%.6f,%d,%d,%d,%.6f\n",
                    runId, record.index(), record.startNanos(), record.durationNanos() / 1_000_000.0,
                    record.stepsStarted(), record.stepsCompleted(), activeEnvironments,
                    targetStepsPerSecond));
            }
        }
        RECORDS.clear();
        outputPath = null;
        return output;
    }

    /** 在服务端 tick 最前段记录起始时间并清零本 tick 计数。 */
    public static void onServerTickPre(ServerTickEvent.Pre event) {
        if (!enabled) {
            return;
        }
        try (var zone = Profiler.get().zone("gymcraft_tick_metrics")) {
            tickStartNanos = System.nanoTime();
            stepsStarted = 0;
            stepsCompleted = 0;
        }
    }

    /** 在服务端 tick 最后段记录耗时与交互数量。 */
    public static void onServerTickPost(ServerTickEvent.Post event) {
        if (!enabled) {
            return;
        }
        try (var zone = Profiler.get().zone("gymcraft_tick_metrics")) {
            long duration = System.nanoTime() - tickStartNanos;
            RECORDS.add(new TickRecord(++tickIndex, tickStartNanos, duration, stepsStarted, stepsCompleted));
        }
    }

    /** 记录当前 tick 新消费一个动作批次。 */
    public static void recordStepStarted() {
        if (enabled) {
            stepsStarted++;
        }
    }

    /** 记录当前 tick 发布一个动作批次终态。 */
    public static void recordStepCompleted() {
        if (enabled) {
            stepsCompleted++;
        }
    }

    /** 单条不可变 tick 记录。 */
    private record TickRecord(
        long index,
        long startNanos,
        long durationNanos,
        int stepsStarted,
        int stepsCompleted
    ) {
    }
}
