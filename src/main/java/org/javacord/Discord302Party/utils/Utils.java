package org.javacord.Discord302Party.utils;

import org.javacord.Discord302Party.Member;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public class Utils {
    public static int parseTimeRequirement(String timeInput) {
        String[] parts = timeInput.split(" ");
        int value = Integer.parseInt(parts[0]);
        String unit = parts[1].toLowerCase();

        switch (unit) {
            case "days":
                return value;
            case "weeks":
                return value * 7;
            case "months":
                return value * 30; // assuming 30 days per month
            default:
                throw new IllegalArgumentException("Invalid time unit. Please use 'days', 'weeks', or 'months'.");
        }
    }
    public static List<Member> parseWOMMembers(String jsonResponse) {
        List<Member> members = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();

        try {
            JsonNode rootNode = mapper.readTree(jsonResponse);
            JsonNode membersNode = rootNode.path("members"); // Assuming the JSON array is under "members"

            for (JsonNode memberNode : membersNode) {
                int WOMId = memberNode.get("id").asInt();
                String username = memberNode.get("username").asText();
                String rank = memberNode.get("rank").asText();
                Timestamp rankObtainedTimestamp = Timestamp.valueOf(memberNode.get("rankObtainedTimestamp").asText());

                Member member = new Member(WOMId, username, rank, rankObtainedTimestamp);
                members.add(member);
            }

        } catch (Exception e) {
            e.printStackTrace(); // Handle parsing exceptions
        }

        return members;
    }
}
