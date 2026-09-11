package com.lemonlightmc.minecicd.data;

import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;

public interface Actor {
  public String getName();

  public void sendMessage(Component message);

  public CommandSender getCommandSender();

  public static Actor fromSender(final CommandSender sender) {
    if (sender == null) {
      return null;
    }
    if (sender instanceof final Player player) {
      return new PlayerActor(player);
    } else if (sender instanceof ConsoleCommandSender) {
      return new ConsoleActor(sender);
    }
    throw new IllegalArgumentException("Unknown sender type: " + sender.getClass().getName());
  }

  public static Actor fromConsole() {
    return new ConsoleActor(Bukkit.getConsoleSender());
  }

  public static Actor fromActions(final Consumer<Component> messageConsumer) {
    return new ActionActor(messageConsumer);
  }

  public static Actor fromApi() {
    return new ApiActor("Console", Bukkit.getConsoleSender());
  }

  public static Actor fromApi(final CommandSender sender) {
    return new ApiActor(sender == null ? null : sender.getName(), sender);
  }

  public static Actor fromApi(final String name, final CommandSender sender) {
    return new ApiActor(name, sender);
  }

  public static Actor fromWebhook() {
    return new WebhookActor();
  }

  public static Actor fromString(String key) {
    if (key == null || key.isBlank()) {
      return fromConsole();
    }
    switch (key) {
      case "actions":
        return fromActions((message) -> {
        });
      case "Webhook":
        return fromWebhook();
      case "API":
        return fromApi();
      case "Console":
        return fromConsole();
      default:
        final Player player = Bukkit.getPlayerExact(key);
        if (player != null) {
          return fromSender(player);
        }
        return fromConsole();
    }
  }

  class PlayerActor implements Actor {
    private final CommandSender sender;

    private PlayerActor(final CommandSender sender) {
      this.sender = sender;
    }

    @Override
    public String getName() {
      return sender.getName();
    }

    @Override
    public String toString() {
      return sender.getName();
    }

    @Override
    public void sendMessage(final Component message) {
      sender.sendMessage(message);
    }

    @Override
    public CommandSender getCommandSender() {
      return sender;
    }
  }

  class ConsoleActor implements Actor {
    private final CommandSender sender;

    private ConsoleActor(final CommandSender sender) {
      this.sender = sender;
    }

    @Override
    public String getName() {
      return "Console";
    }

    @Override
    public String toString() {
      return "Console";
    }

    @Override
    public void sendMessage(final Component message) {
      sender.sendMessage(message);
    }

    @Override
    public CommandSender getCommandSender() {
      return sender;
    }
  }

  class ActionActor implements Actor {
    private final Consumer<Component> messageConsumer;

    private ActionActor(final Consumer<Component> messageConsumer) {
      this.messageConsumer = messageConsumer;
    }

    @Override
    public String getName() {
      return "Actions";
    }

    @Override
    public String toString() {
      return "Actions";
    }

    @Override
    public void sendMessage(final Component message) {
      messageConsumer.accept(message);
    }

    @Override
    public CommandSender getCommandSender() {
      return Bukkit.getConsoleSender();
    }
  }

  class WebhookActor implements Actor {

    private WebhookActor() {
    }

    @Override
    public String getName() {
      return "Webhook";
    }

    @Override
    public String toString() {
      return "Webhook";
    }

    @Override
    public void sendMessage(final Component message) {
    }

    @Override
    public CommandSender getCommandSender() {
      return Bukkit.getConsoleSender();
    }
  }

  class ApiActor implements Actor {
    private final CommandSender sender;
    private final String name;

    private ApiActor(final String name, final CommandSender sender) {
      this.sender = sender == null ? Bukkit.getConsoleSender() : sender;
      this.name = name == null || name.isBlank() ? "API" : name;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String toString() {
      return name;
    }

    @Override
    public void sendMessage(final Component message) {
      sender.sendMessage(message);
    }

    @Override
    public CommandSender getCommandSender() {
      return sender;
    }
  }

}
