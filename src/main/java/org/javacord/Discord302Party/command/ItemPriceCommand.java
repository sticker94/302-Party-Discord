package org.javacord.Discord302Party.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javacord.Discord302Party.utils.GETrackerApi;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;

public class ItemPriceCommand implements SlashCommandCreateListener {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void onSlashCommandCreate(SlashCommandCreateEvent event) {
        if (event.getSlashCommandInteraction().getCommandName().equalsIgnoreCase("item_price")) {
            String itemName = event.getSlashCommandInteraction().getArguments().get(0).getStringValue().orElse("");

            // Check if item name is provided
            if (itemName.isEmpty()) {
                event.getSlashCommandInteraction().createImmediateResponder()
                        .setContent("Please provide a valid item name.")
                        .respond().join();
                return;
            }

            // Fetch item details from GE Tracker by name
            String itemId = GETrackerApi.fetchItemIdByName(itemName);

            if (itemId == null) {
                event.getSlashCommandInteraction().createImmediateResponder()
                        .setContent("Item not found. Please check the item name.")
                        .respond().join();
                return;
            }

            // Fetch detailed item data by ID
            String itemData = GETrackerApi.fetchItemData(Integer.parseInt(itemId));

            if (itemData == null) {
                event.getSlashCommandInteraction().createImmediateResponder()
                        .setContent("Failed to retrieve item data from GE Tracker.")
                        .respond().join();
                return;
            }

            // Parse the JSON data
            try {
                JsonNode rootNode = objectMapper.readTree(itemData);
                JsonNode dataNode = rootNode.get("data");

                // Extract relevant fields
                String itemNameDisplay = dataNode.get("name").asText();
                String itemIcon = dataNode.get("icon").asText();
                int buyingPrice = dataNode.get("buying").asInt();
                int sellingPrice = dataNode.get("selling").asInt();
                int profit = dataNode.get("approxProfit").asInt();
                String geUrl = dataNode.get("url").asText();
                String wikiUrl = dataNode.get("wikiUrl").asText();

                // Create an embed with the extracted data
                EmbedBuilder embed = new EmbedBuilder()
                        .setTitle(itemNameDisplay)
                        .setThumbnail(itemIcon)
                        .addField("Buying Price", String.valueOf(buyingPrice), true)
                        .addField("Selling Price", String.valueOf(sellingPrice), true)
                        .addField("Approximate Profit", String.valueOf(profit), true)
                        .addField("Links", "[GE Tracker](" + geUrl + ") | [Wiki](" + wikiUrl + ")")
                        .setFooter("Data from GE Tracker", "https://www.ge-tracker.com/favicon.ico");

                // Send the response with the embed
                event.getSlashCommandInteraction().createImmediateResponder()
                        .addEmbed(embed)
                        .respond().join();

            } catch (Exception e) {
                event.getSlashCommandInteraction().createImmediateResponder()
                        .setContent("Error parsing item data.")
                        .respond().join();
            }
        }
    }
}
