package com.psiog.perfinsight.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** Small helpers for reading loosely-typed JSON attribute maps. */
public final class JsonMaps {
    private JsonMaps() {}

    public static Double num(Map<String, Object> m, String key) {
        if (m == null) return null;
        return toDouble(m.get(key));
    }

    public static Double toDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try {
            String s = v.toString().trim();
            return s.isEmpty() ? null : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String str(Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    public static boolean bool(Map<String, Object> m, String key) {
        if (m == null) return false;
        Object v = m.get(key);
        if (v instanceof Boolean b) return b;
        return v != null && Boolean.parseBoolean(v.toString());
    }

    public static Instant toInstant(Object v) {
        if (v == null) return null;
        if (v instanceof Instant i) return i;
        if (v instanceof Number n) {
            long l = n.longValue();
            return l > 10_000_000_000L ? Instant.ofEpochMilli(l) : Instant.ofEpochSecond(l);
        }
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        try { return Instant.parse(s); } catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(s).toInstant(); } catch (DateTimeParseException ignored) { }
        try {
            // Jira style: 2026-06-03T10:15:30.000+0530
            return OffsetDateTime.parse(s, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")).toInstant();
        } catch (DateTimeParseException ignored) { }
        try { return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s).atStartOfDay().toInstant(ZoneOffset.UTC); }
        catch (DateTimeParseException ignored) { }
        // common export formats: Jira CSV "03/Jun/26 10:15 AM", ADO/Excel "6/3/2026 10:15 AM", "03-06-2026"
        for (String p : new String[]{"dd/MMM/yy h:mm a", "M/d/yyyy h:mm a", "M/d/yyyy H:mm", "dd-MM-yyyy HH:mm", "M/d/yyyy", "dd-MM-yyyy", "dd/MM/yyyy"}) {
            try {
                var f = new java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p)
                        .toFormatter(java.util.Locale.ENGLISH);
                var parsed = f.parseBest(s, java.time.LocalDateTime::from, LocalDate::from);
                if (parsed instanceof java.time.LocalDateTime ldt) return ldt.toInstant(ZoneOffset.UTC);
                if (parsed instanceof LocalDate ld) return ld.atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) { }
        }
        return null;
    }
}
