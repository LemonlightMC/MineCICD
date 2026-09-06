package com.lemonlightmc.minecicd.http;

import com.lemonlightmc.minecicd.exceptions.ParseException;
import com.lemonlightmc.minecicd.git.CommitActions;
import com.lemonlightmc.minecicd.git.CommitActions.Action;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ControlRequest {

    private final String requestId;
    private final String branch;
    private final List<Action> actions;

    public ControlRequest(final String requestId, final String branch, final List<Action> actions) {
        this.requestId = requestId;
        this.branch = branch;
        this.actions = actions;
    }

    public String requestId() {
        return requestId;
    }

    public String branch() {
        return branch;
    }

    public List<Action> actions() {
        return actions;
    }

    public static ControlRequest parse(final String body) {
        // mitigate deep-nesting JSON bomb within 65KB
        // reject depth > 64 outside strings
        int depth = 0, maxDepth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < body.length(); i++) {
            final char c = body.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{' || c == '[') {
                depth++;
                if (depth > maxDepth)
                    maxDepth = depth;
                if (maxDepth > 64)
                    throw new ParseException("JSON too deeply nested");
            } else if (c == '}' || c == ']') {
                depth = Math.max(0, depth - 1);
            }
        }

        JSONObject json;
        try {
            json = new JSONObject(body);
        } catch (final Exception e) {
            throw new ParseException("Invalid JSON body");
        }
        if (!json.has("requestId")) {
            throw new ParseException("Missing requestId");
        }
        final String branch = json.has("branch") && !json.isNull("branch") ? json.getString("branch") : null;
        if (!json.has("actions")) {
            throw new ParseException("Missing actions");
        }
        final JSONArray array = json.getJSONArray("actions");
        final List<Action> actions = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            if (array.get(i) instanceof final String str) {
                actions.add(CommitActions.parseControlItem(str));
            } else {
                throw new ParseException("Action at index " + i + " must be a string");
            }
        }
        if (actions.isEmpty()) {
            throw new ParseException("actions must not be empty");
        }
        return new ControlRequest(json.getString("requestId"), branch, actions);
    }

}