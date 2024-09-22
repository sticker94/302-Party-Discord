package org.javacord.Discord302Party.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javacord.Discord302Party.utils.GETrackerApi;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MoneymakeCommand implements SlashCommandCreateListener {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger logger = LogManager.getLogger(MoneymakeCommand.class);

    // Helper method to format large numbers
    public String formatLargeNumber(int number) {
        if (number >= 1_000_000_000) {
            return String.format("%.1fB", number / 1_000_000_000.0);
        } else if (number >= 1_000_000) {
            return String.format("%.1fM", number / 1_000_000.0);
        } else if (number >= 1_000) {
            return String.format("%.1fK", number / 1_000.0);
        } else {
            return String.valueOf(number);
        }
    }

    @Override
    public void onSlashCommandCreate(SlashCommandCreateEvent event) {
        if (event.getSlashCommandInteraction().getCommandName().equalsIgnoreCase("moneymake")) {

            String subCommand = event.getSlashCommandInteraction().getOptions().get(0).getOptions().get(0).getName();
            if (subCommand.equalsIgnoreCase("blast-furnace")) {
                // Fetch blast furnace data
                String blastFurnaceData = GETrackerApi.fetchBlastFurnaceData();

                if (blastFurnaceData == null) {
                    event.getSlashCommandInteraction().createImmediateResponder()
                            .setContent("Failed to retrieve blast furnace data from GE Tracker.")
                            .respond().join();
                    return;
                }

                // Log the raw response for debugging
                logger.info("Blast Furnace Data: {}", blastFurnaceData);

                try {
                    // Parse the JSON response
                    JsonNode rootNode = objectMapper.readTree(blastFurnaceData);
                    JsonNode dataNode = rootNode.get("data");

                    // Initialize variables for the table content
                    StringBuilder tableChunk = new StringBuilder();
                    int totalLength = 0;

                    // Create embed
                    EmbedBuilder embed = new EmbedBuilder()
                            .setTitle("Blast Furnace Smithing Profits")
                            .setDescription("Below are the top profits for smithing bars in the blast furnace.");

                    // Loop through each bar data
                    for (JsonNode itemNode : dataNode) {
                        JsonNode itemData = itemNode.get("target").get("item").get("data");

                        String itemName = itemData.get("name").asText();
                        int cost = itemNode.get("cost").get("cost").asInt();
                        int sellPrice = itemData.get("selling").asInt();
                        int profit = itemNode.get("cost").get("profit").asInt();
                        int buyingQty = itemData.get("buyingQuantity").asInt();
                        int sellingQty = itemData.get("sellingQuantity").asInt();
                        double profitPerHour = itemNode.get("cost").get("profitHr").asDouble();

                        // Format the table row
                        String row = String.format("%-20s %-10s %-10s %-10s %-10s %-10s %-10s%n",
                                itemName,
                                formatLargeNumber(cost),
                                formatLargeNumber(sellPrice),
                                formatLargeNumber(profit),
                                formatLargeNumber(buyingQty),
                                formatLargeNumber(sellingQty),
                                formatLargeNumber((int) profitPerHour));

                        // Add row to the table chunk
                        tableChunk.append(row);
                        totalLength += row.length();

                        // If the total length exceeds 1024, create a new field and reset the table chunk
                        if (totalLength > 900) {
                            embed.addField("Profits Table (cont'd)", "```" + tableChunk.toString() + "```");
                            tableChunk.setLength(0);  // Clear the buffer
                            totalLength = 0;
                        }
                    }

                    // Add the last chunk if it's not empty
                    if (tableChunk.length() > 0) {
                        embed.addField("Profits Table", "```" + tableChunk.toString() + "```");
                    }

                    // Send the response with the embed
                    event.getSlashCommandInteraction().createImmediateResponder()
                            .addEmbed(embed)
                            .respond().join();

                } catch (Exception e) {
                    logger.error("Error parsing blast furnace data", e);
                    event.getSlashCommandInteraction().createImmediateResponder()
                            .setContent("Error parsing blast furnace data.")
                            .respond().join();
                }
            }
        }
    }
}
