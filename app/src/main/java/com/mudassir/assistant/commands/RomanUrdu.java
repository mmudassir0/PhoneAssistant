package com.mudassir.assistant.commands;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Understands Roman Urdu commands ("6 baje alarm laga do", "torch band karo", "Ammi ko call karo")
 * by rewriting them into the English commands the parser already knows.
 *
 * Every rule is anchored on Urdu words (karo, laga do, kholo, baje…) so it never fires on an
 * English sentence. Plain Java, unit-tested.
 */
public final class RomanUrdu {

    private RomanUrdu() {}

    private interface Rule { String apply(Matcher m); }

    private static final class R {
        final Pattern p; final Rule r;
        R(String regex, Rule r) { this.p = Pattern.compile(regex); this.r = r; }
    }

    // Imperative endings: "karo", "kar do", "laga do"…
    private static final String DO = "(?:karo|kardo|kar do|kar dein|kar dena|kar den|kar dijiye|kijiye|krdo|kr do|kro|karna|kar)";
    private static final String END = "(?: (?:do|dein|den|dena|dijiye|o|na|lo|le|zara|please|jaldi))*";

    private static final Map<String, String> NUMBERS = new HashMap<>();
    static {
        String[][] n = {{"ek", "1"}, {"aik", "1"}, {"do", "2"}, {"teen", "3"}, {"char", "4"}, {"chaar", "4"},
            {"paanch", "5"}, {"panch", "5"}, {"chay", "6"}, {"chhe", "6"}, {"che", "6"}, {"saat", "7"}, {"sat", "7"},
            {"aath", "8"}, {"ath", "8"}, {"nau", "9"}, {"no", "9"}, {"das", "10"}, {"dus", "10"}, {"gyarah", "11"},
            {"barah", "12"}, {"bara", "12"}, {"pandrah", "15"}, {"bees", "20"}, {"pachees", "25"}, {"tees", "30"},
            {"chalees", "40"}, {"pachaas", "50"}, {"pachas", "50"}, {"saath", "60"}};
        for (String[] x : n) NUMBERS.put(x[0], x[1]);
    }

    private static final Map<String, String> UNITS = new HashMap<>();
    static {
        UNITS.put("minute", "minutes"); UNITS.put("minutes", "minutes"); UNITS.put("mint", "minutes"); UNITS.put("min", "minutes");
        UNITS.put("second", "seconds"); UNITS.put("seconds", "seconds"); UNITS.put("sec", "seconds");
        UNITS.put("ghanta", "hours"); UNITS.put("ghante", "hours"); UNITS.put("ghantay", "hours"); UNITS.put("hour", "hours");
        UNITS.put("din", "days");
    }

    private static final String NUM = "(\\d+|ek|aik|do|teen|char|chaar|paanch|panch|chay|chhe|saat|aath|nau|das|dus|pandrah|bees|pachees|tees|chalees|pachaas|pachas)";
    private static final String UNIT = "(minute|minutes|mint|min|second|seconds|sec|ghanta|ghante|ghantay|hour|din)";

    private static String num(String w) { return NUMBERS.getOrDefault(w, w); }

    /** "subah 7 baje" → "7 am", "kal sham 5 baje" → "tomorrow 5 pm", "10 minute baad" → "in 10 minutes". */
    static String when(String s) {
        String t = " " + s.trim() + " ";
        String day = "";
        if (t.contains(" kal ")) { day = "tomorrow "; t = t.replace(" kal ", " "); }
        if (t.matches(".* (?:aaj|aj) raat .*")) { day = "tonight "; t = t.replaceAll(" (?:aaj|aj) raat ", " "); }
        t = t.replaceAll(" (?:aaj|aj) ", " ");
        String mer = "";
        if (t.matches(".* (?:subah|subha|sobah|savere|sawere) .*")) { mer = " am"; t = t.replaceAll(" (?:subah|subha|sobah|savere|sawere) ", " "); }
        if (t.matches(".* (?:dopahar|dopehar|dopher) .*")) { mer = " pm"; t = t.replaceAll(" (?:dopahar|dopehar|dopher) ", " "); }
        if (t.matches(".* (?:sham|shaam|raat|rat) .*")) { mer = " pm"; t = t.replaceAll(" (?:sham|shaam|raat|rat) ", " "); }
        Matcher m = Pattern.compile(" " + NUM + " " + UNIT + " (?:baad|bad|mein|me|main) ").matcher(t);
        if (m.find()) return "in " + num(m.group(1)) + " " + UNITS.get(m.group(2));
        m = Pattern.compile(" (\\d{1,2})(?:[: ](\\d{2}))? (?:baje|bje|bajay|bajey)(?: ka| ki| ke| tak| pe| par)? ").matcher(t);
        if (m.find()) return day + "at " + m.group(1) + (m.group(2) != null ? ":" + m.group(2) : "") + mer;
        m = Pattern.compile(" " + NUM + " (?:baje|bje|bajay|bajey)(?: ka| ki| ke)? ").matcher(t);
        if (m.find()) return day + "at " + num(m.group(1)) + mer;
        return day.trim();
    }

