package com.mudassir.assistant.commands;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Offline prayer times, using the standard astronomical calculation (as on praytimes.org).
 * Defaults match common practice in Pakistan: University of Islamic Sciences, Karachi
 * (Fajr and Isha at 18°) with Hanafi Asr (shadow twice an object's length).
 */
public final class PrayerTimes {

    public static final String[] NAMES = {"Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha"};

    private final double lat, lng, tz, fajrAngle, ishaAngle, asrFactor;
    private double jd;

    /**
     * @param tzHours the place's UTC offset in hours on that date (Pakistan: 5)
     * @param hanafi  true for Hanafi Asr (factor 2), false for the majority view (factor 1)
     */
    public PrayerTimes(double lat, double lng, double tzHours, double fajrAngle, double ishaAngle, boolean hanafi) {
        this.lat = lat;
        this.lng = lng;
        this.tz = tzHours;
        this.fajrAngle = fajrAngle;
        this.ishaAngle = ishaAngle;
        this.asrFactor = hanafi ? 2 : 1;
    }

    /** Karachi method with Hanafi Asr, the usual choice in Pakistan. */
    public static PrayerTimes pakistan(double lat, double lng, double tzHours) {
        return new PrayerTimes(lat, lng, tzHours, 18, 18, true);
    }

    /** Times for [date], in order: Fajr, Sunrise, Dhuhr, Asr, Maghrib, Isha (null if the sun never gets there). */
    public Map<String, LocalTime> on(LocalDate date) {
        jd = julian(date.getYear(), date.getMonthValue(), date.getDayOfMonth()) - lng / (15 * 24.0);
        double[] t = {5, 6, 12, 13, 18, 18}; // first guesses (hours, solar time)
        for (int i = 0; i < 2; i++) { // refine using the sun's position at each estimate
            t = new double[] {
                sunAngleTime(fajrAngle, t[0], true),
                sunAngleTime(0.833, t[1], true),
                midDay(t[2]),
                asrTime(t[3]),
                sunAngleTime(0.833, t[4], false),
                sunAngleTime(ishaAngle, t[5], false),
            };
        }
        Map<String, LocalTime> out = new LinkedHashMap<>();
        t[2] += 1 / 60.0; // Dhuhr: the customary one minute after true noon
        for (int i = 0; i < t.length; i++) out.put(NAMES[i], toTime(t[i] + tz - lng / 15.0));
        return out;
    }

    // ---- astronomy ----

    private static double julian(int year, int month, int day) {
        if (month <= 2) { year -= 1; month += 12; }
        double a = Math.floor(year / 100.0);
        double b = 2 - a + Math.floor(a / 4);
        return Math.floor(365.25 * (year + 4716)) + Math.floor(30.6001 * (month + 1)) + day + b - 1524.5;
    }

    /** {declination, equation of time} of the sun [time] hours into the day. */
    private double[] sun(double time) {
        double d = jd + time / 24 - 2451545.0;
        double g = fix(357.529 + 0.98560028 * d, 360);
        double q = fix(280.459 + 0.98564736 * d, 360);
        double l = fix(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g), 360);
        double e = 23.439 - 0.00000036 * d;
        double ra = darctan2(dcos(e) * dsin(l), dcos(l)) / 15;
        return new double[] {darcsin(dsin(e) * dsin(l)), q / 15 - fix(ra, 24)};
    }

    private double midDay(double time) {
        return fix(12 - sun(time)[1], 24);
    }

    /** When the sun is [angle] degrees below the horizon, before or after noon. */
    private double sunAngleTime(double angle, double time, boolean beforeNoon) {
        double decl = sun(time)[0];
        double cosT = (-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat));
        if (cosT < -1 || cosT > 1) return Double.NaN;
        double t = darccos(cosT) / 15;
        return midDay(time) + (beforeNoon ? -t : t);
    }

    private double asrTime(double time) {
        double decl = sun(time)[0];
        double angle = -darccot(asrFactor + dtan(Math.abs(lat - decl)));
        return sunAngleTime(angle, time, false);
    }

    private static LocalTime toTime(double hours) {
        if (Double.isNaN(hours)) return null;
        double h = fix(hours + 0.5 / 60.0, 24); // round to the nearest minute
        int hh = (int) Math.floor(h);
        int mm = (int) Math.floor((h - hh) * 60);
        return LocalTime.of(hh, Math.min(mm, 59));
    }

    private static double fix(double a, double b) {
        a = a - b * Math.floor(a / b);
        return a < 0 ? a + b : a;
    }

    private static double dsin(double d) { return Math.sin(Math.toRadians(d)); }
    private static double dcos(double d) { return Math.cos(Math.toRadians(d)); }
    private static double dtan(double d) { return Math.tan(Math.toRadians(d)); }
    private static double darcsin(double x) { return Math.toDegrees(Math.asin(x)); }
    private static double darccos(double x) { return Math.toDegrees(Math.acos(x)); }
    private static double darctan2(double y, double x) { return Math.toDegrees(Math.atan2(y, x)); }
    private static double darccot(double x) { return Math.toDegrees(Math.atan(1 / x)); }
}
