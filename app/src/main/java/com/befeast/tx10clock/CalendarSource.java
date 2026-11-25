package com.befeast.tx10clock;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The calendar the sleep plan reads: one read-only iCal feed (for Google Calendar, the calendar's
 * "secret address in iCal format") plus the explicit words that make an event a shift. Everything
 * else in the feed is a meeting; nothing becomes a shift by guess. The file lives beside
 * {@code config.json} as {@code calendar-source.json} and is never committed, logged or shown:
 * the address itself is a credential.
 *
 * <pre>{"schemaVersion": 1, "icsUrl": "https://…/basic.ics", "shiftKeywords": ["shift"]}</pre>
 */
final class CalendarSource {

    static final String FILE = "calendar-source.json";
    private static final int MAX_BYTES = 8 * 1024;

    final URI url;
    final List<String> shiftKeywords;

    private CalendarSource(URI url, List<String> shiftKeywords) {
        this.url = url;
        this.shiftKeywords = shiftKeywords;
    }

    /** The configured source, or null when there is none; a broken file is reported, never guessed. */
    static CalendarSource load(File dir) throws ConfigException {
        File file = new File(dir, FILE);
        if (!file.isFile()) {
            return null;
        }
        if (file.length() > MAX_BYTES) {
            throw new ConfigException(ConfigException.Reason.OVERSIZED, "calendar source file too large");
        }
        String text;
        try {
            text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException(ConfigException.Reason.MALFORMED, "calendar source file unreadable");
        }
        return parse(text);
    }

    @SuppressWarnings("unchecked")
    static CalendarSource parse(String text) throws ConfigException {
        Object root = Json.parse(text);
        if (!(root instanceof Map)) {
            throw new ConfigException(ConfigException.Reason.NOT_OBJECT, "calendar source must be an object");
        }
        Map<String, Object> map = (Map<String, Object>) root;
        for (String key : map.keySet()) {
            if (!key.equals("schemaVersion") && !key.equals("icsUrl") && !key.equals("shiftKeywords")) {
                throw new ConfigException(ConfigException.Reason.UNKNOWN_KEY, "unknown field in calendar source");
            }
        }
        Object version = map.get("schemaVersion");
        if (!(version instanceof Long) || (Long) version != 1L) {
            throw new ConfigException(ConfigException.Reason.OUT_OF_RANGE, "calendar source schemaVersion must be 1");
        }
        Object rawUrl = map.get("icsUrl");
        if (!(rawUrl instanceof String)) {
            throw new ConfigException(ConfigException.Reason.WRONG_TYPE, "calendar source needs icsUrl");
        }
        URI url;
        try {
            url = new URI((String) rawUrl);
        } catch (URISyntaxException e) {
            throw new ConfigException(ConfigException.Reason.OUT_OF_RANGE, "calendar source icsUrl is not a URL");
        }
        if (!"https".equals(url.getScheme()) || url.getHost() == null || url.getRawUserInfo() != null
                || url.getRawFragment() != null) {
            throw new ConfigException(ConfigException.Reason.OUT_OF_RANGE, "calendar source icsUrl must be https without credentials");
        }
        Object rawKeywords = map.get("shiftKeywords");
        if (!(rawKeywords instanceof List) || ((List<Object>) rawKeywords).isEmpty()) {
            throw new ConfigException(ConfigException.Reason.WRONG_TYPE, "calendar source needs shiftKeywords: the words that make an event a shift");
        }
        List<String> keywords = new ArrayList<>();
        for (Object k : (List<Object>) rawKeywords) {
            if (!(k instanceof String) || ((String) k).trim().isEmpty() || ((String) k).length() > 40) {
                throw new ConfigException(ConfigException.Reason.OUT_OF_RANGE, "calendar source shiftKeywords must be short non-empty strings");
            }
            keywords.add(((String) k).trim().toLowerCase(Locale.ROOT));
        }
        return new CalendarSource(url, Collections.unmodifiableList(keywords));
    }

    /**
     * Identifies this source in the cache without storing its address: a copy fetched for another
     * calendar or other shift words is never shown as this one.
     */
    String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(url.toString().getBytes(StandardCharsets.UTF_8));
            for (String k : shiftKeywords) {
                digest.update((byte) 0);
                digest.update(k.getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder hex = new StringBuilder();
            byte[] hash = digest.digest();
            for (int i = 0; i < 12; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Explicit rule: a title containing one of the shift words is a shift; anything else is a meeting. */
    SleepEvent.Kind kindOf(String title) {
        String lower = title.toLowerCase(Locale.ROOT);
        for (String k : shiftKeywords) {
            if (lower.contains(k)) {
                return SleepEvent.Kind.SHIFT;
            }
        }
        return SleepEvent.Kind.MEETING;
    }
}
