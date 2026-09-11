package com.lemonlightmc.minecicd.bossbar;

import com.lemonlightmc.minecicd.api.MineCICDApi;
import com.lemonlightmc.minecicd.util.Threads;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;

public class BossBars {

    private boolean enabled;
    private int durationTicks;
    private BossBar current;

    public BossBars() {
        this.enabled = MineCICDApi.config().bossBar().enabled();
        this.durationTicks = MineCICDApi.config().bossBar().durationTicks();
    }

    public void reload() {
        this.enabled = MineCICDApi.config().bossBar().enabled();
        this.durationTicks = MineCICDApi.config().bossBar().durationTicks();
        if (!enabled) {
            removeCurrent();
        }
    }

    public void show(final String path, final Map<String, String> placeholders) {
        show(MineCICDApi.messages().get("bossbar-" + path, placeholders));
    }

    public void show(final Component name) {
        if (!enabled) {
            return;
        }
        Threads.marshaled(() -> showAsyncSafe(name));
    }

    private void showAsyncSafe(final Component name) {
        removeCurrent();
        final BossBar bar = BossBar.bossBar(name, 1f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("minecicd.notify")) {
                player.showBossBar(bar);
            }
        }
        current = bar;
        MineCICDApi.plugin().getServer().getScheduler().runTaskLater(MineCICDApi.plugin(), () -> {
            if (current == bar) {
                removeCurrent();
            }
        }, durationTicks);
    }

    private void removeCurrent() {
        if (current == null) {
            return;
        }
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("minecicd.notify")) {
                player.hideBossBar(current);
            }
        }
        current = null;
    }
}