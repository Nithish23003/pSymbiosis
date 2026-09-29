package com.psiog.perfinsight.ingest.mapping;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.psiog.perfinsight.activity.ActivityType;
import com.psiog.perfinsight.common.JsonMaps;
import com.psiog.perfinsight.identity.ActorRef;
import com.psiog.perfinsight.ingest.connector.SourceRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns any tool record into canonical activities using a JSON mapping spec - so each project's field
 * mappings are configuration, not code (criterion 1).
 *
 * <pre>
 * { "activities": [ {
 *     "when":       {"kind": "issue"},                 // record kind filter (optional)
 *     "type":       "TICKET",
 *     "forEach":    "$.reviews[*]",                    // optional: one activity per array element
 *     "externalId": "gh-review-{$.id}",                // "$..." path | "=literal" | "text {path} text"
 *     "title":      "$.fields.summary",
 *     "occurredAt": ["$.fields.resolutiondate", "$.fields.updated"],   // first non-null wins
 *     "actor":      {"externalId": "...", "username": "...", "email": "...", "displayName": "..."},
 *     "attributes": {
 *        "storyPoints": {"path": "$.fields.${settings.storyPointsField|customfield_10016}", "type": "number"},
 *        "completed":   {"path": "$.fields.status.statusCategory.key", "equalsAny": ["done"]},
 *        "cycleTimeHours": {"type": "hoursBetween", "from": "$.fields.created", "to": "$.fields.resolutiondate"},
 *        "reviewers":   {"path": "$.reviews", "type": "count"} },
 *     "skipWhen":   {"path": "$.merged_at", "isNull": true}      // also: equals, in
 * } ] }
 * </pre>
 * Inside forEach, "$" is the element and "^" is the whole record. ${settings.x|default} is replaced from the tool config.
 */
@Component
@RequiredArgsConstructor
public class MappingEngine {

    private static final Pattern TEMPLATE = Pattern.compile("\\{([$^][^}]*)}");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern SETTING = Pattern.compile("\\$\\{settings\\.([A-Za-z0-9_]+)(?:\\|([^}]*))?}");

    private final Configuration jsonPathConf;

