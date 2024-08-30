package org.javacord.Discord302Party.service;

import io.github.cdimascio.dotenv.Dotenv;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.Discord302Party.Member;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class WOMClientService {
    private static final Logger logger = LogManager.getLogger(WOMClientService.class);
    private static final Dotenv dotenv = Dotenv.load();
    private static final String API_KEY = dotenv.get("WOM_API_KEY");
    private static final String USER_AGENT = dotenv.get("DISCORD_NAME");
    private static final String BASE_URL = "https://api.wiseoldman.net/v2";
    private static final String DB_URL = "jdbc:mysql://" + dotenv.get("DB_HOST") + ":3306/" + dotenv.get("DB_NAME");
    private static final String USER = dotenv.get("DB_USER");
    private static final String PASS = dotenv.get("DB_PASS");

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL, USER, PASS);
    }

    public List<Member> getGroupMembers(int groupId) {
        String endpoint = String.format("/groups/%d", groupId);
        String jsonResponse = sendGetRequest(endpoint);
        logger.info(jsonResponse);
        return parseGroupMembers(jsonResponse);
    }

    private String sendGetRequest(String endpoint) {
        try {
            BufferedReader in = getIn(endpoint);
            String inputLine;
            StringBuilder content = new StringBuilder();
            while ((inputLine = in.readLine()) != null) {
                content.append(inputLine);
            }
            in.close();

            return content.toString();
        } catch (Exception e) {
            logger.error("Error sending GET request to Wise Old Man API: {}", e.getMessage());
            return null;
        }
    }

    private static BufferedReader getIn(String endpoint) throws IOException {
        URL url = new URL(BASE_URL + endpoint);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        // Set request properties
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        if (API_KEY != null) {
            connection.setRequestProperty("x-api-key", API_KEY);
        }

        // Get the response
        return new BufferedReader(new InputStreamReader(connection.getInputStream()));
    }

    public List<Member> parseGroupMembers(String jsonResponse) {
        List<Member> members = new ArrayList<>();
        try (Connection connection = connect()) {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode root = objectMapper.readTree(jsonResponse);

            JsonNode memberships = root.get("memberships");
            if (memberships != null && memberships.isArray()) {
                for (JsonNode membership : memberships) {
                    JsonNode player = membership.get("player");

                    int WOMId = player.get("id").asInt();
                    String username = player.get("username").asText();
                    String rank = membership.get("role").asText(); // Assuming 'role' as rank
                    Timestamp rankObtainedTimestamp = Timestamp.valueOf(membership.get("updatedAt").asText().replace("T", " ").replace("Z", ""));
                    Timestamp joinDate = Timestamp.valueOf(membership.get("createdAt").asText().replace("T", " ").replace("Z", ""));

                    // Get the temporary rank if it exists
                    String temporaryRank = getTemporaryRank(connection, username);

                    members.add(new Member(WOMId, username, rank, rankObtainedTimestamp, joinDate, temporaryRank));
                }
            }

        } catch (Exception e) {
            logger.error("Error parsing JSON response: {}", e.getMessage());
        }
        return members;
    }

    private String getTemporaryRank(Connection connection, String username) {
        String temporaryRank = null;
        String query = "SELECT `rank` FROM temporary_ranks WHERE discord_uid = (SELECT discord_uid FROM discord_users WHERE character_name = ?)";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                temporaryRank = rs.getString("rank");
            }
        } catch (SQLException e) {
            logger.error("Error retrieving temporary rank for user: {}", username, e);
        }
        return temporaryRank;
    }
}
