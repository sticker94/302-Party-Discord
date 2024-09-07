package org.javacord.Discord302Party.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.Discord302Party.Member;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.sql.*;
import java.util.concurrent.CompletableFuture;

public class WomGroupValidatorCommand implements SlashCommandCreateListener {

    private static final Logger logger = LogManager.getLogger(WomGroupValidatorCommand.class);
    private static final Dotenv dotenv = Dotenv.load();
    private static final String DB_URL = "jdbc:mysql://" + dotenv.get("DB_HOST") + ":3306/" + dotenv.get("DB_NAME");
    private static final String USER = dotenv.get("DB_USER");
    private static final String PASS = dotenv.get("DB_PASS");
    private static final String WOM_API_KEY = dotenv.get("WOM_API_KEY");
    private static final String GROUP_ID = dotenv.get("GROUP_ID");

    @Override
    public void onSlashCommandCreate(SlashCommandCreateEvent event) {
        if (event.getSlashCommandInteraction().getCommandName().equalsIgnoreCase("validate_group")) {
            event.getSlashCommandInteraction()
                    .createImmediateResponder()
                    .setContent("Running WOM Group Validation asynchronously...")
                    .respond();

            // Run validation asynchronously
            CompletableFuture.runAsync(this::validateGroupMembers)
                    .thenRun(() -> logger.info("Validation complete"))
                    .exceptionally(e -> {
                        logger.error("Error during async validation", e);
                        return null;
                    });
        }
    }

    public void validateGroupMembers() {
        try {
            // Fetch group members from the Wise Old Man API
            String jsonString = getGroupDataFromWOM();

            // Log the response to ensure it's valid
            logger.info("WOM API Response: {}", jsonString);

            // Parse the JSON response and retrieve memberships
            JsonNode memberships = new ObjectMapper().readTree(jsonString).get("memberships");

            try (Connection connection = connect()) {
                for (JsonNode membership : memberships) {
                    JsonNode player = membership.get("player");

                    // Get all necessary player info
                    String username = player.get("username").asText();
                    int womId = player.get("id").asInt();
                    String role = membership.get("role").asText();
                    Timestamp joinDate = Timestamp.valueOf(membership.get("createdAt").asText().replace("T", " ").replace("Z", ""));

                    // Create a new Member instance
                    Member member = new Member(womId, username, role, new Timestamp(System.currentTimeMillis()), joinDate, null);

                    // Check if the member exists in the database
                    if (!isMemberInDatabase(connection, member.getUsername())) {
                        // Add the member if not found
                        addMemberToDatabase(connection, member);
                    } else {
                        logger.info("Member {} already exists in the database.", member.getUsername());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error validating group members from Wise Old Man API", e);
        }
    }

    private String getGroupDataFromWOM() throws IOException {
        String urlString = "https://api.wiseoldman.net/v2/groups/" + GROUP_ID + "?limit=50";
        URL url = new URL(urlString);
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("x-api-key", WOM_API_KEY);
        conn.setRequestProperty("User-Agent", "DiscordBot");

        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        String inputLine;
        StringBuilder content = new StringBuilder();
        while ((inputLine = in.readLine()) != null) {
            content.append(inputLine);
        }
        in.close();

        return content.toString();
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

    private void addMemberToDatabase(Connection connection, Member member) throws SQLException {
        String insertSql = "INSERT INTO members (username, WOM_id, `rank`, joinDate, last_rank_update, last_WOM_update) VALUES (?, ?, ?, ?, NOW(), NOW())";
        try (PreparedStatement stmt = connection.prepareStatement(insertSql)) {
            stmt.setString(1, member.getUsername());
            stmt.setInt(2, member.getWOMId());
            stmt.setString(3, member.getRank());
            stmt.setTimestamp(4, member.getJoinDate());
            stmt.executeUpdate();
            logger.info("Added new member {} to the database.", member.getUsername());
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL, USER, PASS);
    }
}