    @SuppressWarnings("unchecked")
    public List<NormalizedActivity> map(Map<String, Object> spec, SourceRecord rec, Map<String, Object> settings) {
        List<NormalizedActivity> out = new ArrayList<>();
        Object entries = spec.get("activities");
        if (!(entries instanceof List<?> list)) return out;

        DocumentContext root = JsonPath.using(jsonPathConf).parse(rec.payload());
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            Map<String, Object> e = (Map<String, Object>) substitute(raw, settings);
            if (!kindMatches(e.get("when"), rec.kind())) continue;

            List<DocumentContext> contexts = new ArrayList<>();
            Object forEach = e.get("forEach");
            if (forEach instanceof String fe) {
                Object items = read(root, root, fe);
                if (items instanceof List<?> li) for (Object item : li) contexts.add(JsonPath.using(jsonPathConf).parse(item));
            } else {
                contexts.add(root);
            }
            for (DocumentContext ctx : contexts) {
                NormalizedActivity n = mapOne(e, ctx, root);
                if (n != null) out.add(n);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private NormalizedActivity mapOne(Map<String, Object> e, DocumentContext ctx, DocumentContext root) {
        Object skip = e.get("skipWhen");
        if (skip instanceof Map<?, ?> one && matches((Map<String, Object>) one, ctx, root)) return null;
        if (skip instanceof List<?> many) {
            for (Object c : many) if (c instanceof Map<?, ?> cm && matches((Map<String, Object>) cm, ctx, root)) return null;
        }

        Object typeVal = eval(e.get("type"), ctx, root);
        if (typeVal == null) return null;
        ActivityType type;
        try { type = ActivityType.valueOf(typeVal.toString().trim().toUpperCase(Locale.ROOT).replace(' ', '_')); }
        catch (IllegalArgumentException ex) { type = ActivityType.OTHER; }
        String externalId = str(eval(e.get("externalId"), ctx, root));
        Instant occurredAt = JsonMaps.toInstant(eval(e.get("occurredAt"), ctx, root));
        if (externalId == null || occurredAt == null) return null;

        ActorRef actor = null;
        if (e.get("actor") instanceof Map<?, ?> am) {
            Map<String, Object> a = (Map<String, Object>) am;
            actor = new ActorRef(str(attribute(a.get("externalId"), ctx, root)), str(attribute(a.get("username"), ctx, root)),
                    str(attribute(a.get("email"), ctx, root)), str(attribute(a.get("displayName"), ctx, root)));
        }

        Map<String, Object> attrs = new LinkedHashMap<>();
        if (e.get("attributes") instanceof Map<?, ?> attrSpec) {
            for (Map.Entry<?, ?> en : attrSpec.entrySet()) {
                Object v = attribute(en.getValue(), ctx, root);
                if (v != null) attrs.put(String.valueOf(en.getKey()), v);
            }
        }
        return new NormalizedActivity(type, externalId, str(eval(e.get("title"), ctx, root)),
                str(eval(e.get("url"), ctx, root)), occurredAt, actor, attrs);
    }

    @SuppressWarnings("unchecked")
    private Object attribute(Object spec, DocumentContext ctx, DocumentContext root) {
        if (!(spec instanceof Map<?, ?> m)) return eval(spec, ctx, root);
        Map<String, Object> s = (Map<String, Object>) m;
        String type = s.getOrDefault("type", "auto").toString();
        if ("hoursBetween".equals(type)) {
            Instant from = JsonMaps.toInstant(eval(s.get("from"), ctx, root));
            Instant to = JsonMaps.toInstant(eval(s.get("to"), ctx, root));
            if (from == null || to == null || to.isBefore(from)) return null;
            return Math.round(Duration.between(from, to).toMinutes() / 6.0) / 10.0;
        }
        Object v = eval(s.get("path"), ctx, root);
        if (s.get("equalsAny") instanceof List<?> options) {
            if (v == null) return false;
            String sv = v.toString();
            return options.stream().anyMatch(opt -> opt.toString().equalsIgnoreCase(sv));
        }
        if (v == null) return s.get("default");
        return switch (type) {
            case "number" -> JsonMaps.toDouble(v);
            case "date" -> { Instant i = JsonMaps.toInstant(v); yield i == null ? null : i.toString(); }
            case "bool" -> v instanceof Boolean ? v : Boolean.parseBoolean(v.toString());
            case "count" -> v instanceof Collection<?> c ? c.size() : (v instanceof Map<?, ?> mm ? mm.size() : 1);
            case "string" -> v.toString();
            case "email" -> { Matcher em = EMAIL.matcher(v.toString()); yield em.find() ? em.group().toLowerCase(Locale.ROOT) : null; }
            case "nameBeforeEmail" -> { String t = v.toString(); int i = t.indexOf('<'); yield (i > 0 ? t.substring(0, i) : t).trim(); }
            default -> v;
        };
    }

    private boolean matches(Map<String, Object> cond, DocumentContext ctx, DocumentContext root) {
        Object v = eval(cond.get("path"), ctx, root);
        if (Boolean.TRUE.equals(cond.get("isNull"))) return v == null;
        if (cond.containsKey("equals")) return v != null && v.toString().equalsIgnoreCase(String.valueOf(cond.get("equals")));
        if (cond.get("in") instanceof List<?> in) return v != null && in.stream().anyMatch(x -> x.toString().equalsIgnoreCase(v.toString()));
        return false;
    }

    private boolean kindMatches(Object when, String kind) {
        if (!(when instanceof Map<?, ?> w) || w.get("kind") == null) return true;
        Object k = w.get("kind");
        if (k instanceof List<?> ks) return ks.stream().anyMatch(x -> x.toString().equals(kind));
        return k.toString().equals(kind);
    }

    /** Expression: list (first non-null), "=literal", template with {path}, or a path. */
    private Object eval(Object expr, DocumentContext ctx, DocumentContext root) {
        if (expr == null) return null;
        if (expr instanceof List<?> options) {
            for (Object o : options) {
                Object v = eval(o, ctx, root);
                if (v != null && !(v instanceof String s && s.isBlank())) return v;
            }
            return null;
        }
        if (!(expr instanceof String s)) return expr;
        if (s.startsWith("=")) return s.substring(1);
        if (s.startsWith("$") || s.startsWith("^")) {
            if (!TEMPLATE.matcher(s).find()) return read(ctx, root, s);
        }
        Matcher m = TEMPLATE.matcher(s);
        if (!m.find()) return s;
        m.reset();
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = read(ctx, root, m.group(1));
            if (v == null) return null;
            m.appendReplacement(sb, Matcher.quoteReplacement(v.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private Object read(DocumentContext ctx, DocumentContext root, String path) {
        try {
            if (path.startsWith("^")) return root.read("$" + path.substring(1));
            return ctx.read(path);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Replace ${settings.key|default} tokens anywhere in the spec with tool-config settings. */
    private Object substitute(Object node, Map<String, Object> settings) {
        if (node instanceof String s && s.contains("${settings.")) {
            Matcher m = SETTING.matcher(s);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                Object v = settings == null ? null : settings.get(m.group(1));
                String rep = v != null ? v.toString() : (m.group(2) == null ? "" : m.group(2));
                m.appendReplacement(sb, Matcher.quoteReplacement(rep));
            }
            m.appendTail(sb);
            return sb.toString();
        }
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), substitute(v, settings)));
            return copy;
        }
        if (node instanceof List<?> list) return list.stream().map(x -> substitute(x, settings)).toList();
        return node;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
