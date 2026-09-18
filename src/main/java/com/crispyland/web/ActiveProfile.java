package com.crispyland.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Which profile the visitor is currently being answered as.
 * <p>
 * Its own cookie rather than part of the conversation id, because the two answer different
 * questions and change at different times: the conversation id is <em>who</em> is asking and
 * outlives everything, while this is <em>how they want answering right now</em> and is meant to be
 * switched back and forth. Folding the profile into the identity cookie would make changing your
 * mind about formatting look like becoming a different person, taking the whole transcript and
 * every remembered fact with it.
 * <p>
 * That separation is what makes the demo work at all: switch profile, keep the same memory, and
 * the difference in the answer is the profile's doing and nothing else's.
 */
@Component
public class ActiveProfile {

    static final String COOKIE_NAME = "agent_profile";

    private static final int THIRTY_DAYS_IN_SECONDS = 30 * 24 * 60 * 60;

    /**
     * Profile ids become filesystem lookups, so the value is constrained to something that
     * cannot traverse a path however the cookie was set. {@code Profiles} resolves ids against a
     * listing rather than by building a path, so this is the second line of defence rather than
     * the only one — but a caller-controlled string heading towards a file gets checked anyway.
     */
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    /** The profile id in force, or null when the visitor has not chosen one. */
    public String current(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
                .filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(ActiveProfile::isSafe)
                .findFirst()
                .orElse(null);
    }

    /** Switches profile, or clears it when {@code id} is blank or malformed. */
    public void switchTo(String id, HttpServletResponse response) {
        String wanted = (id == null) ? null : id.strip().toLowerCase(Locale.ROOT);
        if (wanted == null || !isSafe(wanted)) {
            Cookie cleared = cookie("");
            cleared.setMaxAge(0);
            response.addCookie(cleared);
            return;
        }
        response.addCookie(cookie(wanted));
    }

    private static boolean isSafe(String value) {
        return value != null && SAFE_ID.matcher(value).matches();
    }

    private static Cookie cookie(String value) {
        Cookie cookie = new Cookie(COOKIE_NAME, value);
        cookie.setPath("/");
        cookie.setMaxAge(THIRTY_DAYS_IN_SECONDS);
        cookie.setHttpOnly(true);
        cookie.setAttribute("SameSite", "Lax");
        return cookie;
    }
}
