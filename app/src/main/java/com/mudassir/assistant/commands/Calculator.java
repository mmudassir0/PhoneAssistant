package com.mudassir.assistant.commands;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Exact arithmetic for spoken maths ("15 percent of 2400", "5 into 3", "square root of 81"),
 * because a small AI model gets numbers wrong. Plain Java, unit-tested.
 */
public final class Calculator {

    private Calculator() {}

    /**
     * Turns spoken maths into a symbolic expression, or null if [s] isn't maths.
     * Expects already-normalized lowercase text.
     */
    public static String toExpression(String s) {
        String e = " " + s + " ";
        e = e.replaceAll("^ *(what is|what's|whats|calculate|compute|how much is|solve|tell me) ", " ");
        e = e.replace(",", "");
        e = e.replaceAll(" (square root|sqrt) of ", " sqrt ");
        e = e.replaceAll(" (\\d+(?:\\.\\d+)?) ?(%|percent) of ", " ($1/100)* ");
        e = e.replaceAll(" (\\d+(?:\\.\\d+)?) squared ", " $1^2 ");
        e = e.replaceAll(" (\\d+(?:\\.\\d+)?) cubed ", " $1^3 ");
        e = e.replaceAll(" (to the power of|to the power|power|raised to) ", " ^ ");
        e = e.replaceAll(" (plus|and|add) ", " + ");
        e = e.replaceAll(" (minus|less) ", " - ");
        e = e.replaceAll(" (times|multiplied by|into|x|×) ", " * ");
        e = e.replaceAll(" (divided by|over|by|÷) ", " / ");
        e = e.replaceAll("(\\d)x(\\d)", "$1*$2");
        e = e.replaceAll("(\\d) ?% ", "$1/100 ");
        e = e.replaceAll("[?=]", " ").trim();
        if (!e.matches("[\\d\\s.+\\-*/^()a-z]+")) return null;
        if (!e.replaceAll("sqrt", "").matches("[\\d\\s.+\\-*/^()]+")) return null;
        // Must contain an operator between numbers (or sqrt), not just a number.
        if (!e.matches(".*(\\d\\s*[+\\-*/^]\\s*[\\d(s]|sqrt).*") && !e.contains("/100)")) return null;
        return e;
    }

    /** Evaluates an expression from [toExpression]. Throws IllegalArgumentException if invalid. */
    public static double eval(String expr) {
        Parser p = new Parser(expr.replace(" ", ""));
        double v = p.expression();
        if (p.pos != p.s.length()) throw new IllegalArgumentException("Unexpected '" + p.s.charAt(p.pos) + "'");
        if (Double.isNaN(v) || Double.isInfinite(v)) throw new IllegalArgumentException("Not a number");
        return v;
    }

    /** 1234567.891 → "1,234,567.89"; whole numbers without decimals. */
    public static String format(double v) {
        BigDecimal bd = BigDecimal.valueOf(v).setScale(Math.abs(v) < 1 ? 6 : 2, RoundingMode.HALF_UP).stripTrailingZeros();
        if (bd.scale() < 0) bd = bd.setScale(0);
        String plain = bd.toPlainString();
        String[] parts = plain.split("\\.");
        String whole = String.format(Locale.US, "%,d", Long.parseLong(parts[0].replace("-", "")));
        return (plain.startsWith("-") ? "-" : "") + whole + (parts.length > 1 ? "." + parts[1] : "");
    }

    /** Recursive descent: expression = term (+|- term)*, term = factor (*|/ factor)*, factor = unary (^ factor)? */
    private static final class Parser {
        final String s;
        int pos = 0;

        Parser(String s) { this.s = s; }

        double expression() {
            double v = term();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '+') { pos++; v += term(); }
                else if (c == '-') { pos++; v -= term(); }
                else break;
            }
            return v;
        }

        double term() {
            double v = power();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '*') { pos++; v *= power(); }
                else if (c == '/') {
                    pos++;
                    double d = power();
                    if (d == 0) throw new IllegalArgumentException("Can't divide by zero");
                    v /= d;
                } else if (c == '(' || Character.isDigit(c) || s.startsWith("sqrt", pos)) {
                    v *= power(); // implicit multiplication: 2(3+4)
                } else break;
            }
            return v;
        }

        double power() {
            double base = unary();
            if (pos < s.length() && s.charAt(pos) == '^') {
                pos++;
                return Math.pow(base, power()); // right-associative
            }
            return base;
        }

        double unary() {
            if (pos < s.length() && s.charAt(pos) == '-') { pos++; return -unary(); }
            if (pos < s.length() && s.charAt(pos) == '+') { pos++; return unary(); }
            if (s.startsWith("sqrt", pos)) {
                pos += 4;
                double v = unary();
                if (v < 0) throw new IllegalArgumentException("Can't take the square root of a negative number");
                return Math.sqrt(v);
            }
            if (pos < s.length() && s.charAt(pos) == '(') {
                pos++;
                double v = expression();
                if (pos >= s.length() || s.charAt(pos) != ')') throw new IllegalArgumentException("Missing )");
                pos++;
                return v;
            }
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
            if (start == pos) throw new IllegalArgumentException("Expected a number");
            return Double.parseDouble(s.substring(start, pos));
        }
    }
}
