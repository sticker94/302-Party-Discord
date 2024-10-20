package org.javacord.Discord302Party.command;

import io.github.cdimascio.dotenv.Dotenv;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.api.entity.message.MessageFlag;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.event.interaction.UserContextMenuCommandEvent;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;
import org.javacord.api.listener.interaction.UserContextMenuCommandListener;

import java.sql.*;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class PointsCommand implements SlashCommandCreateListener, UserContextMenuCommandListener {

    private static final Logger logger = LogManager.getLogger(PointsCommand.class);

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
        // Query to sum the points given by the character
        String query = "SELECT SUM(points_change) AS total_given_points " +
                "FROM points_transactions WHERE related_user = ? and timestamp >= NOW() - INTERVAL 1 WEEK ";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, characterName);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if (resultSet.next()) {
                    int totalGivenPoints = resultSet.getInt("total_given_points");
                    logger.info("Total given points found for character name: {}", characterName);
                    return totalGivenPoints;
                } else {
                    logger.warn("No points transactions found for character name: {}", characterName);
                }
            }
        } catch (SQLException e) {
            logger.error("SQL Exception while fetching user given points: ", e);
        }
        return 0;  // Return 0 if no points found or an error occurred
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
                "WHERE related_user = ? AND character_name = ? AND timestamp >= NOW() - INTERVAL 1 DAY";
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

    private int getPointsGivenInLastWeek(String giver, String recipient) {
        String query = "SELECT SUM(points_change) AS total_given FROM points_transactions " +
                "WHERE related_user = ? AND character_name = ? AND timestamp >= NOW() - INTERVAL 7 DAY";
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
        if (event.getSlashCommandInteraction().getCommandName().equalsIgnoreCase("points")) {
            logger.info("Points command received.");

            // Send an initial "Processing..." response to avoid timing out
            event.getSlashCommandInteraction().createImmediateResponder()
                    .setContent("Processing your request, please wait...")
                    .setFlags(MessageFlag.EPHEMERAL)
                    .respond().join();

            // Handle command processing asynchronously
            CompletableFuture.runAsync(() -> processPointsCommand(event));
        }
    }

    @Override
    public void onUserContextMenuCommand(UserContextMenuCommandEvent event) {
        if (event.getUserContextMenuInteraction().getCommandName().equalsIgnoreCase("Give 3 Points")) {
            logger.info("Points context menu command received.");
            User targetUser = event.getUserContextMenuInteraction().getTarget();
            logger.info("User to give points: {}", targetUser.getDiscriminatedName());
            event.getUserContextMenuInteraction().createImmediateResponder()
                    .setContent("Processing points for " + targetUser.getDisplayName(event.getUserContextMenuInteraction().getServer().get()) + "...")
                    .setFlags(MessageFlag.EPHEMERAL)
                    .respond().join();
            CompletableFuture.runAsync(() -> processContextMenuCommand(event));
        }
        if (event.getUserContextMenuInteraction().getCommandName().equalsIgnoreCase("Check Points")) {
            logger.info("Check Points context menu command received.");

            User targetUser = event.getUserContextMenuInteraction().getTarget();
            Server server = event.getUserContextMenuInteraction().getServer().orElse(null);

            if (server == null) {
                event.getUserContextMenuInteraction().createImmediateResponder()
                        .setContent("Error: Couldn't retrieve server information.")
                        .setFlags(MessageFlag.EPHEMERAL)
                        .respond().join();
                return;
            }

            // Find the target user's character name
            String characterName = getCharacterNameByDiscordUid(targetUser.getId());
            if (characterName == null) {
                event.getUserContextMenuInteraction().createImmediateResponder()
                        .setContent("Error: No OSRS character linked to this user.")
                        .setFlags(MessageFlag.EPHEMERAL)
                        .respond().join();
                return;
            }

            // Get points for the character
            int points = getUserPoints(characterName);
            event.getUserContextMenuInteraction().createImmediateResponder()
                    .setContent(targetUser.getDisplayName(server) + " has " + points + " points.")
                    .setFlags(MessageFlag.EPHEMERAL)
                    .respond().join();
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
                        .setFlags(MessageFlag.EPHEMERAL)
                        .send();
                return;
            }

            String characterName = getCharacterNameByDiscordUid(discordUid);
            if (characterName == null) {
                event.getSlashCommandInteraction().createFollowupMessageBuilder()
                        .setContent("Error: No character name associated with your Discord account. Please use the `/name` command to link your OSRS character name to your Discord account first.")
                        .setFlags(MessageFlag.EPHEMERAL)
                        .send();
                return;
            }

            logger.info("Attempting to retrieve optional arguments...");
            User mentionedUser = event.getSlashCommandInteraction().getOptionUserValueByName("user").orElse(null);
            Optional<Long> pointsOpt = event.getSlashCommandInteraction().getOptionLongValueByName("points");
            String reason = event.getSlashCommandInteraction().getOptionStringValueByName("reason").orElse("No reason provided");

            logger.info("Mentioned user: {}", mentionedUser != null ? mentionedUser.getDiscriminatedName() : "None");
            logger.info("Points: {}", pointsOpt.orElse(0L));
            logger.info("Reason: {}", reason);

            int points = pointsOpt.map(Long::intValue).orElse(0);

            if (mentionedUser != null && points != 0) {
                String mentionedCharacterName = getCharacterNameByDiscordUid(mentionedUser.getId());
                logger.info("Attempting to modify points for: {}", mentionedCharacterName);

                if (mentionedCharacterName == null) {
                    event.getSlashCommandInteraction().createFollowupMessageBuilder()
                            .setContent(mentionedUser.getDisplayName(server) + " isn't registered to a character.")
                            .setFlags(MessageFlag.EPHEMERAL)
                            .send();
                    return;
                }

                int currentPoints = getUserPoints(mentionedCharacterName);

                if (points < 0) {
                    if (!hasPermissionToRemovePoints(server, user)) {
                        event.getSlashCommandInteraction().createFollowupMessageBuilder()
                                .setContent("You do not have permission to remove points.")
                                .setFlags(MessageFlag.EPHEMERAL)
                                .send();
                        return;
                    }

                    if (currentPoints + points < 0) {
                        event.getSlashCommandInteraction().createFollowupMessageBuilder()
                                .setContent("Cannot remove more points than the user currently has. " + mentionedCharacterName + " has " + currentPoints + " points.")
                                .setFlags(MessageFlag.EPHEMERAL)
                                .send();
                        return;
                    }
                }

                if (characterName.equalsIgnoreCase(mentionedCharacterName)){
                    event.getSlashCommandInteraction().createFollowupMessageBuilder()
                            .setContent("**Listen here... snowflake**: 302 is about giving! You can't award yourself points, but we appreciate that you tried.")
                            .setFlags(MessageFlag.EPHEMERAL)
                            .send();
                    return;
                }
                /*
                int pointsGivenInLast24Hours = getPointsGivenInLast24Hours(characterName, mentionedCharacterName);
                if (pointsGivenInLast24Hours + points > 5) {
                    event.getSlashCommandInteraction().createFollowupMessageBuilder()
                            .setContent("You have already given " + pointsGivenInLast24Hours + " points to " + mentionedCharacterName + " in the last 24 hours. You can only give a maximum of 5 points per 24 hours to the same user.")
                            .setFlags(MessageFlag.EPHEMERAL)
                            .send();
                    return;
                }
                 */
                int pointsGivenInLastWeek = getPointsGivenInLastWeek(characterName, mentionedCharacterName);
                if (pointsGivenInLastWeek + points > 15) {
                    event.getSlashCommandInteraction().createFollowupMessageBuilder()
                            .setContent("You have given " + pointsGivenInLastWeek + " points to " + mentionedCharacterName + " in the last week. You can only give a maximum of 15 points per week to the same user.")
                            .setFlags(MessageFlag.EPHEMERAL)
                            .send();
                    return;
                }
                int userPoints = getUserPoints(characterName);
                String userRank = getUserRank(characterName);
                int givenPoints = getUserGivenPoints(characterName);
                int totalPoints = getRankTotalPoints(userRank);
                if ((givenPoints + points) > totalPoints){
                    event.getSlashCommandInteraction().createFollowupMessageBuilder()
                            .setContent("You have " + Math.subtractExact(totalPoints, givenPoints) + " points to give currently. You need " + points + ".")
                            .setFlags(MessageFlag.EPHEMERAL)
                            .send();
                    return;
                }

                updateUserPoints(mentionedCharacterName, points);
                int newPoints = currentPoints + points;
                logPointsTransaction(mentionedCharacterName, points, reason, characterName, currentPoints, newPoints);

                String action = points > 0 ? "Received" : "Lost";
                postPointsUpdate(server, mentionedCharacterName + " now has " + newPoints + " points! " + action + " " + Math.abs(points) + " from " + characterName + " for " + reason);

                event.getSlashCommandInteraction().createFollowupMessageBuilder()
                        .setContent(mentionedCharacterName + " now has " + newPoints + " points.")
                        .setFlags(MessageFlag.EPHEMERAL)
                        .send();
            } else {
                int userPoints = getUserPoints(characterName);
                String userRank = getUserRank(characterName);
                int givenPoints = getUserGivenPoints(characterName);
                int totalPoints = getRankTotalPoints(userRank);
                int availablePoints = totalPoints - givenPoints;

                String pointsReceivedFromOthers = getPointsReceivedFromOthers(characterName);

                event.getSlashCommandInteraction().createFollowupMessageBuilder()
                        .setContent("You have " + userPoints + " points.\n" +
                                "You have " + availablePoints + "/" + totalPoints + " points remaining to give.\n" +
                                "Points received from others:\n" + pointsReceivedFromOthers)
                        .setFlags(MessageFlag.EPHEMERAL)
                        .send();
            }
        } catch (Exception e) {
            logger.error("Error processing points command: ", e);
            event.getSlashCommandInteraction().createFollowupMessageBuilder()
                    .setContent("An error occurred while processing your request. Please try again later.")
                    .setFlags(MessageFlag.EPHEMERAL)
                    .send();
        }
    }

    private void processContextMenuCommand(UserContextMenuCommandEvent event) {
        try {
            User user = event.getUserContextMenuInteraction().getUser();
            long discordUid = user.getId();
            Server server = event.getUserContextMenuInteraction().getServer().orElse(null);

            if (server == null) {
                event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                        .setContent("Error: Couldn't retrieve server information.")
                        .setFlags(MessageFlag.EPHEMERAL)
                        .send();
                return;
            }

            String characterName = getCharacterNameByDiscordUid(discordUid);
            if (characterName == null) {
                event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                        .setContent("Error: No character name associated with your Discord account. Please use the `/name` command to link your OSRS character name to your Discord account first.")
                        .setFlags(MessageFlag.EPHEMERAL)
                        .send();
                return;
            }

            User mentionedUser = event.getUserContextMenuInteraction().getTarget();
            if (mentionedUser == null) {
                event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                        .setContent("Error: Could not retrieve the mentioned user.")
                        .setFlags(MessageFlag.EPHEMERAL)
                        .send();
                return;
            }

            // Default points for context menu
            int points = 3;
            String reason = "being awesome!"; // Default reason for context menu

            String mentionedCharacterName = getCharacterNameByDiscordUid(mentionedUser.getId());
            if (mentionedCharacterName == null) {
                event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                        .setContent(mentionedUser.getDisplayName(server) + " isn't registered to a character.")
                        .send();
                return;
            }

            if (characterName.equalsIgnoreCase(mentionedCharacterName)) {
                event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                        .setContent("**Listen here... snowflake**: 302 is about giving! You can't award yourself points, but we appreciate that you tried.")
                        .setFlags(MessageFlag.EPHEMERAL)
                        .send();
                return;
            }

            int currentPoints = getUserPoints(mentionedCharacterName);

            if (points < 0) {
                if (!hasPermissionToRemovePoints(server, user)) {
                    event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                            .setContent("You do not have permission to remove points.")
                            .setFlags(MessageFlag.EPHEMERAL)
                            .send();
                    return;
                }

                if (currentPoints + points < 0) {
                    event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                            .setContent("Cannot remove more points than the user currently has. " + mentionedCharacterName + " has " + currentPoints + " points.")
                            .setFlags(MessageFlag.EPHEMERAL)
                            .send();
                    return;
                }
            }

                /*
                int pointsGivenInLast24Hours = getPointsGivenInLast24Hours(characterName, mentionedCharacterName);
                if (pointsGivenInLast24Hours + points > 5) {
                    event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                            .setContent("You have already given " + pointsGivenInLast24Hours + " points to " + mentionedCharacterName + " in the last 24 hours. You can only give a maximum of 5 points per 24 hours to the same user.")
                            .setFlags(MessageFlag.EPHEMERAL)
                            .send();
                    return;
                }
                 */
            int pointsGivenInLastWeek = getPointsGivenInLastWeek(characterName, mentionedCharacterName);
            if (pointsGivenInLastWeek + points > 15) {
                event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                        .setContent("You have given " + pointsGivenInLastWeek + " points to " + mentionedCharacterName + " in the last week. You can only give a maximum of 15 points per week to the same user.")
                        .setFlags(MessageFlag.EPHEMERAL)
                        .send();
                return;
            }

            int userPoints = getUserPoints(characterName);
            String userRank = getUserRank(characterName);
            int givenPoints = getUserGivenPoints(characterName);
            int totalPoints = getRankTotalPoints(userRank);
            if ((givenPoints + points) > totalPoints){
                event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                        .setContent("You have " + Math.subtractExact(totalPoints, givenPoints) + " points to give currently. You must wait.")
                        .setFlags(MessageFlag.EPHEMERAL)
                        .send();
                return;
            }

            // Update points and log the transaction
            updateUserPoints(mentionedCharacterName, points);
            int newPoints = currentPoints + points;
            logPointsTransaction(mentionedCharacterName, points, reason, characterName, currentPoints, newPoints);

            String action = points > 0 ? "Received" : "Lost";
            postPointsUpdate(server, mentionedCharacterName + " now has " + newPoints + " points! " + action + " " + Math.abs(points) + " from " + characterName + " for " + reason);

            event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                    .setContent(mentionedCharacterName + " now has " + newPoints + " points.")
                    .setFlags(MessageFlag.EPHEMERAL)
                    .send();

        } catch (Exception e) {
            logger.error("Error processing context menu points command: ", e);
            event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                    .setContent("An error occurred while processing your request. Please try again later.")
                    .send();
        }
    }


    private void handlePointsTransaction(Server server, String characterName, User mentionedUser, int points, String reason) {
        String mentionedCharacterName = getCharacterNameByDiscordUid(mentionedUser.getId());
        updateUserPoints(mentionedCharacterName, points);

        String channelId = getPointsChannelId();
        if (channelId != null) {
            server.getTextChannelById(channelId).ifPresent(channel ->
                channel.sendMessage("User " + mentionedUser.getDisplayName(server) +
                        " received " + points + " points from " + characterName + " for: " + reason)
            );
        } else {
            logger.warn("Points channel ID is null. No message posted.");
        }
    }

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
