package org.javacord.Discord302Party;

import io.github.cdimascio.dotenv.Dotenv;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class RankService {

    Dotenv dotenv = Dotenv.load();

    private final String DB_URL = "jdbc:mysql://" + dotenv.get("DB_HOST") + ":3306/" + dotenv.get("DB_NAME");
    private final String USER = dotenv.get("DB_USER");
    private final String PASS = dotenv.get("DB_PASS");

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL, USER, PASS);
    }

    public List<String> getAllRanks() {
        List<String> ranks = new ArrayList<>();
        String query = "SELECT rank FROM config ORDER BY rank_order ASC";

        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query);
             ResultSet resultSet = preparedStatement.executeQuery()) {

            while (resultSet.next()) {
                ranks.add(resultSet.getString("rank"));
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return ranks;
    }

    public boolean validatePointsRequirement(String discordUid, String rank, int pointsRequired) {
        String query = "SELECT points FROM members WHERE username = (SELECT character_name FROM discord_users WHERE discord_uid = ?) AND rank = ?";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, discordUid);
            preparedStatement.setString(2, rank);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                int points = resultSet.getInt("points");
                return points >= pointsRequired;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public boolean validatePointsFromDifferentPlayers(String discordUid, int requiredPoints, int differentPlayers) {
        String query = "SELECT COUNT(DISTINCT related_user) AS unique_players FROM points_transactions WHERE character_name = (SELECT character_name FROM discord_users WHERE discord_uid = ?) AND points_change > 0";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, discordUid);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                int uniquePlayers = resultSet.getInt("unique_players");
                return uniquePlayers >= differentPlayers;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public boolean validatePointsFromDifferentRanks(String discordUid, int requiredPoints, int differentRanks) {
        String query = "SELECT COUNT(DISTINCT r.rank) AS unique_ranks " +
                "FROM points_transactions pt " +
                "JOIN discord_users du ON pt.related_user = du.character_name " +
                "JOIN config r ON du.rank = r.rank " +
                "WHERE pt.character_name = (SELECT character_name FROM discord_users WHERE discord_uid = ?) AND pt.points_change > 0";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, discordUid);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                int uniqueRanks = resultSet.getInt("unique_ranks");
                return uniqueRanks >= differentRanks;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public boolean validateTimeInClan(String discordUid, int requiredMonths) {
        String query = "SELECT TIMESTAMPDIFF(MONTH, MIN(timestamp), NOW()) AS time_in_clan " +
                "FROM points_transactions WHERE character_name = (SELECT character_name FROM discord_users WHERE discord_uid = ?)";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, discordUid);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                int timeInClan = resultSet.getInt("time_in_clan");
                return timeInClan >= requiredMonths;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public boolean validateTimeAtCurrentRank(String discordUid, int requiredMonths) {
        String query = "SELECT TIMESTAMPDIFF(MONTH, last_rank_update, NOW()) AS time_at_rank " +
                "FROM members WHERE username = (SELECT character_name FROM discord_users WHERE discord_uid = ?)";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, discordUid);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                int timeAtRank = resultSet.getInt("time_at_rank");
                return timeAtRank >= requiredMonths;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }
}
