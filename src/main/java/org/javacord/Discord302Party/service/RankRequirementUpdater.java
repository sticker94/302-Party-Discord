package org.javacord.Discord302Party.service;

import org.javacord.Discord302Party.Member;
import io.github.cdimascio.dotenv.Dotenv;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class RankRequirementUpdater {

    static Dotenv dotenv = Dotenv.load();

    private final String DB_URL = "jdbc:mysql://" + dotenv.get("DB_HOST") + ":3306/" + dotenv.get("DB_NAME");
    private final String USER = dotenv.get("DB_USER");
    private final String PASS = dotenv.get("DB_PASS");
    private static final long UPDATE_INTERVAL = Long.parseLong(dotenv.get("UPDATE_INTERVAL", "3600")) * 1000; // 1 hour by default

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL, USER, PASS);
    }

    public void startUpdater() {
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                validateAllRankRequirements();
            }
        }, 0, UPDATE_INTERVAL);  // Run immediately, then repeat every hour
    }

    public void validateAllRankRequirements() {
        try (Connection connection = connect()) {
            String query = "SELECT discord_uid FROM discord_users";
            try (PreparedStatement preparedStatement = connection.prepareStatement(query);
                 ResultSet resultSet = preparedStatement.executeQuery()) {

                while (resultSet.next()) {
                    String discordUid = resultSet.getString("discord_uid");
                    validateRequirementsForUser(discordUid);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void validateRequirementsForUser(String discordUid) {
        try (Connection connection = connect()) {
            // Retrieve the user's current rank and username
            String currentRankQuery = "SELECT `rank`, username FROM members WHERE username = (SELECT character_name FROM discord_users WHERE discord_uid = ? LIMIT 1)";
            String nextRankQuery = "SELECT r_next.rank " +
                    "FROM config r_current " +
                    "JOIN config r_next ON r_next.rank_order = (" +
                    "    SELECT COALESCE(MAX(rank_order), 0) " +
                    "    FROM config " +
                    "    WHERE rank_order < r_current.rank_order " +
                    ") " +
                    "WHERE r_current.rank = ?";

            String currentRank = null;
            String characterName = null;
            String nextRank = null;

            // Get the current rank and username
            try (PreparedStatement currentRankStmt = connection.prepareStatement(currentRankQuery)) {
                currentRankStmt.setString(1, discordUid);
                try (ResultSet rs = currentRankStmt.executeQuery()) {
                    if (rs.next()) {
                        currentRank = rs.getString("rank");
                        characterName = rs.getString("username");
                    }
                }
            }

            if (currentRank == null) {
                // System.err.println("User's current rank not found. Discord UID: " + discordUid + ", Character Name: " + (characterName != null ? characterName : "Unknown"));
                return;
            }

            // Skip validation if the current rank is "owner"
            if (currentRank.equalsIgnoreCase("owner")) {
                return;
            }

            // Get the next higher rank based on rank_order
            try (PreparedStatement nextRankStmt = connection.prepareStatement(nextRankQuery)) {
                nextRankStmt.setString(1, currentRank);
                try (ResultSet rs = nextRankStmt.executeQuery()) {
                    if (rs.next()) {
                        nextRank = rs.getString("rank");
                    }
                }
            }

            if (nextRank == null) {
                System.err.println("Next rank not found for the current rank: " + currentRank + ", Character Name: " + characterName);
                return;
            }

            // Create a Member object
            Member member = new Member(0, characterName, currentRank, null, null, null);

            // Validate requirements for the next rank
            validateRankRequirements(member, nextRank);

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void validateRankRequirements(Member member, String nextRank) {
        try (Connection connection = connect()) {
            String query = "SELECT id, requirement_type, required_value FROM rank_requirements WHERE `rank` = ?";
            try (PreparedStatement preparedStatement = connection.prepareStatement(query)) {
                preparedStatement.setString(1, nextRank);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {

                    while (resultSet.next()) {
                        int requirementId = resultSet.getInt("id");
                        String requirementType = resultSet.getString("requirement_type");
                        String requiredValueStr = resultSet.getString("required_value");

                        // Check if the required value is a numeric value
                        if (requirementType.equals("Other")) {
                            // Handle "Other" requirements
                            if (hasOtherRequirementBeenValidated(member.getUsername(), requirementId)) {
                                storeValidation(member.getUsername(), nextRank, requirementId);
                            }
                        } else {
                            int requiredValue;
                            try {
                                requiredValue = Integer.parseInt(requiredValueStr);
                            } catch (NumberFormatException e) {
                                // Log or handle the error if required_value is not a number
                                System.err.println("Invalid numeric value for requirement: " + requirementId);
                                continue; // Skip this requirement
                            }

                            // Proceed with numeric requirement validation
                            switch (requirementType) {
                                case "Points":
                                    if (validatePointsRequirement(member, nextRank, requiredValue)) {
                                        storeValidation(member.getUsername(), nextRank, requirementId);
                                    }
                                    break;
                                case "Points from X different players":
                                    if (validatePointsFromDifferentPlayers(member, requiredValue)) {
                                        storeValidation(member.getUsername(), nextRank, requirementId);
                                    }
                                    break;
                                case "Points from X different ranks":
                                    if (validatePointsFromDifferentRanks(member, requiredValue)) {
                                        storeValidation(member.getUsername(), nextRank, requirementId);
                                    }
                                    break;
                                case "Time in Clan":
                                    if (validateTimeInClan(member, requiredValue)) {
                                        storeValidation(member.getUsername(), nextRank, requirementId);
                                    }
                                    break;
                                case "Time at Current Rank":
                                    if (validateTimeAtCurrentRank(member, requiredValue)) {
                                        storeValidation(member.getUsername(), nextRank, requirementId);
                                    }
                                    break;
                                default:
                                    // Handle unknown requirement types if necessary
                                    break;
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private boolean hasOtherRequirementBeenValidated(String username, int requirementId) {
        String query = "SELECT COUNT(*) FROM validation_log WHERE character_name = ? AND requirement_id = ?";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, username);
            preparedStatement.setInt(2, requirementId);
            ResultSet resultSet = preparedStatement.executeQuery();
            if (resultSet.next()) {
                return resultSet.getInt(1) > 0;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private void storeValidation(String username, String rank, int requirementId) {
        String insertSql = "INSERT INTO validation_log (character_name, `rank`, requirement_id, validated_by, validation_date) " +
                "VALUES (?, ?, ?, ?, NOW())";

        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(insertSql)) {
            preparedStatement.setString(1, username);
            preparedStatement.setString(2, rank);
            preparedStatement.setInt(3, requirementId);
            preparedStatement.setString(4, "SYSTEM"); // Assuming 'SYSTEM' as validated_by in auto-validation, you can replace this with actual user ID if needed
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public List<String> getAllRanks() {
        List<String> ranks = new ArrayList<>();
        String query = "SELECT `rank` FROM config ORDER BY rank_order ";

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

    public boolean validatePointsRequirement(Member member, String nextRank, int pointsRequired) {
        String query = "SELECT points FROM members WHERE username = ? AND `rank` = ?";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, member.getUsername());
            preparedStatement.setString(2, member.getRank());
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

    public boolean validatePointsFromDifferentPlayers(Member member, int differentPlayers) {
        String query = "SELECT COUNT(DISTINCT related_user) AS unique_players FROM points_transactions WHERE character_name = ? AND points_change > 0";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, member.getUsername());
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

    public boolean validatePointsFromDifferentRanks(Member member, int differentRanks) {
        String query = "SELECT COUNT(DISTINCT r.rank) AS unique_ranks " +
                "FROM points_transactions pt " +
                "JOIN discord_users du ON pt.related_user = du.character_name " +
                "JOIN config r ON du.rank = r.rank " +
                "WHERE pt.character_name = ? AND pt.points_change > 0";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, member.getUsername());
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

    public boolean validateTimeInClan(Member member, int requiredMonths) {
        String query = "SELECT TIMESTAMPDIFF(MONTH, MIN(timestamp), NOW()) AS time_in_clan " +
                "FROM points_transactions WHERE character_name = ?";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, member.getUsername());
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

    public boolean validateTimeAtCurrentRank(Member member, int requiredMonths) {
        String query = "SELECT TIMESTAMPDIFF(MONTH, last_rank_update, NOW()) AS time_at_rank " +
                "FROM members WHERE username = ?";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, member.getUsername());
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

    private boolean isTemporaryRank(String rank) {
        String[] temporaryRanks = {"monarch", "competitor", "attacker", "enforcer", "defender", "ranger", "priest", "magician", "runecrafter", "medic", "athlete", "herbologist", "thief", "crafter", "fletcher", "miner", "smith", "fisher", "cook", "firemaker", "lumberjack", "slayer", "farmer", "constructor", "hunter", "skiller"};
        for (String tempRank : temporaryRanks) {
            if (tempRank.equalsIgnoreCase(rank)) {
                return true;
            }
        }
        return false;
    }

    private void removeTemporaryRank(Connection connection, String discordUid) throws SQLException {
        String deleteSql = "DELETE FROM temporary_ranks WHERE discord_uid = ?";
        try (PreparedStatement stmt = connection.prepareStatement(deleteSql)) {
            stmt.setString(1, discordUid);
            stmt.executeUpdate();
        }
    }
}
