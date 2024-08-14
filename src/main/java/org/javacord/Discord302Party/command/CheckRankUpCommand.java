package org.javacord.Discord302Party.command;

import io.github.cdimascio.dotenv.Dotenv;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
                String query = "SELECT m.username, m.rank, m.points, r.required_points " +
                        "FROM members m " +
                        "JOIN rank_requirements r ON m.rank = r.rank " +
                        "WHERE m.points >= r.required_points";
                try (PreparedStatement preparedStatement = connection.prepareStatement(query);
                     ResultSet resultSet = preparedStatement.executeQuery()) {

                    StringBuilder response = new StringBuilder("Players eligible for rank-up:\n");
                    while (resultSet.next()) {
                        String username = resultSet.getString("username");
                        String rank = resultSet.getString("rank");
                        int points = resultSet.getInt("points");
                        int requiredPoints = resultSet.getInt("required_points");

                        response.append(username)
                                .append(" - Rank: ").append(rank)
                                .append(", Points: ").append(points)
                                .append(" (Required: ").append(requiredPoints).append(")\n");
                    }
                    event.getSlashCommandInteraction().createImmediateResponder()
                            .setContent(response.toString())
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
