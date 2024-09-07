package org.javacord.Discord302Party.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;


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
            // Fetch name changes from WOM API
            checkAndUpdateNameChanges();

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

                // Handle members who have left the group or joined the group
                updateMembersBasedOnActivity(connection);

            } catch (SQLException e) {
                logger.error("Error updating members in database", e);
            }

        } catch (Exception e) {
            logger.error("Error fetching group members from Wise Old Man API", e);
        }
    }

    private void checkAndUpdateNameChanges() {
        try {
            JsonNode nameChanges = getNameChanges("/name-changes");

            try (Connection connection = connect()) {
                for (JsonNode nameChange : nameChanges) {
                    String oldName = nameChange.get("oldName").asText();
                    String newName = nameChange.get("newName").asText();

                    // Store the name change in the database
                    storeNameChange(connection, oldName, newName);

                    // Update the name in the members table
                    updateMemberName(connection, oldName, newName);
                }
            } catch (SQLException e) {
                logger.error("Error updating name changes in database", e);
            }

        } catch (Exception e) {
            logger.error("Error fetching name changes from Wise Old Man API", e);
        }
    }

    private void storeNameChange(Connection connection, String oldName, String newName) throws SQLException {
        // Check if the name change is already stored
        if (isNameChangeStored(connection, oldName, newName)) {
            return; // Skip insertion if the name change is already in the database
        }

        String insertSql = "INSERT INTO name_changes (old_name, new_name, change_date) VALUES (?, ?, NOW())";
        try (PreparedStatement stmt = connection.prepareStatement(insertSql)) {
            stmt.setString(1, oldName);
            stmt.setString(2, newName);
            stmt.executeUpdate();
            logger.info("Stored name change from {} to {}", oldName, newName);
        }
    }

    private boolean isNameChangeStored(Connection connection, String oldName, String newName) throws SQLException {
        String query = "SELECT COUNT(*) FROM name_changes WHERE old_name = ? AND new_name = ?";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, oldName);
            stmt.setString(2, newName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0; // Return true if the name change is already stored
                }
            }
        }
        return false; // Return false if the name change is not found in the database
    }

    private void updateMemberName(Connection connection, String oldName, String newName) throws SQLException {
        String updateSql = "UPDATE members SET username = ? WHERE username = ?";
        try (PreparedStatement stmt = connection.prepareStatement(updateSql)) {
            stmt.setString(1, newName);
            stmt.setString(2, oldName);
            int rowsUpdated = stmt.executeUpdate();
            if (rowsUpdated > 0) {
                logger.info("Updated username from {} to {}", oldName, newName);
            }
        }
    }

    private void checkAndUpdateRank(Connection connection, Member member) throws SQLException {
        String discordUid = findDiscordUidByCharacterName(connection, member.getUsername());
        if (discordUid == null) {
            return; // Skip if no Discord UID found
        }

        // Check if this rank is a temporary rank
        boolean isTemporaryRank = isTemporaryRank(member.getTemporaryRank());
        String currentRank = findCurrentRank(connection, discordUid);

        if (currentRank != null && !currentRank.equalsIgnoreCase(member.getRank()) && !isTemporaryRank(member.getRank())) {
            if (isTemporaryRank) {
                // If it's a temporary rank, update the temporary ranks table and skip normal rank update
                updateTemporaryRankInDatabase(connection, discordUid, member.getTemporaryRank());
            } else {
                // Update the rank in the discord_users table
                updateRankInDatabase(connection, discordUid, member.getRank());

                // Update the Discord roles
                updateDiscordRoles(discordUid, member.getRank());
            }
        }

        // Continue with the existing database updates
        if (hasRankChanged(member)) {
            updateMemberRank(connection, member);
            logRankHistory(connection, member);
        }
    }

    private void updateMembersBasedOnActivity(Connection connection) {
        try {
            // Retrieve the last processed "updatedAt" timestamp
            Timestamp lastProcessedTimestamp = getLastProcessedActivityTime(connection);

            // Fetch group activity from Wise Old Man API
            JsonNode activityEvents = getGroupActivity("/activity");

            // Ensure activityEvents is an array
            if (activityEvents.isArray()) {
                // Sort the activity list based on the createdAt timestamp in ascending order
                List<JsonNode> activityList = StreamSupport.stream(activityEvents.spliterator(), false)
                        .sorted(Comparator.comparing(event -> {
                            String createdAtStr = event.get("createdAt").asText();
                            return Timestamp.valueOf(createdAtStr.replace("T", " ").replace("Z", ""));
                        }))
                        .collect(Collectors.toList());

                for (JsonNode event : activityList) {
                    logger.info("Processing event: {}", event.toString());

                    // Extract event details
                    JsonNode playerNode = event.get("player");
                    if (playerNode == null) {
                        logger.warn("Skipping event due to missing player node: {}", event.toString());
                        continue;
                    }

                    String updatedAtStr = event.get("createdAt").asText();
                    Timestamp updatedAt = Timestamp.valueOf(updatedAtStr.replace("T", " ").replace("Z", ""));
                    Thread.sleep(250);

                    // Handle the case where lastProcessedTimestamp is null
                    if (lastProcessedTimestamp == null) {
                        // If we don't have a last processed timestamp, initialize it to a default value
                        lastProcessedTimestamp = Timestamp.valueOf("1970-01-01 00:00:00");
                    }

                    // If the event is older than the last processed timestamp, skip it
                    if (updatedAt.before(lastProcessedTimestamp)) {
                        continue;
                    }

                    String role = null;
                    String tempRole = null;
                    String username = playerNode.get("username").asText();
                    int womId = playerNode.get("id").asInt();
                    String eventType = event.get("type").asText();

                    if (isTemporaryRank(event.get("role").asText())) {
                        tempRole = event.get("role").asText();
                    } else {
                        role = event.get("role").asText();
                    }

                    Timestamp joinDate = event.has("createdAt") ?
                            Timestamp.valueOf(event.get("createdAt").asText().replace("T", " ").replace("Z", "")) :
                            null;

                    // Create Member object
                    Member member = new Member(womId, username, role, updatedAt, joinDate, tempRole);

                    // Handle different event types
                    switch (eventType) {
                        case "left":
                            handleMemberLeft(connection, member);
                            break;
                        case "joined":
                            handleMemberJoined(connection, member);
                            break;
                        case "changed_role":
                            handleMemberRoleChange(connection, member);
                            break;
                        default:
                            logger.warn("Unhandled event type: {} for user: {}", eventType, username);
                            break;
                    }

                    // Update the last processed timestamp
                    updateLastProcessedActivityTime(connection, updatedAt);
                }

                // Handle removing expired temporary ranks
                removeExpiredTemporaryRanks(connection);
            } else {
                logger.warn("Activity events are not in an array format: {}", activityEvents.toString());
            }
        } catch (Exception e) {
            logger.error("Error fetching group activity from Wise Old Man API", e);
        }
    }

    private static JsonNode getGroupActivity(String x) throws IOException {
        String urlString = "https://api.wiseoldman.net/v2/groups/" + GROUP_ID + x +"?limit=50";
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

        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.readTree(content.toString());
    }

    private void handleMemberLeft(Connection connection, Member member) throws SQLException, InterruptedException {
        String rank = getMemberRank(connection, member.getUsername());
        if (rank != null) {
            removeMemberFromDatabase(connection, member.getUsername());
            removeRolesFromDiscord(member.getUsername(), rank);
        }
    }

    private void handleMemberJoined(Connection connection, Member member) throws SQLException {
        if (!isMemberInDatabase(connection, member.getUsername())) {
            addMemberToDatabase(connection, member.getUsername(), member.getWOMId(), member.getRank() != null ? member.getRank() : "default", member.getJoinDate());
        }
    }

    private void handleMemberRoleChange(Connection connection, Member member) throws SQLException {
        if (isTemporaryRank(member.getTemporaryRank())) {
            logger.info("Temporary rank identified for user: {} rank: {}", member.getUsername(), member.getTemporaryRank());
        }
        // If the member no longer has a temporary rank, remove it
        if (member.getRank() != null) {
            updateRoleForMember(connection, member.getUsername(), member.getRank(), null);
            if (member.getTemporaryRank()==null) {
                removeTemporaryRank(connection, member.getUsername());
                updateRoleForMember(connection, member.getUsername(), member.getRank(), null);
            }
        } else {
            if (member.getTemporaryRank() != null) {
                updateRoleForMember(connection, member.getUsername(), null, member.getTemporaryRank());
            } else {
                logger.warn("Role change event with null role and null temp role for user: {}", member.getUsername());
            }
        }
    }

    private void removeTemporaryRank(Connection connection, String discordUid) throws SQLException {
        // First, check if the user exists in the temporary_ranks table
        String checkSql = "SELECT COUNT(*) FROM temporary_ranks WHERE discord_uid = ?";
        try (PreparedStatement checkStmt = connection.prepareStatement(checkSql)) {
            checkStmt.setString(1, discordUid);
            try (ResultSet rs = checkStmt.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    // User exists in the temporary_ranks table, proceed to remove the rank
                    String deleteSql = "DELETE FROM temporary_ranks WHERE discord_uid = ?";
                    try (PreparedStatement deleteStmt = connection.prepareStatement(deleteSql)) {
                        deleteStmt.setString(1, discordUid);
                        int rowsDeleted = deleteStmt.executeUpdate();
                        if (rowsDeleted > 0) {
                            logger.info("Removed temporary rank for user with Discord UID: {}", discordUid);
                        }
                    }
                }
            }
        }
    }

    private void updateRoleForMember(Connection connection, String username, String newRole, String tempRank) throws SQLException {
        // Fetch the discord_uid associated with the username
        String discordUid = findDiscordUidByCharacterName(connection, username);
        if (discordUid == null) {
            logger.warn("Discord UID not found for username: {}", username);
            return;
        }
        if (tempRank != null) {updateDiscordRoles(discordUid, tempRank); return;}
        // Update the rank in the database
        updateRankInDatabase(connection, discordUid, newRole);

        // Update the roles on Discord
        updateDiscordRoles(discordUid, newRole);
    }

    private static JsonNode getNameChanges(String x) throws IOException {
        String urlString = "https://api.wiseoldman.net/v2/groups/" + GROUP_ID + x;
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

        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.readTree(content.toString());
    }

    private boolean isMemberInDatabase(Connection connection, String username) throws SQLException {
        String query = "SELECT COUNT(*) FROM members WHERE username = ?";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        }
        return false;
    }

    private void addMemberToDatabase(Connection connection, String username, int womId, String role, Timestamp joinDate) throws SQLException {
        String insertSql = "INSERT INTO members (username, `rank`, WOM_id, joinDate, last_rank_update, last_WOM_update) VALUES (?, ?, ?, ?, NOW(), NOW())";
        try (PreparedStatement stmt = connection.prepareStatement(insertSql)) {
            stmt.setString(1, username);
            stmt.setString(2, role);
            stmt.setInt(3, womId);
            stmt.setTimestamp(4, joinDate);
            stmt.executeUpdate();
            logger.info("Added new member {} to the members table.", username);
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
        String query = "SELECT `rank` FROM discord_users WHERE discord_uid = ?";
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
        String updateSql = "UPDATE discord_users SET `rank` = ? WHERE discord_uid = ?";
        try (PreparedStatement stmt = connection.prepareStatement(updateSql)) {
            stmt.setString(1, newRank);
            stmt.setString(2, discordUid);
            stmt.executeUpdate();
            logger.info("Updated rank to {} for Discord UID: {}", newRank, discordUid);
        }
    }

    private void updateTemporaryRankInDatabase(Connection connection, String discordUid, String newRank) throws SQLException {
        String insertSql = "INSERT INTO temporary_ranks (discord_uid, `rank`) VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE `rank` = VALUES(rank), added_date = CURRENT_TIMESTAMP";
        try (PreparedStatement stmt = connection.prepareStatement(insertSql)) {
            stmt.setString(1, discordUid);
            stmt.setString(2, newRank);
            stmt.executeUpdate();
            logger.info("Updated temporary rank to {} for Discord UID: {}", newRank, discordUid);
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
                if (isRankRole(role.getName()) || isTemporaryRank(role.getName())) {
                    user.removeRole(role).exceptionally(ExceptionLogger.get());
                    logger.info("Removed role: {} from user: {}", role.getName(), user.getName());
                }
            });

            // Assign the new role based on the rank
            Optional<org.javacord.api.entity.permission.Role> newRole = server.getRolesByNameIgnoreCase(newRank).stream().findFirst();
            newRole.ifPresent(role -> {
                user.addRole(role).exceptionally(ExceptionLogger.get());
                logger.info("Assigned new role: {} to user: {}", role.getName(), user.getName());
            });
        } else {
            logger.warn("User not found on the server: {}", discordUid);
        }
    }

    private boolean hasRankChanged(Member member) {
        try (Connection connection = connect()) {
            String query = "SELECT `rank` FROM members WHERE WOM_id = ?";
            try (PreparedStatement stmt = connection.prepareStatement(query)) {
                stmt.setInt(1, member.getWOMId());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String currentRank = rs.getString("rank");
                        if (isTemporaryRank(member.getTemporaryRank())){
                            return false;
                        }
                        return !currentRank.equalsIgnoreCase(member.getRank());
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error checking if rank has changed for user: {}", member.getUsername(), e);
        }
        return false;
    }

    private void updateMemberRank(Connection connection, Member member) throws SQLException {
        String sql = "UPDATE members SET `rank` = ?, last_rank_update = NOW() WHERE username = ? AND WOM_id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, member.getRank());
            stmt.setString(2, member.getUsername());
            stmt.setInt(3, member.getWOMId());
            int rowsUpdated = stmt.executeUpdate();
            if (rowsUpdated > 0) {
                logger.info("Successfully updated rank for user: {}", member.getUsername());
            } else {
                logger.warn("Failed to update rank for user: {}", member.getUsername());
            }
        }
    }

    private void logRankHistory(Connection connection, Member member) throws SQLException {
        String sql = "INSERT INTO rank_history (WOM_id, username, `rank`, rank_obtained_timestamp, rank_pulled_timestamp) " +
                "VALUES (?, ?, ?, ?, NOW())";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, member.getWOMId());
            stmt.setString(2, member.getUsername());
            stmt.setString(3, member.getRank());
            stmt.setTimestamp(4, member.getRankObtainedTimestamp());
            stmt.executeUpdate();
        }
    }

    private void removeMemberFromDatabase(Connection connection, String username) throws SQLException {
        String deleteSql = "DELETE FROM members WHERE username = ?";
        try (PreparedStatement stmt = connection.prepareStatement(deleteSql)) {
            stmt.setString(1, username);
            int rowsDeleted = stmt.executeUpdate();
            if (rowsDeleted > 0) {
                logger.info("Deleted member {} from the members table.", username);
            } else {
                logger.warn("No member found with username {}", username);
            }
        }
    }

    private void removeRolesFromDiscord(String username, String rank) throws InterruptedException {
        Server server = api.getServerById(Long.parseLong(dotenv.get("GUILD_ID"))).orElse(null);
        if (server == null) {
            logger.error("Server not found!");
            return;
        }

        Optional<User> userOptional = server.getMembersByName(username).stream().findFirst();
        if (userOptional.isPresent()) {
            User user = userOptional.get();

            Thread.sleep(250);
            // Remove the rank role
            Optional<org.javacord.api.entity.permission.Role> rankRole = server.getRolesByNameIgnoreCase(rank).stream().findFirst();
            rankRole.ifPresent(role -> user.removeRole(role).exceptionally(ExceptionLogger.get()));

            Thread.sleep(250);
            // Remove the "Green Party Hats" role
            Optional<org.javacord.api.entity.permission.Role> greenPartyHatsRole = server.getRolesByNameIgnoreCase("Green Party Hats").stream().findFirst();
            greenPartyHatsRole.ifPresent(role -> user.removeRole(role).exceptionally(ExceptionLogger.get()));

            logger.info("Removed roles from user: {}", username);
        } else {
            logger.warn("User not found on the server: {}", username);
        }
    }

    private void removeExpiredTemporaryRanks(Connection connection) {
        try {
            // First, retrieve the users with expired temporary ranks
            String query = "SELECT discord_uid, `rank` FROM temporary_ranks WHERE added_date < (NOW() - INTERVAL 1 MONTH)";
            try (PreparedStatement stmt = connection.prepareStatement(query)) {
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String discordUid = rs.getString("discord_uid");
                        String rank = rs.getString("rank");

                        // Check if the user still has the rank in the database
                        if (isTemporaryRankStillPresent(connection, discordUid, rank)) {
                            // Remove the role from Discord
                            removeTempRoleFromDiscord(discordUid, rank);

                            // After removing the role, delete the temporary rank from the database
                            deleteTempRankFromDatabase(connection, discordUid, rank);
                        } else {
                            logger.info("User {} no longer has the temporary rank {}", discordUid, rank);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error removing expired temporary ranks.", e);
        }
    }

    private boolean isTemporaryRankStillPresent(Connection connection, String discordUid, String rank) throws SQLException {
        String checkSql = "SELECT COUNT(*) FROM temporary_ranks WHERE discord_uid = ? AND `rank` = ?";
        try (PreparedStatement stmt = connection.prepareStatement(checkSql)) {
            stmt.setString(1, discordUid);
            stmt.setString(2, rank);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return true; // Temporary rank is still present in the database
                }
            }
        }
        return false; // Temporary rank has already been removed
    }

    private void deleteTempRankFromDatabase(Connection connection, String discordUid, String rank) throws SQLException {
        String deleteSql = "DELETE FROM temporary_ranks WHERE discord_uid = ? AND `rank` = ?";
        try (PreparedStatement stmt = connection.prepareStatement(deleteSql)) {
            stmt.setString(1, discordUid);
            stmt.setString(2, rank);
            int rowsDeleted = stmt.executeUpdate();
            if (rowsDeleted > 0) {
                logger.info("Removed expired temporary rank {} for Discord UID: {}", rank, discordUid);
            }
        }
    }

    private void removeTempRoleFromDiscord(String discordUid, String rank) {
        Server server = api.getServerById(Long.parseLong(dotenv.get("GUILD_ID"))).orElse(null);
        if (server == null) {
            logger.error("Server not found!");
            return;
        }

        Optional<User> userOptional = server.getMemberById(discordUid);
        if (userOptional.isPresent()) {
            User user = userOptional.get();

            // Find the role on the server that matches the temporary rank
            Optional<org.javacord.api.entity.permission.Role> roleToRemove = server.getRolesByNameIgnoreCase(rank).stream().findFirst();
            if (roleToRemove.isPresent()) {
                user.removeRole(roleToRemove.get()).exceptionally(ExceptionLogger.get());
                logger.info("Removed temporary role: {} from user: {}", roleToRemove.get().getName(), user.getName());
            } else {
                logger.warn("Role not found on the server for rank: {}", rank);
            }
        } else {
            logger.warn("User not found on the server for Discord UID: {}", discordUid);
        }
    }

    private boolean isRankRole(String roleName) {
        try (Connection connection = connect()) {
            String query = "SELECT COUNT(*) FROM config WHERE `rank` = ?";
            try (PreparedStatement stmt = connection.prepareStatement(query)) {
                stmt.setString(1, roleName);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1) > 0;
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error checking if role is a rank role: {}", roleName, e);
        }
        return false;
    }

    private String getMemberRank(Connection connection, String username) throws SQLException {
        String query = "SELECT `rank` FROM members WHERE username = ?";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("rank");
                }
            }
        }
        return null;
    }

    private boolean isTemporaryRank(String rank) {
        // Here we check if the rank is in the list of known temporary ranks
        String[] temporaryRanks = {"monarch", "competitor", "attacker", "enforcer", "defender", "ranger", "priest",
                "magician", "runecrafter", "medic", "athlete", "herbologist", "thief", "crafter",
                "fletcher", "miner", "smith", "fisher", "cook", "firemaker", "lumberjack", "slayer",
                "farmer", "constructor", "hunter", "skiller"};

        for (String tempRank : temporaryRanks) {
            if (tempRank.equalsIgnoreCase(rank)) {
                return true;
            }
        }
        return false;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL, USER, PASS);
    }

    // Methods to track and update the last processed activity timestamp
    private Timestamp getLastProcessedActivityTime(Connection connection) throws SQLException {
        String query = "SELECT MAX(last_processed_at) FROM activity_log_tracker";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getTimestamp(1);
                }
            }
        }
        return Timestamp.valueOf("1970-01-01 00:00:00");
    }

    private void updateLastProcessedActivityTime(Connection connection, Timestamp lastProcessedAt) throws SQLException {
        String insertSql = "INSERT INTO activity_log_tracker (last_processed_at) VALUES (?) " +
                "ON DUPLICATE KEY UPDATE last_processed_at = VALUES(last_processed_at)";
        try (PreparedStatement stmt = connection.prepareStatement(insertSql)) {
            stmt.setTimestamp(1, lastProcessedAt);
            stmt.executeUpdate();
        }
    }
}
