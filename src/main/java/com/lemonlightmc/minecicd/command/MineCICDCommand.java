package com.lemonlightmc.minecicd.command;

import com.lemonlightmc.minecicd.api.MineCICDApi;
import com.lemonlightmc.minecicd.data.Action;
import com.lemonlightmc.minecicd.data.Actor;
import com.lemonlightmc.minecicd.data.Results;
import com.lemonlightmc.minecicd.services.AnalyticsService;
import com.lemonlightmc.minecicd.services.AuditService;
import com.lemonlightmc.minecicd.services.AuditService.Entry;
import com.lemonlightmc.minecicd.util.Utils;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class MineCICDCommand {

    public MineCICDCommand() {
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("minecicd")
                .then(Commands.literal("init")
                        .requires(req("minecicd.init"))
                        .executes(ctx -> {
                            MineCICDApi.cicdService().init(actor(ctx));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("deinit")
                        .requires(req("minecicd.deinit"))
                        .executes(ctx -> {
                            MineCICDApi.cicdService().deinit(actor(ctx));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("pull")
                        .requires(req("minecicd.pull"))
                        .then(Commands.literal("force")
                                .executes(ctx -> {
                                    MineCICDApi.cicdService().pull(actor(ctx), true);
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .executes(ctx -> {
                            MineCICDApi.cicdService().pull(actor(ctx), false);
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("push")
                        .requires(req("minecicd.push"))
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    MineCICDApi.cicdService().push(actor(ctx),
                                            StringArgumentType.getString(ctx, "message"));
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .executes(ctx -> {
                            MineCICDApi.messages().send(actor(ctx), "push-usage", Map.of("label", "minecicd"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("add")
                        .requires(req("minecicd.add"))
                        .then(Commands.argument("path", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    MineCICDApi.cicdService().add(actor(ctx),
                                            StringArgumentType.getString(ctx, "path"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("remove")
                        .requires(req("minecicd.remove"))
                        .then(Commands.argument("path", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    MineCICDApi.cicdService().remove(actor(ctx),
                                            StringArgumentType.getString(ctx, "path"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("reset")
                        .requires(req("minecicd.reset"))
                        .then(Commands.argument("commit", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    MineCICDApi.cicdService().reset(actor(ctx),
                                            StringArgumentType.getString(ctx, "commit"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("revert")
                        .requires(req("minecicd.revert"))
                        .then(Commands.argument("commit", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    MineCICDApi.cicdService().revert(actor(ctx),
                                            StringArgumentType.getString(ctx, "commit"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("rollback")
                        .requires(req("minecicd.rollback"))
                        .then(Commands.argument("date", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    MineCICDApi.cicdService().rollback(actor(ctx),
                                            StringArgumentType.getString(ctx, "date"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("script")
                        .requires(req("minecicd.script"))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .suggests((ctx, builder) -> {
                                    for (final String s : MineCICDApi.scriptService().listScripts()) {
                                        builder.suggest(s);
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    MineCICDApi.scriptService().run(StringArgumentType.getString(ctx, "name"),
                                            actor(ctx), null);
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
                                    MineCICDApi.cicdService().resolve(actor(ctx),
                                            StringArgumentType.getString(ctx, "mode"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("log")
                        .requires(req("minecicd.log"))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> logPage(actor(ctx), IntegerArgumentType.getInteger(ctx, "page"))))
                        .then(Commands.argument("commit", StringArgumentType.greedyString())
                                .executes(ctx -> showCommit(actor(ctx), StringArgumentType.getString(ctx, "commit"))))
                        .executes(ctx -> logPage(actor(ctx), 1)))
                .then(Commands.literal("status")
                        .requires(req("minecicd.status"))
                        .executes(ctx -> {
                            printStatus(actor(ctx));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("audit")
                        .requires(req("minecicd.audit"))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> auditPage(actor(ctx), IntegerArgumentType.getInteger(ctx, "page"))))
                        .executes(ctx -> auditPage(actor(ctx), 1)))
                .then(Commands.literal("analytics")
                        .requires(req("minecicd.analytics"))
                        .then(Commands.literal("reset")
                                .executes(ctx -> {
                                    MineCICDApi.analyticsService().reset();
                                    MineCICDApi.auditService().log(actor(ctx), Action.ANALYTICS_RESET, true, null);
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .executes(ctx -> {
                            printAnalytics(actor(ctx));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("confirm")
                        .requires(req("minecicd.confirm"))
                        .then(Commands.argument("approvalId", StringArgumentType.word())
                                .executes(ctx -> {
                                    final String id = StringArgumentType.getString(ctx, "approvalId");
                                    final boolean result = MineCICDApi.approvalService().confirm(actor(ctx), id);
                                    if (result) {
                                        MineCICDApi.messages().send(actor(ctx), "approval-confirm-ok",
                                                Map.of("id", id));
                                    } else {
                                        MineCICDApi.messages().send(actor(ctx), "approval-not-found",
                                                Map.of("id", id));
                                    }
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .executes(ctx -> {
                            MineCICDApi.messages().send(actor(ctx), "approval-usage", Map.of("label", "minecicd"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("cancel")
                        .requires(req("minecicd.confirm"))
                        .then(Commands.argument("approvalId", StringArgumentType.word())
                                .executes(ctx -> {
                                    final String id = StringArgumentType.getString(ctx, "approvalId");
                                    final boolean result = MineCICDApi.approvalService().cancel(actor(ctx), id);
                                    if (result) {
                                        MineCICDApi.messages().send(actor(ctx), "approval-cancel-ok",
                                                Map.of("id", id));
                                    } else {
                                        MineCICDApi.messages().send(actor(ctx), "approval-not-found",
                                                Map.of("id", id));
                                    }
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .executes(ctx -> {
                            MineCICDApi.messages().send(actor(ctx), "approval-usage", Map.of("label", "minecicd"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("diff")
                        .requires(req("minecicd.diff"))
                        .then(Commands.literal("local")
                                .executes(ctx -> {
                                    printDiff(actor(ctx), false);
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .then(Commands.literal("remote")
                                .executes(ctx -> {
                                    printDiff(actor(ctx), true);
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("reload")
                        .requires(req("minecicd.reload"))
                        .executes(ctx -> {
                            MineCICDApi.cicdService().reload();
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("help")
                        .requires(req("minecicd.help"))
                        .executes(ctx -> {
                            MineCICDApi.messages().sendList(actor(ctx), "help", Map.of("label", "minecicd"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .executes(ctx -> {
                    MineCICDApi.messages().sendList(actor(ctx), "help", Map.of("label", "minecicd"));
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    private static Actor actor(final com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        return Actor.fromSender(ctx.getSource().getSender());
    }

    private Predicate<CommandSourceStack> req(final String permission) {
        return source -> {
            final CommandSender sender = source.getSender();
            return sender != null && (sender.hasPermission(permission) || sender.hasPermission("minecicd.*"));
        };
    }

    private int logPage(final Actor actor, final int page) {
        MineCICDApi.cicdService().log(actor, page).thenAccept(p -> {
            if (p == null || p.entries().isEmpty()) {
                return;
            }
            MineCICDApi.messages().sendRaw(
                    actor, MineCICDApi.messages().get("log-list-header", Map.of(
                            "page", String.valueOf(p.page()),
                            "maxPage", String.valueOf(p.maxPage()))));
            for (final Results.LogEntry entry : p.entries()) {
                MineCICDApi.messages().sendRaw(
                        actor, MineCICDApi.messages().get("log-list-line", Map.of(
                                "date", entry.date(), "revision", entry.revision(),
                                "author", entry.author(), "message", Utils.escape(entry.message()))));
            }
            MineCICDApi.messages().sendRaw(actor, MineCICDApi.messages().get("log-list-end", Map.of()));
        });
        return Command.SINGLE_SUCCESS;
    }

    private int showCommit(final Actor actor, final String ref) {
        MineCICDApi.cicdService().commit(actor, ref).thenAccept(entry -> {
            if (entry == null) {
                MineCICDApi.messages().send(actor, "log-invalid-commit");
                return;
            }
            MineCICDApi.messages().sendList(
                    actor,
                    "log-single-commit", Map.of(
                            "revision", entry.revision(), "author", entry.author(),
                            "date", entry.date(), "message", Utils.escape(entry.message()),
                            "changes", String.join(", ", entry.changes())));
        });
        return Command.SINGLE_SUCCESS;
    }

    private int printStatus(final Actor actor) {
        MineCICDApi.cicdService().status(actor).thenAccept(s -> {
            if (s == null) {
                return;
            }
            MineCICDApi.messages().sendList(
                    actor, "status", Map.of(
                            "branch", s.branch(), "remote", s.remote(),
                            "control-status", String.valueOf(MineCICDApi.controlService().isControlActive()),
                            "control-address", MineCICDApi.controlService().controlAddress(),
                            "local-changes", String.valueOf(s.localChanges()),
                            "remote-changes", String.valueOf(s.remoteChanges()),
                            "approvals-pending", String.valueOf(MineCICDApi.approvalService().pendingCount())));
        });
        return Command.SINGLE_SUCCESS;
    }

    private int auditPage(final Actor actor, final int page) {
        final List<Entry> entries = MineCICDApi.auditService().read(page, 15);
        if (entries == null || entries.isEmpty()) {
            MineCICDApi.messages().send(actor, "audit-empty", Map.of());
            return Command.SINGLE_SUCCESS;
        }
        for (final AuditService.Entry entry : entries) {
            MineCICDApi.messages().sendRaw(
                    actor, MineCICDApi.messages().get("audit-line", Map.of(
                            "when", entry.iso(), "actor", Utils.escape(entry.actor().getName()),
                            "action", Utils.escape(entry.action().toString()),
                            "outcome", Utils.escape(entry.outcome()),
                            "detail", Utils.escape(entry.message()))));
        }
        MineCICDApi.messages().sendRaw(
                actor,
                MineCICDApi.messages().get("audit-end", Map.of("page", String.valueOf(page))));
        return Command.SINGLE_SUCCESS;
    }

    private int printAnalytics(final Actor actor) {
        final AnalyticsService.Summary summary = MineCICDApi.analyticsService().summary();

        if (summary == null) {
            MineCICDApi.messages().send(actor, "analytics-empty", Map.of());
            return Command.SINGLE_SUCCESS;
        }
        MineCICDApi.messages().sendList(
                actor, "analytics", Map.of(
                        "started", String.valueOf(summary.deploys()),
                        "completed", String.valueOf(summary.successes()),
                        "failed", String.valueOf(summary.failures()),
                        "rolledBack", String.valueOf(summary.rollbacks()),
                        "deploys", String.valueOf(summary.deploys()),
                        "successRate", String.valueOf(summary.successRatePercent()),
                        "avgMillis", String.valueOf(summary.avgDurationMillis())));
        return Command.SINGLE_SUCCESS;
    }

    private int printDiff(final Actor actor, final boolean remote) {
        MineCICDApi.cicdService().diff(actor, remote).thenAccept(changes -> {
            MineCICDApi.messages().sendRaw(actor,
                    MineCICDApi.messages().get(remote ? "diff-remote-header" : "diff-local-header", Map.of()));
            if (changes == null || changes.isEmpty()) {
                MineCICDApi.messages().sendRaw(actor, MineCICDApi.messages().get("diff-no-changes", Map.of()));
            } else {
                for (final String change : changes) {
                    MineCICDApi.messages().sendRaw(actor,
                            MineCICDApi.messages().get("diff-line", Map.of("change", Utils.escape(change))));
                }
            }
            MineCICDApi.messages().sendRaw(actor, MineCICDApi.messages().get("diff-end", Map.of()));
        });
        return Command.SINGLE_SUCCESS;
    }
}