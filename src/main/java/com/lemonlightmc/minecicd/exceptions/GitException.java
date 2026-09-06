package com.lemonlightmc.minecicd.exceptions;

public class GitException extends RuntimeException {

    public GitException(final String message) {
        super(message);
    }

    public GitException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public static class PullAborted extends GitException {
        public PullAborted(final String message) {
            super(message);
        }
    }
}