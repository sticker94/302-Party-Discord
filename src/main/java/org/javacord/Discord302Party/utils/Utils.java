package org.javacord.Discord302Party.utils;

public class Utils {

    /**
     * Parses the time requirement string and returns the equivalent time in days.
     *
     * @param timeInput the time requirement string (e.g., "3 days", "2 weeks").
     * @return the equivalent time in days.
     */
    public static int parseTimeRequirement(String timeInput) {
        String[] parts = timeInput.split(" ");
        int value = Integer.parseInt(parts[0]);
        String unit = parts[1].toLowerCase();

        switch (unit) {
            case "days":
                return value;
            case "weeks":
                return value * 7;
            case "months":
                return value * 30; // assuming 30 days per month
            default:
                throw new IllegalArgumentException("Invalid time unit. Please use 'days', 'weeks', or 'months'.");
        }
    }

    /**
     * Returns the custom emoji for a given rank.
     *
     * @param rank the rank name.
     * @return the corresponding custom emoji, or null if none exists.
     */
    public static String getCustomEmoji(String rank) {
        switch (rank.toLowerCase()) {
            case "owner":
                return "<:owner:1280283101943169119>";
            case "deputy_owner":
                return "<:deputy_owner:1280283136277876777>";
            case "anchor":
                return "<:anchor:1280283214497189979>";
            case "mentor":
                return "<:mentor:1280283178858446888>";
            case "moderator":
                return "<:moderator:1280292516134125598>";
            case "administrator":
                return "<:administrator:1280283178858446888>";
            case "maxed":
                return "<:maxed:1280282808094560346>";
            case "tzkal":
                return "<:tzkal:1280282992274833439>";
            case "tztok":
                return "<:tztok:1280283021706006650>";
            case "defiler":
                return "<:defiler:1280282632797814915>";
            case "trialist":
                return "<:trialist:1280282963942178968>";
            case "templar":
                return "<:templar:1280282935584620575>";
            case "vanguard":
                return "<:vanguard:1280283049556312267>";
            case "warden":
                return "<:warden:1280288675992698971>";
            case "guardian":
                return "<:guardian:1280282673226448926>";
            case "justiciar":
                return "<:justiciar:1280282766671351919>";
            case "sentry":
                return "<:sentry:1280282905742016582>";
            case "learner":
                return "<:learner:1280282712858558464>";
            default:
                return null; // No custom emoji found for this rank
        }
    }
}
