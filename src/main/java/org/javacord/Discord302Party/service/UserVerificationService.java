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
                    String tempRank = getTempRank(discordUid);

                    Optional<User> userOptional = server.getMemberById(discordUid);
                    if (userOptional.isPresent()) {
                        User user = userOptional.get();

                        // Check if the user already has the Green Party Hat role
                        if (!user.getRoles(server).contains(greenPartyHatRole)) {
                            user.addRole(greenPartyHatRole).exceptionally(ExceptionLogger.get());
                        }

                        // Assign the rank and temporary rank roles
                        if (rank != null) {
                            assignRoleToUser(server, user, rank);
                            if (tempRank != null) {
                                assignRoleToUser(server, user, tempRank);
                            } else {
                                // Remove any expired temporary roles
                                removeExpiredTempRolesFromUser(server, connection, discordUid, user);
                            }
                        } else {
                            logger.warn("Couldn't find the rank for character: {}. Moving data to archived_users.", characterName);
                            moveUserToArchive(connection, resultSet);

                            // Remove all roles except @everyone
                            user.getRoles(server).forEach(role -> {
                                if (!role.getName().equals("@everyone")) {
                                    user.removeRole(role).exceptionally(e -> {
                                        logger.warn("Failed to remove role {} from user {}: {}", role.getName(), user.getName(), e.getMessage());
                                        return null;
                                    });
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

    private void removeExpiredTempRolesFromUser(Server server, Connection connection, long discordUid, User user) throws SQLException {
        String query = "SELECT `rank` FROM temporary_ranks WHERE discord_uid = ? AND added_date < (NOW() - INTERVAL 1 MONTH)";
        try (PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setLong(1, discordUid);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                while (resultSet.next()) {
                    String expiredRank = resultSet.getString("rank");
                    Optional<Role> expiredRole = user.getRoles(server).stream()
                            .filter(role -> role.getName().equalsIgnoreCase(expiredRank))
                            .findFirst();
                    if (expiredRole.isPresent()) {
                        user.removeRole(expiredRole.get()).exceptionally(ExceptionLogger.get());
                        logger.info("Removed expired temporary role {} from user {}", expiredRank, user.getName());
                    }

                    // Remove from database
                    String deleteSql = "DELETE FROM temporary_ranks WHERE discord_uid = ? AND `rank` = ?";
                    try (PreparedStatement deleteStmt = connection.prepareStatement(deleteSql)) {
                        deleteStmt.setLong(1, discordUid);
                        deleteStmt.setString(2, expiredRank);
                        deleteStmt.executeUpdate();
                        logger.info("Deleted expired temporary rank {} for Discord UID: {}", expiredRank, discordUid);
                    }
                }
            }
        }
    }

    private void assignRoleToUser(Server server, User user, String roleName) {
        Optional<Role> roleOptional = server.getRolesByNameIgnoreCase(roleName).stream().findFirst();
        if (roleOptional.isPresent()) {
            Role role = roleOptional.get();
            if (!user.getRoles(server).contains(role)) {
                user.addRole(role).exceptionally(ExceptionLogger.get());
                logger.info("Assigned role {} to user {}", role.getName(), user.getName());
            }
        } else {
            logger.warn("Role {} not found on the server", roleName);
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

    private String getTempRank(long discordUID) {
        String query = "SELECT `rank` FROM temporary_ranks WHERE discord_uid = ?";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, String.valueOf(discordUID));
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                return resultSet.getString("rank");
            }
        } catch (SQLException e) {
            logger.error("SQL Exception occurred while fetching Temporary rank for UID: {}.", discordUID, e);
        }
        return null;
    }

    private void moveUserToArchive(Connection connection, ResultSet resultSet) throws SQLException {
        try {
            // Begin transaction
            connection.setAutoCommit(false);

            // Insert the row into archived_users
            String insertQuery = "INSERT INTO archived_users (discord_uid, character_name, `rank`, replit_user_id) VALUES (?, ?, ?, ?)";
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