    private static final String SAY = "(?: ke| ki| keh| k| that| ye| yeh|:)?";
    private static final List<R> RULES = new ArrayList<>();

    private static void rule(String regex, Rule r) { RULES.add(new R(regex, r)); }

    static {
        // ---- alarms and timers ----
        rule("^(?:mujhe |mujhay )?(.+?) (?:baje|bje|bajay)(?: ka| ki| ke)? (?:alarm|alaram) (?:laga|lagao|set|set kar|laga de|lagaa)" + END + "(?: " + DO + ")?$",
            m -> "set an alarm for " + when(m.group(1) + " baje").replaceFirst("^(tomorrow |tonight )?at ", "$1"));
        rule("^(?:alarm|alaram) (?:laga|lagao|set kar|set)" + END + "(?: " + DO + ")? (.+?) (?:baje|bje|bajay)(?: ka)?$",
            m -> "set an alarm for " + when(m.group(1) + " baje").replaceFirst("^(tomorrow |tonight )?at ", "$1"));
        rule("^(?:mujhe |mujhay )?(.+?) (?:baje|bje|bajay) (?:utha|uthana|utha dena|jaga|jaga dena|jagana)" + END + "$",
            m -> "wake me up " + when(m.group(1) + " baje"));
        rule("^(?:alarm|alaram) (?:laga|lagao|set kar|set)" + END + "(?: " + DO + ")?$", m -> "set an alarm");
        rule("^" + NUM + " " + UNIT + "(?: ka| ki| ke)? (?:timer|taimer) (?:laga|lagao|set|chala|chalao|start)" + END + "(?: " + DO + ")?$",
            m -> "set a timer for " + num(m.group(1)) + " " + UNITS.get(m.group(2)));
        rule("^(?:aadha|adha) ghant(?:a|e)(?: ka)? (?:timer|taimer) (?:laga|lagao|set)" + END + "(?: " + DO + ")?$", m -> "set a timer for 30 minutes");

        // ---- reminders ----
        rule("^(?:mujhe |mujhay )?(.+?) (?:yaad|yad) (?:dila|dilana|dilao|dila do|dila dena|dilaa|karwa|karwana|karwa dena|kara dena)" + END + SAY + " (.+)$",
            m -> "remind me " + when(m.group(1)) + " to " + m.group(2));
        rule("^(?:mujhe |mujhay )?(?:yaad|yad) (?:dila|dilana|dilao|dila do|dila dena|karwa dena)" + END + SAY + " (.+?) (?:ka|ki|ke) (.+)$",
            m -> "remind me " + when(m.group(2)) + " to " + m.group(1));

        // ---- calls and messages ----
        rule("^(.+?) (?:ko|ku) (?:call|kaal|phone|fone|cal) (?:karo|kar do|kardo|kro|krdo|kr do|lagao|laga do|milao|mila do|kijiye|kar dein|karein)" + END + "$",
            m -> "call " + m.group(1));
        rule("^(.+?) (?:ka|ki) (?:number|nambar|numbar) (?:milao|mila do|lagao|laga do|dial karo)" + END + "$", m -> "call " + m.group(1));
        rule("^(.+?) (?:ko|ku) (whatsapp|watsapp|whatsap|message|msg|massage|sms|text) (?:karo|kar do|kardo|kro|krdo|bhejo|bhej do|kar dein|bhej dein)" + END + SAY + " (.+)$",
            m -> (m.group(2).matches("sms|text") ? "text " : "whatsapp ") + m.group(1) + " saying " + m.group(3));
        rule("^(.+?) (?:ko|ku) (whatsapp|watsapp|whatsap|message|msg|massage|sms|text) (?:karo|kar do|kardo|kro|krdo|bhejo|bhej do)" + END + "$",
            m -> (m.group(2).matches("sms|text") ? "text " : "whatsapp ") + m.group(1));
        rule("^(?!mujhe |mujhay |hamein |humein )(.+?) (?:ko|ku) (?:bolo|bol do|bata do|batao|keh do|kaho)" + SAY + " (.+)$",
            m -> "whatsapp " + m.group(1) + " saying " + m.group(2));

        // ---- flashlight, volume, brightness, sound mode ----
        rule("^(?:torch|flash ?light|flash|batti) (?:on|jala|jalao|jala do|chala|chalao|laga|lagao|khol|kholo)" + END + "(?: " + DO + ")?$", m -> "turn on the flashlight");
        rule("^(?:torch|flash ?light|flash|batti) (?:band|bandh|off|bujha|bujhao|bujha do)" + END + "(?: " + DO + ")?$", m -> "turn off the flashlight");
        rule("^(?:awaz|awaaz|aawaz|avaz|volume|sound) (?:tez|taiz|barha|barhao|badha|badhao|zyada|ziada|ooncha|oonchi|unchi|upar|oopar)" + END + "(?: " + DO + ")?$", m -> "volume up");
        rule("^(?:awaz|awaaz|aawaz|avaz|volume|sound) (?:kam|halka|halki|neeche|niche|dheema|dheemi|dheeme)" + END + "(?: " + DO + ")?$", m -> "volume down");
        rule("^(?:awaz|awaaz|aawaz|avaz|volume|sound) (?:band|bandh|khatam|off|mute)" + END + "(?: " + DO + ")?$", m -> "mute");
        rule("^(?:awaz|awaaz|aawaz|avaz|volume|sound) (?:poori|puri|full|sab se zyada)" + END + "(?: " + DO + ")?$", m -> "max volume");
        rule("^(?:roshni|roshnee|brightness|screen) (?:tez|barha|barhao|badha|zyada|ziada|full)" + END + "(?: " + DO + ")?$", m -> "increase brightness");
        rule("^(?:roshni|roshnee|brightness|screen) (?:kam|halki|halka|dark|dheemi|andheri)" + END + "(?: " + DO + ")?$", m -> "decrease brightness");
        rule("^(?:phone |mobile |fone )?(?:ko )?(silent|vibrate|vibration|vibrator) (?:pe |par |per |mein |me |main )?(?:karo|kar do|dal do|daal do|lagao|laga do|kr do|krdo|rakh do|kardo)" + END + "$",
            m -> "put phone on " + (m.group(1).equals("silent") ? "silent" : "vibrate"));
        rule("^(?:phone |mobile )?(?:awaz|ringer|ring|ghanti) (?:wapis |dobara )?(?:on|chalu|khol)" + END + "(?: " + DO + ")?$", m -> "turn off silent mode");
        rule("^silent (?:hata|hatao|hata do|khatam|band|off)" + END + "(?: " + DO + ")?$", m -> "turn off silent mode");

        // ---- music ----
        rule("^(?:gaana|gana|ganna|song|music|gaane|ganay|gane) (?:chalao|chala do|chala|lagao|laga do|play karo|bajao|baja do|shuru karo)" + END + "$", m -> "play music");
        rule("^(?:gaana|gana|ganna|song|music|gaane|gane) (?:band|bandh|rok|roko|rok do|ruko|pause|thehro)" + END + "(?: " + DO + ")?$", m -> "pause");
        rule("^(?:agla|aglaa|next|dusra|doosra) (?:gaana|gana|song)(?: (?:chalao|lagao|bajao|laga do|chala do))?" + END + "$", m -> "next song");
        rule("^(?:pichla|pichhla|pehla|previous) (?:gaana|gana|song)(?: (?:chalao|lagao|bajao|laga do|chala do))?" + END + "$", m -> "previous song");

        // ---- apps, camera ----
        rule("^(?:selfie|selfi) (?:lo|le lo|lelo|khencho|kheencho|khainch do|khench do|khinch do|lena)" + END + "$", m -> "take a selfie");
        rule("^(?:photo|foto|tasveer|tasweer|tasvir|picture|pic) (?:lo|le lo|lelo|khencho|kheencho|khainch do|khench do|khinch do|lena)" + END + "$", m -> "take a photo");
        rule("^(?:camera|kamera) (?:kholo|khol do|khol|on karo|chalao)" + END + "$", m -> "open camera");
        rule("^(.+?) (?:kholo|khol do|khol dein|kholna|open karo|open kar do|open kardo|start karo|start kar do)" + END + "$", m -> "open " + m.group(1));
        rule("^(.+?) (?:pe|par|per) (.+?) (?:search karo|search kar do|dhoondo|dhoondho|talash karo|dhundo)" + END + "$",
            m -> m.group(1).matches("youtube|you tube") ? "play " + m.group(2) + " on youtube" : "search for " + m.group(2));
        rule("^(.+?) (?:search karo|search kar do|google karo|dhoondo|dhoondho|talash karo|dhundo)" + END + "$", m -> "search for " + m.group(1));

        // ---- time, date, weather, news, battery ----
        rule("^(?:time|waqt|tym|taim|samay) (?:kya|kia|kiya) (?:hua|huwa|hoa|ho gaya|hai)(?: hai)?$", m -> "what time is it");
        rule("^(?:kya|kia) (?:time|waqt|tym) (?:hua|huwa|hai|ho gaya)(?: hai)?$", m -> "what time is it");
        rule("^kitne (?:baje|bje|bajay) (?:hain|hai|hue|huay|ho gaye)(?: hain)?$", m -> "what time is it");
        rule("^(?:aaj|aj) (?:ki|kya) (?:tareekh|tarikh|tareekh|date)(?: hai| kya hai| batao)?$", m -> "what's the date today");
        rule("^(?:aaj|aj) (?:kaun sa|konsa|kon sa) din hai$", m -> "what day is it");
        rule("^.*\\b(?:mausam|mosam|mousam)\\b.*$", m -> "what's the weather");
        rule("^.*\\bbaarish\\b.*\\b(?:hogi|hai|ho gi|aaye gi|ayegi)\\b.*$", m -> "will it rain today");
        rule("^(?:aaj ki |taza |taaza )?(?:khabrein|khabren|khabar|khabrain|news) (?:sunao|batao|suna do|bata do|kya hain|kya hai)$", m -> "what's the news");
        rule("^battery (?:kitni|kitna|kitne)(?: hai| reh gayi| bachi| bachi hai)?(?: hai)?$", m -> "how much battery");

        // ---- prayer ----
        rule("^(?:agli|aglii|next|ane wali|aane wali) (?:namaz|namaaz|salah)(?: kab hai| ka waqt| kitne baje hai| kab)?(?: hai)?$", m -> "next namaz");
        rule("^(?:namaz|namaaz) (?:ka|ke|ki) (?:waqt|auqat|time|timings|timing)(?: kya hai| batao)?$", m -> "prayer times");

        // ---- places ----
        rule("^(?:mujhe )?(.+?) (?:le chalo|le jao|le chalein|ka rasta|ka raasta|ka route|ki taraf chalo|jana hai|jaana hai|chalna hai)(?: (?:batao|dikhao|bata do|dikha do))?" + END + "$",
            m -> "navigate to " + place(m.group(1)));
        rule("^(?:main|mein|me|hum) (?:kahan|kahaan|kahn|kidhar|kidar) (?:hoon|hun|hain|hu|hoo)$", m -> "where am i");

        // ---- lists, notes, memory, name ----
        rule("^(.+?) (?:ko )?(?:shopping|grocery|sauda|saudey|saudy|sodha) (?:list |wali list )?(?:mein|me|main|men) (?:daal do|dal do|daalo|dalo|daal|add karo|add kar do|add kardo|likh do|likho|shamil karo)" + END + "$",
            m -> "add " + m.group(1) + " to my shopping list");
        rule("^(?:meri )?(?:shopping|grocery|sauda|saudey) list (?:dikhao|batao|sunao|dikha do|bata do|mein kya hai)$", m -> "what's on my shopping list");
        rule("^(?:note|not) (?:karo|kar lo|kr lo|likh lo|likho)" + SAY + " (.+)$", m -> "take a note " + m.group(1));
        rule("^(?:yaad|yad) (?:rakho|rakhna|rakh lo|rakhlo|rakh lena)" + SAY + " (.+)$", m -> "remember that " + m.group(1));
        rule("^(?:mera|meraa) (?:naam|nam|name) (.+?) (?:hai|he|h)$", m -> "my name is " + m.group(1));

        // ---- money ----
        rule("^(\\d+(?:\\.\\d+)?) (?:rupay|rupaye|rupee|rupees|rs|ka|ki|ke)?\\s*(?:ka |ki |ke )?(.+?) (?:liya|li|liye|lia|khareeda|khareedi|kharida|kharidi|dalwaya|dalwaya hai|bhara|diya|diye|piya|khaya)$",
            m -> "spent " + m.group(1) + " on " + m.group(2));
        rule("^(.+?) (?:pe|par|per|mein|me) (\\d+(?:\\.\\d+)?) (?:rupay |rupaye |rupees |rs )?(?:kharch|kharcha|kharche|lagaye|lag gaye|lage|diye)(?: (?:kiye|kiya|hue|huay|hogaye|ho gaye))?$",
            m -> "spent " + m.group(2) + " on " + m.group(1));
        rule("^(?:is|iss|es) (?:mahine|maheene|month) (?:kitna|kitne|kitni) (?:kharcha|kharch|kharche|paise|pesay)(?: (?:hua|hue|huay|lage|ho gaya|ho gaye|kiya|kiye))?(?: hai)?$",
            m -> "how much did i spend this month");
        rule("^(?:aaj|aj) (?:kitna|kitne|kitni) (?:kharcha|kharch|kharche|paise)(?: (?:hua|hue|huay|lage|ho gaya|ho gaye|kiya|kiye))?(?: hai)?$",
            m -> "how much did i spend today");

        // ---- stocks ----
        rule("^(.+?) (?:ka|ki|ke) (?:price|rate|bhao|bhav|qeemat|keemat|share price|shares? ka rate)(?: (?:kya hai|kitna hai|kitni hai|batao|kya chal raha hai))?$", m -> m.group(1) + " price");
        rule("^(?:market|kse 100|kse100|stock market|share market|psx) (?:kaisi|kaisa|kesi|kesa|kaise) (?:hai|chal rahi hai|ja rahi hai|chal raha hai|ja raha hai)$", m -> "how is kse 100 today");
        rule("^(?:mere|meray) (?:shares|stocks|share) (?:kaise|kaisay|kese) (?:hain|chal rahe hain|ja rahe hain)$", m -> "how are my stocks doing");

        // ---- translate, help, routines, chat ----
        rule("^(.+?) (?:ko )?(urdu|english|angrezi|arabic|arbi|punjabi) (?:mein|me|main) (?:kya kehte hain|kya kehte hai|kya kehtay hain|translate karo|translate kar do|kya hota hai|kaise kehte hain)$",
            m -> "translate " + m.group(1) + " to " + (m.group(2).equals("angrezi") ? "english" : m.group(2).equals("arbi") ? "arabic" : m.group(2)));
        rule("^(?:tum|aap|ap) (?:kya kya|kia kia|kya) kar (?:sakte|saktay|sakti) (?:ho|hain|hai)$", m -> "what can you do");
        rule("^(?:shab bakhair|shab-e-khair|shab e khair|shabba khair|good night)$", m -> "good night");
        rule("^(?:subah bakhair|subha bakhair)$", m -> "good morning");
        rule("^(?:naya|nayi|nai) (?:chat|baat|guftagu)(?: shuru karo)?$", m -> "new chat");
        rule("^(?:ye|yeh|ya|is|isay|isey) (?:kya|kia) (?:hai|he|h)$", m -> "what is this");
        rule("^(?:ye|yeh|isay|isey|is ko) (?:parho|padho|parh do|padh do|parh ke sunao)$", m -> "read this");
    }

