package com.crispyland.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Decides which conversation the caller is continuing.
 * <p>
 * Deliberately not the servlet session id: a session id is designed to rotate — containers
 * expire it and OWASP requires regenerating it on privilege change — so keying stored data on
 * it means correct security behaviour silently orphans the history. Restarting the JVM would
 * do exactly that, which is the one thing this feature exists to prevent.
 * <p>
 * Instead this is the standard anonymous-principal pattern: a long-lived, server-minted id in
 * its own cookie. Identity stays here in the web layer; the agent still owns the messages.
 */
@Component
public class ConversationIdResolver {

    static final String COOKIE_NAME = "agent_conv";

    private static final int THIRTY_DAYS_IN_SECONDS = 30 * 24 * 60 * 60;
    private static final Pattern UUID_FORMAT =
            Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");

    /**
     * Returns the caller's conversation id, minting and setting one if the cookie is absent
     * or does not hold a well-formed UUID.
     */
    public String resolve(HttpServletRequest request, HttpServletResponse response) {
        String existing = readCookie(request);
        if (existing != null) {
            return existing;
        }

        String minted = UUID.randomUUID().toString();
        response.addCookie(cookie(minted));
        return minted;
    }

    /**
     * Issues a brand-new identity, so the caller comes back as a stranger.
     * <p>
     * This is the last step of forgetting someone, never the first. Rotate before the stored data
     * is deleted and the delete misses — it would go looking under the new id — leaving a record
     * that still holds everything the visitor asked to have removed and no longer has any id
     * pointing at it. Unreachable is not deleted; it is deleted's worst impersonation.
     */
    public String rotate(HttpServletResponse response) {
        String minted = UUID.randomUUID().toString();
        response.addCookie(cookie(minted));
        return minted;
    }

    private static Cookie cookie(String value) {
        Cookie cookie = new Cookie(COOKIE_NAME, value);
        cookie.setPath("/");
        cookie.setMaxAge(THIRTY_DAYS_IN_SECONDS);
        cookie.setHttpOnly(true);
        cookie.setAttribute("SameSite", "Lax");
        return cookie;
    }

    /**
     * The cookie value is caller-controlled and ends up as a key in a file we write, so it is
     * only trusted once it is confirmed to be a UUID. Anything else is treated as a new visitor.
     */
    private static String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
                .filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && UUID_FORMAT.matcher(value).matches())
                .findFirst()
                .orElse(null);
    }
}
