package org.javacord.Discord302Party.command;

import io.github.cdimascio.dotenv.Dotenv;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.api.DiscordApi;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.interaction.SlashCommand;
import org.javacord.api.interaction.SlashCommandOption;
import org.javacord.api.interaction.SlashCommandOptionChoice;
import org.javacord.api.interaction.SlashCommandOptionType;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ViewRankRequirementsCommand implements SlashCommandCreateListener {

    private static final Logger logger = LogManager.getLogger(ViewRankRequirementsCommand.class);

    private static final Dotenv dotenv = Dotenv.load();
    private static final String DB_URL = "jdbc:mysql://" + dotenv.get("DB_HOST") + ":3306/" + dotenv.get("DB_NAME");
    private static final String USER = dotenv.get("DB_USER");
    private static final String PASS = dotenv.get("DB_PASS");

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL, USER, PASS);
    }

    private List<String> getAllRanks() {
        List<String> ranks = new ArrayList<>();
        String query = "SELECT `rank` FROM config ORDER BY rank_order";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query);
             ResultSet resultSet = preparedStatement.executeQuery()) {
            while (resultSet.next()) {
                ranks.add(resultSet.getString("rank"));
            }
        } catch (SQLException e) {
            logger.error("Error fetching ranks", e);
        }
        return ranks;
    }

    private void showRankRequirements(SlashCommandCreateEvent event, String selectedRank) {
        try (Connection connection = connect()) {
            String query;
            if (selectedRank != null) {
                query = "SELECT `rank`, requirement_type, required_value FROM rank_requirements WHERE `rank` = ? ORDER BY `rank`";
            } else {
                query = "SELECT `rank`, requirement_type, required_value FROM rank_requirements ORDER BY `rank`";
            }

            try (PreparedStatement preparedStatement = connection.prepareStatement(query)) {
                if (selectedRank != null) {
                    preparedStatement.setString(1, selectedRank);
                }

                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    EmbedBuilder embed = new EmbedBuilder()
                            .setTitle("Rank Requirements")
                            .setDescription("Here are the current rank requirements:");

                    StringBuilder description = new StringBuilder();
                    while (resultSet.next()) {
                        String rank = resultSet.getString("rank");
                        String requirementType = resultSet.getString("requirement_type");
                        String requiredValue = resultSet.getString("required_value");

                        description.append("**Rank:** ").append(rank).append("\n")
                                .append("**Requirement:** ").append(requirementType).append("\n")
                                .append("**Required Value:** ").append(requiredValue).append("\n\n");
                    }

                    embed.setDescription(description.toString());

                    event.getSlashCommandInteraction().createImmediateResponder()
                            .addEmbed(embed)
                            .respond().join();
                }
            }
        } catch (SQLException e) {
            logger.error("Error fetching rank requirements", e);
            event.getSlashCommandInteraction().createImmediateResponder()
                    .setContent("Failed to retrieve rank requirements.")
                    .respond().join();
        }
    }

    @Override
    public void onSlashCommandCreate(SlashCommandCreateEvent event) {
        if (event.getSlashCommandInteraction().getCommandName().equalsIgnoreCase("view_rank_requirements")) {
            String selectedRank = event.getSlashCommandInteraction().getOptionStringValueByName("rank").orElse(null);
            showRankRequirements(event, selectedRank);
        }
    }

    public static void registerCommand(DiscordApi api, long guildId) {
        List<SlashCommandOptionChoice> rankChoices = new ArrayList<>();
        List<String> ranks = new ViewRankRequirementsCommand().getAllRanks();
        for (String rank : ranks) {
            rankChoices.add(SlashCommandOptionChoice.create(rank, rank));
        }

        SlashCommand command =
                SlashCommand.with("view_rank_requirements", "View all the rank requirements",
                                Collections.singletonList(
                                        SlashCommandOption.createWithOptions(SlashCommandOptionType.SUB_COMMAND, "view", "View requirements",
                                                Collections.singletonList(
                                                        SlashCommandOption.createWithChoices(SlashCommandOptionType.STRING, "rank", "Select a rank to view its requirements", false, rankChoices)
                                                )
                                        )
                                ))
                        .createForServer(api.getServerById(guildId).get())
                        .join();
    }
}
