package org.javacord.Discord302Party.command;

import io.github.cdimascio.dotenv.Dotenv;
import org.javacord.api.entity.permission.Role;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.interaction.UserContextMenuCommandEvent;
import org.javacord.api.event.message.MessageCreateEvent;
import org.javacord.api.listener.interaction.UserContextMenuCommandListener;
import org.javacord.api.listener.message.MessageCreateListener;
import org.javacord.api.util.logging.ExceptionLogger;

import java.sql.*;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class LinkOSRSNameCommand implements UserContextMenuCommandListener, MessageCreateListener {

    private static final Dotenv dotenv = Dotenv.load();
    private static final String DB_URL = "jdbc:mysql://" + dotenv.get("DB_HOST") + ":3306/" + dotenv.get("DB_NAME");
    private static final String USER = dotenv.get("DB_USER");
    private static final String PASS = dotenv.get("DB_PASS");

    private AtomicReference<User> modUserRef = new AtomicReference<>();
    private AtomicReference<User> targetUserRef = new AtomicReference<>();

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL, USER, PASS);
    }

    private boolean isMemberInClan(String username) {
        String query = "SELECT * FROM members WHERE LOWER(REPLACE(username, '_', ' ')) = LOWER(?) COLLATE utf8mb4_general_ci";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, username.toLowerCase());
            ResultSet resultSet = preparedStatement.executeQuery();
            return resultSet.next();
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
                return existingDiscordUid != discordUid;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
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
    public void onUserContextMenuCommand(UserContextMenuCommandEvent event) {
        if (event.getUserContextMenuInteraction().getCommandName().equalsIgnoreCase("Link OSRS Name")) {

            User targetUser = event.getUserContextMenuInteraction().getTarget();
            Optional<Server> serverOptional = event.getUserContextMenuInteraction().getServer();

            if (serverOptional.isPresent()) {
                Server server = serverOptional.get();
                targetUserRef.set(targetUser);

                // Try using the target user's current nickname
                String characterName = targetUser.getDisplayName(server);

                // Initial response to avoid timeout
                event.getUserContextMenuInteraction().createImmediateResponder()
                        .setContent("Attempting to link OSRS name using the current nickname...")
                        .respond();

                long discordUid = targetUser.getId();

                // Check for duplicates
                if (isDuplicateEntry(discordUid, characterName)) {
                    event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                            .setContent("Duplicate entry detected. The OSRS name is already linked to a different account.")
                            .send();
                    return;
                }

                // Check if the character is in the clan
                if (isMemberInClan(characterName)) {
                    // Link the user and update roles as normal
                    handleSuccessfulLink(event, targetUser, server, characterName);
                } else {
                    // If the nickname doesn't match a clan member, ask for manual input via follow-up message
                    event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                            .setContent("The nickname is not recognized as a clan member. Please reply with the OSRS character name.")
                            .send();

                    // Store the moderator's user reference
                    modUserRef.set(event.getUserContextMenuInteraction().getUser());
                }
            } else {
                event.getUserContextMenuInteraction().createImmediateResponder()
                        .setContent("Error: Couldn't retrieve server information.")
                        .respond();
            }
        }
    }

    @Override
    public void onMessageCreate(MessageCreateEvent messageEvent) {
        // Check if the message is from the moderator and it's in the correct server
        if (messageEvent.getMessageAuthor().isUser() &&
                messageEvent.getMessageAuthor().asUser().get().equals(modUserRef.get())) {

            Server server = messageEvent.getServer().orElse(null);
            String osrsCharacterName = messageEvent.getMessageContent();

            // Ensure we're working with a valid server
            if (server != null) {
                // Check if the manually entered character is in the clan
                if (isMemberInClan(osrsCharacterName)) {
                    messageEvent.getChannel().sendMessage("OSRS character " + osrsCharacterName + " is recognized! Proceeding with linking.");
                    handleSuccessfulLinkOnMessage(messageEvent, targetUserRef.get(), server, osrsCharacterName);

                    // After processing the input, remove the listener
                    messageEvent.getApi().removeListener(this);
                } else {
                    messageEvent.getChannel().sendMessage("The provided OSRS character name is not a member of the clan.");
                    // After sending the error message, remove the listener to prevent spamming
                    messageEvent.getApi().removeListener(this);
                }
            }
        }
    }

    private void handleSuccessfulLink(UserContextMenuCommandEvent event, User targetUser, Server server, String characterName) {
        long discordUid = targetUser.getId();

        // Set Discord nickname to the OSRS character name
        boolean nicknameUpdated = retryOperation(() ->
                targetUser.updateNickname(server, characterName).exceptionally(ExceptionLogger.get())
        );

        if (!nicknameUpdated) {
            event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                    .setContent("Failed to update the nickname after several attempts. Please try again later.")
                    .send();
            return;
        }

        // Assign the "Green Party Hats" role by ID
        Role greenPartyHatsRole = server.getRoleById("1168065194858119218").orElse(null);
        if (greenPartyHatsRole != null && !targetUser.getRoles(server).contains(greenPartyHatsRole)) {
            boolean roleAssigned = retryOperation(() ->
                    targetUser.addRole(greenPartyHatsRole).exceptionally(ExceptionLogger.get())
            );
            if (!roleAssigned) {
                event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                        .setContent("Failed to assign the 'Green Party Hats' role.")
                        .send();
                return;
            }
        }

        // Assign the rank role based on the OSRS rank
        String rank = getRank(characterName);
        if (rank != null) {
            Role role = rank.equalsIgnoreCase("deputy_owner") ?
                    server.getRolesByNameIgnoreCase("Co-Owner").stream().findFirst().orElse(null) :
                    server.getRolesByNameIgnoreCase(rank).stream().findFirst().orElse(null);

            if (role != null && !targetUser.getRoles(server).contains(role)) {
                boolean rankRoleAssigned = retryOperation(() ->
                        targetUser.addRole(role).exceptionally(ExceptionLogger.get())
                );
                if (!rankRoleAssigned) {
                    event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                            .setContent("Failed to assign the role for rank: " + rank)
                            .send();
                    return;
                }
            }
            // Save the user details in the database
            saveUserDetails(discordUid, characterName, rank);

            // Respond to the moderator with success
            event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                    .setContent("OSRS name linked and roles updated for " + targetUser.getDisplayName(server) + "!")
                    .send();
        } else {
            event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                    .setContent("Rank not found for the OSRS character.")
                    .send();
        }
    }

    private void handleSuccessfulLinkOnMessage(MessageCreateEvent event, User targetUser, Server server, String characterName) {
        long discordUid = targetUser.getId();

        // Set Discord nickname to the OSRS character name
        boolean nicknameUpdated = retryOperation(() ->
                targetUser.updateNickname(server, characterName).exceptionally(ExceptionLogger.get())
        );

        if (!nicknameUpdated) {
            event.getChannel().sendMessage("Failed to update the nickname after several attempts. Please try again later.");
            return;
        }

        // Assign the "Green Party Hats" role by ID
        Role greenPartyHatsRole = server.getRoleById("1168065194858119218").orElse(null);
        if (greenPartyHatsRole != null && !targetUser.getRoles(server).contains(greenPartyHatsRole)) {
            boolean roleAssigned = retryOperation(() ->
                    targetUser.addRole(greenPartyHatsRole).exceptionally(ExceptionLogger.get())
            );
            if (!roleAssigned) {
                event.getChannel().sendMessage("Failed to assign the 'Green Party Hats' role.");
                return;
            }
        }

        // Assign the rank role based on the OSRS rank
        String rank = getRank(characterName);
        if (rank != null) {
            Role role = rank.equalsIgnoreCase("deputy_owner") ?
                    server.getRolesByNameIgnoreCase("Co-Owner").stream().findFirst().orElse(null) :
                    server.getRolesByNameIgnoreCase(rank).stream().findFirst().orElse(null);

            if (role != null && !targetUser.getRoles(server).contains(role)) {
                boolean rankRoleAssigned = retryOperation(() ->
                        targetUser.addRole(role).exceptionally(ExceptionLogger.get())
                );
                if (!rankRoleAssigned) {
                    event.getChannel().sendMessage("Failed to assign the role for rank: " + rank);
                    return;
                }
            }
            // Save the user details in the database
            saveUserDetails(discordUid, characterName, rank);

            // Notify the moderator that linking is complete
            event.getChannel().sendMessage("OSRS name linked and roles updated for " + targetUser.getDisplayName(server) + "!");
        } else {
            event.getChannel().sendMessage("Rank not found for the OSRS character.");
        }
    }

    private boolean retryOperation(Runnable operation) {
        int maxRetries = 3;
        int retryDelay = 2000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                CompletableFuture<Void> result = CompletableFuture.runAsync(operation);
                result.join();
                return true;
            } catch (Exception e) {
                if (attempt == maxRetries) {
                    return false;
                }
                try {
                    Thread.sleep(retryDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return false;
    }
}
