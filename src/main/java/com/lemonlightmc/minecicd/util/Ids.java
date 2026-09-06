package com.lemonlightmc.minecicd.util;

import java.util.UUID;
import java.util.regex.Pattern;

public final class Ids {

    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private Ids() {
    }

    public static String newRequestId() {
        return UUID.randomUUID().toString();
    }

    public static boolean isValidRequestId(final String id) {
        return id != null && REQUEST_ID.matcher(id).matches();
    }
}