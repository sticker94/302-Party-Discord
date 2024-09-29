package org.javacord.Discord302Party.command;

import io.github.cdimascio.dotenv.Dotenv;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;
import org.javacord.api.util.logging.ExceptionLogger;

import java.sql.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class PointsReactionCommand implements SlashCommandCreateListener {

    private static final Logger logger = LogManager.getLogger(PointsReactionCommand.class);

    // Load environment variables
    private static final Dotenv dotenv = Dotenv.load();
    private static final String DB_URL = "jdbc:mysql://" + dotenv.get("DB_HOST") + ":3306/" + dotenv.get("DB_NAME");
    private static final String USER = dotenv.get("DB_USER");
    private static final String PASS = dotenv.get("DB_PASS");

    // Set to track users who have already reacted
    private final Set<Long> reactedUsers = new HashSet<>();

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
                    return resultSet.getString("character_name");
                }
            }
        } catch (SQLException e) {
            logger.error("SQL Exception while fetching character name: ", e);
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

    private void logToPointsChannel(Server server, String messageContent) {
        String channelId = getPointsChannelId();
        if (channelId != null) {
            server.getTextChannelById(channelId).ifPresent(channel -> {
                channel.sendMessage(messageContent);
            });
        } else {
            logger.warn("Points log channel not found. No message posted.");
        }
    }

    private void handleUserPointAddition(User user, Server server, Message message) {
        long discordUid = user.getId();

        // Check if the user has already reacted
        if (reactedUsers.contains(discordUid)) {
            logger.warn("User {} has already reacted. Ignoring reaction.", user.getDiscriminatedName());
            return; // Ignore if they already reacted
        }

        String characterName = getCharacterNameByDiscordUid(discordUid);

        if (characterName != null) {
            int pointsToAdd = 1;  // We add 1 point per reaction
            updateUserPoints(characterName, pointsToAdd);
            logger.info("Added {} points to {}", pointsToAdd, characterName);

            // Add the user to the set to prevent multiple reactions
            reactedUsers.add(discordUid);

            String logMessage = user.getDisplayName(server) + " has received 1 point!";
            logToPointsChannel(server, logMessage);  // Log to the points log channel
        } else {
            logger.warn("Character name not found for user {}", user.getDiscriminatedName());
        }
    }

    @Override
    public void onSlashCommandCreate(SlashCommandCreateEvent event) {
        if (event.getSlashCommandInteraction().getCommandName().equalsIgnoreCase("pointsreaction")) {
            event.getSlashCommandInteraction().createImmediateResponder()
                    .setContent("** Points Giveaway! **")
                    .respond()
                    .thenAccept(interactionResponse -> {
                        // Fetch the channel from the interaction
                        event.getSlashCommandInteraction().getChannel().ifPresent(channel -> {
                            // Get the current UNIX timestamp and calculate the timestamp for 10 minutes later
                            long currentTime = Instant.now().getEpochSecond();
                            long endTime = currentTime + 10 * 60;  // 10 minutes later

                            // Use Discord's relative timestamp feature for countdown
                            String countdownText = "<t:" + endTime + ":R>"; // Will show "in 10 minutes", "in 5 minutes", etc.

                            // Send the message with the countdown timer
                            channel.sendMessage("React to this message to receive 1 point!\nGiveaway Expires: " + countdownText)
                                    .thenAccept(message -> {
                                        // Add the reaction
                                        message.addReaction("👍").thenAccept(unused -> {
                                            // Add a reaction listener for the users reacting with the 👍 emoji
                                            message.addReactionAddListener(reactionAddEvent -> {
                                                // Check if it's a user (not a bot) reacting with the correct emoji
                                                if (reactionAddEvent.getUser().isPresent()
                                                        && !reactionAddEvent.getUser().get().isBot()
                                                        && reactionAddEvent.getEmoji().equalsEmoji("👍")) {

                                                    // Add point logic
                                                    User reactingUser = reactionAddEvent.getUser().get();
                                                    Server server = event.getSlashCommandInteraction().getServer().orElse(null);
                                                    handleUserPointAddition(reactingUser, server, message);
                                                }
                                            }).removeAfter(10, TimeUnit.MINUTES); // Remove listener after 10 minutes

                                            // Delete the message after 10 minutes
                                            CompletableFuture.delayedExecutor(10, TimeUnit.MINUTES).execute(() -> {
                                                message.delete().join();
                                                reactedUsers.clear();  // Clear the set after the giveaway ends
                                            });
                                        }).exceptionally(ExceptionLogger.get());
                                    }).exceptionally(ExceptionLogger.get());
                        });
                    }).exceptionally(ExceptionLogger.get());
        }
    }
}
