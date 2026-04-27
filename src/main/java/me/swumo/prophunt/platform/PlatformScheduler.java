package me.swumo.prophunt.platform;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Platform-aware scheduler that hides the difference between Paper's single
 * main-thread {@link org.bukkit.scheduler.BukkitScheduler} and Folia's
 * regionised thread model.
 *
 * <p>Folia is detected once at construction by probing for the presence of
 * {@code io.papermc.paper.threadedregions.RegionizedServer}. On Paper, calls are
 * forwarded to {@link Bukkit#getScheduler()}. On Folia, calls route to the
 * appropriate regionised scheduler:
 * <ul>
 *   <li>repeating / delayed work uses {@link Bukkit#getGlobalRegionScheduler()}</li>
 *   <li>async work uses {@link Bukkit#getAsyncScheduler()}</li>
 *   <li>per-entity work uses {@link Entity#getScheduler()}</li>
 * </ul>
 *
 * <p>All scheduling methods return a {@link PlatformTask} that abstracts the
 * underlying {@link BukkitTask} or {@link ScheduledTask} so callers can cancel
 * uniformly without knowing the platform.
 *
 * <p>Limitation: {@link #runGlobalRepeating(Runnable, long, long)} runs on the
 * global region tick thread on Folia. Logic that mutates entities or blocks
 * outside of the global region must be migrated to per-entity or per-region
 * scheduling for full Folia correctness.
 */
public final class PlatformScheduler {

    /**
     * A handle to a scheduled task that can be cancelled regardless of the
     * underlying platform scheduler.
     */
    public interface PlatformTask {
        /**
         * Cancels the task. Safe to call multiple times.
         */
        void cancel();
    }

    private final JavaPlugin plugin;

    /**
     * {@code true} when running on a Folia (regionised) server,
     * {@code false} on standard Paper or Spigot.
     */
    @Getter private final boolean folia;

    /**
     * Creates a scheduler bound to the given plugin and detects whether the
     * runtime is Folia.
     *
     * @param plugin owning plugin used when scheduling tasks
     */
    public PlatformScheduler(JavaPlugin plugin) {
        this.plugin = plugin;
        boolean detected;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            detected = true;
        } catch (ClassNotFoundException ignored) {
            detected = false;
        }
        this.folia = detected;
    }

    /**
     * Schedules a task to run repeatedly on the global region tick thread
     * (Folia) or on the main server thread (Paper).
     *
     * <p>On Folia, both {@code delayTicks} and {@code periodTicks} are clamped
     * to a minimum of 1 because the global region scheduler rejects values
     * below 1.
     *
     * @param task        runnable to invoke each tick
     * @param delayTicks  ticks to wait before the first execution
     * @param periodTicks ticks between consecutive executions
     * @return cancellable handle to the scheduled task
     */
    public PlatformTask runGlobalRepeating(Runnable task, long delayTicks, long periodTicks) {
        if (folia) {
            ScheduledTask t = Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(plugin, st -> task.run(), Math.max(1L, delayTicks), Math.max(1L, periodTicks));
            return t::cancel;
        }
        BukkitTask t = Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
        return t::cancel;
    }

    /**
     * Schedules a task to run once after a delay on the global region tick
     * thread (Folia) or on the main server thread (Paper).
     *
     * <p>On Folia, {@code delayTicks} is clamped to a minimum of 1 because the
     * global region scheduler rejects values below 1.
     *
     * @param task       runnable to invoke
     * @param delayTicks ticks to wait before execution
     * @return cancellable handle to the scheduled task
     */
    public PlatformTask runGlobalLater(Runnable task, long delayTicks) {
        if (folia) {
            ScheduledTask t = Bukkit.getGlobalRegionScheduler()
                .runDelayed(plugin, st -> task.run(), Math.max(1L, delayTicks));
            return t::cancel;
        }
        BukkitTask t = Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        return t::cancel;
    }

    /**
     * Runs a task on a background thread immediately. The task must not call
     * any Bukkit API that requires a tick thread.
     *
     * @param task runnable to invoke off-thread
     * @return cancellable handle to the scheduled task
     */
    public PlatformTask runAsync(Runnable task) {
        if (folia) {
            ScheduledTask t = Bukkit.getAsyncScheduler().runNow(plugin, st -> task.run());
            return t::cancel;
        }
        BukkitTask t = Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        return t::cancel;
    }

    /**
     * Runs a task on the thread that owns the given entity.
     *
     * <p>On Folia, the task is dispatched to the entity's region thread via
     * {@link Entity#getScheduler()}. On Paper, the task runs immediately when
     * already on the main thread, otherwise it is scheduled on the next tick.
     *
     * @param entity entity whose owning thread should execute the task
     * @param task   runnable to invoke on the owning thread
     */
    public void runForEntity(Entity entity, Runnable task) {
        if (folia) {
            entity.getScheduler().run(plugin, st -> task.run(), null);
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
