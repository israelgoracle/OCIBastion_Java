package ocitools;

import java.util.*;

/** Dependency-free JSON codec. Rejects malformed input rather than silently repairing it. */
public final class Json {
    private Json() { }
    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object value = p.value(0);
        p.ws();
        if (p.i != text.length()) throw p.error("Trailing input");
        return value;
    }
    @SuppressWarnings("unchecked")
    public static Map<String,Object> object(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("Expected a JSON object");
        return (Map<String,Object>) value;
    }
    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value) {
        if (!(value instanceof List)) throw new IllegalArgumentException("Expected a JSON array");
        return (List<Object>) value;
    }
    public static String string(Map<String,Object> map, String key, String fallback) {
        Object v = map.get(key);
        if (v == null) return fallback;
        if (!(v instanceof String)) throw new IllegalArgumentException(key + " must be a string");
        return (String)v;
    }
    public static boolean bool(Map<String,Object> map, String key, boolean fallback) {
        Object v = map.get(key);
        if (v == null) return fallback;
        if (!(v instanceof Boolean)) throw new IllegalArgumentException(key + " must be a boolean");
        return (Boolean)v;
    }
    public static int integer(Map<String,Object> map, String key, int fallback) {
        Object v = map.get(key);
        if (v == null) return fallback;
        if (!(v instanceof Number) || ((Number)v).doubleValue() != ((Number)v).intValue())
            throw new IllegalArgumentException(key + " must be an integer");
        return ((Number)v).intValue();
    }
    public static String write(Object value) {
        if (value == null) return "null";
        if (value instanceof String) {
            StringBuilder b = new StringBuilder("\"");
            for (char c : ((String)value).toCharArray()) switch(c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default: if (c < 32) b.append(String.format("\\u%04x", (int)c)); else b.append(c);
            }
            return b.append('"').toString();
        }
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        StringJoiner join = new StringJoiner(",", value instanceof Map ? "{" : "[", value instanceof Map ? "}" : "]");
        if (value instanceof Map) {
            for (Map.Entry<?,?> e : ((Map<?,?>)value).entrySet()) join.add(write(e.getKey().toString()) + ":" + write(e.getValue()));
        } else if (value instanceof Iterable) {
            for (Object v : (Iterable<?>)value) join.add(write(v));
        } else throw new IllegalArgumentException("Unsupported JSON type: " + value.getClass());
        return join.toString();
    }
    public static Map<String,Object> copy(Map<String,Object> value) { return object(parse(write(value))); }

    private static final class Parser {
        final String s; int i;
        Parser(String s) { this.s = s; }
        IllegalArgumentException error(String m) { return new IllegalArgumentException(m + " at offset " + i); }
        void ws() { while (i < s.length() && " \n\r\t".indexOf(s.charAt(i)) >= 0) i++; }
        boolean take(char c) { ws(); if (i < s.length() && s.charAt(i) == c) { i++; return true; } return false; }
        Object value(int depth) {
            if (depth > 64) throw error("JSON nesting too deep");
            ws(); if (i == s.length()) throw error("Missing value");
            char c = s.charAt(i);
            if (c == '"') return str();
            if (take('{')) {
                Map<String,Object> m = new LinkedHashMap<>();
                if (take('}')) return m;
                do {
                    ws(); if (i == s.length() || s.charAt(i) != '"') throw error("Missing property name");
                    String key = str(); if (!take(':')) throw error("Missing colon");
                    if (m.containsKey(key)) throw error("Duplicate property " + key);
                    m.put(key, value(depth+1));
                } while (take(','));
                if (!take('}')) throw error("Missing closing brace"); return m;
            }
            if (take('[')) {
                List<Object> a = new ArrayList<>(); if (take(']')) return a;
                do { a.add(value(depth+1)); } while (take(','));
                if (!take(']')) throw error("Missing closing bracket"); return a;
            }
            for (String literal : Arrays.asList("true", "false", "null")) if (s.startsWith(literal, i)) {
                i += literal.length(); return literal.equals("null") ? null : Boolean.valueOf(literal);
            }
            int start = i;
            while (i < s.length() && "-+0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(start, i);
            if (!n.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw error("Invalid value");
            try {
                if (n.indexOf('.') >= 0 || n.indexOf('e') >= 0 || n.indexOf('E') >= 0) {
                    double d = Double.parseDouble(n); if (!Double.isFinite(d)) throw error("Number out of range"); return d;
                }
                return Long.valueOf(n);
            } catch (NumberFormatException e) { throw error("Number out of range"); }
        }
        String str() {
            i++; StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++); if (c == '"') return b.toString();
                if (c < 32) throw error("Unescaped control character");
                if (c != '\\') { b.append(c); continue; }
                if (i == s.length()) throw error("Unfinished escape");
                c = s.charAt(i++);
                switch(c) {
                    case '"': case '\\': case '/': b.append(c); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw error("Invalid unicode escape");
                        try { b.append((char)Integer.parseInt(s.substring(i,i+4),16)); } catch (NumberFormatException e) { throw error("Invalid unicode escape"); }
                        i += 4; break;
                    default: throw error("Invalid escape");
                }
            }
            throw error("Unterminated string");
        }
    }
}
