package com.mudassir.assistant.psx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsers for the public PSX data portal (dps.psx.com.pk), ported from the psx-mobile app's
 * parse.ts and checked against the same captured pages. Pure Java, no network.
 */
public final class PsxParse {

    private PsxParse() {}

    public static final class Quote {
        public final String symbol, name, asOf;
        public final Double close, change, changePct;
        Quote(String symbol, String name, Double close, Double change, Double changePct, String asOf) {
            this.symbol = symbol; this.name = name; this.close = close; this.change = change; this.changePct = changePct; this.asOf = asOf;
        }
    }

    public static final class IndexQuote {
        public final String code;
        public final Double current, change, changePct;
        IndexQuote(String code, Double current, Double change, Double changePct) {
            this.code = code; this.current = current; this.change = change; this.changePct = changePct;
        }
    }

    /** The quote from /company/{SYMBOL}, or null if the page has no price (unknown symbol). */
    public static Quote company(String symbol, String html) {
        Double close = num(firstText(html, "quote__close"));
        if (close == null) return null;
        String name = null;
        Matcher nm = Pattern.compile("class=\"quote__name\">([\\s\\S]*?)(<div|</div>)").matcher(html);
        if (nm.find()) name = emptyToNull(stripTags(nm.group(1)));
        String pct = firstText(html, "change__percent");
        String asOf = firstText(html, "quote__date");
        return new Quote(symbol, name, close, num(firstText(html, "change__value")),
            pct == null ? null : num(pct.replaceAll("[()]", "")),
            asOf == null ? null : emptyToNull(asOf.replaceFirst("^\\^\\s*", "")));
    }

    /** /indices: INDEX | HIGH | LOW | CURRENT | CHANGE | % CHANGE */
    public static Map<String, IndexQuote> indices(String html) {
        Map<String, IndexQuote> out = new LinkedHashMap<>();
        Matcher tr = Pattern.compile("<tr\\b[^>]*>([\\s\\S]*?)</tr>", Pattern.CASE_INSENSITIVE).matcher(html);
        Pattern td = Pattern.compile("<td\\b([^>]*)>([\\s\\S]*?)</td>", Pattern.CASE_INSENSITIVE);
        while (tr.find()) {
            List<String[]> cells = new ArrayList<>();
            Matcher c = td.matcher(tr.group(1));
            while (c.find()) cells.add(new String[] {c.group(1), stripTags(c.group(2))});
            if (cells.size() < 6) continue;
            String code = cells.get(0)[1].trim();
            if (!code.matches("[A-Z0-9]{2,20}") || out.containsKey(code)) continue;
            out.put(code, new IndexQuote(code, cellNum(cells.get(3)), cellNum(cells.get(4)), cellNum(cells.get(5))));
        }
        return out;
    }

    // ---- helpers (same behaviour as psx-mobile's html.ts) ----

    private static Double cellNum(String[] cell) {
        Matcher m = Pattern.compile("data-order\\s*=\\s*\"([^\"]*)\"").matcher(cell[0]);
        if (m.find() && !m.group(1).isEmpty()) {
            Double v = num(m.group(1));
            if (v != null) return v;
        }
        return num(cell[1]);
    }

    private static String firstText(String html, String cls) {
        Matcher m = Pattern.compile("class=\"" + cls + "\"[^>]*>([\\s\\S]*?)</div>").matcher(html);
        return m.find() ? stripTags(m.group(1)) : null;
    }

    static String stripTags(String html) {
        String t = html.replaceAll("(?i)<script[\\s\\S]*?</script>", " ")
            .replaceAll("(?i)<style[\\s\\S]*?</style>", " ")
            .replaceAll("<[^>]+>", " ");
        t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&nbsp;", " ").replace("&ndash;", "–").replace("&mdash;", "—").replace("&#39;", "'");
        return t.replaceAll("\\s+", " ").trim();
    }

    /** Strips commas, %, spaces and "Rs."; "(1.2)" is negative; null if not a number. */
    static Double num(String raw) {
        if (raw == null) return null;
        String t = raw.replaceAll("[,%\\s\u00a0]", "").replaceFirst("(?i)^Rs\\.?", "").trim();
        if (t.isEmpty() || t.equals("-") || t.equals("—") || t.equalsIgnoreCase("N/A")) return null;
        boolean neg = false;
        if (t.startsWith("(") && t.endsWith(")")) { neg = true; t = t.substring(1, t.length() - 1); }
        try {
            double v = Double.parseDouble(t);
            return neg ? -v : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String emptyToNull(String s) { return s == null || s.isEmpty() ? null : s; }
}
