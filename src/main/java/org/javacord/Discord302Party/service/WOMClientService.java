package org.javacord.Discord302Party.service;

import io.github.cdimascio.dotenv.Dotenv;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.Discord302Party.Member;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public class WOMClientService {
    private static final Logger logger = LogManager.getLogger(WOMClientService.class);
    private static final Dotenv dotenv = Dotenv.load();
    private static final String API_KEY = dotenv.get("WOM_API_KEY");
    private static final String USER_AGENT = dotenv.get("DISCORD_NAME");
    private static final String BASE_URL = "https://api.wiseoldman.net/v2";

    public List<Member> getGroupMembers(int groupId) {
        String endpoint = String.format("/groups/%d", groupId);
        String jsonResponse = sendGetRequest(endpoint);
        logger.info (jsonResponse);
        return parseGroupMembers(jsonResponse);
    }

    private String sendGetRequest(String endpoint) {
        try {
            URL url = new URL(BASE_URL + endpoint);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            // Set request properties
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            if (API_KEY != null) {
                connection.setRequestProperty("x-api-key", API_KEY);
            }

            // Get the response
            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String inputLine;
            StringBuilder content = new StringBuilder();
            while ((inputLine = in.readLine()) != null) {
                content.append(inputLine);
            }
            in.close();

            return content.toString();
        } catch (Exception e) {
            logger.error("Error sending GET request to Wise Old Man API: " + e.getMessage());
            return null;
        }
    }

    public List<Member> parseGroupMembers(String jsonResponse) {
        List<Member> members = new ArrayList<>();
        try {
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

                    members.add(new Member(WOMId, username, rank, rankObtainedTimestamp, joinDate));
                }
            }

        } catch (Exception e) {
            logger.error("Error parsing JSON response: " + e.getMessage());
        }
        return members;
    }
}
