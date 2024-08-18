package org.javacord.Discord302Party.utils;

public class Utils {
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
}
