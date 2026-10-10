package com.mudassir.assistant.commands;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import org.junit.Test;

/** The exact engines: maths, unit conversion and prayer times. */
public class EnginesTest {

    private static String calc(String spoken) {
        String e = Calculator.toExpression(spoken);
        return e == null ? null : Calculator.format(Calculator.eval(e));
    }

    @Test
    public void calculator() {
        assertEquals("360", calc("what is 15 percent of 2400"));
        assertEquals("15", calc("5 into 3"));
        assertEquals("14", calc("2+3*4"));
        assertEquals("20", calc("(2+3)*4"));
        assertEquals("9", calc("square root of 81"));
        assertEquals("1,024", calc("2 to the power of 10"));
        assertEquals("875,000", calc("1250000 minus 375000"));
        assertEquals("3.33", calc("10 divided by 3"));
        assertEquals("-3", calc("-5 plus 2"));
        assertNull(calc("what is the capital of france"));
        assertNull(calc("what is 5"));
    }

    @Test
    public void units() {
        assertEquals("3.11", Calculator.format(UnitConverter.convert(5, "km", "miles")));
        assertEquals("2,250", Calculator.format(UnitConverter.convert(10, "marla", "square feet")));
        assertEquals("20", Calculator.format(UnitConverter.convert(1, "kanal", "marla")));
        assertEquals("37.78", Calculator.format(UnitConverter.convert(100, "fahrenheit", "celsius")));
        assertEquals("11.66", Calculator.format(UnitConverter.convert(1, "tola", "grams")));
        assertNull(UnitConverter.convert(1, "km", "kg"));
        assertEquals("PKR", UnitConverter.currency("rupees"));
    }

    /** Reference times from the Adhan library (Karachi method, Hanafi Asr): we must be within 1 minute. */
    private static final String[][] REFERENCE = {
            {"Lahore", "31.5204", "74.3587", "2026-10-11", "04:42", "06:03", "11:50", "15:56", "17:36", "18:56"},
            {"Lahore", "31.5204", "74.3587", "2026-06-21", "03:19", "04:58", "12:05", "17:01", "19:11", "20:50"},
            {"Lahore", "31.5204", "74.3587", "2026-12-21", "05:31", "06:58", "12:02", "15:26", "17:03", "18:30"},
            {"Lahore", "31.5204", "74.3587", "2026-03-20", "04:46", "06:07", "12:11", "16:31", "18:14", "19:35"},
            {"Karachi", "24.8607", "67.0011", "2026-10-11", "05:12", "06:28", "12:20", "16:31", "18:09", "19:25"},
            {"Karachi", "24.8607", "67.0011", "2026-06-21", "04:14", "05:43", "12:35", "17:16", "19:24", "20:53"},
            {"Karachi", "24.8607", "67.0011", "2026-12-21", "05:51", "07:12", "12:31", "16:12", "17:48", "19:09"},
            {"Karachi", "24.8607", "67.0011", "2026-03-20", "05:20", "06:36", "12:40", "17:02", "18:43", "19:59"},
            {"Islamabad", "33.6844", "73.0479", "2026-10-11", "04:47", "06:09", "11:56", "15:59", "17:39", "19:02"},
            {"Islamabad", "33.6844", "73.0479", "2026-06-21", "03:14", "04:58", "12:11", "17:10", "19:21", "21:05"},
            {"Islamabad", "33.6844", "73.0479", "2026-12-21", "05:39", "07:08", "12:07", "15:25", "17:03", "18:33"},
            {"Islamabad", "33.6844", "73.0479", "2026-03-20", "04:49", "06:12", "12:16", "16:36", "18:19", "19:42"}
    };

    @Test
    public void prayerTimesMatchReference() {
        for (String[] r : REFERENCE) {
            Map<String, LocalTime> mine = PrayerTimes.pakistan(Double.parseDouble(r[1]), Double.parseDouble(r[2]), 5).on(LocalDate.parse(r[3]));
            int i = 4;
            for (Map.Entry<String, LocalTime> e : mine.entrySet()) {
                long diff = Math.abs(Duration.between(LocalTime.parse(r[i++]), e.getValue()).toMinutes());
                assertTrue(r[0] + " " + r[3] + " " + e.getKey() + " off by " + diff + " min", diff <= 1);
            }
        }
    }
}
