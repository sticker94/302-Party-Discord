package org.javacord.Discord302Party;

import io.github.cdimascio.dotenv.Dotenv;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.Discord302Party.command.*;
import org.javacord.Discord302Party.service.RankRequirementUpdater;
import org.javacord.Discord302Party.service.UserVerificationService;
import org.javacord.Discord302Party.service.WOMGroupUpdater;
import org.javacord.Discord302Party.utils.Utils;
import org.javacord.api.DiscordApi;
import org.javacord.api.DiscordApiBuilder;
import org.javacord.api.entity.permission.PermissionType;
import org.javacord.api.entity.permission.Permissions;
import org.javacord.api.entity.permission.PermissionsBuilder;
import org.javacord.api.entity.server.Server;
import org.javacord.api.interaction.*;
import org.javacord.api.util.logging.FallbackLoggerConfiguration;

import java.util.Arrays;
import java.util.List;

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

        // Create an invitation link with administrator permissions
        Permissions permissions = new PermissionsBuilder()
                .setAllowed(PermissionType.ADMINISTRATOR)
                .build();
        String inviteUrl = api.createBotInvite(permissions);
        logger.info("You can invite me by using the following url: {}", inviteUrl);

        // Register commands for a specific guild (server)
        long guildId = Long.parseLong(dotenv.get("GUILD_ID"));
        Server server = api.getServerById(guildId).orElseThrow(() -> new IllegalArgumentException("Guild not found!"));

        // Initialize and start WOMGroupUpdater
        WOMGroupUpdater womGroupUpdater = new WOMGroupUpdater(api);
        womGroupUpdater.startUpdater();

        // Initialize and start RankRequirementUpdater
        RankRequirementUpdater rankRequirementUpdater = new RankRequirementUpdater();
        rankRequirementUpdater.startUpdater();

        // Initialize UserVerificationService
        UserVerificationService userVerificationService = new UserVerificationService();

        // Register all commands
        registerCommands(api, guildId, server, womGroupUpdater, rankRequirementUpdater, userVerificationService);

        // Add listeners for Slash Commands and Select Menu interactions
        ViewRankRequirementsCommand viewRankRequirementsCommand = new ViewRankRequirementsCommand();

        api.addSlashCommandCreateListener(new NameCommand());
        api.addSlashCommandCreateListener(new VerifyCommand());
        api.addSlashCommandCreateListener(new PointsCommand());
        api.addSlashCommandCreateListener(new ConfigCommand());
        api.addSlashCommandCreateListener(new CheckRankUpCommand());
        api.addSlashCommandCreateListener(new SetRankRequirementsCommand());
        api.addSlashCommandCreateListener(new ValidateRankRequirementsCommand());
        api.addSlashCommandCreateListener(viewRankRequirementsCommand);  // Register the view rank requirements command as both a SlashCommand and SelectMenu listener
        api.addSelectMenuChooseListener(viewRankRequirementsCommand);   // Register the SelectMenuChooseListener
        api.addSlashCommandCreateListener(new DeleteRankRequirementsCommand());
        api.addSlashCommandCreateListener(new RunUpdatersCommand(womGroupUpdater, rankRequirementUpdater, userVerificationService));
        api.addSlashCommandCreateListener(new VerifyAllUsersCommand(userVerificationService));
        api.addSlashCommandCreateListener(new WomGroupValidatorCommand());

        // Log a message, if the bot joined or left a server
        api.addServerJoinListener(event -> logger.info("Joined server {}", event.getServer().getName()));
        api.addServerLeaveListener(event -> logger.info("Left server {}", event.getServer().getName()));
    }

    private static void removeExistingCommands(DiscordApi api, long guildId) {
        api.getServerById(guildId).ifPresent(guild -> guild.getSlashCommands().thenAccept(commands -> {
            commands.forEach(command -> {
                command.deleteForServer(guildId); // Delete each command
                logger.info("Deleted command: {}", command.getName());
            });
            logger.info("All guild commands have been deleted.");
        }));
    }

    private static void registerCommands(DiscordApi api, long guildId, Server server, WOMGroupUpdater womGroupUpdater, RankRequirementUpdater rankRequirementUpdater, UserVerificationService userVerificationService) {
        List<String> ranks = rankRequirementUpdater.getAllRanks();

        // Use SlashCommandOptionBuilder directly
        SlashCommandOptionBuilder rankOptionBuilder = new SlashCommandOptionBuilder()
                .setType(SlashCommandOptionType.STRING)
                .setName("rank")
                .setDescription("Select a rank to view its requirements")
                .setRequired(false);

        // Add each rank as a choice to the rank option, including the custom emoji if available
        for (String rank : ranks) {
            String emoji = Utils.getCustomEmoji(rank);
            String label = emoji != null ? emoji + " " + rank : rank;

            // Ensure the label is within the 100-character limit
            if (label.length() > 100) {
                label = label.substring(0, 100);
            }

            rankOptionBuilder.addChoice(SlashCommandOptionChoice.create(label, rank));
        }

        // Register the "name" command
        SlashCommand.with("name", "Link your OSRS character name")
                .addOption(SlashCommandOption.create(SlashCommandOptionType.STRING, "character", "Your OSRS character name", true))
                .createForServer(api.getServerById(guildId).get()).join();

        // Register the "verify" command
        SlashCommand.with("verify", "Verify your Discord account with your Replit account.")
                .addOption(SlashCommandOption.create(SlashCommandOptionType.STRING, "verification_key", "Your verification key", true))
                .createForServer(api.getServerById(guildId).get()).join();

        // Register the "points" command with optional user mention, points, and reason
        SlashCommand.with("points", "Check your points or give points to another user with an optional reason.")
                .addOption(SlashCommandOption.create(SlashCommandOptionType.USER, "user", "The Discord user you want to give points to", false))
                .addOption(SlashCommandOption.create(SlashCommandOptionType.LONG, "points", "The amount of points you want to give", false))
                .addOption(SlashCommandOption.create(SlashCommandOptionType.STRING, "reason", "The reason for giving points", false))
                .createForServer(api.getServerById(guildId).get()).join();

        // Register the "config" command to set the points logging channel
        SlashCommand.with("config", "Configure the bot settings. Requires Administrator privilege.")
                .setDefaultEnabledForPermissions(PermissionType.MANAGE_SERVER)
                .addOption(SlashCommandOption.create(SlashCommandOptionType.CHANNEL, "channel", "The channel where points transactions will be logged", true))
                .createForServer(api.getServerById(guildId).get()).join();

        // Register the "set_rank_requirements" command
        SlashCommand.with("set_rank_requirements", "Set requirements for a rank")
                .addOption(SetRankRequirementsCommand.createRankOption(rankRequirementUpdater))
                .addOption(SlashCommandOption.createWithChoices(SlashCommandOptionType.STRING, "requirement_type", "Type of requirement", true,
                        Arrays.asList(
                                SlashCommandOptionChoice.create("Points", "Points"),
                                SlashCommandOptionChoice.create("From Different Players", "Points from players"),
                                SlashCommandOptionChoice.create("From Different Ranks", "Points from ranks"),
                                SlashCommandOptionChoice.create("Time in Clan", "Time in clan"),
                                SlashCommandOptionChoice.create("Time at Rank", "Time at rank"),
                                SlashCommandOptionChoice.create("Other", "Other")
                        )
                ))
                .addOption(SlashCommandOption.create(SlashCommandOptionType.STRING, "required_value", "The required value", false))
                .addOption(SlashCommandOption.create(SlashCommandOptionType.STRING, "specific_rank", "Specific rank to get points from", false))
                .createForServer(api.getServerById(guildId).get()).join();

        // Register the "validate_rank" command
        SlashCommand.with("validate_rank", "Validate rank requirements for a user")
                .addOption(SlashCommandOption.create(SlashCommandOptionType.USER, "user", "The user to validate", true))
                .addOption(SlashCommandOption.create(SlashCommandOptionType.STRING, "rank", "The rank to validate for", true))
                .createForServer(api.getServerById(guildId).get()).join();

        // Register the "check_rank_up" command
        SlashCommand.with("check_rank_up", "Check which users are waiting for a rank up")
                .createForServer(api.getServerById(guildId).get()).join();

        // Register the "view_rank_requirements" command
        SlashCommand.with("view_rank_requirements", "View all the rank requirements")
                .addOption(rankOptionBuilder.build())
                .createForServer(api.getServerById(guildId).get()).join();

        // Register the "delete_rank_requirement" command
        SlashCommand.with("delete_rank_requirement", "Delete a rank requirement. Requires Manage Server permission.")
                .addOption(rankOptionBuilder.build())
                .setDefaultEnabledForPermissions(PermissionType.MANAGE_SERVER)
                .createForServer(api.getServerById(guildId).get()).join();

        // Register the "run_updaters" command
        SlashCommand.with("run_updaters", "Manually run the WOMGroupUpdater, RankRequirementUpdater, and UserVerificationService.")
                .setDefaultEnabledForPermissions(PermissionType.MANAGE_SERVER)
                .createForServer(api.getServerById(guildId).get()).join();

        // Register the "verify_all_users" command
        SlashCommand.with("verify_all_users", "Manually verify all discord roles to the database.")
                .setDefaultEnabledForPermissions(PermissionType.MANAGE_SERVER)
                .createForServer(api.getServerById(guildId).get()).join();

        // Register the "validate_group" command
        SlashCommand.with("validate_group", "Validate the WOM group data against the database.")
                .setDefaultEnabledForPermissions(PermissionType.MANAGE_SERVER)
                .createForServer(api.getServerById(guildId).get()).join();


        logger.info("Commands registered for guild: {}", guildId);
    }
}
