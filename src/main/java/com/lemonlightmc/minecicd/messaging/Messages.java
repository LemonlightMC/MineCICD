package com.lemonlightmc.minecicd.messaging;

import com.lemonlightmc.minecicd.MineCICD;
import com.lemonlightmc.minecicd.util.Threads;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class Messages {

    private final MineCICD plugin;
    private YamlConfiguration config;

    public Messages(final MineCICD plugin) {
        this.plugin = plugin;
        load();
    }

    public static String rootMessage(final Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        final String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    public void load() {
        final File file = new File(plugin.getDataFolder(), "messages.yml");
        boolean changed = false;
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
            changed = true;
        }

        config = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in == null) {
                return;
            }
            final YamlConfiguration defaults = YamlConfiguration
                    .loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            config.setDefaults(defaults);
            for (final String key : defaults.getKeys(true)) {
                if (!config.contains(key, true)) {
                    config.set(key, defaults.get(key));
                    changed = true;
                }
            }
        } catch (final Exception e) {
            plugin.getLogger().warning("Unable to read bundled messages.yml: " + e.getMessage());
        }
        changed |= migrateLegacyValues(file);

        if (changed) {
            try {
                config.save(file);
            } catch (final Exception e) {
                plugin.getLogger().warning("Unable to save messages.yml: " + e.getMessage());
            }
        }
    }

    public void send(final CommandSender sender, final Component component) {
        if (sender == null) {
            return;
        }
        Threads.marshaled(plugin, () -> sender.sendMessage(prefix().append(component)));
    }

    public void sendRaw(final CommandSender sender, final Component component) {
        if (sender == null) {
            return;
        }
        Threads.marshaled(plugin, () -> sender.sendMessage(component));
    }

    public void send(final CommandSender sender, final String path) {
        send(sender, get(path, Map.of()));
    }

    public void send(final CommandSender sender, final String path, final Map<String, String> placeholders) {
        send(sender, get(path, placeholders));
    }

    public void sendList(final CommandSender sender, final String path, final Map<String, String> placeholders) {
        if (sender == null) {
            return;
        }
        Threads.marshaled(plugin, () -> {
            sender.sendMessage(prefix());
            final List<String> raw = config.getStringList(path);
            final int len = raw.size();
            for (int i = 0; i < len; i++) {
                sender.sendMessage(format(raw.get(i), placeholders));
            }
        });
    }

    public Component get(final String path, final Map<String, String> placeholders) {
        return format(config.getString(path, ""), placeholders);
    }

    public Component prefix() {
        return MiniMessage.miniMessage()
                .deserialize(config.getString("prefix", "<gray>[<green>Mine<aqua>CI<light_purple>CD<gray>] <reset>"));
    }

    public ConfigurationSection raw() {
        return config;
    }

    public static String escape(final String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("<", "\\<").replace(">", "\\>");
    }

    private static Component format(String template, final Map<String, String> placeholders) {
        if (template == null || template.isEmpty()) {
            return Component.empty();
        }
        if (placeholders == null || placeholders.isEmpty()) {
            return MiniMessage.miniMessage().deserialize(template);
        }
        for (final Map.Entry<String, String> entry : placeholders.entrySet()) {
            template = template.replace("{" + entry.getKey() + "}", escape(entry.getValue()));
        }
        return MiniMessage.miniMessage().deserialize(template);
    }

    private boolean migrateLegacyValues(final java.io.File file) {
        boolean changed = false;
        for (final String key : config.getKeys(true)) {
            if (config.isString(key)) {
                final String s = config.getString(key);
                final String migrated = migrateLegacy(s);
                if (!migrated.equals(s)) {
                    config.set(key, migrated);
                    changed = true;
                }
            } else if (config.isList(key)) {
                final List<String> list = config.getStringList(key); // returns ArrayList
                boolean listChanged = false;
                for (int i = 0; i < list.size(); i++) {
                    final String migrated = migrateLegacy(list.get(i));
                    if (!migrated.equals(list.get(i))) {
                        list.set(i, migrated);
                        listChanged = true;
                    }
                }
                if (listChanged) {
                    config.set(key, list);
                    changed = true;
                }
            }
        }
        return changed;
    }

    public static String migrateLegacy(final String input) {
        final StringBuilder sb = new StringBuilder(input.length() + 16);
        int i = 0;
        while (i < input.length()) {
            final char c = input.charAt(i);
            if (c != '&' || i + 1 >= input.length()) {
                sb.append(c);
                i++;
                continue;
            }
            // migrate legacy color in line
            final char code = input.charAt(i + 1);
            if (code == '#') {
                // hex color &#rrggbb
                if (i + 8 <= input.length()) {
                    final String hex = input.substring(i + 2, i + 8);
                    if (isHexColor(hex)) {
                        sb.append("<color:#").append(hex).append(">");
                        i += 8;
                        continue;
                    }
                }
                sb.append(c);
                i++;
                continue;
            }
            final String tag = legacyCode(code);
            if (tag != null) {
                if (code == 'x') {
                    final StringBuilder hex = new StringBuilder("#");
                    int j = i + 2;
                    boolean valid = true;
                    while (j + 2 <= input.length() && hex.length() < 7) {
                        if (input.charAt(j) == '&' && isHex(input.charAt(j + 1))) {
                            hex.append(input.charAt(j + 1));
                            j += 2;
                        } else {
                            valid = false;
                            break;
                        }
                    }
                    if (valid && hex.length() == 7) {
                        sb.append("<color:").append(hex).append(">");
                        i = j;
                        continue;
                    }
                    // Malformed &x sequence: keep it literal instead of emitting "null".
                    sb.append(c).append(code);
                    i += 2;
                    continue;
                }
                sb.append(tag);
                i += 2;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static boolean isHexColor(final String s) {
        if (s.length() != 6) {
            return false;
        }
        for (int i = 0; i < 6; i++) {
            if (!isHex(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHex(final char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static String legacyCode(final char code) {
        return switch (code) {
            case '0' -> "<black>";
            case '1' -> "<dark_blue>";
            case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>";
            case '4' -> "<dark_red>";
            case '5' -> "<dark_purple>";
            case '6' -> "<gold>";
            case '7' -> "<gray>";
            case '8' -> "<dark_gray>";
            case '9' -> "<blue>";
            case 'a' -> "<green>";
            case 'b' -> "<aqua>";
            case 'c' -> "<red>";
            case 'd' -> "<light_purple>";
            case 'e' -> "<yellow>";
            case 'f' -> "<white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default -> null;
        };
    }

}