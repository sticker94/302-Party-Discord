package org.javacord.Discord302Party.command;

import io.github.cdimascio.dotenv.Dotenv;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.api.event.interaction.AutocompleteCreateEvent;
import org.javacord.api.interaction.SlashCommandOptionChoice;
import org.javacord.api.listener.interaction.AutocompleteCreateListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.stream.Collectors;

public class ItemAutocompleteListener implements AutocompleteCreateListener {

    private static final Logger logger = LogManager.getLogger(ItemAutocompleteListener.class);
    private static final Dotenv dotenv = Dotenv.load();
    private static final String API_KEY = dotenv.get("GE_TRACKER_API_KEY");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void onAutocompleteCreate(AutocompleteCreateEvent event) {
        logger.info("Autocomplete Created");

        // 1) Handle `/item_price item`
        if (event.getAutocompleteInteraction().getCommandName().equalsIgnoreCase("item_price")) {
            logger.info("Autocomplete Interaction for /item_price");
            if (event.getAutocompleteInteraction().getFocusedOption().getName().equals("item")) {
                String input = event.getAutocompleteInteraction().getFocusedOption().getStringValue().orElse("");
                logger.info("Autocomplete triggered for item_id with input: " + input);

                List<SlashCommandOptionChoice> matchingItems = fetchItemsFromApi(input).stream()
                        .map(item -> {
                            String truncated = item.length() > 25 ? item.substring(0, 25) : item;
                            return SlashCommandOptionChoice.create(truncated, truncated);
                        })
                        .limit(25)
                        .collect(Collectors.toList());

                event.getAutocompleteInteraction().respondWithChoices(matchingItems).join();
            }
        }

        // 2) Handle `/giveaway start item`
        else if (event.getAutocompleteInteraction().getCommandName().equalsIgnoreCase("giveaway")) {
            // Check subcommand (if present)
            if (!event.getAutocompleteInteraction().getOptions().isEmpty()) {
                String subcommand = event.getAutocompleteInteraction().getOptions().get(0).getName();
                if (subcommand.equalsIgnoreCase("start")) {
                    // Check if the user is typing in the "item" option
                    if (event.getAutocompleteInteraction().getFocusedOption().getName().equals("item")) {
                        String input = event.getAutocompleteInteraction().getFocusedOption().getStringValue().orElse("");
                        logger.info("Autocomplete triggered for /giveaway start item with input: " + input);

                        List<SlashCommandOptionChoice> matchingItems = fetchItemsFromApi(input).stream()
                                .map(item -> {
                                    String truncated = item.length() > 25 ? item.substring(0, 25) : item;
                                    return SlashCommandOptionChoice.create(truncated, truncated);
                                })
                                .limit(25)
                                .collect(Collectors.toList());

                        event.getAutocompleteInteraction().respondWithChoices(matchingItems).join();
                    }
                }
            }
        }
    }

    private List<String> fetchItemsFromApi(String query) {
        try {
            // Construct the API URL
            String apiUrl = "https://www.ge-tracker.com/api/items/search/" + query;

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + API_KEY);
            conn.setRequestProperty("Accept", "application/x.getracker.v2.1+json");

            // Handle the response
            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                response.append(line);
            }
            in.close();

            // Parse the JSON response
            JsonNode rootNode = objectMapper.readTree(response.toString());

            // Return all item names
            return rootNode.findValues("name").stream()
                    .map(JsonNode::asText)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            logger.error("Error fetching items from GE Tracker API", e);
            return List.of();  // Return an empty list on error
        }
    }
}
