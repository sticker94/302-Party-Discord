package org.javacord.Discord302Party.service;

import io.github.cdimascio.dotenv.Dotenv;
import org.javacord.api.entity.permission.Role;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.util.logging.ExceptionLogger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public class UserVerificationService {

    // Load environment variables
    private static final Dotenv dotenv = Dotenv.load();
    private static final String DB_URL = "jdbc:mysql://" + dotenv.get("DB_HOST") + ":3306/" + dotenv.get("DB_NAME");
    private static final String USER = dotenv.get("DB_USER");
    private static final String PASS = dotenv.get("DB_PASS");

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL, USER, PASS);
    }

    public void verifyAllUsers(Server server) {
        try (Connection connection = connect()) {
            String query = "SELECT discord_uid, character_name FROM discord_users";
            try (PreparedStatement preparedStatement = connection.prepareStatement(query);
                 ResultSet resultSet = preparedStatement.executeQuery()) {

                Role greenPartyHatRole = server.getRoleById("1168065194858119218").orElse(null);
                if (greenPartyHatRole == null) {
                    System.err.println("Error: Couldn't find the 'Green Party Hat' role.");
                    return;
                }

                while (resultSet.next()) {
                    long discordUid = resultSet.getLong("discord_uid");
                    String characterName = resultSet.getString("character_name");

                    Optional<User> userOptional = server.getMemberById(discordUid);
                    if (userOptional.isPresent()) {
                        User user = userOptional.get();

                        // Check if the user already has the Green Party Hat role
                        if (!user.getRoles(server).contains(greenPartyHatRole)) {
                            user.addRole(greenPartyHatRole).exceptionally(ExceptionLogger.get());
                        }

                        // Fetch the rank from the members table
                        String rank = getRank(characterName);
                        if (rank != null) {
                            Optional<Role> rankRole = server.getRolesByNameIgnoreCase(rank).stream().findFirst();
                            if (rankRole.isPresent()) {
                                if (!user.getRoles(server).contains(rankRole.get())) {
                                    user.addRole(rankRole.get()).exceptionally(ExceptionLogger.get());
                                    System.out.println("User " + characterName + " has been assigned the role: " + rank + ".");
                                }
                            } else {
                                System.err.println("Error: Couldn't find the role for rank: " + rank + ".");
                            }
                        } else {
                            System.err.println("Error: Couldn't find the rank for character: " + characterName + ".");
                        }
                    } else {
                        System.err.println("Error: Couldn't find user with Discord UID: " + discordUid + ".");
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
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
            e.printStackTrace();
        }
        return null;
    }
}
