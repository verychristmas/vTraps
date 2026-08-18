package im.hinexx.itemsPlugin.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextFormats {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final Pattern HEX_AMP = Pattern.compile("&#([0-9a-fA-F]{6})");
    private static final Pattern HEX_X = Pattern.compile(
            "&[xX](&[0-9a-fA-F]){6}",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LEGACY = Pattern.compile("(?i)&([0-9a-fk-or])");

    private TextFormats() {
    }

    public static String toMiniMessage(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String s = HEX_AMP.matcher(input).replaceAll("<#$1>");
        Matcher hx = HEX_X.matcher(s);
        StringBuilder hexBuf = new StringBuilder();
        while (hx.find()) {
            String raw = hx.group();
            StringBuilder hex = new StringBuilder(6);
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c != '&' && c != 'x' && c != 'X') {
                    hex.append(c);
                }
            }
            hx.appendReplacement(hexBuf, Matcher.quoteReplacement("<#" + hex + ">"));
        }
        hx.appendTail(hexBuf);
        s = hexBuf.toString();

        Matcher m = LEGACY.matcher(s);
        StringBuilder out = new StringBuilder(s.length() + 16);
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(legacyToTag(m.group(1).charAt(0))));
        }
        m.appendTail(out);
        return out.toString();
    }

    public static Component parse(String raw, TagResolver... resolvers) {
        TagResolver resolver = resolvers == null || resolvers.length == 0
                ? TagResolver.empty()
                : TagResolver.resolver(resolvers);
        return MINI.deserialize(toMiniMessage(raw == null ? "" : raw), resolver)
                .decoration(TextDecoration.ITALIC, false);
    }

    private static String legacyToTag(char code) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> "<black>";
            case '1' -> "<dark_blue>";
            case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>";
            case '4' -> "<dark_red>";
            case '5' -> "<dark_purple>";
            case '6' -> "<gold>";
            case '7' -> "<gray>";
            case '8' -> "<dark_gray>";
            case '9' -> "<blue>";
            case 'a' -> "<green>";
            case 'b' -> "<aqua>";
            case 'c' -> "<red>";
            case 'd' -> "<light_purple>";
            case 'e' -> "<yellow>";
            case 'f' -> "<white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default -> "&" + code;
        };
    }
}
