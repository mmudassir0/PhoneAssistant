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
        NOTIFICATIONS, NEWS, WEATHER, FLASHLIGHT_ON, FLASHLIGHT_OFF, NEW_CHAT
    }

    /** A recognised command. Only the fields relevant to its type are set. */
    public static final class Command {
        public final Type type;
        /** ALARM: time of day. */
        public LocalTime time;
        /** REMINDER: when to remind. */
        public LocalDateTime at;
        /** TIMER: length in seconds. */
        public long seconds;
        /** REMINDER: what to remind about. CALL: who. OPEN_APP: app name. NOTIFICATIONS: app filter (may be null). */
        public String text;

        Command(Type type) { this.type = type; }

        @Override public String toString() {
            return type + (time != null ? " time=" + time : "") + (at != null ? " at=" + at : "")
                + (seconds > 0 ? " seconds=" + seconds : "") + (text != null ? " text='" + text + "'" : "");
        }
    }

    // ---------------------------------------------------------------------------------------

    public static Command parse(String input, LocalDateTime now) {
        if (input == null) return null;
        String s = normalize(input);
        if (s.isEmpty()) return null;
        Matcher m;

        if (s.matches("(start a )?(new|fresh) (chat|conversation)|clear (the )?chat|forget (everything|that|it)"))
            return new Command(Type.NEW_CHAT);

        // ---- flashlight ----
        if ((m = Pattern.compile("^(turn|switch|put) (on|off) (the )?(flash ?light|torch)$|^(turn|switch|put) (the )?(flash ?light|torch) (on|off)$|^(flash ?light|torch) (on|off)$").matcher(s)).find()) {
            return new Command(s.contains(" off") ? Type.FLASHLIGHT_OFF : Type.FLASHLIGHT_ON);
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
            if (t != null) {
                Command c = new Command(Type.ALARM);
                c.time = t;
                return c;
            }
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
            }
            if ((m = CLOCK.matcher(what)).find()) {
                LocalTime t = clockFrom(m, tonight);
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
        s = s.replaceAll("^(hey |ok |okay |hi )?(assistant |google )?", "");
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
