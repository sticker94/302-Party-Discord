package org.javacord.Discord302Party.command;

import io.github.cdimascio.dotenv.Dotenv;
import org.javacord.api.entity.permission.Role;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;
import org.javacord.api.util.logging.ExceptionLogger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

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
        String query = "SELECT * FROM discord_users WHERE discord_uid = ? OR LOWER(character_name) = LOWER(?)";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setLong(1, discordUid);
            preparedStatement.setString(2, characterName.toLowerCase());
            ResultSet resultSet = preparedStatement.executeQuery();
            return resultSet.next(); // Return true if a duplicate is found
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
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
        String query = "INSERT INTO discord_users (discord_uid, character_name, rank) VALUES (?, ?, ?)";
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

            if (isDuplicateEntry(discordUid, characterName)) {
                saveToDuplicateTable(discordUid, characterName);
                event.getSlashCommandInteraction().createImmediateResponder()
                        .setContent("Duplicate entry found. Your information has been saved for review.")
                        .respond();
                return;
            }

            if (isMemberInClan(characterName)) {
                // Set Discord name to just the character name
                user.updateNickname(server, characterName).exceptionally(ExceptionLogger.get());

                // Add "Green Party Hats" role to everyone
                Role greenPartyHatsRole = server.getRolesByNameIgnoreCase("Green Party Hats").stream().findFirst().orElse(null);
                if (greenPartyHatsRole != null) {
                    server.addRoleToUser(user, greenPartyHatsRole).exceptionally(ExceptionLogger.get());
                } else {
                    event.getSlashCommandInteraction().createImmediateResponder()
                            .setContent("Green Party Hats role not found.")
                            .respond();
                    return;
                }

                // Assign role based on rank
                String rank = getRank(characterName);
                if (rank != null) {
                    Role role = null;
                    if (rank.equalsIgnoreCase("deputy_owner")) {
                        role = server.getRolesByNameIgnoreCase("Co-Owner").stream().findFirst().orElse(null);
                    } else {
                        role = server.getRolesByNameIgnoreCase(rank).stream().findFirst().orElse(null);
                    }

                    if (role != null) {
                        server.addRoleToUser(user, role).exceptionally(ExceptionLogger.get());
                        // Save user details in the database
                        saveUserDetails(discordUid, characterName, rank);
                        event.getSlashCommandInteraction().createImmediateResponder()
                                .setContent("Name updated, roles assigned, and details saved!")
                                .respond();
                    } else {
                        event.getSlashCommandInteraction().createImmediateResponder()
                                .setContent("Role not found for the rank: " + rank)
                                .respond();
                    }
                } else {
                    event.getSlashCommandInteraction().createImmediateResponder()
                            .setContent("Rank not found for your character.")
                            .respond();
                }
            } else {
                event.getSlashCommandInteraction().createImmediateResponder()
                        .setContent("You are not a member of the clan.")
                        .respond();
            }
        }
    }
}
