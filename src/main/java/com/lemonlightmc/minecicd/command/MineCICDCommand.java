package com.lemonlightmc.minecicd.command;

import com.lemonlightmc.minecicd.CicdService;
import com.lemonlightmc.minecicd.git.Results;
import com.lemonlightmc.minecicd.messaging.Messages;
import com.lemonlightmc.minecicd.services.AuditLogger;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

import org.bukkit.command.CommandSender;

import java.util.Map;
import java.util.function.Predicate;

public class MineCICDCommand {

    private final CicdService service;
    private final Messages messages;

    public MineCICDCommand(final CicdService service, final Messages messages) {
        this.service = service;
        this.messages = messages;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("minecicd")
                .then(Commands.literal("init")
                        .requires(req("minecicd.init"))
                        .executes(ctx -> {
                            service.init(sender(ctx));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("deinit")
                        .requires(req("minecicd.deinit"))
                        .executes(ctx -> {
                            service.deinit(sender(ctx));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("pull")
                        .requires(req("minecicd.pull"))
                        .then(Commands.literal("force")
                                .executes(ctx -> {
                                    service.pull(sender(ctx), true);
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .executes(ctx -> {
                            service.pull(sender(ctx), false);
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("push")
                        .requires(req("minecicd.push"))
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    service.push(sender(ctx), StringArgumentType.getString(ctx, "message"));
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .executes(ctx -> {
                            messages.send(sender(ctx), "push-usage", Map.of("label", "minecicd"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("add")
                        .requires(req("minecicd.add"))
                        .then(Commands.argument("path", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    service.add(sender(ctx), StringArgumentType.getString(ctx, "path"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("remove")
                        .requires(req("minecicd.remove"))
                        .then(Commands.argument("path", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    service.remove(sender(ctx), StringArgumentType.getString(ctx, "path"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("reset")
                        .requires(req("minecicd.reset"))
                        .then(Commands.argument("commit", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    service.reset(sender(ctx), StringArgumentType.getString(ctx, "commit"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("revert")
                        .requires(req("minecicd.revert"))
                        .then(Commands.argument("commit", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    service.revert(sender(ctx), StringArgumentType.getString(ctx, "commit"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("rollback")
                        .requires(req("minecicd.rollback"))
                        .then(Commands.argument("date", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    service.rollback(sender(ctx), StringArgumentType.getString(ctx, "date"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("script")
                        .requires(req("minecicd.script"))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .suggests((ctx, builder) -> {
                                    for (final String s : service.scriptNames()) {
                                        builder.suggest(s);
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    service.script(sender(ctx), StringArgumentType.getString(ctx, "name"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("resolve")
                        .requires(req("minecicd.resolve"))
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    builder.suggest("merge-abort");
                                    builder.suggest("repo-reset");
                                    builder.suggest("reset-local-changes");
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    service.resolve(sender(ctx), StringArgumentType.getString(ctx, "mode"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("log")
                        .requires(req("minecicd.log"))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> logPage(sender(ctx), IntegerArgumentType.getInteger(ctx, "page"))))
                        .then(Commands.argument("commit", StringArgumentType.greedyString())
                                .executes(ctx -> showCommit(sender(ctx), StringArgumentType.getString(ctx, "commit"))))
                        .executes(ctx -> logPage(sender(ctx), 1)))
                .then(Commands.literal("status")
                        .requires(req("minecicd.status"))
                        .executes(ctx -> {
                            printStatus(sender(ctx));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("audit")
                        .requires(req("minecicd.audit"))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> auditPage(sender(ctx), IntegerArgumentType.getInteger(ctx, "page"))))
                        .executes(ctx -> auditPage(sender(ctx), 1)))
                .then(Commands.literal("analytics")
                        .requires(req("minecicd.analytics"))
                        .then(Commands.literal("reset")
                                .executes(ctx -> {
                                    service.analyticsReset(sender(ctx));
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .executes(ctx -> {
                            printAnalytics(sender(ctx));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("confirm")
                        .requires(req("minecicd.confirm"))
                        .then(Commands.argument("approvalId", StringArgumentType.word())
                                .executes(ctx -> {
                                    final String id = StringArgumentType.getString(ctx, "approvalId");
                                    service.confirm(sender(ctx), id).thenAccept(ok -> {
                                        if (ok) {
                                            messages.send(sender(ctx), "approval-confirm-ok", Map.of("id", id));
                                        } else {
                                            messages.send(sender(ctx), "approval-not-found", Map.of("id", id));
                                        }
                                    });
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .executes(ctx -> {
                            messages.send(sender(ctx), "approval-usage", Map.of("label", "minecicd"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("cancel")
                        .requires(req("minecicd.confirm"))
                        .then(Commands.argument("approvalId", StringArgumentType.word())
                                .executes(ctx -> {
                                    final String id = StringArgumentType.getString(ctx, "approvalId");
                                    service.cancel(sender(ctx), id).thenAccept(ok -> {
                                        if (ok) {
                                            messages.send(sender(ctx), "approval-cancel-ok", Map.of("id", id));
                                        } else {
                                            messages.send(sender(ctx), "approval-not-found", Map.of("id", id));
                                        }
                                    });
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .executes(ctx -> {
                            messages.send(sender(ctx), "approval-usage", Map.of("label", "minecicd"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("diff")
                        .requires(req("minecicd.diff"))
                        .then(Commands.literal("local")
                                .executes(ctx -> {
                                    printDiff(sender(ctx), false);
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .then(Commands.literal("remote")
                                .executes(ctx -> {
                                    printDiff(sender(ctx), true);
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("reload")
                        .requires(req("minecicd.reload"))
                        .executes(ctx -> {
                            service.reload();
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("help")
                        .requires(req("minecicd.help"))
                        .executes(ctx -> {
                            messages.sendList(sender(ctx), "help", Map.of("label", "minecicd"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .executes(ctx -> {
                    messages.sendList(sender(ctx), "help", Map.of("label", "minecicd"));
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    private static CommandSender sender(final com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getSender();
    }

    private Predicate<CommandSourceStack> req(final String permission) {
        return source -> {
            final CommandSender sender = source.getSender();
            return sender != null && (sender.hasPermission(permission) || sender.hasPermission("minecicd.*"));
        };
    }

    private int logPage(final CommandSender sender, final int page) {
        service.log(sender, page).thenAccept(p -> {
            if (p == null || p.entries().isEmpty()) {
                return;
            }
            messages.sendRaw(sender, messages.get("log-list-header", Map.of(
                    "page", String.valueOf(p.page()),
                    "maxPage", String.valueOf(p.maxPage()))));
            for (final Results.LogEntry entry : p.entries()) {
                messages.sendRaw(sender, messages.get("log-list-line", Map.of(
                        "date", entry.date(), "revision", entry.revision(),
                        "author", entry.author(), "message", Messages.escape(entry.message()))));
            }
            messages.sendRaw(sender, messages.get("log-list-end", Map.of()));
        });
        return Command.SINGLE_SUCCESS;
    }

    private int showCommit(final CommandSender sender, final String ref) {
        service.commit(sender, ref).thenAccept(entry -> {
            if (entry == null) {
                messages.send(sender, "log-invalid-commit");
                return;
            }
            messages.sendList(sender,
                    "log-single-commit", Map.of(
                            "revision", entry.revision(), "author", entry.author(),
                            "date", entry.date(), "message", Messages.escape(entry.message()),
                            "changes", String.join(", ", entry.changes())));
        });
        return Command.SINGLE_SUCCESS;
    }

    private int printStatus(final CommandSender sender) {
        service.status(sender).thenAccept(s -> {
            if (s == null) {
                return;
            }
            messages.sendList(sender, "status", Map.of(
                    "branch", s.branch(), "remote", s.remote(),
                    "control-status", String.valueOf(service.controlActive()),
                    "control-address", service.controlAddress(),
                    "local-changes", String.valueOf(s.localChanges()),
                    "remote-changes", String.valueOf(s.remoteChanges()),
                    "approvals-pending", String.valueOf(service.pendingApprovals())));
        });
        return Command.SINGLE_SUCCESS;
    }

    private int auditPage(final CommandSender sender, final int page) {
        service.audit(sender, page).thenAccept(entries -> {
            if (entries == null || entries.isEmpty()) {
                messages.send(sender, "audit-empty", Map.of());
                return;
            }
            for (final AuditLogger.Entry entry : entries) {
                messages.sendRaw(sender, messages.get("audit-line", Map.of(
                        "when", entry.iso(), "actor", Messages.escape(entry.actor()),
                        "source", Messages.escape(entry.source().toString()),
                        "action", Messages.escape(entry.action().toString()),
                        "outcome", Messages.escape(entry.outcome()),
                        "detail", Messages.escape(entry.message()))));
            }
            messages.sendRaw(sender, messages.get("audit-end", Map.of("page", String.valueOf(page))));
        });
        return Command.SINGLE_SUCCESS;
    }

    private int printAnalytics(final CommandSender sender) {
        service.analytics(sender).thenAccept(summary -> {
            if (summary == null) {
                messages.send(sender, "analytics-empty", Map.of());
                return;
            }
            messages.sendList(sender, "analytics", Map.of(
                    "started", String.valueOf(summary.deploys()),
                    "completed", String.valueOf(summary.successes()),
                    "failed", String.valueOf(summary.failures()),
                    "rolledBack", String.valueOf(summary.rollbacks()),
                    "deploys", String.valueOf(summary.deploys()),
                    "successRate", String.valueOf(summary.successRatePercent()),
                    "avgMillis", String.valueOf(summary.avgDurationMillis())));
        });
        return Command.SINGLE_SUCCESS;
    }

    private int printDiff(final CommandSender sender, final boolean remote) {
        service.diff(sender, remote).thenAccept(changes -> {
            messages.sendRaw(sender, messages.get(remote ? "diff-remote-header" : "diff-local-header", Map.of()));
            if (changes == null || changes.isEmpty()) {
                messages.sendRaw(sender, messages.get("diff-no-changes", Map.of()));
            } else {
                for (final String change : changes) {
                    messages.sendRaw(sender, messages.get("diff-line", Map.of("change", Messages.escape(change))));
                }
            }
            messages.sendRaw(sender, messages.get("diff-end", Map.of()));
        });
        return Command.SINGLE_SUCCESS;
    }
}