package org.wangshu.ime;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Function;

/** Deterministic transcription formatting for bounded, explicit Chinese numeric contexts. */
final class VoiceNumberFormat {
    private static final String DIGITS = "0-9零〇一二三四五六七八九";
    private static final String INTEGER = DIGITS + "两十百千万亿";
    private static final String NUMBER = INTEGER + "点负";
    private static final Pattern DATE = Pattern.compile("(?<![" + NUMBER + "])([" + DIGITS
            + "]{4})年([" + INTEGER + "]{1,3})月([" + INTEGER + "]{1,3})([日号])");
    private static final Pattern IDENTIFIER = Pattern.compile(
            "((?:验证码|邮编|编号|序列号)(?:是|为|[：:])\\s*)([" + DIGITS
                    + "]{1,20})(?=\\s*(?:[。！？!?，,；;]|$))");
    private static final Pattern QUANTITY = Pattern.compile("(?<![" + NUMBER + "A-Za-z.])"
            + "(负?[" + INTEGER + "]{1,20}?)(?:点([" + DIGITS + "]{1,12}))?"
            + "(?=摄氏度|度|公斤|千克|克|公里|厘米|毫米|米|毫升|升|小时|个|件|元)");

    private VoiceNumberFormat() { }

    static String apply(String raw) {
        if (raw.length() > 8192) return raw;
        String dates = rewrite(DATE, raw, VoiceNumberFormat::formatDate);
        String identifiers = rewrite(IDENTIFIER, dates, match -> match.group(1) + digits(match.group(2)));
        return rewrite(QUANTITY, identifiers, match -> formatQuantity(match, identifiers));
    }

    private static String rewrite(Pattern pattern, String text, Function<MatchResult, String> format) {
        Matcher match = pattern.matcher(text);
        if (!match.find()) return text;
        StringBuilder result = new StringBuilder(text.length());
        int cursor = 0;
        do {
            result.append(text, cursor, match.start()).append(format.apply(match));
            cursor = match.end();
        } while (match.find());
        return result.append(text, cursor, text.length()).toString();
    }

    private static String formatDate(MatchResult match) {
        int year = Integer.parseInt(digits(match.group(1)));
        long month = integer(match.group(2));
        long day = integer(match.group(3));
        if (year == 0 || month < 1 || month > 12 || day < 1 || day > 31) return match.group();
        try {
            LocalDate.of(year, (int) month, (int) day);
            return year + "年" + month + "月" + day + match.group(4);
        } catch (DateTimeException invalidDate) {
            return match.group();
        }
    }

    private static String formatQuantity(MatchResult match, String text) {
        String whole = match.group(1);
        if (embeddedQuantity(text, match.start(), whole.startsWith("负"))) return match.group();
        if (match.group(2) == null && whole.matches("[0-9]+")) return match.group();
        boolean negative = whole.startsWith("负");
        if (negative) whole = whole.substring(1);
        long value = integer(whole);
        String fraction = match.group(2);
        // An isolated zero before a classifier can be lexical, as in 零件.
        if (value < 0 || (value == 0 && fraction == null && !negative)) return match.group();
        return (negative ? "-" : "") + value + (fraction == null ? "" : "." + digits(fraction));
    }

    private static boolean embeddedQuantity(String text, int start, boolean spokenMinus) {
        if (start == 0) return false;
        char previous = text.charAt(start - 1);
        if (spokenMinus && (previous == '+' || previous == '-')) return true;
        if (start < 2 || "@_/\\#=+-?&%".indexOf(previous) < 0) return false;
        char prefix = text.charAt(start - 2);
        return prefix >= 'A' && prefix <= 'Z' || prefix >= 'a' && prefix <= 'z'
                || prefix >= '0' && prefix <= '9' || prefix == '_';
    }

    private static String digits(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            result.append((char) ('0' + digit(text.charAt(index))));
        }
        return result.toString();
    }

    private static int digit(char value) {
        if (value >= '0' && value <= '9') return value - '0';
        if (value == '〇') return 0;
        if (value == '两') return 2;
        return "零一二三四五六七八九".indexOf(value);
    }

    private static long integer(String text) {
        if (text.isEmpty()) return -1;
        long total = 0;
        long lastGroup = Long.MAX_VALUE;
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            long unit = switch (text.charAt(index)) {
                case '亿' -> 100_000_000L;
                case '万' -> 10_000L;
                default -> 0L;
            };
            if (unit == 0) continue;
            if (unit >= lastGroup) return -1;
            int section = section(text.substring(start, index), start != 0);
            if (section <= 0) return -1;
            total += section * unit;
            lastGroup = unit;
            start = index + 1;
        }
        if (start == text.length()) return total;
        String tailText = text.substring(start);
        int tail = section(tailText, start != 0);
        if (start != 0 && (tail <= 0 || (tail < lastGroup / 10 && digit(tailText.charAt(0)) != 0))) {
            return -1;
        }
        return tail < 0 ? -1 : total + tail;
    }

    /** A section is at most 9999, with descending units and explicit skipped-place zeros. */
    private static int section(String text, boolean afterLargeUnit) {
        if (afterLargeUnit && text.length() > 1 && digit(text.charAt(0)) == 0) {
            text = text.substring(1);
        }
        if (text.isEmpty()) return -1;
        int value = 0;
        int pending = -1;
        int previousUnit = 10_000;
        boolean gap = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            int number = digit(current);
            if (number >= 0) {
                if (pending >= 0) {
                    // Arabic digit runs are unambiguous in a quantified context.
                    return text.matches("[0-9]{1,4}") ? Integer.parseInt(text) : -1;
                }
                if (number == 0 && value != 0) {
                    if (gap || index == text.length() - 1) return -1;
                    gap = true;
                } else {
                    pending = number;
                }
                continue;
            }
            int unit = switch (current) {
                case '千' -> 1000;
                case '百' -> 100;
                case '十' -> 10;
                default -> 0;
            };
            if (unit == 0 || unit >= previousUnit) return -1;
            if (pending < 0 && index == 0 && unit == 10) pending = 1;
            if (pending <= 0 || (value != 0 && (unit * 10 != previousUnit) != gap)) return -1;
            value += pending * unit;
            previousUnit = unit;
            pending = -1;
            gap = false;
        }
        if (pending < 0) return value;
        if (value != 0 && (previousUnit > 10) != gap) return -1;
        return value + pending;
    }
}
