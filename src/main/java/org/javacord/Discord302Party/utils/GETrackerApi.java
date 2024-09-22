package org.javacord.Discord302Party.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class GETrackerApi {

    private static final Logger logger = LogManager.getLogger(GETrackerApi.class);
    private static final Dotenv dotenv = Dotenv.load();
    private static final String API_KEY = dotenv.get("GE_TRACKER_API_KEY");
    private static final String BASE_URL = "https://www.ge-tracker.com/api/";
    private static final ObjectMapper objectMapper = new ObjectMapper();


    // Method to fetch highest margins
    public static String fetchHighestMargins() {
        try {
            String apiUrl = "https://www.ge-tracker.com/api/highest-margins";

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + API_KEY);
            conn.setRequestProperty("Accept", "application/x.getracker.v2.1+json");

            // Read the response
            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                response.append(line);
            }
            in.close();

            return response.toString();

        } catch (Exception e) {
            logger.error("Error fetching highest margins from GE Tracker API", e);
            return null;
        }
    }

    // Method to search item by name and return item ID
    public static String fetchItemIdByName(String itemName) {
        try {
            String apiUrl = "https://www.ge-tracker.com/api/items/search/" + itemName;

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + API_KEY);
            conn.setRequestProperty("Accept", "application/x.getracker.v2.1+json");

            // Read the response
            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                response.append(line);
            }
            in.close();

            // Parse the response and get the item ID
            JsonNode rootNode = objectMapper.readTree(response.toString());
            JsonNode dataNode = rootNode.get("data");

            if (dataNode.isArray()) {
                for (JsonNode itemNode : dataNode) {
                    String name = itemNode.get("name").asText();

                    // Check if the item's name matches the provided itemName
                    if (name.equalsIgnoreCase(itemName)) {
                        return itemNode.get("itemId").asText(); // Return the itemId if name matches
                    }
                }
            }

            return null; // Return null if no matching item is found

        } catch (Exception e) {
            logger.error("Error fetching item ID from GE Tracker API", e);
            return null;
        }
    }

    // Method to fetch detailed item data by item ID
    public static String fetchItemData(int itemId) {
        try {
            String apiUrl = "https://www.ge-tracker.com/api/items/" + itemId;

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + API_KEY);
            conn.setRequestProperty("Accept", "application/x.getracker.v2.1+json");

            // Read the response
            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                response.append(line);
            }
            in.close();

            // Return the full response as item data
            return response.toString();

        } catch (Exception e) {
            logger.error("Error fetching item data from GE Tracker API", e);
            return null;
        }
    }

    // Method to fetch blast furnace data
    public static String fetchBlastFurnaceData() {
        try {
            String apiUrl = "https://www.ge-tracker.com/api/blast-furnace";

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + API_KEY);
            conn.setRequestProperty("Accept", "application/x.getracker.v2.1+json");

            // Read the response
            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                response.append(line);
            }
            in.close();

            // Return the raw JSON response
            return response.toString();

        } catch (Exception e) {
            logger.error("Error fetching blast furnace data from GE Tracker API", e);
            return null;
        }
    }
}
