package org.javacord.Discord302Party.command;

import io.github.cdimascio.dotenv.Dotenv;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.Discord302Party.RankService;
import org.javacord.api.entity.message.component.ActionRow;
import org.javacord.api.entity.message.component.SelectMenu;
import org.javacord.api.entity.message.component.SelectMenuOption;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.interaction.SelectMenuInteraction;
import org.javacord.api.interaction.SlashCommandOption;
import org.javacord.api.interaction.SlashCommandOptionBuilder;
import org.javacord.api.interaction.SlashCommandOptionChoice;
import org.javacord.api.interaction.SlashCommandOptionType;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class DeleteRankRequirementsCommand implements SlashCommandCreateListener {

    private static final Logger logger = LogManager.getLogger(DeleteRankRequirementsCommand.class);
    private static final Dotenv dotenv = Dotenv.load();
    private static final String DB_URL = "jdbc:mysql://" + dotenv.get("DB_HOST") + ":3306/" + dotenv.get("DB_NAME");
    private static final String USER = dotenv.get("DB_USER");
    private static final String PASS = dotenv.get("DB_PASS");

    @Override
    public void onSlashCommandCreate(SlashCommandCreateEvent event) {
        if (event.getSlashCommandInteraction().getCommandName().equalsIgnoreCase("delete_rank_requirement")) {
            String rank = event.getSlashCommandInteraction().getOptionStringValueByName("rank").orElse("");

            List<String> requirementTypes = getRequirementTypesForRank(rank);
            if (requirementTypes.isEmpty()) {
                event.getSlashCommandInteraction().createImmediateResponder()
                        .setContent("No requirements found for the selected rank.")
                        .respond().join();
                return;
            }

            List<SelectMenuOption> options = new ArrayList<>();
            for (String type : requirementTypes) {
                options.add(SelectMenuOption.create(type, type));
            }

            event.getInteraction().createImmediateResponder()
                    .setContent("Please select the requirement to delete.")
                    .addComponents(ActionRow.of(SelectMenu.create("delete_requirement_select", "Select a requirement", options)))
                    .respond().thenAccept(interactionOriginalResponseUpdater -> {
                // Register a listener for the select menu interaction
                event.getApi().addSelectMenuChooseListener(selectEvent -> {
                    if (selectEvent.getSelectMenuInteraction().getCustomId().equals("delete_requirement_select")) {
                        SelectMenuInteraction selectMenuInteraction = selectEvent.getSelectMenuInteraction();

                        // Use getChosenOptions() and get(0) to get the first selected option
                        String selectedType = selectMenuInteraction.getChosenOptions().get(0).getValue();

                        deleteRequirement(selectMenuInteraction.getUser(), rank, selectedType);

                        selectMenuInteraction.getMessage().delete().join();  // Delete the message after selection
                        selectMenuInteraction.createImmediateResponder()
                                .setContent("Requirement deleted successfully.")
                                .respond().join();
                    }
                });
            });
        }
    }

    private void deleteRequirement(User user, String rank, String requirementType) {
        try (Connection connection = connect()) {
            String sql = "DELETE FROM rank_requirements WHERE rank = ? AND requirement_type = ?";
            try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
                preparedStatement.setString(1, rank);
                preparedStatement.setString(2, requirementType);
                preparedStatement.executeUpdate();
            }
        } catch (SQLException e) {
            logger.error("Error deleting rank requirement", e);
        }
    }

    private List<String> getRequirementTypesForRank(String rank) {
        List<String> types = new ArrayList<>();
        String query = "SELECT requirement_type FROM rank_requirements WHERE rank = ?";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, rank);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                while (resultSet.next()) {
                    types.add(resultSet.getString("requirement_type"));
                }
            }
        } catch (SQLException e) {
            logger.error("Error fetching requirement types for rank", e);
        }
        return types;
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
                .setDescription("Select a rank to delete its requirement")
                .setRequired(true);

        for (String rank : ranks) {
            rankOptionBuilder.addChoice(SlashCommandOptionChoice.create(rank, rank));
        }

        return rankOptionBuilder.build();
    }
}
