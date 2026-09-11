package com.lemonlightmc.minecicd.git;

import org.eclipse.jgit.revwalk.RevCommit;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CommitActions {

    private static final Pattern CICD_LINE = Pattern.compile("(?im)^\\s*CICD\\s+(.+?)\\s*$");

    public enum ActionType {
        PULL,
        PUSH,
        RESTART,
        GLOBAL_RELOAD,
        RELOAD_PLUGIN,
        COMMAND,
        SCRIPT
    }

    public record CommitAction(ActionType type, String argument) {
        @Override
        public String toString() {
            return switch (type) {
                case PULL -> "pull";
                case PUSH -> argument == null ? "push" : "push:" + argument;
                case RESTART -> "restart";
                case GLOBAL_RELOAD -> "global-reload";
                case RELOAD_PLUGIN -> "reload:" + argument;
                case COMMAND -> "command:" + argument;
                case SCRIPT -> "script:" + argument;
            };
        }
    }

    public static class ParseException extends RuntimeException {
        public ParseException(final String message) {
            super(message);
        }
    }

    private CommitActions() {
    }

    public static List<CommitAction> parseCommitMessage(final RevCommit commit) {
        return parseCommitMessage(commit.getFullMessage());
    }

    public static List<CommitAction> parseCommitMessage(final String message) {
        final List<CommitAction> actions = new ArrayList<>();
        if (message == null) {
            return actions;
        }
        final Matcher matcher = CICD_LINE.matcher(message);
        while (matcher.find()) {
            final CommitAction action = parseItem(matcher.group(1), false);
            if (action != null) {
                actions.add(action);
            }
        }
        return actions;
    }

    public static CommitAction parseControlItem(final String raw) {
        final CommitAction action = parseItem(raw, true);
        if (action == null) {
            throw new ParseException("Unknown action: " + raw);
        }
        return action;
    }

    private static CommitAction parseItem(final String raw, final boolean allowPullPush) {
        final String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (allowPullPush) {
            if ("pull".equals(s)) {
                return new CommitAction(ActionType.PULL, null);
            }
            if ("push".equals(s)) {
                return new CommitAction(ActionType.PUSH, null);
            }
            if (s.startsWith("push:")) {
                final String message = s.substring(5).trim();
                if (message.isEmpty()) {
                    throw new ParseException("push requires a message");
                }
                return new CommitAction(ActionType.PUSH, message);
            }
        }
        if ("restart".equals(s)) {
            return new CommitAction(ActionType.RESTART, null);
        }
        if ("global-reload".equals(s)) {
            return new CommitAction(ActionType.GLOBAL_RELOAD, null);
        }
        if ("reload".equals(s)) {
            return new CommitAction(ActionType.GLOBAL_RELOAD, null);
        }
        if (s.startsWith("reload:") || s.startsWith("reload ")) {
            final String plugin = s.substring(7).trim();
            if (plugin.isEmpty()) {
                throw new ParseException("reload requires a plugin name");
            }
            return new CommitAction(ActionType.RELOAD_PLUGIN, plugin);
        }
        if (s.startsWith("run ")) {
            final String command = s.substring(4).trim();
            if (command.isEmpty()) {
                throw new ParseException("run requires a command");
            }
            return new CommitAction(ActionType.COMMAND, command);
        }
        if (s.startsWith("command:") || s.startsWith("command ")) {
            final String command = s.substring(8).trim();
            if (command.isEmpty()) {
                throw new ParseException("command requires a command");
            }
            return new CommitAction(ActionType.COMMAND, command);
        }
        if (s.startsWith("script:") || s.startsWith("script ")) {
            final String script = s.substring(7).trim();
            if (script.isEmpty()) {
                throw new ParseException("script requires a name");
            }
            return new CommitAction(ActionType.SCRIPT, script);
        }
        throw new ParseException("Unknown action: " + raw);
    }
}