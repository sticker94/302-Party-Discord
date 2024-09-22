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
                String query = "WITH RankedRanks AS ( " +
                        "  SELECT m.username, " +
                        "         m.rank AS current_rank, " +
                        "         m.points AS current_points, " +
                        "         rr.rank AS matching_rank, " +
                        "         CAST(rr.required_value AS UNSIGNED) AS required_points_for_matching_rank, " +
                        "         r.rank_order AS matching_rank_order, " +
                        "         rr.requirement_type, " +
                        "         ROW_NUMBER() OVER (PARTITION BY m.username ORDER BY CAST(rr.required_value AS UNSIGNED) DESC) AS rank_position " +
                        "  FROM members m " +
                        "  JOIN rank_requirements rr ON CAST(m.points AS UNSIGNED) >= CAST(rr.required_value AS UNSIGNED) " +
                        "  JOIN config r ON rr.rank = r.rank " +
                        "  WHERE rr.requirement_type != 'other' " +
                        "    AND rr.required_value REGEXP '^[0-9]+$' " +
                        "    AND m.rank NOT IN ('owner', 'deputy_owner') " +
                        ") " +
                        "SELECT rr.username, " +
                        "       rr.current_rank, " +
                        "       rr.current_points, " +
                        "       rr.matching_rank AS correct_rank, " +
                        "       r_current.rank_order AS current_rank_order, " +
                        "       rr.matching_rank_order AS correct_rank_order, " +
                        "       rr.required_points_for_matching_rank " +
                        "FROM RankedRanks rr " +
                        "JOIN config r_current ON rr.current_rank = r_current.rank " +
                        "WHERE rr.rank_position = 1 " +
                        "  AND r_current.rank_order <> rr.matching_rank_order " +
                        "ORDER BY rr.username;";

                try (PreparedStatement preparedStatement = connection.prepareStatement(query);
                     ResultSet resultSet = preparedStatement.executeQuery()) {

                    EmbedBuilder embedBuilder = new EmbedBuilder();
                    embedBuilder.setTitle("Players Eligible for Rank-Up/Rank-Down");

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
                        String correctRank = resultSet.getString("correct_rank");
                        int points = resultSet.getInt("current_points");
                        int requiredPointsNext = resultSet.getInt("required_points_for_matching_rank");
                        int currentRankOrder = resultSet.getInt("current_rank_order");
                        int correctRankOrder = resultSet.getInt("correct_rank_order");

                        // Determine if it's a rank-up or rank-down situation
                        boolean isRankDown = correctRankOrder > currentRankOrder;
                        String rankDirection = isRankDown ? "Rank Down" : "Rank Up";
                        Color sideColor = isRankDown ? Color.RED : Color.GREEN;

                        String rankInfo = "Current Rank: " + currentRank + "\n" + rankDirection + ": " + correctRank +
                                "\nPoints: " + points + " (Required: " + requiredPointsNext + ")";

                        // Truncate username to 25 characters max
                        if (username.length() > 25) {
                            username = username.substring(0, 22) + "...";
                        }

                        // Truncate rankInfo if it exceeds the 1024-character limit
                        if (rankInfo.length() > 1024) {
                            rankInfo = rankInfo.substring(0, 1021) + "...";  // Truncate if too long
                        }

                        // Add the field with the player's name as the title and their rank-up/down info as the value
                        embedBuilder.addField(username, rankInfo, false)
                                .setColor(sideColor);  // Highlight based on rank-up or rank-down
                    }

                    if (!hasResults) {
                        embedBuilder.setDescription("No players are eligible for rank-up or rank-down.");
                    }

                    event.getSlashCommandInteraction().createImmediateResponder()
                            .addEmbed(embedBuilder)
                            .respond().join();
                }
            } catch (SQLException e) {
                logger.error("Error checking rank-up/down eligibility", e);
                event.getSlashCommandInteraction().createImmediateResponder()
                        .setContent("Failed to check rank-up/down eligibility.")
                        .respond().join();
            }
        }
    }
}
