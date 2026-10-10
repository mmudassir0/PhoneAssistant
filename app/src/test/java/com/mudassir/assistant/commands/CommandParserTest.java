package com.mudassir.assistant.commands;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
            {"set an alarm", "ALARM"},
            {"wake me up at 8 pm", "ALARM time=20:00"},
            {"set an alarm for 11:59 pm", "ALARM time=23:59"},
            {"set an alarm for 25:00", "ALARM"},
            {"ok google set a timer for 2 minutes", "TIMER seconds=120"},
            {"what is an alarm clock", "null"},
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
            {"who called the meeting", "null"},
            {"Turn flashlight on", "FLASHLIGHT_ON"},
            {"flashlight off", "FLASHLIGHT_OFF"},
            {"turn off flash light", "FLASHLIGHT_OFF"},
            {"torch band karo", "FLASHLIGHT_OFF"},
            {"flashlight", "FLASHLIGHT_ON"},
            {"how does a flashlight work", "null"},
            {"Set alarm at", "ALARM"},
            {"set alarm", "ALARM"},
            {"Set alarm at 5:00 p.m.", "ALARM time=17:00"},
            {"Set alarm at 2:00 a.m.", "ALARM time=02:00"},
            {"Open Google.com", "OPEN_URL text='google.com'"},
            {"go to https://github.com/mmudassir0", "OPEN_URL text='https://github.com/mmudassir0'"},
            {"open the website psx.com.pk", "OPEN_URL text='psx.com.pk'"},
            {"Go to browser and open link", "OPEN_URL text='https://www.google.com'"},
            {"open browser", "OPEN_URL text='https://www.google.com'"},
            {"Open my whatsapp", "OPEN_APP text='my whatsapp'"},
            {"Open chrome", "OPEN_APP text='chrome'"},
            {"search for best biryani in lahore", "WEB_SEARCH text='best biryani in lahore'"},
            {"google weather radar", "WEB_SEARCH text='weather radar'"},
            {"look up psx kse 100 index", "WEB_SEARCH text='psx kse 100 index'"},
            {"play coke studio on youtube", "YOUTUBE_SEARCH text='coke studio'"},
            {"search youtube for kotlin tutorial", "YOUTUBE_SEARCH text='kotlin tutorial'"},
            {"turn up the volume", "UNSUPPORTED text='turn up the volume'"},
            {"send a message to ali", "UNSUPPORTED text='send a message to ali'"},
            {"take a screenshot", "UNSUPPORTED text='take a screenshot'"},
            {"Not work", "null"},
            {"Still flashlight is on", "FLASHLIGHT_ON"}
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

    @Test
    public void flashlightFollowUps() {
        CommandParser.Type on = CommandParser.Type.FLASHLIGHT_ON;
        for (String off : new String[]{"Now off it", "off", "turn it off", "now turn it off please"})
            assertEquals(off, CommandParser.Type.FLASHLIGHT_OFF, CommandParser.followUp(off, on).type);
        for (String back : new String[]{"on again", "turn it back on"})
            assertEquals(back, CommandParser.Type.FLASHLIGHT_ON, CommandParser.followUp(back, CommandParser.Type.FLASHLIGHT_OFF).type);
        assertNull(CommandParser.followUp("off", CommandParser.Type.ALARM));
        assertNull(CommandParser.followUp("off", null));
    }
}
