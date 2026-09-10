package com.lemonlightmc.minecicd.schedule;

import com.lemonlightmc.minecicd.MineCICDConfig.QuietHours;
import com.lemonlightmc.minecicd.schedule.AutoPullScheduler.InQuietHours;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoPullInQuietHoursTest {

    @Test
    void disabledAlwaysFalse() {
        final QuietHours disabled = new QuietHours(false, "03:00", "07:00");
        assertFalse(InQuietHours.check(disabled, LocalTime.of(5, 0)));
    }

    @Test
    void insideWindowIsQuiet() {
        final QuietHours qh = new QuietHours(true, "03:00", "07:00");
        assertTrue(InQuietHours.check(qh, LocalTime.of(3, 0)));
        assertTrue(InQuietHours.check(qh, LocalTime.of(6, 59)));
    }

    @Test
    void outsideWindowIsNotQuiet() {
        final QuietHours qh = new QuietHours(true, "03:00", "07:00");
        assertFalse(InQuietHours.check(qh, LocalTime.of(2, 59)));
        assertFalse(InQuietHours.check(qh, LocalTime.of(7, 0)));
    }

    @Test
    void windowWrapsMidnight() {
        final QuietHours qh = new QuietHours(true, "22:00", "02:00");
        assertTrue(InQuietHours.check(qh, LocalTime.of(23, 30)));
        assertTrue(InQuietHours.check(qh, LocalTime.of(1, 0)));
        assertFalse(InQuietHours.check(qh, LocalTime.of(12, 0)));
        assertFalse(InQuietHours.check(qh, LocalTime.of(2, 0)));
    }

    @Test
    void malformedTimesFallBackToNotQuiet() {
        final QuietHours qh = new QuietHours(true, "notatime", "02:00");
        assertFalse(InQuietHours.check(qh, LocalTime.of(23, 30)));
    }
}