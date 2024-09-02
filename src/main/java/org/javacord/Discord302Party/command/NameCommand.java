package org.javacord.Discord302Party.command;

import io.github.cdimascio.dotenv.Dotenv;
import org.javacord.api.entity.permission.Role;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.interaction.callback.InteractionOriginalResponseUpdater;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;
import org.javacord.api.util.logging.ExceptionLogger;

import java.sql.*;
import java.util.concurrent.CompletableFuture;

public class NameCommand implements SlashCommandCreateListener {

    // Load environment variables
    private static final Dotenv dotenv = Dotenv.load();
    private static final String DB_URL = "jdbc:mysql://" + dotenv.get("DB_HOST") + ":3306/" + dotenv.get("DB_NAME");
    private static final String USER = dotenv.get("DB_USER");
    private static final String PASS = dotenv.get("DB_PASS");

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL, USER, PASS);
    }

    private boolean isMemberInClan(String username) {
        String query = "SELECT * FROM members WHERE LOWER(REPLACE(username, '_', ' ')) = LOWER(?)";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, username.toLowerCase());
            ResultSet resultSet = preparedStatement.executeQuery();
            return resultSet.next(); // Return true if a result is found
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    private String getRank(String username) {
        String query = "SELECT rank FROM members WHERE LOWER(REPLACE(username, '_', ' ')) = LOWER(?)";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, username.toLowerCase());
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                return resultSet.getString("rank");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    private boolean isDuplicateEntry(long discordUid, String characterName) {
        String query = "SELECT discord_uid FROM discord_users WHERE LOWER(character_name) = LOWER(?)";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, characterName.toLowerCase());
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                long existingDiscordUid = resultSet.getLong("discord_uid");
                return existingDiscordUid != discordUid; // True if the UID is different, indicating a potential duplicate
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false; // No duplicate found
    }

    private void saveToDuplicateTable(long discordUid, String characterName) {
        String query = "INSERT INTO duplicate_entries (discord_uid, character_name) VALUES (?, ?)";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setLong(1, discordUid);
            preparedStatement.setString(2, characterName);
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void saveUserDetails(long discordUid, String characterName, String rank) {
        String query = "INSERT INTO discord_users (discord_uid, character_name, rank) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE rank = VALUES(rank)";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setLong(1, discordUid);
            preparedStatement.setString(2, characterName);
            preparedStatement.setString(3, rank);
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onSlashCommandCreate(SlashCommandCreateEvent event) {
        if (event.getSlashCommandInteraction().getCommandName().equalsIgnoreCase("name")) {
            String characterName = event.getSlashCommandInteraction()
                    .getOptionStringValueByName("character")
                    .orElse("Unknown");

            User user = event.getSlashCommandInteraction().getUser();
            long discordUid = user.getId();
            Server server = event.getSlashCommandInteraction().getServer().orElse(null);

            if (server == null) {
                event.getSlashCommandInteraction().createImmediateResponder()
                        .setContent("Error: Couldn't retrieve server information.")
                        .respond();
                return;
            }

            // Initial response to avoid timeout
            InteractionOriginalResponseUpdater updater = event.getSlashCommandInteraction()
                    .createImmediateResponder()
                    .setContent("Processing your request...")
                    .respond().join();

            if (isDuplicateEntry(discordUid, characterName)) {
                saveToDuplicateTable(discordUid, characterName);
                updater.setContent("Duplicate entry found. Your information has been saved for review.").update();
                return;
            }

            if (isMemberInClan(characterName)) {
                // Set Discord name to just the character name
                boolean nicknameUpdated = retryOperation(() ->
                        user.updateNickname(server, characterName).exceptionally(ExceptionLogger.get())
                );

                if (!nicknameUpdated) {
                    updater.setContent("Failed to update your nickname after several attempts. Please try again later.").update();
                    return;
                }

                // Assign "Green Party Hats" role by ID for reliability
                Role greenPartyHatsRole = server.getRoleById("1168065194858119218").orElse(null);
                if (greenPartyHatsRole != null) {
                    if (!user.getRoles(server).contains(greenPartyHatsRole)) {
                        try {
                            Thread.sleep(200);  // Introduce a short delay to avoid timing issues
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        boolean roleAssigned = retryOperation(() ->
                                user.addRole(greenPartyHatsRole).exceptionally(ExceptionLogger.get())
                        );
                        if (!roleAssigned) {
                            updater.setContent("Failed to assign the 'Green Party Hats' role after several attempts.").update();
                            return;
                        }
                    }
                } else {
                    updater.setContent("Green Party Hats role not found.").update();
                    return;
                }



                // Assign role based on rank
                String rank = getRank(characterName);
                if (rank != null) {
                    Role role = rank.equalsIgnoreCase("deputy_owner") ?
                            server.getRolesByNameIgnoreCase("Co-Owner").stream().findFirst().orElse(null) :
                            server.getRolesByNameIgnoreCase(rank).stream().findFirst().orElse(null);

                    if (role != null) {
                        if (!user.getRoles(server).contains(role)) {
                            try {
                                Thread.sleep(200);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            boolean rankRoleAssigned = retryOperation(() ->
                                    user.addRole(role).exceptionally(ExceptionLogger.get())
                            );
                            if (!rankRoleAssigned) {
                                updater.setContent("Failed to assign the role for your rank after several attempts.").update();
                                return;
                            }
                        }
                        // Save or update user details in the database
                        saveUserDetails(discordUid, characterName, rank);
                        updater.setContent("Name updated, roles assigned, and details saved!").update();
                    } else {
                        updater.setContent("Role not found for the rank: " + rank).update();
                    }
                } else {
                    updater.setContent("Rank not found for your character.").update();
                }
            } else {
                updater.setContent("You are not a member of the clan. If you recently joined, ask a mod to manually refresh.").update();
            }
        }
    }

    // Retry logic to handle transient failures
    private boolean retryOperation(Runnable operation) {
        int maxRetries = 3;
        int retryDelay = 2000; // 2 seconds delay between retries

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                CompletableFuture<Void> result = CompletableFuture.runAsync(operation);
                result.join(); // Block until the operation completes
                return true; // Operation successful
            } catch (Exception e) {
                if (attempt == maxRetries) {
                    return false; // Max retries reached, return failure
                }
                try {
                    Thread.sleep(retryDelay); // Wait before retrying
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); // Restore interrupted state
                }
            }
        }
        return false; // Should not reach here
    }
}
