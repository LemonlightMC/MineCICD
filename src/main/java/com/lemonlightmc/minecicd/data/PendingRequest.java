package com.lemonlightmc.minecicd.data;

import com.lemonlightmc.minecicd.git.CommitActions.CommitAction;
import com.lemonlightmc.minecicd.git.CommitActions.ActionType;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class PendingRequest {

    public enum Status {
        RUNNING,
        COMPLETED,
        FAILED,
        INTERRUPTED
    }

    private final String requestId;
    private final List<CommitAction> actions;
    private final int total;
    private int index;
    private Status status;
    private String error;
    private final String branch;

    public PendingRequest(final String requestId, final List<CommitAction> actions, final String branch) {
        this(requestId, actions, 0, Status.RUNNING, null, branch);
    }

    private PendingRequest(final String requestId, final List<CommitAction> actions, final int index,
            final Status status,
            final String error,
            final String branch) {
        this.requestId = requestId;
        this.actions = new ArrayList<>(actions);
        this.total = actions.size();
        this.index = index;
        this.status = status;
        this.error = error;
        this.branch = branch;
    }

    private PendingRequest(final String requestId, final List<CommitAction> actions, final int index,
            final String status,
            final String error,
            final String branch) {
        this.requestId = requestId;
        this.actions = new ArrayList<>(actions);
        this.total = actions.size();
        this.index = index;
        this.status = parseStatus(status);
        this.error = error;
        this.branch = branch;
    }

    private static Status parseStatus(final String status) {
        if (status == null || status.isBlank()) {
            return Status.RUNNING;
        }
        try {
            return Status.valueOf(status);
        } catch (final IllegalArgumentException e) {
            return Status.RUNNING;
        }
    }

    public String requestId() {
        return requestId;
    }

    public List<CommitAction> actions() {
        return actions;
    }

    public int total() {
        return total;
    }

    public int index() {
        return index;
    }

    public Status status() {
        return status;
    }

    public String error() {
        return error;
    }

    public String branch() {
        return branch;
    }

    public boolean hasRemaining() {
        return index < total;
    }

    public CommitAction current() {
        return actions.get(index);
    }

    public void advance() {
        index++;
    }

    public void completed() {
        index = total;
        status = Status.COMPLETED;
    }

    public void failed(final String message) {
        status = Status.FAILED;
        error = message;
    }

    public void interrupted(final String message) {
        status = Status.INTERRUPTED;
        error = message;
    }

    public JSONObject toJson() {
        final JSONObject json = new JSONObject();
        json.put("requestId", requestId);
        json.put("branch", branch == null ? "" : branch);
        final JSONArray array = new JSONArray();
        for (final CommitAction action : actions) {
            final JSONObject a = new JSONObject();
            a.put("type", action.type().name());
            a.put("argument", action.argument() == null ? "" : action.argument());
            array.put(a);
        }
        json.put("actions", array);
        json.put("index", index);
        json.put("status", status.name());
        json.put("error", error == null ? "" : error);
        return json;
    }

    public static PendingRequest fromJson(final String json) {
        return fromJson(new JSONObject(json));
    }

    public static PendingRequest fromJson(final JSONObject json) {
        final JSONArray array = json.getJSONArray("actions");
        final List<CommitAction> actions = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            final JSONObject obj = array.getJSONObject(i);
            final String args = obj.optString("argument", null);
            actions.add(new CommitAction(ActionType.valueOf(obj.getString("type")),
                    args != null && args.isEmpty() ? null : args));
        }

        return new PendingRequest(
                json.getString("requestId"),
                actions,
                json.optInt("index", 0),
                json.optString("status", Status.RUNNING.name()),
                json.optString("error", ""),
                json.optString("branch", ""));
    }
}