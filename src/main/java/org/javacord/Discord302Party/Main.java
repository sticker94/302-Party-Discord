package org.javacord.Discord302Party;

import io.github.cdimascio.dotenv.Dotenv;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.Discord302Party.command.ConfigCommand;
import org.javacord.Discord302Party.command.NameCommand;
import org.javacord.Discord302Party.command.VerifyCommand;
import org.javacord.Discord302Party.command.PointsCommand;
import org.javacord.api.DiscordApi;
import org.javacord.api.DiscordApiBuilder;
import org.javacord.api.entity.permission.PermissionType;
import org.javacord.api.entity.permission.Permissions;
import org.javacord.api.entity.permission.PermissionsBuilder;
import org.javacord.api.interaction.SlashCommandBuilder;
import org.javacord.api.interaction.SlashCommandOption;
import org.javacord.api.interaction.SlashCommandOptionType;
import org.javacord.api.util.logging.FallbackLoggerConfiguration;

public class Main {

    private static final Logger logger = LogManager.getLogger(Main.class);

    public static void main(String[] args) {
        // Load environment variables from .env file
        Dotenv dotenv = Dotenv.load();

        // Retrieve the bot token from the environment variables
        String token = dotenv.get("DISCORD_BOT_TOKEN");
        if (token == null) {
            logger.error("Please provide a valid Discord bot token in the .env file!");
            return;
        }

        // Enable debugging, if no slf4j logger was found
        FallbackLoggerConfiguration.setDebug(true);

        // Initialize Discord API with all intents enabled
        DiscordApi api = new DiscordApiBuilder()
                .setToken(token)
                .setAllIntents() // Enable all intents for the bot
                .login().join();

        // Create an invite link with administrator permissions
        Permissions permissions = new PermissionsBuilder()
                .setAllowed(PermissionType.ADMINISTRATOR)
                .build();
        String inviteUrl = api.createBotInvite(permissions);
        logger.info("You can invite me by using the following url: " + inviteUrl);

        // Register commands for a specific guild (server)
        long guildId = Long.parseLong(dotenv.get("GUILD_ID"));
        registerCommands(api, guildId);

        // Add listeners
        api.addSlashCommandCreateListener(new NameCommand());
        api.addSlashCommandCreateListener(new VerifyCommand());
        api.addSlashCommandCreateListener(new PointsCommand());
        api.addSlashCommandCreateListener(new ConfigCommand());

        // Log a message, if the bot joined or left a server
        api.addServerJoinListener(event -> logger.info("Joined server " + event.getServer().getName()));
        api.addServerLeaveListener(event -> logger.info("Left server " + event.getServer().getName()));
    }

    private static void registerCommands(DiscordApi api, long guildId) {
        // Register the "name" command
        new SlashCommandBuilder()
                .setName("name")
                .setDescription("Link your OSRS character name")
                .addOption(SlashCommandOption.create(SlashCommandOptionType.STRING, "character", "Your OSRS character name", true))
                .createForServer(api.getServerById(guildId).get()).join();

        // Register the "verify" command
        new SlashCommandBuilder()
                .setName("verify")
                .setDescription("Verify your Discord account with your Replit account.")
                .addOption(SlashCommandOption.create(SlashCommandOptionType.STRING, "verification_key", "Your verification key", true))
                .createForServer(api.getServerById(guildId).get()).join();

        // Register the "points" command with optional user mention and points
        new SlashCommandBuilder()
                .setName("points")
                .setDescription("Check your points and available points to give out, or give points to another user.")
                .addOption(SlashCommandOption.create(SlashCommandOptionType.USER, "user", "The Discord user you want to give points to", false))
                .addOption(SlashCommandOption.create(SlashCommandOptionType.LONG, "points", "The amount of points you want to give", false))
                .createForServer(api.getServerById(guildId).get()).join();

        // Register the "config" command to set the points logging channel
        new SlashCommandBuilder()
                .setName("config")
                .setDescription("Configure the bot settings. Requires Administrator privilege.")
                .addOption(SlashCommandOption.create(SlashCommandOptionType.CHANNEL, "channel", "The channel where points transactions will be logged", true))
                .createForServer(api.getServerById(guildId).get()).join();

        logger.info("Commands registered for guild: " + guildId);
    }
}
