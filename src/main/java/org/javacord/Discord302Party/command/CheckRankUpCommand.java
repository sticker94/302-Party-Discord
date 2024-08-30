package org.javacord.Discord302Party.command;

import io.github.cdimascio.dotenv.Dotenv;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.*;
import java.sql.*;

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
                String query = "SELECT " +
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

                    StringBuilder response = new StringBuilder();
                    EmbedBuilder embedBuilder = new EmbedBuilder();
                    embedBuilder.setTitle("Players Eligible for Rank-Up")
                            .setColor(Color.GREEN);

                    boolean hasResults = false;

                    while (resultSet.next()) {
                        hasResults = true;
                        String username = resultSet.getString("username");
                        String currentRank = resultSet.getString("current_rank");
                        String nextRank = resultSet.getString("next_rank");
                        int points = resultSet.getInt("current_points");
                        String requiredPoints = resultSet.getString("required_points_for_next_rank");
                        String requirementType = resultSet.getString("requirement_type");

                        String rankUpInfo = "Current Rank: " + currentRank + "\nNext Rank: " + nextRank;

                        if (!"other".equalsIgnoreCase(requirementType)) {
                            rankUpInfo += "\nPoints: " + points + " (Required: " + requiredPoints + ")";
                        } else {
                            rankUpInfo += "\nSpecial Requirement: Validated";
                        }

                        response.append(username)
                                .append(" - ").append(rankUpInfo).append("\n");

                        // Add to embed for structured display
                        embedBuilder.addField(username, rankUpInfo);
                    }

                    if (!hasResults) {
                        response = new StringBuilder("No players are eligible for rank-up.");
                        embedBuilder.setDescription(response.toString());
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
