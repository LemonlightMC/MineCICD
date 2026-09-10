package com.lemonlightmc.minecicd.errors;

import com.lemonlightmc.minecicd.exceptions.GitException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorCatalogTest {

    @Test
    void pullConflictSuggestsResolve() {
        assertEquals("Run '/minecicd resolve merge-abort' and try '/minecicd pull' again",
                ErrorCatalog.suggest("Merge conflict in config.yml"));
    }

    @Test
    void authFailureSuggestsTokenScope() {
        final String suggestion = ErrorCatalog.suggest("Push failed: not authorized");
        assertTrue(suggestion.contains("contents"), suggestion);
    }

    @Test
    void unpushedChangesSuggestsForceOrPush() {
        final String suggestion = ErrorCatalog.suggest(new GitException.PullAborted("unpushed changes"));
        assertTrue(suggestion.contains("pull force"), suggestion);
    }

    @Test
    void unknownErrorHasNoSuggestion() {
        assertEquals("", ErrorCatalog.suggest("some obscure failure"));
        assertEquals("", ErrorCatalog.suggest(new IllegalStateException("odd")));
    }

    @Test
    void nullMessageHasNoSuggestion() {
        final Throwable t = new RuntimeException();
        assertEquals("", ErrorCatalog.suggest(t));
        assertFalse(ErrorCatalog.suggest((String) null).contains("secret"));
    }
}