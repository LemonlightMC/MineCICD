package com.lemonlightmc.minecicd.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import org.bukkit.Bukkit;

import com.lemonlightmc.minecicd.MineCICD;

public final class Threads {

    private Threads() {
    }

    public static ThreadFactory daemonFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    public static ExecutorService singleDaemonWorker(String name) {
        return Executors.newSingleThreadExecutor(daemonFactory(name));
    }

    public static ExecutorService dameonThreadPool(String name, int amount) {
        return Executors.newFixedThreadPool(amount, daemonFactory(name));
    }

    public static ScheduledExecutorService scheduledThreadExecutor(String name) {
        return Executors.newSingleThreadScheduledExecutor(daemonFactory(name));
    }

    public static void marshaled(MineCICD plugin, Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, runnable);
        }
    }
}