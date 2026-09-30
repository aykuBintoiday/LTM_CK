package common;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class Protocol {
    private Protocol() {}

    public static String enc(String value) {
        if (value == null) value = "";
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String dec(String value) {
        if (value == null || value.isBlank()) return "";
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    public static String clean(String s) {
        if (s == null) return "";
        return s.trim().replace("|", "/").replace("\r", " ").replace("\n", " ");
    }

    public static String packRows(List<String[]> rows) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) b.append('\n');
            String[] row = rows.get(i);
            for (int j = 0; j < row.length; j++) {
                if (j > 0) b.append('\t');
                b.append(row[j] == null ? "" : row[j].replace("\t", " ").replace("\r", " ").replace("\n", " "));
            }
        }
        return enc(b.toString());
    }

    public static List<String[]> unpackRows(String encoded) {
        List<String[]> rows = new ArrayList<>();
        String raw = dec(encoded);
        if (raw.isBlank()) return rows;
        for (String line : raw.split("\\n", -1)) rows.add(line.split("\\t", -1));
        return rows;
    }

    public static int intValue(String s, int fallback) {
        try { return Integer.parseInt(s); } catch (Exception e) { return fallback; }
    }

    public static long longValue(String s, long fallback) {
        try { return Long.parseLong(s); } catch (Exception e) { return fallback; }
    }
}
