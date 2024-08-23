package org.javacord.Discord302Party.service;

import io.github.cdimascio.dotenv.Dotenv;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.Discord302Party.Member;
import org.javacord.api.DiscordApi;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.util.logging.ExceptionLogger;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.sql.*;
import java.util.List;
import java.util.Optional;
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
                for (Member member : members) {
                    // Check and update rank if necessary
                    checkAndUpdateRank(connection, member);
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

    private void checkAndUpdateRank(Connection connection, Member member) throws SQLException {
        String discordUid = findDiscordUidByCharacterName(connection, member.getUsername());
        if (discordUid == null) {
            logger.warn("No Discord UID found for character: " + member.getUsername());
            return; // Skip if no Discord UID found
        }

        String currentRank = findCurrentRank(connection, discordUid);
        if (currentRank != null && !currentRank.equalsIgnoreCase(member.getRank())) {
            // Update the rank in the discord_users table
            updateRankInDatabase(connection, discordUid, member.getRank());

            // Update the Discord roles
            updateDiscordRoles(discordUid, member.getRank());
        }

        // Continue with the existing database updates
        if (hasRankChanged(member)) {
            updateMemberRank(connection, member);
            logRankHistory(connection, member);
        }
    }

    private String findDiscordUidByCharacterName(Connection connection, String characterName) throws SQLException {
        String query = "SELECT discord_uid FROM discord_users WHERE character_name = ?";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, characterName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("discord_uid");
                }
            }
        }
        return null;
    }

    private String findCurrentRank(Connection connection, String discordUid) throws SQLException {
        String query = "SELECT rank FROM discord_users WHERE discord_uid = ?";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, discordUid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("rank");
                }
            }
        }
        return null;
    }

    private void updateRankInDatabase(Connection connection, String discordUid, String newRank) throws SQLException {
        String updateSql = "UPDATE discord_users SET rank = ? WHERE discord_uid = ?";
        try (PreparedStatement stmt = connection.prepareStatement(updateSql)) {
            stmt.setString(1, newRank);
            stmt.setString(2, discordUid);
            stmt.executeUpdate();
            logger.info("Updated rank to " + newRank + " for Discord UID: " + discordUid);
        }
    }

    private void updateDiscordRoles(String discordUid, String newRank) {
        Server server = api.getServerById(Long.parseLong(dotenv.get("GUILD_ID"))).orElse(null);
        if (server == null) {
            logger.error("Server not found!");
            return;
        }

        Optional<User> userOptional = server.getMemberById(discordUid);
        if (userOptional.isPresent()) {
            User user = userOptional.get();

            // Remove previous roles related to ranks
            user.getRoles(server).forEach(role -> {
                if (isRankRole(role.getName())) {
                    user.removeRole(role).exceptionally(ExceptionLogger.get());
                }
            });

            // Assign the new role based on the rank
            Optional<org.javacord.api.entity.permission.Role> newRole = server.getRolesByNameIgnoreCase(newRank).stream().findFirst();
            newRole.ifPresent(role -> {
                user.addRole(role).exceptionally(ExceptionLogger.get());
                logger.info("Assigned new role: " + role.getName() + " to user: " + user.getName());
            });
        } else {
            logger.warn("User not found on the server: " + discordUid);
        }
    }

    private boolean hasRankChanged(Member member) {
        try (Connection connection = connect()) {
            String query = "SELECT rank FROM members WHERE WOM_id = ?";
            try (PreparedStatement stmt = connection.prepareStatement(query)) {
                stmt.setInt(1, member.getWOMId());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String currentRank = rs.getString("rank");
                        return !currentRank.equalsIgnoreCase(member.getRank());
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error checking if rank has changed for user: " + member.getUsername(), e);
        }
        return false;
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

    private void removeLeftMembers(List<Member> currentMembers) {
        try (Connection connection = connect()) {
            String sql = "SELECT username FROM members WHERE last_WOM_update < (NOW() - INTERVAL 1 HOUR) AND deleted = 0";
            try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    while (resultSet.next()) {
                        String username = resultSet.getString("username");

                        Server server = api.getServerById(Long.parseLong(dotenv.get("GUILD_ID"))).orElse(null);
                        if (server != null) {
                            server.getMembersByName(username).forEach(user -> {
                                removeRoleFromUser(user, "Green Party Hat");
                                // Optionally remove other rank-related roles if necessary
                            });
                        }

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
        Server server = api.getServerById(Long.parseLong(dotenv.get("GUILD_ID"))).orElse(null);
        if (server != null) {
            Optional<org.javacord.api.entity.permission.Role> roleOptional = server.getRolesByNameIgnoreCase(roleName).stream().findFirst();
            roleOptional.ifPresent(role -> user.removeRole(role).exceptionally(ExceptionLogger.get()));
        }
    }

    private boolean isRankRole(String roleName) {
        try (Connection connection = connect()) {
            String query = "SELECT COUNT(*) FROM config WHERE rank = ?";
            try (PreparedStatement stmt = connection.prepareStatement(query)) {
                stmt.setString(1, roleName);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1) > 0;
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error checking if role is a rank role: " + roleName, e);
        }
        return false;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL, USER, PASS);
    }
}
