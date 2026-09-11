package com.lemonlightmc.minecicd.data;

import org.eclipse.jgit.revwalk.RevCommit;

import java.util.List;

public final class Results {

    private Results() {
    }

    public record PullResult(List<RevCommit> commits, boolean initialized) {
        public boolean changed() {
            return !commits.isEmpty();
        }
    }

    public record PushResult(int commitsPushed, boolean hadChanges) {
    }

    public record LogEntry(String revision, String author, String date, String message, List<String> changes) {
    }

    public record LogPage(int page, int maxPage, List<LogEntry> entries) {
        public boolean valid() {
            return page >= 1 && page <= maxPage;
        }
    }

    public record StatusInfo(String branch, String remote, int localChanges, int remoteChanges) {
    }

    public enum DeployResult {
        SUCCESS,
        NO_CHANGES,
        FAILED,
        ROLLBACK;

    }
}