package org.javacord.Discord302Party.command;

import io.github.cdimascio.dotenv.Dotenv;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class PointsCommand implements SlashCommandCreateListener {

    // Load environment variables
    private static final Dotenv dotenv = Dotenv.load();
    private static final String DB_URL = "jdbc:mysql://" + dotenv.get("DB_HOST") + ":3306/" + dotenv.get("DB_NAME");
    private static final String USER = dotenv.get("DB_USER");
    private static final String PASS = dotenv.get("DB_PASS");

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL, USER, PASS);
    }

    private String getCharacterNameByDiscordUid(long discordUid) {
        String query = "SELECT character_name FROM discord_users WHERE discord_uid = ?";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setLong(1, discordUid);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                return resultSet.getString("character_name");
            }
        } catch (SQLException e) {
            e.printStackTrace();
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
                return resultSet.getInt("points");
            }
        } catch (SQLException e) {
            e.printStackTrace();
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
                return resultSet.getInt("given_points");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    private int getRankTotalPoints(String rank) {
        String query = "SELECT total_points FROM config WHERE rank = ?";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, rank);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                return resultSet.getInt("total_points");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    private String getUserRank(String characterName) {
        String query = "SELECT rank FROM members WHERE username = ?";
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

    @Override
    public void onSlashCommandCreate(SlashCommandCreateEvent event) {
        if (event.getSlashCommandInteraction().getCommandName().equalsIgnoreCase("points")) {
            User user = event.getSlashCommandInteraction().getUser();
            long discordUid = user.getId();
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

            // Fetch user's points
            int userPoints = getUserPoints(characterName);

            // Fetch user's rank and given points
            String userRank = getUserRank(characterName);
            int givenPoints = getUserGivenPoints(characterName);
            int totalPoints = getRankTotalPoints(userRank);

            // Calculate available points
            int availablePoints = totalPoints - givenPoints;

            // Respond with the points information
            event.getSlashCommandInteraction().createImmediateResponder()
                    .setContent("You have " + userPoints + " points.\nYou have " + availablePoints + "/" + totalPoints + " points remaining to give.")
                    .respond();
        }
    }
}

