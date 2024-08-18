package org.javacord.Discord302Party.command;

import io.github.cdimascio.dotenv.Dotenv;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.sql.*;

public class ValidateRankRequirementsCommand implements SlashCommandCreateListener {

    private static final Logger logger = LogManager.getLogger(ValidateRankRequirementsCommand.class);

    private static final Dotenv dotenv = Dotenv.load();
    private static final String DB_URL = "jdbc:mysql://" + dotenv.get("DB_HOST") + ":3306/" + dotenv.get("DB_NAME");
    private static final String USER = dotenv.get("DB_USER");
    private static final String PASS = dotenv.get("DB_PASS");

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL, USER, PASS);
    }

    @Override
    public void onSlashCommandCreate(SlashCommandCreateEvent event) {
        if (event.getSlashCommandInteraction().getCommandName().equalsIgnoreCase("validate_rank")) {
            User targetUser = event.getSlashCommandInteraction().getOptionUserValueByName("user").orElse(null);
            String rank = event.getSlashCommandInteraction().getOptionStringValueByName("rank").orElse("");
            String validatedBy = event.getSlashCommandInteraction().getUser().getDiscriminatedName();

            if (targetUser != null) {
                String characterName = getCharacterNameByDiscordUid(targetUser.getId());

                if (characterName != null) {
                    try (Connection connection = connect()) {
                        String query = "INSERT INTO validation_log (character_name, rank, validated_by) VALUES (?, ?, ?)";
                        try (PreparedStatement preparedStatement = connection.prepareStatement(query)) {
                            preparedStatement.setString(1, characterName);
                            preparedStatement.setString(2, rank);
                            preparedStatement.setString(3, validatedBy);
                            preparedStatement.executeUpdate();
                        }
                        event.getSlashCommandInteraction().createImmediateResponder()
                                .setContent("Rank validation logged successfully for " + characterName)
                                .respond().join();
                    } catch (SQLException e) {
                        logger.error("Error validating rank", e);
                        event.getSlashCommandInteraction().createImmediateResponder()
                                .setContent("Failed to validate rank.")
                                .respond().join();
                    }
                } else {
                    event.getSlashCommandInteraction().createImmediateResponder()
                            .setContent("Character name not found.")
                            .respond().join();
                }
            }
        }
    }

    private String getCharacterNameByDiscordUid(long discordUid) {
        String query = "SELECT character_name FROM discord_users WHERE discord_uid = ?";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setLong(1, discordUid);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                return resultSet.getString("character_name");
            }
        } catch (SQLException e) {
            logger.error("Error fetching character name", e);
        }
        return null;
    }
}
