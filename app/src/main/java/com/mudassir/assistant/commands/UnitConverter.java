package com.mudassir.assistant.commands;

import java.util.HashMap;
import java.util.Map;

/**
 * Offline unit conversion, including Pakistani units (marla, kanal, tola, maund).
 * Currency codes are recognised here but converted online by the app.
 */
public final class UnitConverter {

    private UnitConverter() {}

    /** A unit: its kind, and how many base units (metre, kilogram, litre, …) one of it is. */
    private static final class Unit {
        final String kind, name;
        final double factor;
        Unit(String kind, String name, double factor) { this.kind = kind; this.name = name; this.factor = factor; }
    }

    private static final Map<String, Unit> UNITS = new HashMap<>();
    private static final Map<String, String> CURRENCIES = new HashMap<>();

    private static void add(String kind, String name, double factor, String... aliases) {
        Unit u = new Unit(kind, name, factor);
        UNITS.put(name, u);
        for (String a : aliases) UNITS.put(a, u);
    }

    static {
        // length (metre)
        add("length", "millimetres", 0.001, "mm", "millimeter", "millimeters", "millimetre");
        add("length", "centimetres", 0.01, "cm", "centimeter", "centimeters", "centimetre");
        add("length", "metres", 1, "m", "meter", "meters", "metre");
        add("length", "kilometres", 1000, "km", "kms", "kilometer", "kilometers", "kilometre");
        add("length", "inches", 0.0254, "in", "inch", "\"");
        add("length", "feet", 0.3048, "ft", "foot");
        add("length", "yards", 0.9144, "yd", "yard");
        add("length", "miles", 1609.344, "mi", "mile");
        // mass (kilogram)
        add("mass", "milligrams", 1e-6, "mg", "milligram");
        add("mass", "grams", 0.001, "g", "gm", "gram", "grams");
        add("mass", "kilograms", 1, "kg", "kgs", "kilo", "kilos", "kilogram");
        add("mass", "tonnes", 1000, "ton", "tons", "tonne", "metric ton", "metric tons");
        add("mass", "pounds", 0.45359237, "lb", "lbs", "pound");
        add("mass", "ounces", 0.028349523125, "oz", "ounce");
        add("mass", "tola", 0.0116638, "tolas", "tole");
        add("mass", "maund", 37.3242, "maunds", "mann");
        // volume (litre)
        add("volume", "millilitres", 0.001, "ml", "milliliter", "milliliters", "millilitre");
        add("volume", "litres", 1, "l", "liter", "liters", "litre", "ltr");
        add("volume", "gallons", 3.785411784, "gal", "gallon", "us gallons");
        add("volume", "cups", 0.2365882365, "cup");
        add("volume", "teaspoons", 0.00492892, "tsp", "teaspoon");
        add("volume", "tablespoons", 0.0147868, "tbsp", "tablespoon");
        // area (square metre). Marla in Lahore is usually 225 sq ft; a kanal is 20 marla.
        add("area", "square feet", 0.09290304, "sq ft", "sqft", "square foot", "sq. ft");
        add("area", "square metres", 1, "sq m", "square meter", "square meters", "square metre", "sqm");
        add("area", "square yards", 0.83612736, "sq yd", "square yard", "sq yards", "gaz", "sq gaz");
        add("area", "marla", 225 * 0.09290304, "marlas");
        add("area", "kanal", 20 * 225 * 0.09290304, "kanals");
        add("area", "acres", 4046.8564224, "acre");
        add("area", "hectares", 10000, "hectare", "ha");
        // speed (metre per second)
        add("speed", "km/h", 1 / 3.6, "kmph", "kph", "kilometres per hour", "kilometers per hour", "km per hour");
        add("speed", "mph", 0.44704, "miles per hour", "mile per hour");
        add("speed", "m/s", 1, "metres per second", "meters per second");
        add("speed", "knots", 0.514444, "knot", "kn");
        // data (byte)
        add("data", "KB", 1024, "kb", "kilobyte", "kilobytes");
        add("data", "MB", 1024 * 1024, "mb", "megabyte", "megabytes");
        add("data", "GB", 1024.0 * 1024 * 1024, "gb", "gigabyte", "gigabytes");
        add("data", "TB", 1024.0 * 1024 * 1024 * 1024, "tb", "terabyte", "terabytes");
        // time (second)
        add("time", "seconds", 1, "sec", "secs", "second", "s");
        add("time", "minutes", 60, "min", "mins", "minute");
        add("time", "hours", 3600, "hr", "hrs", "hour", "h");
        add("time", "days", 86400, "day");
        add("time", "weeks", 604800, "week");
        // temperature (handled specially)
        add("temp", "°C", 0, "c", "celsius", "centigrade", "degrees celsius", "degree celsius");
        add("temp", "°F", 0, "f", "fahrenheit", "degrees fahrenheit", "degree fahrenheit");
        add("temp", "K", 0, "k", "kelvin", "kelvins");

        String[][] cur = {
            {"PKR", "pkr", "rupee", "rupees", "rs", "pakistani rupees", "pakistani rupee"},
            {"USD", "usd", "dollar", "dollars", "us dollars", "us dollar", "$"},
            {"EUR", "eur", "euro", "euros"},
            {"GBP", "gbp", "pound sterling", "pounds sterling", "british pounds", "british pound"},
            {"AED", "aed", "dirham", "dirhams", "uae dirham"},
            {"SAR", "sar", "riyal", "riyals", "saudi riyal", "saudi riyals"},
            {"INR", "inr", "indian rupees", "indian rupee"},
            {"CNY", "cny", "yuan", "rmb"},
            {"CAD", "cad", "canadian dollars", "canadian dollar"},
            {"AUD", "aud", "australian dollars", "australian dollar"},
            {"JPY", "jpy", "yen"},
            {"TRY", "try", "lira", "turkish lira"},
            {"QAR", "qar", "qatari riyal", "qatari riyals"},
            {"KWD", "kwd", "kuwaiti dinar", "kuwaiti dinars"},
            {"MYR", "myr", "ringgit"},
        };
        for (String[] row : cur) for (int i = 1; i < row.length; i++) CURRENCIES.put(row[i], row[0]);
    }

    /** "rupees" → "PKR", "usd" → "USD", or null. */
    public static String currency(String word) {
        return word == null ? null : CURRENCIES.get(word.trim().toLowerCase());
    }

    public static boolean isUnit(String word) {
        return word != null && UNITS.containsKey(word.trim().toLowerCase());
    }

    /** Converts [value] from one unit to another; null if they aren't the same kind. */
    public static Double convert(double value, String from, String to) {
        Unit a = UNITS.get(from.trim().toLowerCase());
        Unit b = UNITS.get(to.trim().toLowerCase());
        if (a == null || b == null || !a.kind.equals(b.kind)) return null;
        if (a.kind.equals("temp")) {
            double c = a.name.equals("°C") ? value : a.name.equals("°F") ? (value - 32) * 5 / 9 : value - 273.15;
            return b.name.equals("°C") ? c : b.name.equals("°F") ? c * 9 / 5 + 32 : c + 273.15;
        }
        return value * a.factor / b.factor;
    }

    /** Display name of a unit ("km" → "kilometres"). */
    public static String name(String unit) {
        Unit u = UNITS.get(unit.trim().toLowerCase());
        return u == null ? unit : u.name;
    }
}
