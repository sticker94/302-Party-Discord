package org.javacord.Discord302Party.command;

import org.javacord.Discord302Party.utils.Utils;  // Import the Utils class
import io.github.cdimascio.dotenv.Dotenv;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.api.entity.message.component.ActionRow;
import org.javacord.api.entity.message.component.SelectMenu;
import org.javacord.api.entity.message.component.SelectMenuOption;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.event.interaction.SelectMenuChooseEvent;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.listener.interaction.SelectMenuChooseListener;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class ViewRankRequirementsCommand implements SlashCommandCreateListener, SelectMenuChooseListener {

    private static final Logger logger = LogManager.getLogger(ViewRankRequirementsCommand.class);

    private static final Dotenv dotenv = Dotenv.load();
    private static final String DB_URL = "jdbc:mysql://" + dotenv.get("DB_HOST") + ":3306/" + dotenv.get("DB_NAME");
    private static final String USER = dotenv.get("DB_USER");
    private static final String PASS = dotenv.get("DB_PASS");

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL, USER, PASS);
    }

    private List<SelectMenuOption> getRankOptions() {
        List<SelectMenuOption> options = new ArrayList<>();
        String query = "SELECT `rank`, rank_order FROM config ORDER BY rank_order DESC";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query);
             ResultSet resultSet = preparedStatement.executeQuery()) {
            while (resultSet.next()) {
                String rank = resultSet.getString("rank");
                int rankOrder = resultSet.getInt("rank_order");

                // For the dropdown, just show the rank name and order
                String label = rank;

                options.add(SelectMenuOption.create(label, rank));
            }
        } catch (SQLException e) {
            logger.error("Error fetching ranks", e);
        }
        return options;
    }


    @Override
    public void onSlashCommandCreate(SlashCommandCreateEvent event) {
        if (event.getSlashCommandInteraction().getCommandName().equalsIgnoreCase("view_rank_requirements")) {
            List<SelectMenuOption> rankOptions = getRankOptions();
            if (rankOptions.isEmpty()) {
                event.getSlashCommandInteraction().createImmediateResponder()
                        .setContent("No ranks found.")
                        .respond().join();
                return;
            }

            SelectMenu rankMenu = SelectMenu.create("rank-menu", rankOptions);
            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle("Select a Rank")
                    .setDescription("Choose a rank from the dropdown to view its requirements.");

            event.getSlashCommandInteraction().createImmediateResponder()
                    .addEmbed(embed)
                    .addComponents(ActionRow.of(rankMenu))  // Updated method with ActionRow
                    .respond().join();
        }
    }

    @Override
    public void onSelectMenuChoose(SelectMenuChooseEvent event) {
        try {
            // Defer the interaction response to give more time
            event.getSelectMenuInteraction().respondLater().join();

            String selectedRank = event.getSelectMenuInteraction().getChosenOptions().get(0).getValue();
            logger.info("Selected Rank: {}", selectedRank);

            // Now process the interaction
            showRankCard(event, selectedRank);
        } catch (Exception e) {
            logger.error("Error handling SelectMenuChooseEvent", e);
        }
    }

    private void showRankCard(SelectMenuChooseEvent event, String rank) {
        try (Connection connection = connect()) {
            String query = "SELECT `rank`, requirement_type, required_value FROM rank_requirements WHERE `rank` = ? ORDER BY `rank`";
            try (PreparedStatement preparedStatement = connection.prepareStatement(query)) {
                preparedStatement.setString(1, rank);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    if (resultSet.next()) {
                        EmbedBuilder embed = new EmbedBuilder()
                                .setTitle("Rank: " + Utils.getCustomEmoji(rank) + " " + rank) // Use custom emoji in title
                                .setDescription("Here are the requirements for rank **" + rank + "**:");

                        StringBuilder description = new StringBuilder();
                        do {
                            String requirementType = resultSet.getString("requirement_type");
                            String requiredValue = resultSet.getString("required_value");

                            description.append("**Requirement:** ").append(getRequirementEmoji(requirementType)).append(" ").append(requirementType).append("\n")
                                    .append("**Required Value:** ").append(requiredValue).append("\n\n");
                        } while (resultSet.next());

                        embed.setDescription(description.toString());

                        // Use editMessage() if you've deferred the initial response
                        event.getSelectMenuInteraction().createFollowupMessageBuilder()
                                .addEmbed(embed)
                                .send().join();
                    } else {
                        event.getSelectMenuInteraction().createFollowupMessageBuilder()
                                .setContent("No requirements found for the selected rank.")
                                .send().join();
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error fetching rank requirements", e);
            event.getSelectMenuInteraction().createFollowupMessageBuilder()
                    .setContent("Failed to retrieve rank requirements.")
                    .send().join();
        }
    }

    private String getRequirementEmoji(String requirementType) {
        switch (requirementType) {
            case "Points":
                return "🏆";
            case "Time in Clan":
                return "⏳";
            case "Time at Current Rank":
                return "⏱️";
            default:
                return "🔹";
        }
    }
}
