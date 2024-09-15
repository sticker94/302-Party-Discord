package org.javacord.Discord302Party.command;

import io.github.cdimascio.dotenv.Dotenv;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.api.entity.permission.Role;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;

import java.sql.*;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class OwnerPointsCommand implements SlashCommandCreateListener {

    private static final Logger logger = LogManager.getLogger(ModPointsCommand.class);

    // Load environment variables
    private static final Dotenv dotenv = Dotenv.load();
    private static final String DB_URL = "jdbc:mysql://" + dotenv.get("DB_HOST") + ":3306/" + dotenv.get("DB_NAME");
    private static final String USER = dotenv.get("DB_USER");
    private static final String PASS = dotenv.get("DB_PASS");

    private Connection connect() throws SQLException {
        logger.info("Attempting to connect to the database...");
        return DriverManager.getConnection(DB_URL, USER, PASS);
    }

    private String getCharacterNameByDiscordUid(long discordUid) {
        String query = "SELECT character_name FROM discord_users WHERE discord_uid = ?";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setLong(1, discordUid);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if (resultSet.next()) {
                    logger.info("Character name found for Discord UID: {}", discordUid);
                    return resultSet.getString("character_name");
                } else {
                    logger.warn("No character name found for Discord UID: {}", discordUid);
                }
            }
        } catch (SQLException e) {
            logger.error("SQL Exception while fetching character name: ", e);
        }
        return null;
    }

    private int getUserPoints(String characterName) {
        String query = "SELECT points FROM members WHERE username = ?";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, characterName);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if (resultSet.next()) {
                    logger.info("Points found for character name: {}", characterName);
                    return resultSet.getInt("points");
                } else {
                    logger.warn("No points found for character name: {}", characterName);
                }
            }
        } catch (SQLException e) {
            logger.error("SQL Exception while fetching user points: ", e);
        }
        return 0;
    }

    private int getUserGivenPoints(String characterName) {
        String query = "SELECT given_points FROM members WHERE username = ?";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, characterName);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if (resultSet.next()) {
                    logger.info("Given points found for character name: {}", characterName);
                    return resultSet.getInt("given_points");
                } else {
                    logger.warn("No given points found for character name: {}", characterName);
                }
            }
        } catch (SQLException e) {
            logger.error("SQL Exception while fetching user given points: ", e);
        }
        return 0;
    }

    private int getRankTotalPoints(String rank) {
        String query = "SELECT total_points FROM config WHERE `rank` = ?";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, rank);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if (resultSet.next()) {
                    logger.info("Total points found for rank: {}", rank);
                    return resultSet.getInt("total_points");
                } else {
                    logger.warn("No total points found for rank: {}", rank);
                }
            }
        } catch (SQLException e) {
            logger.error("SQL Exception while fetching rank total points: ", e);
        }
        return 0;
    }

    private String getUserRank(String characterName) {
        String query = "SELECT `rank` FROM members WHERE username = ?";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, characterName);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if (resultSet.next()) {
                    logger.info("Rank found for character name: {}", characterName);
                    return resultSet.getString("rank");
                } else {
                    logger.warn("No rank found for character name: {}", characterName);
                }
            }
        } catch (SQLException e) {
            logger.error("SQL Exception while fetching user rank: ", e);
        }
        return null;
    }

    private void updateUserPoints(String characterName, int pointsToAdd) {
        String query = "UPDATE members SET points = points + ? WHERE username = ?";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setInt(1, pointsToAdd);
            preparedStatement.setString(2, characterName);
            int rowsUpdated = preparedStatement.executeUpdate();
            if (rowsUpdated > 0) {
                logger.info("Successfully updated points for character name: {}", characterName);
            } else {
                logger.warn("No rows updated for character name: {}", characterName);
            }
        } catch (SQLException e) {
            logger.error("SQL Exception while updating user points: ", e);
        }
    }

    private int getPointsGivenInLast24Hours(String giver, String recipient) {
        String query = "SELECT SUM(points_change) AS total_given FROM points_transactions " +
                "WHERE character_name = ? AND related_user = ? AND timestamp >= NOW() - INTERVAL 1 DAY";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, giver);
            preparedStatement.setString(2, recipient);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt("total_given");
                }
            }
        } catch (SQLException e) {
            logger.error("SQL Exception while checking points given in last 24 hours: ", e);
        }
        return 0;
    }

    private void logPointsTransaction(String characterName, int pointsChange, String reason, String relatedUser, int previousPoints, int newPoints) {
        String query = "INSERT INTO points_transactions (character_name, points_change, reason, timestamp, related_user, previous_points, new_points) VALUES (?, ?, ?, NOW(), ?, ?, ?)";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, characterName);
            preparedStatement.setInt(2, pointsChange);
            preparedStatement.setString(3, reason);
            preparedStatement.setString(4, relatedUser);
            preparedStatement.setInt(5, previousPoints);
            preparedStatement.setInt(6, newPoints);
            preparedStatement.executeUpdate();
            logger.info("Points transaction logged for character: {}", characterName);
        } catch (SQLException e) {
            logger.error("SQL Exception while logging points transaction: ", e);
        }
    }

    private String getPointsChannelId() {
        String query = "SELECT value FROM disc_config WHERE key_name = 'points_channel_id'";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query);
             ResultSet resultSet = preparedStatement.executeQuery()) {
            if (resultSet.next()) {
                logger.info("Points channel ID found.");
                return resultSet.getString("value");
            } else {
                logger.warn("No points channel ID found.");
            }
        } catch (SQLException e) {
            logger.error("SQL Exception while fetching points channel ID: ", e);
        }
        return null;
    }

    private void postPointsUpdate(Server server, String message) {
        String channelId = getPointsChannelId();
        if (channelId != null) {
            server.getTextChannelById(channelId).ifPresent(channel -> {
                logger.info("Posting points update to channel ID: {}", channelId);
                channel.sendMessage(message);
            });
        } else {
            logger.warn("Points channel ID is null. No message posted.");
        }
    }

    @Override
    public void onSlashCommandCreate(SlashCommandCreateEvent event) {
        if (event.getSlashCommandInteraction().getCommandName().equalsIgnoreCase("owner_points")) {
            logger.info("Points command received.");

            // Send an initial "Processing..." response to avoid timing out
            event.getSlashCommandInteraction().createImmediateResponder()
                    .setContent("Processing your request, please wait...")
                    .respond().join();

            // Handle command processing asynchronously
            CompletableFuture.runAsync(() -> processPointsCommand(event));
        }
    }

    private boolean hasPermissionToRemovePoints(Server server, User user) {
        return server.getRoles(user).stream()
                .flatMap(role -> role.getAllowedPermissions().stream())
                .anyMatch(permission -> permission == org.javacord.api.entity.permission.PermissionType.MANAGE_SERVER);
    }

    private void processPointsCommand(SlashCommandCreateEvent event) {
        try {
            User user = event.getSlashCommandInteraction().getUser();
            long discordUid = user.getId();
            Server server = event.getSlashCommandInteraction().getServer().orElse(null);

            if (server == null) {
                logger.error("Server information could not be retrieved.");
                event.getSlashCommandInteraction().createFollowupMessageBuilder()
                        .setContent("Error: Couldn't retrieve server information.")
                        .send();
                return;
            }

            // Retrieve the character name associated with the user's Discord UID
            String characterName = getCharacterNameByDiscordUid(discordUid);
            if (characterName == null) {
                event.getSlashCommandInteraction().createFollowupMessageBuilder()
                        .setContent("Error: No character name associated with your Discord account. Please use the `/name` command to link your OSRS character name to your Discord account first.")
                        .send();
                return;
            }

            // Check if a mention, points, and reason were provided
            logger.info("Attempting to retrieve optional arguments...");
            Optional<User> mentionedUserOpt = event.getSlashCommandInteraction().getOptionUserValueByName("user");
            Optional<Role> mentionedRoleOpt = event.getSlashCommandInteraction().getOptionRoleValueByName("role");
            Optional<Long> pointsOpt = event.getSlashCommandInteraction().getOptionLongValueByName("points");
            String reason = event.getSlashCommandInteraction().getOptionStringValueByName("reason").orElse("No reason provided");

            logger.info("Points: {}", pointsOpt.orElse(0L));
            logger.info("Reason: {}", reason);

            int points = pointsOpt.map(Long::intValue).orElse(0);
            logger.info("mentionedUserOpt: {}", mentionedUserOpt);
            logger.info("mentionedRoleOpt: {}", mentionedRoleOpt);
            if (mentionedUserOpt.isPresent() && points != 0) {
                // Handle adding or removing points for an individual user
                User mentionedUser = mentionedUserOpt.get();
                String mentionedCharacterName = getCharacterNameByDiscordUid(mentionedUser.getId());
                logger.info("Attempting to modify points for: {}", mentionedCharacterName);

                if (mentionedCharacterName == null) {
                    event.getSlashCommandInteraction().createFollowupMessageBuilder()
                            .setContent(mentionedUser.getDisplayName(server) + " isn't registered to a character.")
                            .send();
                    return;
                }

                // Fetch current points of the mentioned user
                int currentPoints = getUserPoints(mentionedCharacterName);

                // Check if the action is to remove points
                if (points < 0) {
                    // Check if the user has the MANAGE_SERVER permission
                    if (!hasPermissionToRemovePoints(server, user)) {
                        event.getSlashCommandInteraction().createFollowupMessageBuilder()
                                .setContent("You do not have permission to remove points.")
                                .send();
                        return;
                    }

                    // Ensure the user cannot remove more points than the user currently has
                    if (currentPoints + points < 0) {
                        event.getSlashCommandInteraction().createFollowupMessageBuilder()
                                .setContent("Cannot remove more points than the user currently has. " + mentionedCharacterName + " has " + currentPoints + " points.")
                                .send();
                        return;
                    }
                }

                if (characterName.equalsIgnoreCase(mentionedCharacterName)){
                    event.getSlashCommandInteraction().createFollowupMessageBuilder()
                            .setContent("**Listen here... snowflake**: 302 is about giving! You can't award yourself points, but we appreciate that you tried.")
                            .send();
                    return;
                }

                // Check how many points the giver has given in the last 24 hours to the recipient
                int pointsGivenInLast24Hours = getPointsGivenInLast24Hours(characterName, mentionedCharacterName);
                if (pointsGivenInLast24Hours + points > 3500) {
                    event.getSlashCommandInteraction().createFollowupMessageBuilder()
                            .setContent("You have already given " + pointsGivenInLast24Hours + " points to " + mentionedCharacterName + " in the last 24 hours. You can only give a maximum of 3500 points per 24 hours to the same user.")
                            .send();
                    return;
                }

                // Update points for the mentioned user
                updateUserPoints(mentionedCharacterName, points);

                // Log the transaction
                int newPoints = currentPoints + points;
                logPointsTransaction(mentionedCharacterName, points, reason, characterName, currentPoints, newPoints);

                // Post to the configured channel
                String action = points > 0 ? "Received" : "Lost";
                postPointsUpdate(server, mentionedCharacterName + " now has " + newPoints + " points! " + action + " " + Math.abs(points) + " from " + characterName + " for " + reason);

                event.getSlashCommandInteraction().createFollowupMessageBuilder()
                        .setContent(mentionedCharacterName + " now has " + newPoints + " points.")
                        .send();
            } else if (mentionedRoleOpt.isPresent() && points != 0) {
                // Handle distributing points to all members of a role
                Role mentionedRole = mentionedRoleOpt.get();
                java.util.Set<User> usersInRole = mentionedRole.getUsers();

                if (usersInRole.isEmpty()) {
                    event.getSlashCommandInteraction().createFollowupMessageBuilder()
                            .setContent("The role " + mentionedRole.getName() + " has no members.")
                            .send();
                    return;
                }

                int numUsers = usersInRole.size();
                int pointsPerUser = points / numUsers;

                if (pointsPerUser == 0) {
                    event.getSlashCommandInteraction().createFollowupMessageBuilder()
                            .setContent("You don't have enough points to give at least 1 point to each member of the role.")
                            .send();
                    return;
                }

                for (User roleUser : usersInRole) {
                    String roleUserCharacterName = getCharacterNameByDiscordUid(roleUser.getId());
                    if (roleUserCharacterName != null) {
                        int currentPoints = getUserPoints(roleUserCharacterName);
                        updateUserPoints(roleUserCharacterName, pointsPerUser);
                        logPointsTransaction(roleUserCharacterName, pointsPerUser, reason, characterName, currentPoints, currentPoints + pointsPerUser);
                    }
                }

                event.getSlashCommandInteraction().createFollowupMessageBuilder()
                        .setContent("Distributed " + pointsPerUser + " points to " + numUsers + " users in the role " + mentionedRole.getName() + ".")
                        .send();

                postPointsUpdate(server, characterName + " distributed " + pointsPerUser + " points to " + numUsers + " users in the " + mentionedRole.getName() + " role for " + reason + ".");
        } else {
                // Fetch and display user's own points
                int userPoints = getUserPoints(characterName);
                String userRank = getUserRank(characterName);
                int givenPoints = getUserGivenPoints(characterName);
                int totalPoints = getRankTotalPoints(userRank);
                int availablePoints = totalPoints - givenPoints;

                // Fetch points received from other users
                String pointsReceivedFromOthers = getPointsReceivedFromOthers(characterName);

                event.getSlashCommandInteraction().createFollowupMessageBuilder()
                        .setContent("You have " + userPoints + " points.\n" +
                                "You have " + availablePoints + "/" + totalPoints + " points remaining to give.\n" +
                                "Points received from others:\n" + pointsReceivedFromOthers)
                        .send();
            }
        } catch (Exception e) {
            logger.error("Error processing points command: ", e);
            event.getSlashCommandInteraction().createFollowupMessageBuilder()
                    .setContent("An error occurred while processing your request. Please try again later.")
                    .send();
        }
    }

    /**
     * This method will fetch the list of points the user has received from others in descending order by points received.
     */
    private String getPointsReceivedFromOthers(String characterName) {
        String query = "SELECT related_user, SUM(points_change) AS total_points " +
                "FROM points_transactions " +
                "WHERE character_name = ? AND points_change > 0 " +
                "GROUP BY related_user " +
                "ORDER BY total_points DESC";
        StringBuilder result = new StringBuilder();

        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, characterName);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                while (resultSet.next()) {
                    String relatedUser = resultSet.getString("related_user");
                    int totalPoints = resultSet.getInt("total_points");
                    result.append(relatedUser).append(": ").append(totalPoints).append(" points\n");
                }
            }
        } catch (SQLException e) {
            logger.error("SQL Exception while fetching points received from others: ", e);
        }

        return result.length() > 0 ? result.toString() : "No points received from others.";
    }

}
