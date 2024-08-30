package org.javacord.Discord302Party.command;

import io.github.cdimascio.dotenv.Dotenv;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.*;
import java.util.Optional;

public class PointsCommand implements SlashCommandCreateListener {

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
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                logger.info("Character name found for Discord UID: {}", discordUid);
                return resultSet.getString("character_name");
            } else {
                logger.warn("No character name found for Discord UID: {}", discordUid);
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
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                logger.info("Points found for character name: {}", characterName);
                return resultSet.getInt("points");
            } else {
                logger.warn("No points found for character name: {}", characterName);
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
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                logger.info("Given points found for character name: {}", characterName);
                return resultSet.getInt("given_points");
            } else {
                logger.warn("No given points found for character name: {}", characterName);
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
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                logger.info("Total points found for rank: {}", rank);
                return resultSet.getInt("total_points");
            } else {
                logger.warn("No total points found for rank: {}", rank);
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
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                logger.info("Rank found for character name: {}", characterName);
                return resultSet.getString("rank");
            } else {
                logger.warn("No rank found for character name: {}", characterName);
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

    private void updateUserGivenPoints(String characterName, int pointsGiven) {
        String query = "UPDATE members SET given_points = given_points + ? WHERE username = ?";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setInt(1, pointsGiven);
            preparedStatement.setString(2, characterName);
            int rowsUpdated = preparedStatement.executeUpdate();
            if (rowsUpdated > 0) {
                logger.info("Successfully updated given points for character name: {}", characterName);
            } else {
                logger.warn("No rows updated for character name: {}", characterName);
            }
        } catch (SQLException e) {
            logger.error("SQL Exception while updating user given points: ", e);
        }
    }

    private String getPointsChannelId() {
        String query = "SELECT value FROM disc_config WHERE key_name = 'points_channel_id'";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            ResultSet resultSet = preparedStatement.executeQuery();
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
                    .respond().join();

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
            User mentionedUser = event.getSlashCommandInteraction().getOptionUserValueByName("user").orElse(null);
            Optional<Long> pointsToGiveOpt = event.getSlashCommandInteraction().getOptionLongValueByName("points");
            String reason = event.getSlashCommandInteraction().getOptionStringValueByName("reason").orElse("No reason provided");

            logger.info("Mentioned user: {}", mentionedUser != null ? mentionedUser.getDiscriminatedName() : "None");
            logger.info("Points to give: {}", pointsToGiveOpt.orElse(0L));
            logger.info("Reason: {}", reason);

            int pointsToGive = pointsToGiveOpt.map(Long::intValue).orElse(0);

            if (mentionedUser != null && pointsToGive > 0) {
                // Handle giving points
                String mentionedCharacterName = getCharacterNameByDiscordUid(mentionedUser.getId());
                logger.info("Attempting to give points to: {}", mentionedCharacterName);

                if (mentionedCharacterName == null) {
                    event.getSlashCommandInteraction().createFollowupMessageBuilder()
                            .setContent(mentionedUser.getDisplayName(server) + " isn't registered to a character.")
                            .send();
                    return;
                }

                // Verify if the user has enough points to give
                String userRank = getUserRank(characterName);
                int givenPoints = getUserGivenPoints(characterName);
                int totalPoints = getRankTotalPoints(userRank);
                int availablePoints = totalPoints - givenPoints;

                logger.info("User rank: {}, Given points: {}, Available points: {}", userRank, givenPoints, availablePoints);

                if (availablePoints >= pointsToGive) {
                    // Update points for the mentioned user and giver
                    updateUserPoints(mentionedCharacterName, pointsToGive);
                    updateUserGivenPoints(characterName, pointsToGive);

                    // Fetch the remaining available points
                    int updatedGivenPoints = getUserGivenPoints(characterName);
                    int updatedAvailablePoints = totalPoints - updatedGivenPoints;

                    // Post to the configured channel
                    postPointsUpdate(server, mentionedCharacterName + " now has " + getUserPoints(mentionedCharacterName)
                            + " points! Received " + pointsToGive + " from " + characterName + " for " + reason);

                    event.getSlashCommandInteraction().createFollowupMessageBuilder()
                            .setContent("Gave " + mentionedCharacterName + " " + pointsToGive + " points | " + updatedAvailablePoints + " remaining.")
                            .send();
                } else {
                    event.getSlashCommandInteraction().createFollowupMessageBuilder()
                            .setContent("You don't have enough points to give. You have " + availablePoints + " points available.")
                            .send();
                }
            } else {
                // Fetch and display user's own points
                int userPoints = getUserPoints(characterName);
                String userRank = getUserRank(characterName);
                int givenPoints = getUserGivenPoints(characterName);
                int totalPoints = getRankTotalPoints(userRank);
                int availablePoints = totalPoints - givenPoints;

                event.getSlashCommandInteraction().createFollowupMessageBuilder()
                        .setContent("You have " + userPoints + " points.\nYou have " + availablePoints + "/" + totalPoints + " points remaining to give.")
                        .send();
            }
        }
    }
}