    /** "ghar" → "home", "daftar" → "office". */
    static String place(String p) {
        String s = p.trim();
        if (s.matches("ghar|gher|ghr|apne ghar|apnay ghar")) return "home";
        if (s.matches("daftar|daftur|office|ofis|dafter")) return "office";
        if (s.matches("masjid|masjid tak")) return "mosque";
        return s.replaceAll(" (?:tak|ki taraf)$", "");
    }

    /** English command for a Roman Urdu one, or null if [s] isn't recognised. [s] is normalized lowercase. */
    public static String rewrite(String s) {
        if (s == null) return null;
        String t = s.trim().replaceAll("\\s+", " ");
        for (R rule : RULES) {
            Matcher m = rule.p.matcher(t);
            if (m.find()) {
                String out = rule.r.apply(m);
                return out == null ? null : out.replaceAll("\\s+", " ").trim();
            }
        }
        return null;
    }

    /** "band karo" right after a flashlight command. Returns "off", "on" or null. */
    public static String onOff(String s) {
        if (s.matches("^(?:ab |ok |acha |achha )?(?:isay |isey |ise |ye |yeh )?(?:band|bandh|off|bujha|bujha do|bujhao)(?: " + DO + ")?" + END + "$")) return "off";
        if (s.matches("^(?:ab |ok |acha |achha )?(?:isay |isey |ise |ye |yeh )?(?:on|jala|jala do|jalao|chala do|chalao|wapis on)(?: " + DO + ")?" + END + "$")) return "on";
        return null;
    }
}
