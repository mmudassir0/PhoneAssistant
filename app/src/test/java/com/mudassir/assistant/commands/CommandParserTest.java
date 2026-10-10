package com.mudassir.assistant.commands;

import static org.junit.Assert.assertEquals;

import java.time.LocalDateTime;
import org.junit.Test;

/** Phrasings the parser must understand, checked against a fixed "now": Saturday 2:00 PM. */
public class CommandParserTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 14, 0);

    private static final String[][] CASES = {
            {"Set an alarm for 7:30 a.m.", "ALARM time=07:30"},
            {"wake me up at 6", "ALARM time=06:00"},
            {"set alarm for 5", "ALARM time=17:00"},
            {"alarm at 9:45 pm", "ALARM time=21:45"},
            {"set an alarm for 19:15", "ALARM time=19:15"},
            {"Hey assistant, can you set an alarm for 12 noon please?", "ALARM time=12:00"},
            {"set an alarm for noon", "ALARM time=12:00"},
            {"wake me up at 7 in the morning", "ALARM time=07:00"},
            {"set an alarm for 6 30", "ALARM time=18:30"},
            {"set an alarm", "null"},
            {"wake me up at 8 pm", "ALARM time=20:00"},
            {"set an alarm for 11:59 pm", "ALARM time=23:59"},
            {"set an alarm for 25:00", "null"},
            {"set a timer for 10 minutes", "TIMER seconds=600"},
            {"timer 90 seconds", "TIMER seconds=90"},
            {"start a timer for 1 hour and 20 minutes", "TIMER seconds=4800"},
            {"set a timer for half an hour", "TIMER seconds=1800"},
            {"set a timer for five minutes", "TIMER seconds=300"},
            {"countdown 2 min", "TIMER seconds=120"},
            {"remind me to call Ali at 5 pm", "REMINDER at=2026-10-10T17:00 text='call ali'"},
            {"Remind me in 20 minutes to take medicine", "REMINDER at=2026-10-10T14:20 text='take medicine'"},
            {"remind me to drink water in an hour", "REMINDER at=2026-10-10T15:00 text='drink water'"},
            {"remind me tomorrow at 9 to pay the electricity bill", "REMINDER at=2026-10-11T09:00 text='pay the electricity bill'"},
            {"remind me to submit the report tomorrow", "REMINDER at=2026-10-11T09:00 text='submit the report'"},
            {"remind me at 5 to pick up the kids", "REMINDER at=2026-10-10T17:00 text='pick up the kids'"},
            {"remind me to pray at 1:30", "REMINDER at=2026-10-11T01:30 text='pray'"},
            {"remind me tonight at 9 to call mom", "REMINDER at=2026-10-10T21:00 text='call mom'"},
            {"remind me to buy milk", "REMINDER text='buy milk'"},
            {"call Ali", "CALL text='ali'"},
            {"please call mom", "CALL text='mom'"},
            {"call 0300 1234567", "CALL text='0300 1234567'"},
            {"open WhatsApp", "OPEN_APP text='whatsapp'"},
            {"launch the camera app", "OPEN_APP text='camera'"},
            {"open youtube please", "OPEN_APP text='youtube'"},
            {"what time is it?", "TIME"},
            {"What's the time", "TIME"},
            {"what's the date today", "DATE"},
            {"what day is it", "DATE"},
            {"how much battery do I have", "BATTERY"},
            {"what's the weather like", "WEATHER"},
            {"will it rain today", "WEATHER"},
            {"what's the news", "NEWS"},
            {"tell me the latest headlines", "NEWS"},
            {"turn on the flashlight", "FLASHLIGHT_ON"},
            {"turn the torch off", "FLASHLIGHT_OFF"},
            {"summarize my WhatsApp messages", "NOTIFICATIONS text='whatsapp'"},
            {"read my notifications", "NOTIFICATIONS"},
            {"what did I miss", "NOTIFICATIONS"},
            {"new chat", "NEW_CHAT"},
            {"what is the capital of France", "null"},
            {"tell me a joke", "null"},
            {"explain how alarms work in android", "null"},
            {"how do I open a bank account", "null"},
            {"who called the meeting", "null"}
    };

    @Test
    public void understandsCommands() {
        StringBuilder failures = new StringBuilder();
        for (String[] c : CASES) {
            CommandParser.Command cmd = CommandParser.parse(c[0], NOW);
            String got = cmd == null ? "null" : cmd.toString();
            if (!got.equals(c[1])) failures.append("\n  \"").append(c[0]).append("\" -> ").append(got).append("  (expected ").append(c[1]).append(")");
        }
        assertEquals("Misunderstood:" + failures, 0, failures.length());
    }
}
