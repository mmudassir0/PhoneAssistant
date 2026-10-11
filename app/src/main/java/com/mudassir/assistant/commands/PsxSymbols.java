package com.mudassir.assistant.commands;

import java.util.HashMap;
import java.util.Map;

/** Spoken company names → PSX tickers ("lucky cement" → LUCK). Unknown single words are tried as tickers. */
public final class PsxSymbols {

    private PsxSymbols() {}

    private static final Map<String, String> NAMES = new HashMap<>();

    private static void add(String ticker, String... names) {
        NAMES.put(ticker.toLowerCase(), ticker);
        for (String n : names) NAMES.put(n, ticker);
    }

    static {
        add("LUCK", "lucky", "lucky cement");
        add("OGDC", "ogdcl", "oil and gas development", "oil and gas development company");
        add("PPL", "pakistan petroleum");
        add("PSO", "pakistan state oil");
        add("POL", "pakistan oilfields", "pakistan oil fields");
        add("MARI", "mari petroleum", "mari energies");
        add("HBL", "habib bank");
        add("MCB", "mcb bank");
        add("UBL", "united bank");
        add("MEBL", "meezan", "meezan bank");
        add("NBP", "national bank");
        add("BAFL", "bank alfalah", "alfalah");
        add("BAHL", "bank al habib", "al habib");
        add("ENGRO", "engro corp", "engro corporation");
        add("EFERT", "engro fertilizer", "engro fertilizers", "engro fert");
        add("FFC", "fauji fertilizer", "fauji fertiliser", "fauji");
        add("FCCL", "fauji cement");
        add("HUBC", "hubco", "hub power");
        add("KAPCO", "kot addu");
        add("SYS", "systems", "systems limited");
        add("TRG", "trg pakistan");
        add("AVN", "avanceon");
        add("MLCF", "maple leaf", "maple leaf cement");
        add("DGKC", "dg khan", "dg khan cement");
        add("CHCC", "cherat", "cherat cement");
        add("PIOC", "pioneer cement");
        add("PSMC", "pak suzuki", "suzuki");
        add("INDU", "indus motor", "toyota");
        add("HCAR", "honda", "honda atlas");
        add("NESTLE", "nestle pakistan");
        add("KEL", "k electric", "k-electric");
        add("PTC", "ptcl");
        add("ATRL", "attock refinery");
        add("NRL", "national refinery");
        add("SNGP", "sui northern", "sngpl");
        add("SSGC", "sui southern");
        add("PAEL", "pak elektron", "pel");
        add("ISL", "international steels");
        add("ILP", "interloop");
        add("SEARL", "searle");
        add("GLAXO", "glaxo");
        add("AIRLINK", "air link");
        add("TOMCL", "the organic meat");
        add("CNERGY", "cnergyico");
        add("PIBTL", "pakistan international bulk");
        add("FABL", "faysal bank");
        add("AKBL", "askari bank");
        add("BOP", "bank of punjab");
    }

    /** Words that look like tickers but aren't stocks people ask about. */
    private static final java.util.Set<String> NOT_TICKERS = new java.util.HashSet<>(java.util.Arrays.asList(
        "the", "a", "my", "it", "this", "that", "your", "his", "her", "their", "our", "today", "now", "share", "stock"));

    public static boolean isKnown(String spoken) {
        return spoken != null && NAMES.containsKey(spoken.trim().toLowerCase());
    }

    /** Ticker for a spoken name, a plausible ticker for an unknown single word, or null. */
    public static String resolve(String spoken) {
        if (spoken == null) return null;
        String s = spoken.trim().toLowerCase().replaceFirst("^(the) ", "").replaceAll(" (ltd|limited|company|shares?|stock)$", "");
        String known = NAMES.get(s);
        if (known != null) return known;
        if (s.matches("[a-z]{2,8}") && !NOT_TICKERS.contains(s)) return s.toUpperCase();
        return null;
    }
}
