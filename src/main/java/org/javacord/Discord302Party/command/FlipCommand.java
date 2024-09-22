package org.javacord.Discord302Party.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javacord.Discord302Party.utils.GETrackerApi;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.entity.message.Message;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;
import org.javacord.api.entity.emoji.Emoji;

import java.util.concurrent.CompletableFuture;

public class FlipCommand implements SlashCommandCreateListener {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final int ITEMS_PER_PAGE = 5;

    @Override
    public void onSlashCommandCreate(SlashCommandCreateEvent event) {
        if (event.getSlashCommandInteraction().getCommandName().equalsIgnoreCase("flip")) {
            int page = 0; // Start at page 0
            fetchAndSendPage(event, page);
        }
    }

    private void fetchAndSendPage(SlashCommandCreateEvent event, int page) {
        // Fetch the highest margin items from the GE Tracker API
        String itemData = GETrackerApi.fetchHighestMargins();

        if (itemData == null) {
            event.getSlashCommandInteraction().createImmediateResponder()
                    .setContent("Failed to retrieve item data from GE Tracker.")
                    .respond().join();
            return;
        }

        try {
            // Parse the JSON response
            JsonNode rootNode = objectMapper.readTree(itemData);
            JsonNode dataNode = rootNode.get("data");

            // Calculate the page boundaries
            int start = page * ITEMS_PER_PAGE;
            int end = Math.min(start + ITEMS_PER_PAGE, dataNode.size());

            if (start >= dataNode.size()) {
                event.getSlashCommandInteraction().createImmediateResponder()
                        .setContent("No more items available.")
                        .respond().join();
                return;
            }

            // Create an embed for this page
            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle("Highest Margin Items (Page " + (page + 1) + ")")
                    .setFooter("Data from GE Tracker", "https://www.ge-tracker.com/favicon.ico");

            for (int i = start; i < end; i++) {
                JsonNode item = dataNode.get(i);
                String itemName = item.get("name").asText();
                String itemIcon = item.get("icon").asText();
                int buyingPrice = item.get("buying").asInt();
                int sellingPrice = item.get("selling").asInt();
                int profit = item.get("approxProfit").asInt();
                int buyLimit = item.get("buyLimit").asInt();
                int tax = item.get("tax").asInt();
                String geUrl = item.get("url").asText();
                String wikiUrl = item.get("wikiUrl").asText();

                // Add the item data to the embed
                embed.addField(itemName,
                        "Buy: " + buyingPrice +
                                " | Sell: " + sellingPrice +
                                " | Profit: " + profit +
                                " | Tax: " + tax +
                                " | [GE Tracker](" + geUrl + ") | [Wiki](" + wikiUrl + ")");
            }

            // Send the embed and add reactions for pagination
            CompletableFuture<Message> futureMessage = event.getSlashCommandInteraction().createImmediateResponder()
                    .addEmbed(embed)
                    .respond().thenApply(interactionOriginalResponse -> interactionOriginalResponse.update().join());

            futureMessage.thenAccept(message -> {
                message.addReaction("⬅️");  // Left arrow
                message.addReaction("➡️");  // Right arrow

                message.addReactionAddListener(reactionEvent -> {
                    Emoji emoji = reactionEvent.getEmoji();

                    if (emoji.equalsEmoji("⬅️")) {
                        if (page > 0) {
                            message.removeAllReactions().join();
                            fetchAndSendPage(event, page - 1); // Previous page
                        }
                    } else if (emoji.equalsEmoji("➡️")) {
                        message.removeAllReactions().join();
                        fetchAndSendPage(event, page + 1); // Next page
                    }
                }).removeAfter(10, java.util.concurrent.TimeUnit.MINUTES); // Remove reaction listener after 10 minutes to avoid stale reactions
            });

        } catch (Exception e) {
            event.getSlashCommandInteraction().createImmediateResponder()
                    .setContent("Error parsing item data.")
                    .respond().join();
        }
    }
}
