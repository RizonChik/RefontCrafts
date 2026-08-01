package ru.refontstudio.refontcrafts.util;

import org.bukkit.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Text {
    private static final Pattern RAW_HEX = Pattern.compile("(?i)§x(§[0-9a-f]){6}");
    private static final Pattern AMP_HEX = Pattern.compile("(?i)&#([0-9a-f]{6})");

    private static final char[] LEGACY_CODES = {
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };
    private static final int[] LEGACY_RGB = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
            0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
            0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };

    private Text() {
    }

    public static String color(String input) {
        if (input == null) return "";
        String value = expandAmpHex(input);
        value = ChatColor.translateAlternateColorCodes('&', value);
        if (!Compat.supportsHexColors()) value = downgradeRawHex(value);
        return value;
    }

    public static String plain(String value) {
        return ChatColor.stripColor(color(value));
    }

    private static String expandAmpHex(String input) {
        Matcher matcher = AMP_HEX.matcher(input);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            if (Compat.supportsHexColors()) {
                StringBuilder replacement = new StringBuilder("§x");
                for (int i = 0; i < hex.length(); i++) replacement.append('§').append(hex.charAt(i));
                matcher.appendReplacement(out, Matcher.quoteReplacement(replacement.toString()));
            } else {
                matcher.appendReplacement(out, Matcher.quoteReplacement("§" + nearestLegacy(hex)));
            }
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String downgradeRawHex(String input) {
        Matcher matcher = RAW_HEX.matcher(input);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String sequence = matcher.group();
            StringBuilder hex = new StringBuilder(6);
            for (int i = 3; i < sequence.length(); i += 2) hex.append(sequence.charAt(i));
            matcher.appendReplacement(out, Matcher.quoteReplacement("§" + nearestLegacy(hex.toString())));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static char nearestLegacy(String hex) {
        int rgb;
        try {
            rgb = Integer.parseInt(hex, 16);
        } catch (Throwable ignored) {
            return 'f';
        }
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < LEGACY_RGB.length; i++) {
            int candidate = LEGACY_RGB[i];
            int cr = (candidate >> 16) & 0xFF;
            int cg = (candidate >> 8) & 0xFF;
            int cb = candidate & 0xFF;
            long dr = r - cr;
            long dg = g - cg;
            long db = b - cb;
            long distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return LEGACY_CODES[best];
    }
}
