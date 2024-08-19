package org.javacord.Discord302Party.service;

import io.github.cdimascio.dotenv.Dotenv;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.Discord302Party.Member;
import org.javacord.api.DiscordApi;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.sql.*;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class WOMGroupUpdater {

    private static final Logger logger = LogManager.getLogger(WOMGroupUpdater.class);
    private static final Dotenv dotenv = Dotenv.load();
    private static final String DB_URL = "jdbc:mysql://" + dotenv.get("DB_HOST") + ":3306/" + dotenv.get("DB_NAME");
    private static final String USER = dotenv.get("DB_USER");
    private static final String PASS = dotenv.get("DB_PASS");
    private static final String WOM_API_KEY = dotenv.get("WOM_API_KEY");
    private static final String DISCORD_NAME = dotenv.get("DISCORD_NAME");
    private static final String GROUP_ID = dotenv.get("GROUP_ID");
    private static final long UPDATE_INTERVAL = Long.parseLong(dotenv.get("UPDATE_INTERVAL", "3600")) * 1000;

    private final DiscordApi api;

    public WOMGroupUpdater(DiscordApi api) {
        this.api = api;
    }

    public void startUpdater() {
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                updateGroupMembers();
            }
        }, 0, UPDATE_INTERVAL);
    }

    public void updateGroupMembers() {
        try {
            String urlString = "https://api.wiseoldman.net/v2/groups/" + GROUP_ID;
            URL url = new URL(urlString);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("x-api-key", WOM_API_KEY);
            conn.setRequestProperty("User-Agent", DISCORD_NAME);

            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String inputLine;
            StringBuilder content = new StringBuilder();
            while ((inputLine = in.readLine()) != null) {
                content.append(inputLine);
            }
            in.close();

            List<Member> members = new WOMClientService().parseGroupMembers(content.toString());

            try (Connection connection = connect()) {
                int rankOrder = 1;

                for (Member member : members) {
                    // Check for username changes and rank updates
                    checkForNameChangeAndUpdateRank(connection, member);

                    // Update the member's rank in the database if it has changed
                    updateMemberRank(connection, member);

                    // Update the rank order in the config table
                    updateRankOrderInConfig(connection, member.getRank(), rankOrder);
                    rankOrder++;
                }

                // Handle members who have left the group
                removeLeftMembers(members);

            } catch (SQLException e) {
                logger.error("Error updating members in database", e);
            }

        } catch (Exception e) {
            logger.error("Error fetching group members from Wise Old Man API", e);
        }
    }

    private void checkForNameChangeAndUpdateRank(Connection connection, Member member) throws SQLException {
        String checkSql = "SELECT username, rank, WOM_id FROM members WHERE username = ? AND deleted = 0";
        try (PreparedStatement checkStmt = connection.prepareStatement(checkSql)) {
            checkStmt.setString(1, member.getUsername());
            try (ResultSet rs = checkStmt.executeQuery()) {
                if (rs.next()) {
                    String existingUsername = rs.getString("username");
                    String existingRank = rs.getString("rank");
                    int existingWOMId = rs.getInt("WOM_id");

                    if (existingWOMId != member.getWOMId()) {
                        updateWOMId(connection, member);
                    }

                    if (!existingUsername.equals(member.getUsername())) {
                        logNameChange(connection, existingWOMId, existingUsername, member.getUsername());
                        updateUsername(connection, member);
                    }

                    if (!existingRank.equals(member.getRank())) {
                        logRankHistory(connection, member);
                    }

                } else {
                    insertOrUpdateMember(connection, member);
                    logRankHistory(connection, member);
                }
            }
        }
    }

    private void updateMemberRank(Connection connection, Member member) throws SQLException {
        String sql = "UPDATE members SET rank = ?, last_rank_update = NOW() WHERE username = ? AND WOM_id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, member.getRank());
            stmt.setString(2, member.getUsername());
            stmt.setInt(3, member.getWOMId());
            int rowsUpdated = stmt.executeUpdate();
            if (rowsUpdated > 0) {
                logger.info("Successfully updated rank for user: " + member.getUsername());
            } else {
                logger.warn("Failed to update rank for user: " + member.getUsername());
            }
        }
    }

    private void updateWOMId(Connection connection, Member member) throws SQLException {
        String sql = "UPDATE members SET WOM_id = ?, deleted = 0 WHERE username = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, member.getWOMId());
            stmt.setString(2, member.getUsername());
            stmt.executeUpdate();
        }
    }

    private void logNameChange(Connection connection, int WOMId, String oldUsername, String newUsername) throws SQLException {
        String sql = "INSERT INTO rank_history (WOM_id, username, old_username, rank, rank_obtained_timestamp, rank_pulled_timestamp) " +
                "VALUES (?, ?, ?, 'Name Change', NOW(), NOW())";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, WOMId);
            stmt.setString(2, newUsername);
            stmt.setString(3, oldUsername);
            stmt.executeUpdate();
        }
    }

    private void updateUsername(Connection connection, Member member) throws SQLException {
        String sql = "UPDATE members SET username = ? WHERE WOM_id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, member.getUsername());
            stmt.setInt(2, member.getWOMId());
            stmt.executeUpdate();
        }
    }

    private void logRankHistory(Connection connection, Member member) throws SQLException {
        String sql = "INSERT INTO rank_history (WOM_id, username, rank, rank_obtained_timestamp, rank_pulled_timestamp) " +
                "VALUES (?, ?, ?, ?, NOW())";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, member.getWOMId());
            stmt.setString(2, member.getUsername());
            stmt.setString(3, member.getRank());
            stmt.setTimestamp(4, member.getRankObtainedTimestamp());
            stmt.executeUpdate();
        }
    }

    private void insertOrUpdateMember(Connection connection, Member member) throws SQLException {
        String checkSql = "SELECT id, WOM_id, rank FROM members WHERE username = ?";
        try (PreparedStatement checkStmt = connection.prepareStatement(checkSql)) {
            checkStmt.setString(1, member.getUsername());
            try (ResultSet rs = checkStmt.executeQuery()) {
                if (rs.next()) {
                    int existingWOMId = rs.getInt("WOM_id");
                    String existingRank = rs.getString("rank");

                    if (existingWOMId != member.getWOMId() || !existingRank.equals(member.getRank())) {
                        String updateSql = "UPDATE members SET WOM_id = ?, rank = ?, last_rank_update = NOW(), last_WOM_update = NOW(), joinDate = ?, deleted = 0 WHERE username = ?";
                        try (PreparedStatement updateStmt = connection.prepareStatement(updateSql)) {
                            updateStmt.setInt(1, member.getWOMId());
                            updateStmt.setString(2, member.getRank());
                            updateStmt.setTimestamp(3, member.getJoinDate());
                            updateStmt.setString(4, member.getUsername());
                            updateStmt.executeUpdate();
                        }
                    } else {
                        String updateTimestampSql = "UPDATE members SET last_WOM_update = NOW(), deleted = 0 WHERE username = ?";
                        try (PreparedStatement updateStmt = connection.prepareStatement(updateTimestampSql)) {
                            updateStmt.setString(1, member.getUsername());
                            updateStmt.executeUpdate();
                        }
                    }
                } else {
                    String insertSql = "INSERT INTO members (WOM_id, username, rank, last_rank_update, last_WOM_update, joinDate, deleted) " +
                            "VALUES (?, ?, ?, NOW(), NOW(), ?, 0)";
                    try (PreparedStatement insertStmt = connection.prepareStatement(insertSql)) {
                        insertStmt.setInt(1, member.getWOMId());
                        insertStmt.setString(2, member.getUsername());
                        insertStmt.setString(3, member.getRank());
                        insertStmt.setTimestamp(4, member.getJoinDate());
                        insertStmt.executeUpdate();
                    }
                }
            }
        }
    }

    private void updateRankOrderInConfig(Connection connection, String rank, int rankOrder) throws SQLException {
        String updateRankOrderSql = "INSERT INTO config (rank, rank_order) VALUES (?, ?) ON DUPLICATE KEY UPDATE rank_order=?";
        try (PreparedStatement updateStmt = connection.prepareStatement(updateRankOrderSql)) {
            updateStmt.setString(1, rank);
            updateStmt.setInt(2, rankOrder);
            updateStmt.setInt(3, rankOrder);
            updateStmt.executeUpdate();
        }
    }

    private boolean hasRankChanged(Member member) {
        // Implement logic to check if rank has changed
        return true;
    }

    private void updateDiscordRoles(Member member) {
        // Implement logic to update Discord roles based on rank changes
    }

    private void removeLeftMembers(List<Member> currentMembers) {
        try (Connection connection = connect()) {
            String sql = "SELECT username FROM members WHERE last_WOM_update < (NOW() - INTERVAL 1 HOUR) AND deleted = 0";
            try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    while (resultSet.next()) {
                        String username = resultSet.getString("username");

                        Server server = api.getServerById(Long.parseLong(dotenv.get("GUILD_ID"))).get();
                        server.getMembers().stream()
                                .filter(user -> user.getName().equals(username))
                                .forEach(user -> {
                                    removeRoleFromUser(user, "Green Party Hat");
                                });

                        String updateSql = "UPDATE members SET deleted = 1 WHERE username = ?";
                        try (PreparedStatement updateStmt = connection.prepareStatement(updateSql)) {
                            updateStmt.setString(1, username);
                            updateStmt.executeUpdate();
                        }

                        logger.info("Marked user as deleted: " + username);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error removing left members from database", e);
        }
    }

    private void removeRoleFromUser(User user, String roleName) {
        // Implement logic to remove role from user in Discord
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL, USER, PASS);
    }
}
