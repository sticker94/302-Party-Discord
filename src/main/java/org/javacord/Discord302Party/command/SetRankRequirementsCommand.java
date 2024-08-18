package org.javacord.Discord302Party.command;

import io.github.cdimascio.dotenv.Dotenv;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.Discord302Party.RankService;
import org.javacord.Discord302Party.Utils;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.interaction.SlashCommandOption;
import org.javacord.api.interaction.SlashCommandOptionBuilder;
import org.javacord.api.interaction.SlashCommandOptionChoice;
import org.javacord.api.interaction.SlashCommandOptionType;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

public class SetRankRequirementsCommand implements SlashCommandCreateListener {

    private static final Logger logger = LogManager.getLogger(SetRankRequirementsCommand.class);
    private static final Dotenv dotenv = Dotenv.load();
    private static final String DB_URL = "jdbc:mysql://" + dotenv.get("DB_HOST") + ":3306/" + dotenv.get("DB_NAME");
    private static final String USER = dotenv.get("DB_USER");
    private static final String PASS = dotenv.get("DB_PASS");

    @Override
    public void onSlashCommandCreate(SlashCommandCreateEvent event) {
        if (event.getSlashCommandInteraction().getCommandName().equalsIgnoreCase("set_rank_requirements")) {
            String rank = event.getSlashCommandInteraction().getOptionStringValueByName("rank").orElse("");
            String requirementType = event.getSlashCommandInteraction().getOptionStringValueByName("requirement_type").orElse("");
            String requiredValueStr = event.getSlashCommandInteraction().getOptionStringValueByName("required_value").orElse("");
            String specificRank = event.getSlashCommandInteraction().getOptionStringValueByName("specific_rank").orElse(null);

            try (Connection connection = connect()) {
                String requiredValueForDB = requiredValueStr;

                if (requirementType.equalsIgnoreCase("Time in Clan") ||
                        requirementType.equalsIgnoreCase("Time at Current Rank")) {
                    // Assuming parseTimeRequirement returns days or another numeric format as an integer
                    int requiredValueInDays = Utils.parseTimeRequirement(requiredValueStr);
                    requiredValueForDB = String.valueOf(requiredValueInDays); // Convert to string
                } else if (requirementType.equalsIgnoreCase("Points") ||
                        requirementType.equalsIgnoreCase("Points from X different players") ||
                        requirementType.equalsIgnoreCase("Points from X different ranks")) {
                    // For points, keep the string as-is
                    requiredValueForDB = requiredValueStr;
                } else {
                    // For "Other" or any non-numeric requirements
                    requiredValueForDB = requiredValueStr; // Store as-is
                }

                // SQL to insert or update rank requirement
                String sql = "INSERT INTO rank_requirements (rank, requirement_type, required_value, specific_rank) " +
                        "VALUES (?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE requirement_type = VALUES(requirement_type), " +
                        "required_value = VALUES(required_value), " +
                        "specific_rank = VALUES(specific_rank)";

                try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
                    preparedStatement.setString(1, rank);
                    preparedStatement.setString(2, requirementType);
                    preparedStatement.setString(3, requiredValueForDB);

                    if (specificRank != null) {
                        preparedStatement.setString(4, specificRank);
                    } else {
                        preparedStatement.setNull(4, java.sql.Types.VARCHAR);
                    }

                    preparedStatement.executeUpdate();

                    event.getSlashCommandInteraction().createImmediateResponder()
                            .setContent("Requirement for rank " + rank + " has been set/updated successfully.")
                            .respond().join();
                }

            } catch (SQLException | IllegalArgumentException e) {
                logger.error("Error setting rank requirements", e);
                event.getSlashCommandInteraction().createImmediateResponder()
                        .setContent("Failed to set rank requirements: " + e.getMessage())
                        .respond().join();
            }
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL, USER, PASS);
    }

    // Method to create the rank option dynamically
    public static SlashCommandOption createRankOption(RankService rankService) {
        List<String> ranks = rankService.getAllRanks();

        SlashCommandOptionBuilder rankOptionBuilder = new SlashCommandOptionBuilder()
                .setType(SlashCommandOptionType.STRING)
                .setName("rank")
                .setDescription("Select a rank to set its requirements")
                .setRequired(true);  // Set as required

        for (String rank : ranks) {
            rankOptionBuilder.addChoice(SlashCommandOptionChoice.create(rank, rank));
        }

        return rankOptionBuilder.build();
    }
}
