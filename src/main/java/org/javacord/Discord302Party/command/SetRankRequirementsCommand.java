package org.javacord.Discord302Party.command;

import io.github.cdimascio.dotenv.Dotenv;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.*;

public class SetRankRequirementsCommand implements SlashCommandCreateListener {

    private static final Logger logger = LogManager.getLogger(SetRankRequirementsCommand.class);

    private static final Dotenv dotenv = Dotenv.load();
    private static final String DB_URL = "jdbc:mysql://" + dotenv.get("DB_HOST") + ":3306/" + dotenv.get("DB_NAME");
    private static final String USER = dotenv.get("DB_USER");
    private static final String PASS = dotenv.get("DB_PASS");

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL, USER, PASS);
    }

    @Override
    public void onSlashCommandCreate(SlashCommandCreateEvent event) {
        if (event.getSlashCommandInteraction().getCommandName().equalsIgnoreCase("set_rank_requirements")) {
            String rank = event.getSlashCommandInteraction().getOptionStringValueByName("rank").orElse("");
            String requirementDescription = event.getSlashCommandInteraction().getOptionStringValueByName("description").orElse("");
            Long requiredPoints = event.getSlashCommandInteraction().getOptionLongValueByName("points").orElse(0L);

            try (Connection connection = connect()) {
                String query = "INSERT INTO rank_requirements (rank, requirement_description, required_points) VALUES (?, ?, ?)";
                try (PreparedStatement preparedStatement = connection.prepareStatement(query)) {
                    preparedStatement.setString(1, rank);
                    preparedStatement.setString(2, requirementDescription);
                    preparedStatement.setLong(3, requiredPoints);
                    preparedStatement.executeUpdate();
                }
                event.getSlashCommandInteraction().createImmediateResponder()
                        .setContent("Rank requirements set successfully!")
                        .respond().join();
            } catch (SQLException e) {
                logger.error("Error setting rank requirements", e);
                event.getSlashCommandInteraction().createImmediateResponder()
                        .setContent("Failed to set rank requirements.")
                        .respond().join();
            }
        }
    }
}
