package org.javacord.Discord302Party.service;

import io.github.cdimascio.dotenv.Dotenv;
import org.javacord.api.entity.permission.Role;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.util.logging.ExceptionLogger;

import java.sql.*;
import java.util.Optional;

public class UserVerificationService {

    // Load environment variables
    private static final Dotenv dotenv = Dotenv.load();
    private static final String DB_URL = "jdbc:mysql://" + dotenv.get("DB_HOST") + ":3306/" + dotenv.get("DB_NAME");
    private static final String USER = dotenv.get("DB_USER");
    private static final String PASS = dotenv.get("DB_PASS");

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL, USER, PASS);
    }

    public void verifyAllUsers(Server server) {
        try (Connection connection = connect()) {
            String query = "SELECT * FROM discord_users";
            try (PreparedStatement preparedStatement = connection.prepareStatement(query);
                 ResultSet resultSet = preparedStatement.executeQuery()) {

                Role greenPartyHatRole = server.getRoleById("1168065194858119218").orElse(null);
                if (greenPartyHatRole == null) {
                    System.err.println("Error: Couldn't find the 'Green Party Hat' role.");
                    return;
                }

                while (resultSet.next()) {
                    long discordUid = resultSet.getLong("discord_uid");
                    String characterName = resultSet.getString("character_name");
                    String rank = getRank(characterName);

                    // Skip if the rank is "deputy_owner" or "owner"
                    if ("deputy_owner".equalsIgnoreCase(rank) || "owner".equalsIgnoreCase(rank)) {
                        continue;
                    }

                    Optional<User> userOptional = server.getMemberById(discordUid);
                    if (userOptional.isPresent()) {
                        User user = userOptional.get();

                        // Check if the user already has the Green Party Hat role
                        if (!user.getRoles(server).contains(greenPartyHatRole)) {
                            user.addRole(greenPartyHatRole).exceptionally(ExceptionLogger.get());
                        }

                        if (rank != null) {
                            Optional<Role> rankRole = server.getRolesByNameIgnoreCase(rank).stream().findFirst();
                            if (rankRole.isPresent()) {
                                if (!user.getRoles(server).contains(rankRole.get())) {
                                    user.addRole(rankRole.get()).exceptionally(ExceptionLogger.get());
                                    System.out.println("User " + characterName + " has been assigned the role: " + rank + ".");
                                }
                            } else {
                                System.err.println("Error: Couldn't find the role for rank: " + rank + ".");
                            }
                        } else {
                            System.err.println("Error: Couldn't find the rank for character: " + characterName + ".");
                        }
                    } else {
                        System.err.println("Error: Couldn't find user with Discord UID: " + discordUid + ". Moving data to archived_users.");
                        moveUserToArchive(connection, discordUid);
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
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

    private void moveUserToArchive(Connection connection, long discordUid) {
        try {
            // Begin transaction
            connection.setAutoCommit(false);

            // Select the row from discord_users
            String selectQuery = "SELECT * FROM discord_users WHERE discord_uid = ?";
            try (PreparedStatement selectStmt = connection.prepareStatement(selectQuery)) {
                selectStmt.setLong(1, discordUid);
                try (ResultSet resultSet = selectStmt.executeQuery()) {
                    if (resultSet.next()) {
                        // Insert the row into archived_users
                        String insertQuery = "INSERT INTO archived_users (discord_uid, character_name, rank) VALUES (?, ?, ?)";
                        try (PreparedStatement insertStmt = connection.prepareStatement(insertQuery)) {
                            insertStmt.setLong(1, resultSet.getLong("discord_uid"));
                            insertStmt.setString(2, resultSet.getString("character_name"));
                            insertStmt.setString(3, resultSet.getString("rank"));
                            insertStmt.executeUpdate();
                        }

                        // Delete the row from discord_users
                        String deleteQuery = "DELETE FROM discord_users WHERE discord_uid = ?";
                        try (PreparedStatement deleteStmt = connection.prepareStatement(deleteQuery)) {
                            deleteStmt.setLong(1, discordUid);
                            deleteStmt.executeUpdate();
                        }

                        // Commit transaction
                        connection.commit();
                        System.out.println("User with Discord UID " + discordUid + " moved to archived_users.");
                    }
                }
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
