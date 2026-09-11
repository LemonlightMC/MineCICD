package com.lemonlightmc.minecicd.messaging;

import org.junit.jupiter.api.Test;

import com.lemonlightmc.minecicd.services.Messages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesTest {

    @Test
    void migratesClassicColorCodes() {
        assertEquals("<green>Hello", Messages.migrateLegacy("&aHello"));
        assertEquals("<red>Err <gray>x", Messages.migrateLegacy("&cErr &7x"));
    }

    @Test
    void migratesHexColorCodes() {
        String migrated = Messages.migrateLegacy("&#ff0000Red");
        assertEquals("<color:#ff0000>Red", migrated);
    }

    @Test
    void leavesUnmatchedAmpersandAlone() {
        assertEquals("a & b", Messages.migrateLegacy("a & b"));
    }

    @Test
    void escapesPlaceholderValues() {
        assertEquals("a\\\\b", Messages.escape("a\\b"));
        assertEquals("a\\<b\\>", Messages.escape("a<b>"));
    }
}