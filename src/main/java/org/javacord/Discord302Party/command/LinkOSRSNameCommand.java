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

    // References used to track who triggered the command, which user is being linked, etc.
    private AtomicReference<User> modUserRef = new AtomicReference<>();
    private AtomicReference<User> targetUserRef = new AtomicReference<>();

    // Variables to handle overwrite flow
    private AtomicReference<Boolean> awaitingOverwriteRef = new AtomicReference<>(false);
    private AtomicReference<Long> pendingDiscordUidRef = new AtomicReference<>();
    private AtomicReference<String> pendingCharacterNameRef = new AtomicReference<>();
    private AtomicReference<UserContextMenuCommandEvent> pendingEventRef = new AtomicReference<>();

    // ----------------------------------
    // Database methods
    // ----------------------------------
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
                if (existingDiscordUid != discordUid) {
                    // Log the duplicate entry
                    saveToDuplicateTable(discordUid, characterName);
                    return true;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
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
        String query = "INSERT INTO discord_users (discord_uid, character_name, rank) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE character_name = VALUES(character_name), rank = VALUES(rank)";
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

    // ----------------------------------
    // Main listener: user context menu
    // ----------------------------------
    @Override
    public void onUserContextMenuCommand(UserContextMenuCommandEvent event) {
        if (event.getUserContextMenuInteraction().getCommandName().equalsIgnoreCase("Link OSRS Name")) {

            User targetUser = event.getUserContextMenuInteraction().getTarget();
            Optional<Server> serverOptional = event.getUserContextMenuInteraction().getServer();

            if (serverOptional.isPresent()) {
                Server server = serverOptional.get();
                targetUserRef.set(targetUser);

                // Use the target user's current nickname as a guess
                String characterName = targetUser.getDisplayName(server);

                // Immediate response to avoid interaction timeout
                event.getUserContextMenuInteraction().createImmediateResponder()
                        .setContent("Attempting to link OSRS name using the current nickname...")
                        .respond();

                long discordUid = targetUser.getId();

                // Check for duplicates
                if (isDuplicateEntry(discordUid, characterName)) {
                    // Prompt the user (moderator) if they want to overwrite
                    event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                            .setContent("**Duplicate entry detected!** The OSRS name `" + characterName
                                    + "` is already linked to a different Discord account.\n"
                                    + "Would you like to **overwrite** the existing linkage? Reply `yes` or `no`.")
                            .send();

                    // Store references for overwrite flow
                    modUserRef.set(event.getUserContextMenuInteraction().getUser());   // Who triggered the command
                    pendingDiscordUidRef.set(discordUid);
                    pendingCharacterNameRef.set(characterName);
                    pendingEventRef.set(event);
                    awaitingOverwriteRef.set(true);

                    // We do NOT return here; we just wait for the moderator’s yes/no.
                    return;
                }

                // If no duplicate, proceed with normal logic:
                // Check if the character is in the clan
                if (isMemberInClan(characterName)) {
                    handleSuccessfulLink(event, targetUser, server, characterName);
                } else {
                    // If the nickname doesn't match a clan member, ask for manual input
                    event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                            .setContent("The nickname `" + characterName + "` is not recognized as a clan member. "
                                    + "Please reply with the correct OSRS character name.")
                            .send();
                    modUserRef.set(event.getUserContextMenuInteraction().getUser());
                }
            } else {
                event.getUserContextMenuInteraction().createImmediateResponder()
                        .setContent("Error: Couldn't retrieve server information.")
                        .respond();
            }
        }
    }

    // ----------------------------------
    // Message create listener
    // ----------------------------------
    @Override
    public void onMessageCreate(MessageCreateEvent messageEvent) {
        // 1) Overwrite flow: if we are waiting for a yes/no from the mod
        if (awaitingOverwriteRef.get()
                && messageEvent.getMessageAuthor().isUser()
                && messageEvent.getMessageAuthor().asUser().get().equals(modUserRef.get())) {

            String content = messageEvent.getMessageContent().trim().toLowerCase();

            if (content.equals("yes") || content.equals("no")) {
                // Stop waiting for overwrite after this response
                awaitingOverwriteRef.set(false);

                if (content.equals("yes")) {
                    // They agreed to overwrite => proceed with linking
                    messageEvent.getChannel().sendMessage("Overwriting existing linkage...");
                    overwriteExistingLink();
                } else {
                    // They declined
                    messageEvent.getChannel().sendMessage("Not overwriting existing linkage. Cancelled.");
                }

                // Reset references so we don't accidentally reuse them
                modUserRef.set(null);
                pendingDiscordUidRef.set(null);
                pendingCharacterNameRef.set(null);
                pendingEventRef.set(null);

                return;
            }
        }

        // 2) The existing manual OSRS name flow
        // Check if the message is from the moderator who was asked for a clan member name
        if (messageEvent.getMessageAuthor().isUser()
                && messageEvent.getMessageAuthor().asUser().get().equals(modUserRef.get())) {

            Server server = messageEvent.getServer().orElse(null);
            String osrsCharacterName = messageEvent.getMessageContent();

            // Ensure we have a valid server
            if (server != null && !awaitingOverwriteRef.get()) {
                // Check if the manually entered character is in the clan
                if (isMemberInClan(osrsCharacterName)) {
                    messageEvent.getChannel().sendMessage("OSRS character `" + osrsCharacterName
                            + "` is recognized! Proceeding with linking.");
                    handleSuccessfulLinkOnMessage(messageEvent, targetUserRef.get(), server, osrsCharacterName);
                } else {
                    messageEvent.getChannel().sendMessage("The provided OSRS character name is not a member of the clan.");
                }

                // Remove this listener from the API (clean up) and re-add context menu listener
                messageEvent.getApi().removeListener(this);
                messageEvent.getApi().addUserContextMenuCommandListener(this);
            }
        }
    }

    /**
     * Called if the user typed "yes" in response to the duplicate overwrite question.
     * We do the same flow as a normal link, except we skip the 'duplicate' check since we are intentionally overwriting.
     */
    private void overwriteExistingLink() {
        UserContextMenuCommandEvent event = pendingEventRef.get();
        if (event == null) {
            return;
        }

        Optional<Server> serverOptional = event.getUserContextMenuInteraction().getServer();
        if (serverOptional.isEmpty()) {
            event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                    .setContent("Error: No server found; cannot overwrite.")
                    .send();
            return;
        }

        Server server = serverOptional.get();
        User targetUser = targetUserRef.get();
        if (targetUser == null) {
            event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                    .setContent("Error: No target user found; cannot overwrite.")
                    .send();
            return;
        }

        long discordUid = pendingDiscordUidRef.get();
        String characterName = pendingCharacterNameRef.get();

        // *At this point, we already know it's a "duplicate" scenario, but the user said "yes" to overwrite.*
        // We can re-check clan membership or simply continue.
        // Let's keep the same logic to ensure valid clan membership:
        if (isMemberInClan(characterName)) {
            // Proceed with linking (same as normal)
            handleSuccessfulLink(event, targetUser, server, characterName);
        } else {
            event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                    .setContent("The nickname `" + characterName + "` is not recognized as a clan member. Overwrite aborted.")
                    .send();
        }
    }

    // ----------------------------------
    // Linking methods
    // ----------------------------------
    private void handleSuccessfulLink(UserContextMenuCommandEvent event, User targetUser, Server server, String characterName) {
        long discordUid = targetUser.getId();

        // Update Discord nickname to OSRS name
        boolean nicknameUpdated = retryOperation(() ->
                targetUser.updateNickname(server, characterName).exceptionally(ExceptionLogger.get())
        );

        if (!nicknameUpdated) {
            event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                    .setContent("Failed to update the nickname after several attempts. Please try again later.")
                    .send();
            return;
        }

        // Assign "Green Party Hats" role
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

        // Determine OSRS rank and assign corresponding role
        String rank = getRank(characterName);
        if (rank != null) {
            Role role = rank.equalsIgnoreCase("deputy_owner")
                    ? server.getRolesByNameIgnoreCase("Co-Owner").stream().findFirst().orElse(null)
                    : server.getRolesByNameIgnoreCase(rank).stream().findFirst().orElse(null);

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

            // Save user details in database
            saveUserDetails(discordUid, characterName, rank);

            // Respond with success
            event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                    .setContent("OSRS name linked and roles updated for **" + targetUser.getDisplayName(server) + "**!")
                    .send();
        } else {
            event.getUserContextMenuInteraction().createFollowupMessageBuilder()
                    .setContent("Rank not found for the OSRS character.")
                    .send();
        }
    }

    private void handleSuccessfulLinkOnMessage(MessageCreateEvent event, User targetUser, Server server, String characterName) {
        long discordUid = targetUser.getId();

        boolean nicknameUpdated = retryOperation(() ->
                targetUser.updateNickname(server, characterName).exceptionally(ExceptionLogger.get())
        );

        if (!nicknameUpdated) {
            event.getChannel().sendMessage("Failed to update the nickname after several attempts. Please try again later.");
            return;
        }

        // Assign "Green Party Hats" role
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

        // Determine OSRS rank
        String rank = getRank(characterName);
        if (rank != null) {
            Role role = rank.equalsIgnoreCase("deputy_owner")
                    ? server.getRolesByNameIgnoreCase("Co-Owner").stream().findFirst().orElse(null)
                    : server.getRolesByNameIgnoreCase(rank).stream().findFirst().orElse(null);

            if (role != null && !targetUser.getRoles(server).contains(role)) {
                boolean rankRoleAssigned = retryOperation(() ->
                        targetUser.addRole(role).exceptionally(ExceptionLogger.get())
                );
                if (!rankRoleAssigned) {
                    event.getChannel().sendMessage("Failed to assign the role for rank: " + rank);
                    return;
                }
            }

            // Save user details in DB
            saveUserDetails(discordUid, characterName, rank);
            event.getChannel().sendMessage("OSRS name linked and roles updated for **" + targetUser.getDisplayName(server) + "**!");
        } else {
            event.getChannel().sendMessage("Rank not found for the OSRS character.");
        }
    }

    /**
     * Retries the given operation (async) up to 3 times.
     */
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
