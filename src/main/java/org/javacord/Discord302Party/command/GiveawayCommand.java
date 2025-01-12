package org.javacord.Discord302Party.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.Discord302Party.utils.GETrackerApi;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.interaction.SlashCommandInteraction;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;

import java.awt.*;
import java.sql.*;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

public class GiveawayCommand implements SlashCommandCreateListener {

    private static final Logger logger = LogManager.getLogger(GiveawayCommand.class);

    private static final Dotenv dotenv = Dotenv.load();
    private static final String DB_URL = "jdbc:mysql://" + dotenv.get("DB_HOST") + ":3306/" + dotenv.get("DB_NAME");
    private static final String USER = dotenv.get("DB_USER");
    private static final String PASS = dotenv.get("DB_PASS");

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL, USER, PASS);
    }

    // Helper method to look up OSRS name by Discord UID
    private String getOsrsNameByDiscordUid(long discordUid) {
        String query = "SELECT character_name FROM discord_users WHERE discord_uid = ?";
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement(query)) {
            ps.setLong(1, discordUid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("character_name");
                }
            }
        } catch (SQLException e) {
            logger.error("Error looking up OSRS name by Discord UID: ", e);
        }
        return null;
    }

    @Override
    public void onSlashCommandCreate(SlashCommandCreateEvent event) {
        SlashCommandInteraction interaction = event.getSlashCommandInteraction();
        if (!interaction.getCommandName().equalsIgnoreCase("giveaway")) {
            return;
        }

        if (interaction.getOptions().isEmpty()) {
            // No subcommand provided
            interaction.createImmediateResponder().setContent("No subcommand provided!").respond();
            return;
        }

        String subCommand = interaction.getOptions().get(0).getName(); // "start", "end", or "claim"
        switch (subCommand.toLowerCase()) {
            case "start":
                handleGiveawayStart(interaction);
                break;
            case "end":
                handleGiveawayEnd(interaction);
                break;
            case "claim":
                handleGiveawayClaim(interaction);
                break;
            default:
                interaction.createImmediateResponder()
                        .setContent("Unknown subcommand.")
                        .respond();
        }
    }

    private void handleGiveawayStart(SlashCommandInteraction interaction) {
        // Immediately acknowledge to avoid the 3s timeout
        interaction.createImmediateResponder()
                .setContent("Creating your giveaway...")
                .respond();

        CompletableFuture.runAsync(() -> {
            try {
                User host = interaction.getUser();
                long hostDiscordUid = host.getId();
                String hostOsrsName = getOsrsNameByDiscordUid(hostDiscordUid);
                if (hostOsrsName == null) {
                    interaction.createFollowupMessageBuilder()
                            .setContent("You haven't linked an OSRS name. Use `/name` first.")
                            .send();
                    return;
                }

                // Gather input data
                String prize = interaction.getOptionStringValueByName("prize").orElse("No Prize");
                long days   = interaction.getOptionLongValueByName("days").orElse(0L);
                long hours  = interaction.getOptionLongValueByName("hours").orElse(0L);
                long mins   = interaction.getOptionLongValueByName("minutes").orElse(0L);

                // Convert total time to seconds
                long totalMinutes = days * 24 * 60 + hours * 60 + mins;
                long durationSeconds = totalMinutes * 60;

                // Current Unix timestamp (seconds)
                long nowEpoch = Instant.now().getEpochSecond();

                // The giveaway’s end time in epoch seconds
                long endEpoch = nowEpoch + durationSeconds;

                long winners = interaction.getOptionLongValueByName("winners").orElse(1L);
                String fundsSource = interaction.getOptionStringValueByName("funds_source").orElse("personal");
                String description = interaction.getOptionStringValueByName("description").orElse("");
                String itemName = interaction.getOptionStringValueByName("item").orElse("");

                // Optional: If itemName was provided, fetch GE & Wiki data
                String geUrl = null;
                String wikiUrl = null;
                String itemIcon = null;

                if (!itemName.isEmpty()) {
                    String itemId = GETrackerApi.fetchItemIdByName(itemName);
                    if (itemId != null) {
                        String itemData = GETrackerApi.fetchItemData(Integer.parseInt(itemId));
                        if (itemData != null) {
                            try {
                                JsonNode root = new ObjectMapper().readTree(itemData).get("data");
                                geUrl = root.get("url").asText();
                                wikiUrl = root.get("wikiUrl").asText();
                                itemIcon = root.get("icon").asText();
                            } catch (Exception e) {
                                logger.error("Error parsing item data for giveaway embed", e);
                            }
                        }
                    }
                }

                // Insert into DB
                int giveawayId;
                try (Connection connection = connect()) {
                    String insertSql = "INSERT INTO giveaways (discord_uid, host_osrs_name, prize_name, ge_link, wiki_link, funds_source, duration_seconds, winner_count, description) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement ps = connection.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                        ps.setString(1, String.valueOf(hostDiscordUid));
                        ps.setString(2, hostOsrsName);
                        ps.setString(3, prize);
                        ps.setString(4, geUrl);
                        ps.setString(5, wikiUrl);
                        ps.setString(6, fundsSource);
                        ps.setLong(7, durationSeconds);
                        ps.setLong(8, winners);
                        ps.setString(9, description);
                        ps.executeUpdate();

                        try (ResultSet rs = ps.getGeneratedKeys()) {
                            if (rs.next()) {
                                giveawayId = rs.getInt(1);
                            } else {
                                interaction.createFollowupMessageBuilder()
                                        .setContent("Failed to create the giveaway in the database!")
                                        .send();
                                return;
                            }
                        }
                    }
                }

                // Build an embed to show details
                EmbedBuilder embed = new EmbedBuilder()
                        .setTitle("New Giveaway Started!")
                        .setColor(Color.GREEN)
                        .setTimestamp(Instant.now())
                        .addField("Prize", prize);

                // Show actual day/hour/min breakdown
                StringBuilder timeString = new StringBuilder();
                if (days   > 0) timeString.append(days).append("d ");
                if (hours  > 0) timeString.append(hours).append("h ");
                if (mins   > 0) timeString.append(mins).append("m ");
                if (timeString.length() == 0) timeString.append("0m");

                // **LIVE** relative time field
                embed.addField("Ends In", "<t:" + endEpoch + ":R>", true)
                        .addField("Number of Winners", String.valueOf(winners), true)
                        .addField("Funds Source", fundsSource, true)
                        .addField("Description", description.isEmpty() ? "N/A" : description);

                if (itemIcon != null) {
                    embed.setThumbnail(itemIcon);
                }
                if (geUrl != null || wikiUrl != null) {
                    StringBuilder links = new StringBuilder();
                    if (geUrl != null) {
                        links.append("[GE Tracker](").append(geUrl).append(") ");
                    }
                    if (wikiUrl != null) {
                        links.append("| [Wiki](").append(wikiUrl).append(")");
                    }
                    embed.addField("Links", links.toString());
                }

                // Optionally mention the giveaway ID so you can end it later
                embed.setFooter("Giveaway ID: " + giveawayId);

                interaction.createFollowupMessageBuilder()
                        .addEmbed(embed)
                        .send();

            } catch (SQLException e) {
                logger.error("SQL Error creating giveaway: ", e);
                interaction.createFollowupMessageBuilder()
                        .setContent("Database error while creating the giveaway.")
                        .send();
            }
        });
    }

    private void handleGiveawayEnd(SlashCommandInteraction interaction) {
        interaction.createImmediateResponder()
                .setContent("Ending the giveaway...")
                .respond();

        CompletableFuture.runAsync(() -> {
            try {
                long giveawayId = interaction.getOptionLongValueByName("giveaway_id").orElse(-1L);
                if (giveawayId == -1L) {
                    interaction.createFollowupMessageBuilder()
                            .setContent("Invalid giveaway ID specified.")
                            .send();
                    return;
                }

                // Mark ended=1 in DB
                try (Connection connection = connect()) {
                    String updateSql = "UPDATE giveaways SET ended = 1 WHERE id = ?";
                    try (PreparedStatement ps = connection.prepareStatement(updateSql)) {
                        ps.setLong(1, giveawayId);
                        int updated = ps.executeUpdate();
                        if (updated == 0) {
                            interaction.createFollowupMessageBuilder()
                                    .setContent("No giveaway with that ID was found!")
                                    .send();
                            return;
                        }
                    }
                }

                interaction.createFollowupMessageBuilder()
                        .setContent("Giveaway #" + giveawayId + " ended. You can now pay the winners!")
                        .send();

            } catch (SQLException e) {
                logger.error("SQL Error ending giveaway: ", e);
                interaction.createFollowupMessageBuilder()
                        .setContent("Database error while ending the giveaway.")
                        .send();
            }
        });
    }

    private void handleGiveawayClaim(SlashCommandInteraction interaction) {
        interaction.createImmediateResponder()
                .setContent("Marking the giveaway prize as claimed...")
                .respond();

        CompletableFuture.runAsync(() -> {
            try {
                long giveawayId = interaction.getOptionLongValueByName("giveaway_id").orElse(-1L);
                User winnerUser = interaction.getOptionUserValueByName("winner").orElse(null);

                if (giveawayId == -1L || winnerUser == null) {
                    interaction.createFollowupMessageBuilder()
                            .setContent("You must specify a valid giveaway ID and a winner user.")
                            .send();
                    return;
                }

                // Look up winner's OSRS name
                String winnerOsrsName = getOsrsNameByDiscordUid(winnerUser.getId());
                if (winnerOsrsName == null) {
                    interaction.createFollowupMessageBuilder()
                            .setContent("That user has no linked OSRS name!")
                            .send();
                    return;
                }

                // Mark as claimed in DB
                try (Connection connection = connect()) {
                    // Usually you'd have inserted them as a winner row already:
                    // So let's see if the row exists. If not, you might want to insert it first.
                    String checkWinner = "SELECT id FROM giveaway_winners WHERE giveaway_id=? AND winner_discord_uid=?";
                    int rowId = -1;
                    try (PreparedStatement ps = connection.prepareStatement(checkWinner)) {
                        ps.setLong(1, giveawayId);
                        ps.setString(2, String.valueOf(winnerUser.getId()));
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                rowId = rs.getInt("id");
                            }
                        }
                    }
                    if (rowId == -1) {
                        // If we never stored them, we can insert them now
                        String insertWinner = "INSERT INTO giveaway_winners (giveaway_id, winner_discord_uid, winner_osrs_name, claimed) "
                                + "VALUES (?, ?, ?, 0)";
                        try (PreparedStatement ps = connection.prepareStatement(insertWinner, Statement.RETURN_GENERATED_KEYS)) {
                            ps.setLong(1, giveawayId);
                            ps.setString(2, String.valueOf(winnerUser.getId()));
                            ps.setString(3, winnerOsrsName);
                            ps.executeUpdate();
                            try (ResultSet rs = ps.getGeneratedKeys()) {
                                if (rs.next()) {
                                    rowId = rs.getInt(1);
                                }
                            }
                        }
                    }

                    // Now mark claimed=1
                    String updateSql = "UPDATE giveaway_winners SET claimed=1, claim_timestamp=NOW() WHERE id=?";
                    try (PreparedStatement ps = connection.prepareStatement(updateSql)) {
                        ps.setInt(1, rowId);
                        ps.executeUpdate();
                    }
                }

                interaction.createFollowupMessageBuilder()
                        .setContent("Prize for user " + winnerOsrsName + " was marked as claimed.")
                        .send();

            } catch (SQLException e) {
                logger.error("SQL Error claiming giveaway prize: ", e);
                interaction.createFollowupMessageBuilder()
                        .setContent("Database error while claiming the giveaway.")
                        .send();
            }
        });
    }
}
