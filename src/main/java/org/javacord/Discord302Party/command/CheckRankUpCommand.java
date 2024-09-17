package org.javacord.Discord302Party.command;

import io.github.cdimascio.dotenv.Dotenv;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;

import java.awt.*;
import java.sql.*;
import java.util.HashSet;
import java.util.Set;

public class CheckRankUpCommand implements SlashCommandCreateListener {

    private static final Logger logger = LogManager.getLogger(CheckRankUpCommand.class);

    private static final Dotenv dotenv = Dotenv.load();
    private static final String DB_URL = "jdbc:mysql://" + dotenv.get("DB_HOST") + ":3306/" + dotenv.get("DB_NAME");
    private static final String USER = dotenv.get("DB_USER");
    private static final String PASS = dotenv.get("DB_PASS");

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL, USER, PASS);
    }

    @Override
    public void onSlashCommandCreate(SlashCommandCreateEvent event) {
        if (event.getSlashCommandInteraction().getCommandName().equalsIgnoreCase("check_rank_up")) {
            try (Connection connection = connect()) {
                String query = "SELECT DISTINCT " +
                        "    m.username, " +
                        "    m.rank AS current_rank, " +
                        "    m.points AS current_points, " +
                        "    r_next.rank AS next_rank, " +
                        "    rr.required_value AS required_points_for_next_rank, " +
                        "    rr.requirement_type " +
                        "FROM " +
                        "    members m " +
                        "JOIN " +
                        "    config r_current ON m.rank = r_current.rank " +
                        "LEFT JOIN " +
                        "    config r_next ON r_next.rank_order = (" +
                        "        SELECT MAX(rank_order) " +
                        "        FROM config " +
                        "        WHERE rank_order < r_current.rank_order " +
                        "    ) " +
                        "LEFT JOIN " +
                        "    rank_requirements rr ON rr.rank = r_next.rank " +
                        "LEFT JOIN " +
                        "    validation_log vl ON m.username = vl.character_name AND rr.rank = vl.rank AND rr.id = vl.requirement_id " +
                        "WHERE " +
                        "    (rr.requirement_type != 'other' AND m.points >= rr.required_value) " +
                        "    OR (rr.requirement_type = 'other' AND vl.id IS NOT NULL) " +
                        "ORDER BY " +
                        "    r_current.rank_order;";

                try (PreparedStatement preparedStatement = connection.prepareStatement(query);
                     ResultSet resultSet = preparedStatement.executeQuery()) {

                    EmbedBuilder embedBuilder = new EmbedBuilder();
                    embedBuilder.setTitle("Players Eligible for Rank-Up")
                            .setColor(Color.GREEN);

                    boolean hasResults = false;
                    Set<String> processedUsers = new HashSet<>();  // Track processed usernames

                    while (resultSet.next()) {
                        String username = resultSet.getString("username");

                        // If the username is already processed, skip to avoid duplicates
                        if (processedUsers.contains(username)) {
                            continue;
                        }

                        // Add to processed set
                        processedUsers.add(username);

                        hasResults = true;
                        String currentRank = resultSet.getString("current_rank");
                        String nextRank = resultSet.getString("next_rank");
                        int points = resultSet.getInt("current_points");
                        String requiredPoints = resultSet.getString("required_points_for_next_rank");
                        String requirementType = resultSet.getString("requirement_type");

                        // Truncate username to 25 characters max
                        if (username.length() > 25) {
                            username = username.substring(0, 22) + "...";
                        }

                        // Generate rank-up info
                        String rankUpInfo = "Current Rank: " + currentRank + "\nNext Rank: " + nextRank;
                        if (!"other".equalsIgnoreCase(requirementType)) {
                            rankUpInfo += "\nPoints: " + points + " (Required: " + requiredPoints + ")";
                        } else {
                            rankUpInfo += "\nSpecial Requirement: Validated";
                        }

                        // Truncate rankUpInfo if it exceeds the 1024-character limit
                        if (rankUpInfo.length() > 1024) {
                            rankUpInfo = rankUpInfo.substring(0, 1021) + "...";  // Truncate if too long
                        }

                        // Add the field with the player's name as the title and their rank-up info as the value
                        embedBuilder.addField(username, rankUpInfo);
                    }

                    if (!hasResults) {
                        embedBuilder.setDescription("No players are eligible.");
                    }

                    event.getSlashCommandInteraction().createImmediateResponder()
                            .addEmbed(embedBuilder)
                            .respond().join();
                }
            } catch (SQLException e) {
                logger.error("Error checking rank-up eligibility", e);
                event.getSlashCommandInteraction().createImmediateResponder()
                        .setContent("Failed to check rank-up eligibility.")
                        .respond().join();
            }
        }
    }
}
