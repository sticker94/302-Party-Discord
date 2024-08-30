package org.javacord.Discord302Party.command;

import io.github.cdimascio.dotenv.Dotenv;
import org.javacord.api.entity.permission.Role;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;
import org.javacord.api.util.logging.ExceptionLogger;
import org.javacord.api.interaction.callback.InteractionOriginalResponseUpdater;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public class VerifyAllUsersCommand implements SlashCommandCreateListener {

    // Load environment variables
    private static final Dotenv dotenv = Dotenv.load();
    private static final String DB_URL = "jdbc:mysql://" + dotenv.get("DB_HOST") + ":3306/" + dotenv.get("DB_NAME");
    private static final String USER = dotenv.get("DB_USER");
    private static final String PASS = dotenv.get("DB_PASS");

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL, USER, PASS);
    }

    private String getRank(String characterName) {
        String query = "SELECT `rank` FROM members WHERE REPLACE(username, '_', ' ') = ?";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, characterName);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                return resultSet.getString("rank");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public void onSlashCommandCreate(SlashCommandCreateEvent event) {
        if (event.getSlashCommandInteraction().getCommandName().equalsIgnoreCase("verify_all_users")) {
            Server server = event.getSlashCommandInteraction().getServer().orElse(null);

            if (server == null) {
                event.getSlashCommandInteraction().createImmediateResponder()
                        .setContent("Error: Couldn't retrieve server information.")
                        .respond();
                return;
            }

            // Initial response to acknowledge the command
            InteractionOriginalResponseUpdater updater = event.getSlashCommandInteraction()
                    .createImmediateResponder()
                    .setContent("Starting the verification process...")
                    .respond().join();

            try (Connection connection = connect()) {
                String query = "SELECT discord_uid, character_name FROM discord_users";
                try (PreparedStatement preparedStatement = connection.prepareStatement(query);
                     ResultSet resultSet = preparedStatement.executeQuery()) {

                    Role greenPartyHatRole = server.getRoleById("1168065194858119218").orElse(null);
                    if (greenPartyHatRole == null) {
                        updater.setContent("Error: Couldn't find the 'Green Party Hat' role.").update();
                        return;
                    }

                    StringBuilder responseBuilder = new StringBuilder();
                    int updateCount = 0;

                    while (resultSet.next()) {
                        long discordUid = resultSet.getLong("discord_uid");
                        String characterName = resultSet.getString("character_name");

                        Optional<User> userOptional = server.getMemberById(discordUid);
                        if (userOptional.isPresent()) {
                            User user = userOptional.get();

                            // Check if the user already has the Green Party Hat role
                            if (!user.getRoles(server).contains(greenPartyHatRole)) {
                                user.addRole(greenPartyHatRole).exceptionally(ExceptionLogger.get());
                                updateCount++;
                            }

                            // Fetch the rank from the members table
                            String rank = getRank(characterName);
                            if (rank != null) {
                                Optional<Role> rankRole = server.getRolesByNameIgnoreCase(rank).stream().findFirst();
                                if (rankRole.isPresent()) {
                                    if (!user.getRoles(server).contains(rankRole.get())) {
                                        user.addRole(rankRole.get()).exceptionally(ExceptionLogger.get());
                                        responseBuilder.append("User **").append(characterName).append("** has been assigned the role: **").append(rank).append("**.\n");
                                        updateCount++;
                                    }
                                } else {
                                    responseBuilder.append("Error: Couldn't find the role for rank: **").append(rank).append("**.\n");
                                }
                            } else {
                                responseBuilder.append("Error: Couldn't find the rank for character: **").append(characterName).append("**.\n");
                            }
                        } else {
                            responseBuilder.append("Error: Couldn't find user with Discord UID: **").append(discordUid).append("**.\n");
                        }

                        // Send updates in batches to avoid exceeding the message length limit
                        if (responseBuilder.length() > 1000) {
                            updater.setContent(responseBuilder.toString()).update().join();
                            responseBuilder.setLength(0);  // Clear the builder
                        }
                    }

                    // Final update after all users are processed
                    if (updateCount > 0) {
                        responseBuilder.append("Verification process completed. ").append(updateCount).append(" updates made.");
                    } else {
                        responseBuilder.append("Verification process completed. No updates were necessary.");
                    }

                    updater.setContent(responseBuilder.toString()).update();
                }
            } catch (SQLException e) {
                e.printStackTrace();
                updater.setContent("Error: Database error occurred.").update();
            }
        }
    }
}
