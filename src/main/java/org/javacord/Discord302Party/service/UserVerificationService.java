package org.javacord.Discord302Party.service;

import io.github.cdimascio.dotenv.Dotenv;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

    // Logger instance
    private static final Logger logger = LogManager.getLogger(UserVerificationService.class);

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
                    logger.error("Couldn't find the 'Green Party Hat' role.");
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
                                    logger.info("User {} has been assigned the role: {}.", characterName, rank);
                                }
                            } else {
                                logger.warn("Couldn't find the role for rank: {}.", rank);
                            }
                        } else {
                            // Rank not found, move user to archive
                            logger.warn("Couldn't find the rank for character: {}. Moving data to archived_users.", characterName);
                            moveUserToArchive(connection, resultSet);

                            // Remove roles from Discord user
                            user.getRoles(server).forEach(role -> {
                                if (!"deputy_owner".equalsIgnoreCase(role.getName()) && !"owner".equalsIgnoreCase(role.getName())) {
                                    user.removeRole(role).exceptionally(ExceptionLogger.get());
                                }
                            });
                        }
                    } else {
                        logger.warn("Couldn't find user with Discord UID: {}. Moving data to archived_users.", discordUid);
                        moveUserToArchive(connection, resultSet);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("SQL Exception occurred during user verification process.", e);
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
            logger.error("SQL Exception occurred while fetching rank for character: {}.", characterName, e);
        }
        return null;
    }

    private void moveUserToArchive(Connection connection, ResultSet resultSet) throws SQLException {
        try {
            // Begin transaction
            connection.setAutoCommit(false);

            // Insert the row into archived_users
            String insertQuery = "INSERT INTO archived_users (discord_uid, character_name, 'rank', replit_user_id) VALUES (?, ?, ?, ?)";
            try (PreparedStatement insertStmt = connection.prepareStatement(insertQuery)) {
                insertStmt.setLong(1, resultSet.getLong("discord_uid"));
                insertStmt.setString(2, resultSet.getString("character_name"));
                insertStmt.setString(3, resultSet.getString("rank"));
                insertStmt.setLong(4, resultSet.getLong("replit_user_id"));
                insertStmt.executeUpdate();
            }

            // Delete the row from discord_users
            String deleteQuery = "DELETE FROM discord_users WHERE discord_uid = ?";
            try (PreparedStatement deleteStmt = connection.prepareStatement(deleteQuery)) {
                deleteStmt.setLong(1, resultSet.getLong("discord_uid"));
                deleteStmt.executeUpdate();
            }

            // Commit transaction
            connection.commit();
            logger.info("User with Discord UID {} moved to archived_users.", resultSet.getLong("discord_uid"));

        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackException) {
                logger.error("Error occurred during transaction rollback.", rollbackException);
            }
            logger.error("SQL Exception occurred while moving user with Discord UID {} to archive.", resultSet.getLong("discord_uid"), e);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                logger.error("Error occurred while resetting auto-commit mode.", e);
            }
        }
    }
}
