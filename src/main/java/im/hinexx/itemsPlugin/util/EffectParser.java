package im.hinexx.itemsPlugin.util;

import org.bukkit.Registry;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Parses effect lines like: DARKNESS: 1 5  (level 1, 5 seconds)
 */
public final class EffectParser {

    public record ParsedEffect(PotionEffectType type, int amplifier, int durationTicks) {
        public PotionEffect toPotionEffect() {
            return new PotionEffect(type, durationTicks, amplifier, false, true, true);
        }
    }

    private EffectParser() {
    }

    public static List<ParsedEffect> parseAll(List<String> rawLines, Logger logger) {
        if (rawLines == null || rawLines.isEmpty()) {
            return List.of();
        }
        List<ParsedEffect> result = new ArrayList<>(rawLines.size());
        for (String line : rawLines) {
            ParsedEffect parsed = parse(line, logger);
            if (parsed != null) {
                result.add(parsed);
            }
        }
        return result;
    }

    public static ParsedEffect parse(String raw, Logger logger) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if ((text.startsWith("'") && text.endsWith("'")) || (text.startsWith("\"") && text.endsWith("\""))) {
            text = text.substring(1, text.length() - 1).trim();
        }

        // DARKNESS: 1 5   or   DARKNESS:1:5   or   DARKNESS 1 5
        String typePart;
        String rest;
        int colon = text.indexOf(':');
        if (colon > 0) {
            typePart = text.substring(0, colon).trim();
            rest = text.substring(colon + 1).trim().replace(':', ' ');
        } else {
            String[] bits = text.split("\\s+");
            if (bits.length < 3) {
                if (logger != null) {
                    logger.warning("Invalid effect line: " + raw);
                }
                return null;
            }
            typePart = bits[0];
            rest = bits[1] + " " + bits[2];
        }

        String[] nums = rest.trim().split("\\s+");
        if (nums.length < 2) {
            if (logger != null) {
                logger.warning("Invalid effect line (need level and duration): " + raw);
            }
            return null;
        }

        PotionEffectType type = resolveType(typePart);
        if (type == null) {
            if (logger != null) {
                logger.warning("Unknown potion effect: " + typePart);
            }
            return null;
        }

        try {
            int level = Integer.parseInt(nums[0]);
            int seconds = (int) Math.round(Double.parseDouble(nums[1].replace("s", "")));
            int amplifier = Math.max(0, level - 1); // 1 = level I
            int ticks = Math.max(1, seconds * 20);
            return new ParsedEffect(type, amplifier, ticks);
        } catch (NumberFormatException e) {
            if (logger != null) {
                logger.warning("Invalid effect numbers: " + raw);
            }
            return null;
        }
    }

    public static void apply(LivingEntity entity, List<ParsedEffect> effects) {
        if (entity == null || effects == null || effects.isEmpty()) {
            return;
        }
        for (ParsedEffect effect : effects) {
            entity.addPotionEffect(effect.toPotionEffect());
        }
    }

    public static void applyToPlayers(Iterable<? extends Player> players, List<ParsedEffect> effects) {
        if (effects == null || effects.isEmpty()) {
            return;
        }
        for (Player player : players) {
            if (player != null && player.isOnline()) {
                apply(player, effects);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static PotionEffectType resolveType(String name) {
        String key = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        PotionEffectType type = PotionEffectType.getByName(key);
        if (type != null) {
            return type;
        }
        // Paper/Bukkit registry fallback by key name
        try {
            return Registry.EFFECT.get(org.bukkit.NamespacedKey.minecraft(key));
        } catch (Throwable ignored) {
            return null;
        }
    }
}
