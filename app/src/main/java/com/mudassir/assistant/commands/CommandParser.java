package com.mudassir.assistant.commands;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns what you said into a phone action ("wake me up at 6:30", "remind me in 20 minutes to
 * call Ali", "open WhatsApp"). Anything it doesn't recognise goes to the AI as a normal chat.
 *
 * Rules instead of the AI because they are instant, work offline and never misfire on a
 * small model. Plain Java with "now" passed in, so it can be unit-tested off-device.
 */
public final class CommandParser {

    private CommandParser() {}

    public enum Type {
        ALARM, TIMER, REMINDER, CALL, OPEN_APP, TIME, DATE, BATTERY,
        NOTIFICATIONS, NEWS, WEATHER, FLASHLIGHT_ON, FLASHLIGHT_OFF, NEW_CHAT,
        OPEN_URL, WEB_SEARCH, YOUTUBE_SEARCH,
        WHATSAPP, SMS, VOLUME, BRIGHTNESS, RINGER, SETTING, HELP,
        CALC, CONVERT, CALENDAR, CALENDAR_ADD, NOTE_ADD, NOTE_READ, NOTE_REMOVE, NOTE_CLEAR,
        REMEMBER, RECALL, FORGET, SET_NAME, MEDIA, NAVIGATE, WHERE_AM_I, SHARE_LOCATION,
        CAMERA, TRANSLATE, PRAYER, BRIEFING, CONTACT_INFO,
        /** Looks like a phone command we can't do: answer honestly instead of letting the AI pretend. */
        UNSUPPORTED
    }

    /** A recognised command. Only the fields relevant to its type are set. */
    public static final class Command {
        public final Type type;
        /** ALARM: time of day (null = ask for it). */
        public LocalTime time;
        /** REMINDER: when to remind. */
        public LocalDateTime at;
        /** TIMER: length in seconds. */
        public long seconds;
        /** WHATSAPP/SMS: message body (null = ask). NOTE_*: the item. REMEMBER: the fact. TRANSLATE: the phrase. CONVERT: target unit. */
        public String message;
        /** CONVERT: the amount. */
        public double value;
        /** REMINDER: what. CALL/WHATSAPP/SMS: who. VOLUME/BRIGHTNESS: up|down|max|min|mute|unmute|status or a percent. RINGER: normal|vibrate|silent. SETTING: wifi|bluetooth|data|airplane|hotspot|location. OPEN_APP: app name. OPEN_URL: address. *_SEARCH: query. NOTIFICATIONS: app filter (may be null). */
        public String text;

        public Command(Type type) { this.type = type; }

        @Override public String toString() {
            return type + (time != null ? " time=" + time : "") + (at != null ? " at=" + at : "")
                + (seconds > 0 ? " seconds=" + seconds : "") + (text != null ? " text='" + text + "'" : "")
                + (message != null ? " message='" + message + "'" : "")
                + (type == Type.CONVERT ? " value=" + Calculator.format(value) : "");
        }
    }

    // ---------------------------------------------------------------------------------------

    public static Command parse(String input, LocalDateTime now) {
        if (input == null) return null;
        String s = normalize(input);
        if (s.isEmpty()) return null;
        Matcher m;

        if (s.matches("help|what can you do|what (are|is) your (features|commands|skills)|what commands (do you know|can i use)|how do i use (you|this app)"))
            return new Command(Type.HELP);

        if (s.matches("(start a )?(new|fresh) (chat|conversation)|clear (the )?chat|forget (everything|that|it)"))
            return new Command(Type.NEW_CHAT);

        // ---- flashlight ----
        if (s.matches(".*\\b(flash ?light|torch|flash)\\b.*") && !s.matches("^(what|how|why|where|which|who|is|does|can|do) .*")) {
            if (s.matches(".*\\b(off|band|bund|close|disable|stop|deactivate)\\b.*")) return new Command(Type.FLASHLIGHT_OFF);
            if (s.matches(".*\\b(on|open|start|enable|activate|turn|switch|light)\\b.*") || s.matches("(flash ?light|torch)")) return new Command(Type.FLASHLIGHT_ON);
        }

        // ---- maths and conversions (exact, never the AI) ----
        String expr = Calculator.toExpression(s);
        if (expr != null) {
            try {
                Calculator.eval(expr);
                Command c = new Command(Type.CALC);
                c.text = expr;
                return c;
            } catch (IllegalArgumentException e) {
                if (e.getMessage() != null && e.getMessage().contains("divide by zero")) {
                    Command c = new Command(Type.CALC);
                    c.text = expr;
                    return c;
                }
            }
        }
        Command conversion = parseConversion(s);
        if (conversion != null) return conversion;

        // ---- calendar ----
        if (s.matches("(what('s| is) (on )?)?(my )?(calendar|schedule|agenda)( for)?( today| tomorrow| this week)?|what('s| is) on my (calendar|schedule|agenda)( for)?( today| tomorrow| this week)?|do i have (any )?(meetings|events|appointments|plans)( today| tomorrow| this week)?|what (meetings|events|appointments) do i have( today| tomorrow| this week)?|(show|read|check) (me )?my (calendar|schedule|agenda)( for)?( today| tomorrow| this week)?")) {
            Command c = new Command(Type.CALENDAR);
            c.text = s.contains("tomorrow") ? "tomorrow" : s.contains("week") ? "week" : "today";
            return c;
        }
        if ((m = Pattern.compile("^(?:add|schedule|create|put|set up|book|make)(?: a| an| the)? (?<title>.+?)(?: (?:to|in|on|into) (?:my |the )?calendar)(?<rest>.*)$|^(?:add|schedule|create|set up|book|make)(?: a| an)? (?<title2>(?:meeting|appointment|event|call|interview|lunch|dinner)(?: with [a-z ]+?)?)(?<rest2> (?:at|on|tomorrow|today|tonight|in|next).*)$").matcher(s)).find()) {
            String title = firstNonNull(m.group("title"), m.group("title2"));
            String rest = firstNonNull(m.group("rest"), m.group("rest2"));
            Command r = parseReminder(title + " " + rest.trim(), now);
            Command c = new Command(Type.CALENDAR_ADD);
            c.text = r.text;
            c.at = r.at;
            return c;
        }

        // ---- lists and notes ----
        if ((m = Pattern.compile("^(?:add|put) (?<item>.+?) (?:to|on|in) (?:my |the )?(?<list>[a-z]+(?: [a-z]+)?) list$").matcher(s)).find()) {
            Command c = new Command(Type.NOTE_ADD);
            c.text = m.group("list");
            c.message = m.group("item");
            return c;
        }
        if ((m = Pattern.compile("^(?:take|make|write|create|add)(?: a| this)? note(?: that| saying| of)?:? (?<item>.+)$|^note(?: that|:)? (?<item2>.+)$|^(?:write|jot) down (?:that )?(?<item3>.+)$").matcher(s)).find()) {
            Command c = new Command(Type.NOTE_ADD);
            c.text = "notes";
            c.message = firstNonNull(m.group("item"), m.group("item2"), m.group("item3"));
            return c;
        }
        if ((m = Pattern.compile("^(?:remove|delete|take|cross off|tick off) (?<item>.+?) (?:from|off) (?:my |the )?(?<list>[a-z]+(?: [a-z]+)?) list$").matcher(s)).find()) {
            Command c = new Command(Type.NOTE_REMOVE);
            c.text = m.group("list");
            c.message = m.group("item");
            return c;
        }
        if ((m = Pattern.compile("^(?:clear|empty|delete|erase)(?: all of| everything (?:on|in|from))? (?:my |the )?(?<list>[a-z]+(?: [a-z]+)?) list$").matcher(s)).find()) {
            Command c = new Command(Type.NOTE_CLEAR);
            c.text = m.group("list");
            return c;
        }
        if ((m = Pattern.compile("^(?:what(?:'s| is)(?: on| in)?|read(?: me)?|show(?: me)?|tell me|open|check)(?: out)?(?: what's on)? (?:my |the )?(?<list>[a-z]+(?: [a-z]+)?) list$|^(?:read|show|open|what are)(?: me)? (?:my |the )?(?<notes>notes)$").matcher(s)).find()) {
            Command c = new Command(Type.NOTE_READ);
            c.text = m.group("list") != null ? m.group("list") : "notes";
            return c;
        }

        // ---- memory ----
        if ((m = Pattern.compile("^(?:my name is|i am called|call me) (?<n>[a-z]+(?: [a-z]+)?)$").matcher(s)).find()
                && !m.group("n").matches("(back|later|now|when .*|at .*|tomorrow|tonight|in .*)")) {
            Command c = new Command(Type.SET_NAME);
            c.text = m.group("n");
            return c;
        }
        if ((m = Pattern.compile("^remember(?: that)? (?<fact>.+)$").matcher(s)).find() && !s.matches("^remember to .*")) {
            Command c = new Command(Type.REMEMBER);
            c.message = m.group("fact");
            return c;
        }
        if ((m = Pattern.compile("^forget (?:that |about |what i said about )?(?<q>.+)$").matcher(s)).find()) {
            Command c = new Command(Type.FORGET);
            c.text = m.group("q");
            return c;
        }

        // ---- music ----
        if ((m = Pattern.compile("^(?<a>play|resume|continue|pause|stop|next|skip|previous|go back)(?: to)?(?: the| this| that)?(?: music| song| track| audio| playback| video)?(?: please)?$|^(?<b>next|previous|last) (?:song|track)$|^skip (?:this |the )?(?:song|track)$").matcher(s)).find()) {
            String a = firstNonNull(m.group("a"), m.group("b"), "next");
            Command c = new Command(Type.MEDIA);
            c.text = a.matches("play|resume|continue") ? "play" : a.matches("pause|stop") ? "pause"
                : a.matches("previous|last|go back") ? "previous" : "next";
            return c;
        }

        // ---- location ----
        if ((m = Pattern.compile("^(?:share|send) (?:my )?(?:current |live )?location (?:to|with) (?<w>.+?)(?: on whatsapp| via whatsapp)?$").matcher(s)).find()) {
            Command c = new Command(Type.SHARE_LOCATION);
            c.text = m.group("w");
            return c;
        }
        if (s.matches("where am i( right now| now)?|what('s| is) my (current )?(location|address)|my (current )?location|where are we( now)?")) {
            return new Command(Type.WHERE_AM_I);
        }
        if ((m = Pattern.compile("^(?:navigate|take me|directions|get directions|give me directions|drive|show me the way|how do i get|route me) (?:to |for )?(?<d>.+)$").matcher(s)).find()
                && !s.matches("^go (back|to sleep|ahead|on)\\b.*")) {
            Command c = new Command(Type.NAVIGATE);
            c.text = m.group("d").replaceFirst("^the ", "");
            return c;
        }

        // ---- camera ----
        if ((m = Pattern.compile("^(?:take|click|capture|shoot|snap)(?: a| me a| my)? (?<k>selfie|photo|picture|pic|video)(?: of me)?$|^open (?:the )?(?<f>front |selfie )?camera$|^(?<k2>selfie)$").matcher(s)).find()) {
            Command c = new Command(Type.CAMERA);
            String k = firstNonNull(m.group("k"), m.group("k2"), m.group("f") != null ? "selfie" : "photo");
            c.text = k.equals("video") ? "video" : k.equals("selfie") ? "selfie" : "photo";
            return c;
        }

        // ---- translate ----
        if ((m = Pattern.compile("^translate (?<p>.+?) (?:to|into|in) (?<l>[a-z]+)$|^how (?:do|would) (?:you|i) say (?<p2>.+?) in (?<l2>[a-z]+)$|^what(?:'s| is) (?<p3>.+?) in (?<l3>urdu|english|arabic|punjabi|hindi|french|spanish|german|chinese|turkish|persian|pashto|sindhi)$").matcher(s)).find()) {
            Command c = new Command(Type.TRANSLATE);
            c.message = firstNonNull(m.group("p"), m.group("p2"), m.group("p3")).replaceAll("^[\"']|[\"']$", "");
            c.text = firstNonNull(m.group("l"), m.group("l2"), m.group("l3"));
            return c;
        }

        // ---- prayer times ----
        if ((m = Pattern.compile("\\b(?<p>fajr|fajar|sehri|sahri|suhoor|sunrise|zuhr|zohr|zohar|dhuhr|duhr|asr|maghrib|magrib|iftar|isha|esha|namaz|namaaz|prayer|prayers|salah|salat|azan|azaan|adhan)\\b").matcher(s)).find()
                && !s.matches("^(how|why) .*") && !s.matches(".*\\b(remind|alarm)\\b.*")) {
            String p = m.group("p");
            Command c = new Command(Type.PRAYER);
            c.text = p.matches("fajr|fajar|sehri|sahri|suhoor") ? "Fajr" : p.equals("sunrise") ? "Sunrise"
                : p.matches("zuhr|zohr|zohar|dhuhr|duhr") ? "Dhuhr" : p.equals("asr") ? "Asr"
                : p.matches("maghrib|magrib|iftar") ? "Maghrib" : p.matches("isha|esha") ? "Isha"
                : s.matches(".*\\b(next|upcoming|coming)\\b.*") ? "next" : "all";
            return c;
        }

        // ---- daily briefing ----
        if (s.matches("good morning|morning|(my |daily |morning )?briefing|brief me|(what's|how does|how is) my day( look| looking)?( like)?( today)?|plan my day|what's up today")) {
            return new Command(Type.BRIEFING);
        }
        // ---- timer (before alarm: "set a timer for 10 minutes") ----
        if (s.matches(".*\\b(timer|countdown|count down)\\b.*")) {
            long secs = parseDuration(s);
            if (secs > 0) {
                Command c = new Command(Type.TIMER);
                c.seconds = secs;
                return c;
            }
        }

        // ---- reminder ----
        if ((m = Pattern.compile("^remind me(?: to)? (.+)$").matcher(s)).find()) {
            return parseReminder(m.group(1), now);
        }

        // ---- alarm ----
        if (s.matches(".*\\b(alarm|wake me( up)?)\\b.*")) {
            // "Wake me up at 6" means morning; "alarm for 6" means whichever 6 comes next.
            LocalTime t = parseClock(s, now, !s.matches(".*\\bwake me( up)?\\b.*"));
            Command c = new Command(Type.ALARM);
            c.time = t; // null: the app asks "what time?"
            // "Set an alarm", "set alarm at", or a time we couldn't read: ask for the time.
            if (t != null || s.matches("^(set|create|add|make|put|wake)\\b.*") || s.matches("^(an |a |the |my )?alarm( at| for)?$")) return c;
        }

        // ---- web ----
        if ((m = Pattern.compile("^(?:open|go to|visit|browse to|load)(?: the)?(?: website| site| page| link)? (?<u>(?:https?://)?[a-z0-9-]+(?:\\.[a-z0-9-]+)+(?:/\\S*)?)$").matcher(s)).find()) {
            Command c = new Command(Type.OPEN_URL);
            c.text = m.group("u");
            return c;
        }
        if (s.matches("^(open|go to|start|launch)( the| my)? (browser|internet|web browser|chrome browser)( and.*)?$")) {
            Command c = new Command(Type.OPEN_URL);
            c.text = "https://www.google.com";
            return c;
        }
        if ((m = Pattern.compile("^(?:search|look up|find|play|watch)(?: for)? (?<q>.+?) on youtube$|^(?:search )?youtube(?: for)? (?<q2>.+)$|^(?:play|watch) (?<q3>.+?) (?:video|song)s?$").matcher(s)).find()) {
            Command c = new Command(Type.YOUTUBE_SEARCH);
            c.text = firstNonNull(m.group("q"), m.group("q2"), m.group("q3"));
            return c;
        }
        if ((m = Pattern.compile("^(?:search(?: the web| google| online| internet)?(?: for)?|google|look up|search for) (?<q>.+?)(?: on google| online| on the internet)?$").matcher(s)).find()) {
            Command c = new Command(Type.WEB_SEARCH);
            c.text = m.group("q");
            return c;
        }

        // ---- WhatsApp / SMS ----
        if ((m = Pattern.compile("^(?:send |write )?(?:a |an )?(?<kind>whatsapp|text|sms|message|msg)(?: message)? (?:to )?(?<who>.+?)(?:(?: saying| that says| that| says|:| and say| and tell (?:him|her|them)(?: that)?) (?<msg>.+))?$").matcher(s)).find()
                || (m = Pattern.compile("^tell (?<who>.+?) on (?<kind>whatsapp|text|sms)(?: that)? (?<msg>.+)$").matcher(s)).find()) {
            String kind = m.group("kind");
            if (m.group("who").matches("(me|myself|us)( .*)?")) return null; // "text me a joke" is a question
            Command c = new Command(kind.equals("text") || kind.equals("sms") ? Type.SMS : Type.WHATSAPP);
            c.text = m.group("who").replaceFirst(" on whatsapp$", "").trim();
            c.message = m.group("msg") == null ? null : m.group("msg").trim();
            return c;
        }

        // ---- volume / brightness / sound mode ----
        if ((s.matches(".*\\b(volume|louder|quieter|softer)\\b.*") && !s.matches("^(what|how|why|which|who|when) .*(of|for|in) (a|an|the) .*")) || s.matches("^(mute|unmute)( the)?( phone| sound| volume| media| music)?$")) {
            Command c = new Command(Type.VOLUME);
            c.text = level(s, "louder|up|increase|raise|higher|turn up|more", "quieter|softer|down|decrease|lower|reduce|turn down|less");
            return c;
        }
        if (s.matches(".*\\b(brightness|brighter|dimmer|darker)\\b.*") && !s.matches("^(what|how|why) .*")) {
            Command c = new Command(Type.BRIGHTNESS);
            c.text = level(s, "brighter|up|increase|raise|higher|turn up|more", "dimmer|darker|down|decrease|lower|reduce|turn down|less");
            return c;
        }
        if (s.matches(".*\\b(silent|vibrate|vibration|ringer|ring mode|sound mode|normal mode|general mode|do not disturb|dnd)\\b.*") && !s.matches("^(what|how|why) .*")) {
            Command c = new Command(Type.RINGER);
            c.text = s.matches(".*\\b(vibrat\\w*)\\b.*") ? "vibrate"
                : s.matches(".*\\b(normal|general|ring(er)? on|sound on|turn off (silent|do not disturb|dnd))\\b.*") ? "normal" : "silent";
            return c;
        }

        // ---- connection settings (Android only lets apps open these switches) ----
        if ((m = Pattern.compile("\\b(wi-?fi|wifi|bluetooth|mobile data|cellular data|internet|airplane mode|flight mode|aeroplane mode|hotspot|location|gps)\\b").matcher(s)).find()
                && s.matches("^(turn|switch|enable|disable|open|start|stop|connect|disconnect|put|activate|deactivate|show|on|off)\\b.*|.*\\b(on|off)$")) {
            String w = m.group(1);
            Command c = new Command(Type.SETTING);
            c.text = w.startsWith("wi") ? "wifi" : w.equals("bluetooth") ? "bluetooth"
                : w.contains("data") || w.equals("internet") ? "data"
                : w.contains("mode") ? "airplane" : w.equals("hotspot") ? "hotspot" : "location";
            return c;
        }

        // ---- contact numbers ----
        if ((m = Pattern.compile("^(?:what(?:'s| is)|give me|tell me|show me|find|get)(?: me)? (?<w>.+?)(?:'s|s') (?:phone |mobile |cell )?number$|^(?:what(?:'s| is)|give me|tell me|find) the (?:phone )?number (?:of|for) (?<w2>.+)$").matcher(s)).find()) {
            Command c = new Command(Type.CONTACT_INFO);
            c.text = firstNonNull(m.group("w"), m.group("w2"));
            return c;
        }

        // ---- call ----
        if ((m = Pattern.compile("^(?:please )?(?:call|phone|dial|ring) (.+?)(?: please| now| for me)?$").matcher(s)).find()) {
            Command c = new Command(Type.CALL);
            c.text = m.group(1).replaceFirst("^(to )", "").trim();
            return c;
        }

        // ---- open app ----
        if ((m = Pattern.compile("^(?:please )?(?:open|launch|start|run|go to) (?:the )?(.+?)(?: app| application)?(?: please| for me)?$").matcher(s)).find()) {
            Command c = new Command(Type.OPEN_APP);
            c.text = m.group(1).trim();
            return c;
        }

        // ---- notifications ----
        if ((m = Pattern.compile("\\b(summari[sz]e|read|check|show)(?: out)?(?: me)?(?: my| the)?(?: new| latest| recent| unread)? ?(\\w+)? ?(messages|notifications|notification|chats|texts|msgs)\\b").matcher(s)).find()
                || s.matches(".*\\b(what did i miss|any new messages|did anyone (message|text) me)\\b.*")) {
            Command c = new Command(Type.NOTIFICATIONS);
            String app = m.find(0) && m.group(2) != null ? m.group(2) : null;
            if (app != null && !app.matches("new|latest|recent|unread|all|my|the")) c.text = app;
            return c;
        }

        // ---- quick facts ----
        if (s.matches("(what('s| is) the )?time( is it)?( now)?|what time is it( now)?|tell me the time|current time"))
            return new Command(Type.TIME);
        if (s.matches("(what('s| is) )?(the |today's )?date( today)?|what day is (it|today)( today)?|what's today('s date)?|today's date"))
            return new Command(Type.DATE);
        if (s.matches(".*\\bbattery\\b.*")) return new Command(Type.BATTERY);
        if (s.matches(".*\\b(weather|temperature|forecast|will it rain|is it (going to )?rain(ing)?|how (hot|cold) is it)\\b.*"))
            return new Command(Type.WEATHER);
        if (s.matches("(what('s| is) (the )?)?(latest |today's |top )?(news|headlines)( today| now)?|(tell|give|read) me the (latest )?(news|headlines)|what's happening( in the world)?"))
            return new Command(Type.NEWS);

        // ---- things you told me to remember ----
        if ((m = Pattern.compile("^where (?:did|have) i (?<q1>.+)$|^where (?:is|are) my (?<q2>.+)$|^what(?:'s| is| was| are| were) my (?<q3>.+)$|^when(?:'s| is| was) my (?<q4>.+)$|^do you remember (?<q5>.+)$|^what(?:'s| is) (?<q6>.+?) i (?:told|asked) you to remember$").matcher(s)).find()) {
            Command c = new Command(Type.RECALL);
            c.text = firstNonNull(m.group("q1"), m.group("q2"), m.group("q3"), m.group("q4"), m.group("q5"), m.group("q6"));
            return c;
        }
        if (s.matches("what do you (know|remember)( about me)?|what have i told you|(show|list) (my )?memories|what did i ask you to remember")) {
            return new Command(Type.RECALL);
        }

        // Sounds like an instruction for the phone that we can't do: say so honestly.
        if (s.matches("^(turn|switch|set|send|book|order|pay|delete|install|uninstall|enable|disable|increase|decrease|lower|raise|connect|disconnect|take|record|share|lock|unlock|shut ?down|restart|reboot) .*")
                || s.matches(".*\\b(screenshot|screen record\\w*)\\b.*")) {
            Command c = new Command(Type.UNSUPPORTED);
            c.text = s;
            return c;
        }

        return null;
    }

    /**
     * Short follow-ups that only make sense after the previous command, like "now off it" or
     * "turn it off" after turning the flashlight on. Returns null if [input] isn't one.
     */
    public static Command followUp(String input, Type previous) {
        if (previous == null || input == null) return null;
        String s = normalize(input);
        boolean flash = previous == Type.FLASHLIGHT_ON || previous == Type.FLASHLIGHT_OFF;
        if (flash && s.matches("^(now |ok |okay |and )?(please )?((turn|switch|put) )?(it |that |this )?(off|band|bund|close)( it| that| this| now| again| please)*$"))
            return new Command(Type.FLASHLIGHT_OFF);
        if (flash && s.matches("^(now |ok |okay |and )?(please )?((turn|switch|put) )?(it |that |this )?(on|back on)( it| that| this| now| again| please)*$"))
            return new Command(Type.FLASHLIGHT_ON);
        return null;
    }

    private static final Pattern CONVERT_A = Pattern.compile(
        "^(?:convert |change |what is |what's |how much is |how much are |how many )?(?<v>\\$?\\d+(?:\\.\\d+)?|a|an|one)? ?(?<from>[a-z/°$\" .]+?) (?:to|in|into|in to|as|equals?) (?<to>[a-z/°$ .]+?)$");
    private static final Pattern CONVERT_B = Pattern.compile(
        "^how many (?<to>[a-z/° .]+?) (?:are |is )?(?:there )?in (?:a |an |one |(?<v>\\d+(?:\\.\\d+)?) )?(?<from>[a-z/° .]+?)$");

    /** "5 km to miles", "how many grams in a tola", "100 dollars in rupees". */
    private static Command parseConversion(String s) {
        for (Pattern p : new Pattern[] {CONVERT_A, CONVERT_B}) {
            Matcher m = p.matcher(s);
            if (!m.find()) continue;
            String from = m.group("from").trim(), to = m.group("to").trim();
            String v = m.group("v");
            if (from.startsWith("$")) { from = "usd"; }
            double value = v == null || v.matches("a|an|one") ? 1 : Double.parseDouble(v.replace("$", ""));
            if (v != null && v.startsWith("$")) from = from.isEmpty() ? "usd" : from;
            boolean units = UnitConverter.isUnit(from) && UnitConverter.isUnit(to) && UnitConverter.convert(1, from, to) != null;
            boolean money = UnitConverter.currency(from) != null && UnitConverter.currency(to) != null;
            if (!units && !money) continue;
            Command c = new Command(Type.CONVERT);
            c.value = value;
            c.text = money ? UnitConverter.currency(from) : from;
            c.message = money ? UnitConverter.currency(to) : to;
            return c;
        }
        return null;
    }

    /** "max", "min", "mute", "unmute", "up", "down", "status" or a percentage like "40". */
    private static String level(String s, String upWords, String downWords) {
        Matcher pct = Pattern.compile("\\b(\\d{1,3}) ?(%|percent)?").matcher(s);
        if (pct.find()) return String.valueOf(Math.min(100, Integer.parseInt(pct.group(1))));
        if (s.matches(".*\\bunmute\\b.*")) return "unmute";
        if (s.matches(".*\\b(mute|silence|zero|off)\\b.*")) return "mute";
        if (s.matches(".*\\b(max|maximum|full|highest|all the way up)\\b.*")) return "max";
        if (s.matches(".*\\b(min|minimum|lowest|all the way down)\\b.*")) return "min";
        if (s.matches(".*\\b(half|medium|middle)\\b.*")) return "50";
        if (s.matches(".*\\b(" + upWords + ")\\b.*")) return "up";
        if (s.matches(".*\\b(" + downWords + ")\\b.*")) return "down";
        return "status";
    }

    private static String firstNonNull(String... xs) {
        for (String x : xs) if (x != null) return x;
        return null;
    }

    // ---- reminders ---------------------------------------------------------------------

    private static Command parseReminder(String rest, LocalDateTime now) {
        // "in 20 minutes to call Ali" / "to call Ali in 20 minutes"
        String what = rest;
        LocalDateTime at = null;
        Matcher m;

        Pattern relative = Pattern.compile("\\bin (\\d+|an?|half an?|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty|forty|forty five|fifty|sixty) ?(hours?|hrs?|minutes?|mins?|seconds?|secs?|hour|days?)\\b(?: and (\\d+) (minutes?|mins?))?");
        if ((m = relative.matcher(what)).find()) {
            long secs = parseDuration(m.group(0));
            if (secs > 0) at = now.plusSeconds(secs);
            what = cut(what, m);
        } else {
            LocalDate day = now.toLocalDate();
            boolean explicitDay = false;
            boolean tonight = false;
            if ((m = Pattern.compile("\\b(tomorrow|tonight|today)\\b").matcher(what)).find()) {
                if (m.group(1).equals("tomorrow")) day = day.plusDays(1);
                tonight = m.group(1).equals("tonight");
                explicitDay = !m.group(1).equals("today");
                what = cut(what, m);
            } else if ((m = Pattern.compile("\\b(?:on |this |next |coming )?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b").matcher(what)).find()) {
                java.time.DayOfWeek target = java.time.DayOfWeek.valueOf(m.group(1).toUpperCase(java.util.Locale.US));
                int ahead = (target.getValue() - day.getDayOfWeek().getValue() + 7) % 7;
                if (ahead == 0 && m.group(0).startsWith("next")) ahead = 7;
                day = day.plusDays(ahead);
                explicitDay = ahead > 0;
                what = cut(what, m);
            }
            if ((m = CLOCK.matcher(what)).find()) {
                LocalTime t = clockFrom(m, tonight);
                // A bare "at 5" on another day means 5 pm: nobody books things at 1-6 am by default.
                if (t != null && explicitDay && m.group(1) == null && m.group(4) == null && t.getHour() >= 1 && t.getHour() <= 6) {
                    t = t.plusHours(12);
                }
                if (t != null) {
                    at = LocalDateTime.of(day, t);
                    if (!explicitDay && !at.isAfter(now)) {
                        // "at 5" with no am/pm: try the pm one today before rolling over to tomorrow.
                        LocalDateTime pm = m.group(4) == null && t.getHour() < 12 ? at.plusHours(12) : null;
                        at = pm != null && pm.isAfter(now) ? pm : at.plusDays(1);
                    }
                    what = cut(what, m);
                }
            } else if (explicitDay) {
                at = LocalDateTime.of(day, tonight ? LocalTime.of(21, 0) : LocalTime.of(9, 0));
            }
        }

        what = what.replaceAll("^\\s*(to|that|about)\\s+", "").replaceAll("\\s+(to|at|on)$", "").trim();
        if (what.isEmpty()) what = "Reminder";
        Command c = new Command(Type.REMINDER);
        c.text = what;
        c.at = at; // null = time missing; the app asks for it
        return c;
    }

    private static String cut(String s, Matcher m) {
        return (s.substring(0, m.start()) + " " + s.substring(m.end())).replaceAll("\\s+", " ").trim();
    }

    // ---- clock times ---------------------------------------------------------------------

    /** "7", "7:30", "7 30", "7 am", "7:30 pm", "19:45", "7 o'clock", "noon", "midnight", with optional "at". */
    private static final Pattern CLOCK = Pattern.compile(
        "\\b(?:at |for )?(?:(noon|midday|midnight)|(\\d{1,2})(?:[: ](\\d{2}))?(?: ?o'?clock)? ?(am|pm|in the morning|in the evening|in the afternoon|at night|tonight)?)\\b");

    /** Time of day mentioned in [s]. With [nextOccurrence], a bare "7" means whichever 7 comes first. */
    static LocalTime parseClock(String s, LocalDateTime now, boolean nextOccurrence) {
        Matcher m = CLOCK.matcher(s);
        while (m.find()) {
            if (m.group(1) == null && m.group(2) == null) continue;
            LocalTime t = clockFrom(m, false);
            if (t == null) continue;
            if (nextOccurrence && m.group(1) == null && m.group(4) == null && t.getHour() < 12) {
                LocalTime pm = t.plusHours(12);
                LocalTime nowT = now.toLocalTime();
                // The earliest upcoming of the two.
                if (!t.isAfter(nowT) && pm.isAfter(nowT)) return pm;
            }
            return t;
        }
        return null;
    }

    private static LocalTime clockFrom(Matcher m, boolean preferPm) {
        if (m.group(1) != null) return m.group(1).equals("midnight") ? LocalTime.MIDNIGHT : LocalTime.NOON;
        if (m.group(2) == null) return null;
        int h = Integer.parseInt(m.group(2));
        int min = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
        String mer = m.group(4);
        if (h > 23 || min > 59) return null;
        if (mer != null) {
            boolean pm = mer.equals("pm") || mer.contains("evening") || mer.contains("afternoon") || mer.contains("night");
            if (h > 12) return null;
            if (pm && h < 12) h += 12;
            if (!pm && h == 12) h = 0;
        } else if (preferPm && h < 12) {
            h += 12;
        }
        return LocalTime.of(h, min);
    }

    // ---- durations -----------------------------------------------------------------------

    private static final Map<String, Integer> NUMBERS = new HashMap<>();
    static {
        String[] words = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty"};
        for (int i = 0; i < words.length; i++) NUMBERS.put(words[i], i);
        NUMBERS.put("thirty", 30); NUMBERS.put("forty", 40); NUMBERS.put("forty five", 45);
        NUMBERS.put("fifty", 50); NUMBERS.put("sixty", 60); NUMBERS.put("ninety", 90);
        NUMBERS.put("a", 1); NUMBERS.put("an", 1);
    }

    private static final Pattern DURATION_PART = Pattern.compile(
        "(\\d+(?:\\.\\d+)?|forty five|[a-z]+) ?(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s|days?)\\b");

    /** Total seconds in phrases like "1 hour 20 minutes", "half an hour", "ten minutes", "90 seconds". */
    static long parseDuration(String s) {
        long total = 0;
        if (s.matches(".*\\bhalf an? hour\\b.*")) {
            total += 1800;
            s = s.replaceAll("\\bhalf an? hour\\b", " ");
        }
        if (s.matches(".*\\ban? hour and a half\\b.*")) {
            total += 5400;
            s = s.replaceAll("\\ban? hour and a half\\b", " ");
        }
        Matcher m = DURATION_PART.matcher(s);
        while (m.find()) {
            Double n = number(m.group(1));
            if (n == null) continue;
            String unit = m.group(2);
            long mult = unit.startsWith("d") ? 86400 : unit.startsWith("h") ? 3600 : unit.startsWith("m") ? 60 : 1;
            total += Math.round(n * mult);
        }
        return total;
    }

    private static Double number(String w) {
        if (w.matches("\\d+(\\.\\d+)?")) return Double.parseDouble(w);
        Integer n = NUMBERS.get(w);
        return n == null ? null : n.doubleValue();
    }

    // ---- text clean-up ---------------------------------------------------------------------

    static String normalize(String input) {
        String s = input.toLowerCase(Locale.US).trim();
        s = s.replace("a.m.", "am").replace("p.m.", "pm").replace("a. m.", "am").replace("p. m.", "pm");
        s = s.replaceAll("(\\d)(am|pm)\\b", "$1 $2");
        s = s.replaceAll("[?!.,]+$", "").replaceAll("[,]", "");
        s = s.replaceAll("^((hey|ok|okay|hi) (assistant|google) |(hey|ok|okay|hi) |assistant )", "");
        s = s.replaceAll("^(can you |could you |would you |will you |please |i want you to |i need you to )+", "");
        s = s.replaceAll("\\s+(please|for me)$", "");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    /** Human-friendly "in 2 h 5 min" for confirmations. */
    public static String describe(Duration d) {
        long m = Math.max(1, Math.round(d.getSeconds() / 60.0));
        long h = m / 60;
        m %= 60;
        if (h == 0) return m + " min";
        return m == 0 ? h + " h" : h + " h " + m + " min";
    }
}
