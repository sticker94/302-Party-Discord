package org.javacord.Discord302Party.command;

import io.github.cdimascio.dotenv.Dotenv;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;

import java.sql.*;

public class VerifyCommand implements SlashCommandCreateListener {

    // Load environment variables
    private static final Dotenv dotenv = Dotenv.load();
    private static final String DB_URL = "jdbc:mysql://" + dotenv.get("DB_HOST") + ":3306/" + dotenv.get("DB_NAME");
    private static final String USER = dotenv.get("DB_USER");
    private static final String PASS = dotenv.get("DB_PASS");

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL, USER, PASS);
    }

    private String getCharacterNameByDiscordUid(long discordUid) {
        System.out.println("Checking for character name linked to Discord UID: " + discordUid); // Log the Discord UID
        String query = "SELECT character_name FROM discord_users WHERE discord_uid = ?";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setLong(1, discordUid);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                String characterName = resultSet.getString("character_name");
                System.out.println("Found character name: " + characterName); // Log the found character name
                return characterName;
            } else {
                System.out.println("No character name found for Discord UID: " + discordUid); // Log if nothing is found
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    private boolean verifyCharacter(String characterName, String verificationKey) {
        String query = "SELECT * FROM characters WHERE character_name = ? AND verification_key = ?";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, characterName);
            preparedStatement.setString(2, verificationKey);
            ResultSet resultSet = preparedStatement.executeQuery();
            return resultSet.next(); // Return true if the character and key match
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    private int getReplitUserId(String characterName, String verificationKey) {
        String query = "SELECT replit_user_id FROM characters WHERE character_name = ? AND verification_key = ?";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, characterName);
            preparedStatement.setString(2, verificationKey);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                return resultSet.getInt("replit_user_id");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return -1;
    }

    private void linkDiscordToReplit(long discordUid, int replitUserId, String characterName) {
        String query = "UPDATE discord_users SET replit_user_id = ? WHERE discord_uid = ? AND character_name = ?";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setInt(1, replitUserId);
            preparedStatement.setLong(2, discordUid);
            preparedStatement.setString(3, characterName);
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void markCharacterAsVerified(String characterName) {
        String query = "UPDATE characters SET verified = 1 WHERE character_name = ?";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, characterName);
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onSlashCommandCreate(SlashCommandCreateEvent event) {
        if (event.getSlashCommandInteraction().getCommandName().equalsIgnoreCase("verify")) {
            String verificationKey = event.getSlashCommandInteraction()
                    .getOptionStringValueByName("verification_key")
                    .orElse("Unknown");

            User user = event.getSlashCommandInteraction().getUser();
            long discordUid = user.getId();
            System.out.println("User Discord UID: " + discordUid); // Log the user's Discord UID
            Server server = event.getSlashCommandInteraction().getServer().orElse(null);

            if (server == null) {
                event.getSlashCommandInteraction().createImmediateResponder()
                        .setContent("Error: Couldn't retrieve server information.")
                        .respond();
                return;
            }

            // Retrieve the character name associated with the user's Discord UID
            String characterName = getCharacterNameByDiscordUid(discordUid);
            if (characterName == null) {
                event.getSlashCommandInteraction().createImmediateResponder()
                        .setContent("Error: No character name associated with your Discord account. Please use the `/name` command to link your OSRS character name to your Discord account first.")
                        .respond();
                return;
            }

            // Verify the character and key
            if (verifyCharacter(characterName, verificationKey)) {
                int replitUserId = getReplitUserId(characterName, verificationKey);
                if (replitUserId != -1) {
                    linkDiscordToReplit(discordUid, replitUserId, characterName);
                    markCharacterAsVerified(characterName);
                    event.getSlashCommandInteraction().createImmediateResponder()
                            .setContent("Your account has been successfully verified!")
                            .respond();
                } else {
                    event.getSlashCommandInteraction().createImmediateResponder()
                            .setContent("Error: Could not retrieve your Replit user ID.")
                            .respond();
                }
            } else {
                event.getSlashCommandInteraction().createImmediateResponder()
                        .setContent("Verification failed: The verification key provided does not match the character name associated with your account.")
                        .respond();
            }
        }
    }
}
