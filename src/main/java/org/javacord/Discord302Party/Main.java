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

        /* removeExistingCommands(api, guildId); */

        // Register all commands
        registerCommands(api, guildId, server, womGroupUpdater, rankRequirementUpdater, userVerificationService);

        // Add listeners for Slash Commands and Select Menu interactions
        ViewRankRequirementsCommand viewRankRequirementsCommand = new ViewRankRequirementsCommand();

        // Add listeners for UserContext and Slash Commands with Points
        PointsCommand pointsCommand = new PointsCommand();

        ItemAutocompleteListener itemAutocompleteListener = new ItemAutocompleteListener();

        // Add dual listeners for LinkOSRSNameCommand
        LinkOSRSNameCommand linkOSRSNameCommand = new LinkOSRSNameCommand();

        api.addSlashCommandCreateListener(new NameCommand());
        api.addSlashCommandCreateListener(new VerifyCommand());
        api.addSlashCommandCreateListener(pointsCommand); // Register points command as both a slash command and user context menu
        api.addUserContextMenuCommandListener(pointsCommand); // Register the user context menu
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
        api.addSlashCommandCreateListener(new ModPointsCommand());
        api.addSlashCommandCreateListener(new OwnerPointsCommand());
        api.addUserContextMenuCommandListener(linkOSRSNameCommand); // Register user context and message listener for OSRSNameCommand
        api.addMessageCreateListener(linkOSRSNameCommand); // #2
        api.addSlashCommandCreateListener(new PointsReactionCommand());
        api.addSlashCommandCreateListener(new ItemPriceCommand());
        // Register the item autocomplete listener
        api.addAutocompleteCreateListener(itemAutocompleteListener);
        api.addSlashCommandCreateListener(new FlipCommand());
        api.addSlashCommandCreateListener(new MoneymakeCommand());
        api.addSlashCommandCreateListener(new GiveawayCommand());


        // Log a message, if the bot joined or left a server
        api.addServerJoinListener(event -> logger.info("Joined server {}", event.getServer().getName()));
        api.addServerLeaveListener(event -> logger.info("Left server {}", event.getServer().getName()));
    }

    private static void removeExistingCommands(DiscordApi api, long guildId) {
        // Remove guild-specific commands
        api.getServerById(guildId).ifPresent(guild -> guild.getSlashCommands().thenAccept(commands -> {
            commands.forEach(command -> {
                command.deleteForServer(guildId); // Delete each guild-specific command
                logger.info("Deleted guild-specific command: {}", command.getName());
            });
            logger.info("All guild-specific commands have been deleted.");
        }));

        // Remove global commands
        api.getGlobalSlashCommands().thenAccept(globalCommands -> {
            globalCommands.forEach(command -> {
                command.deleteGlobal(); // Delete each global command
                command.deleteForServer(guildId);
                logger.info("Deleted global command: {}", command.getName());
            });
            logger.info("All global commands have been deleted.");
        });
        api.getGlobalMessageContextMenus().thenAccept(globalMessageContextMenus -> {
            globalMessageContextMenus.forEach(command -> {
                command.deleteGlobal();
                command.deleteForServer(guildId);
            });
        });
        api.getGlobalApplicationCommands().thenAccept(globalApplicationCommands -> {
            globalApplicationCommands.forEach(command -> {
                command.deleteForServer(guildId);
                command.deleteGlobal();
                logger.info("Deleted global command: {}", command.getName());
            });
        });
        // Remove all guild-specific slash commands
        api.getServerById(guildId).ifPresent(guild -> guild.getSlashCommands().thenAccept(commands -> {
            commands.forEach(command -> {
                command.deleteForServer(guildId); // Delete guild-specific slash command
                logger.info("Deleted guild-specific slash command: {}", command.getName());
            });
            logger.info("All guild-specific slash commands have been deleted.");
        }));

        // Remove all global slash commands
        api.getGlobalSlashCommands().thenAccept(globalCommands -> {
            globalCommands.forEach(command -> {
                command.deleteGlobal(); // Delete global slash command
                command.deleteForServer(guildId);
                logger.info("Deleted global slash command: {}", command.getName());
            });
            logger.info("All global slash commands have been deleted.");
        });

        // Remove all global message context menus
        api.getGlobalMessageContextMenus().thenAccept(globalMenus -> {
            globalMenus.forEach(menu -> {
                menu.deleteGlobal(); // Delete global message context
                menu.deleteForServer(guildId);
                logger.info("Deleted global message context menu: {}", menu.getName());
            });
            logger.info("All global message context menus have been deleted.");
        });

        // Remove all global user context menus
        api.getGlobalUserContextMenus().thenAccept(globalMenus -> {
            globalMenus.forEach(menu -> {
                menu.deleteGlobal(); // Delete global user context menu
                menu.deleteForServer(guildId);
                logger.info("Deleted global user context menu: {}", menu.getName());
            });
            logger.info("All global user context menus have been deleted.");
        });

    }

    private static void registerCommands(DiscordApi api, long guildId, Server server, WOMGroupUpdater womGroupUpdater, RankRequirementUpdater rankRequirementUpdater, UserVerificationService userVerificationService) {
        List<String> ranks = rankRequirementUpdater.getAllRanks();

        // Use SlashCommandOptionBuilder directly
        SlashCommandOptionBuilder rankOptionBuilder = new SlashCommandOptionBuilder()
                .setType(SlashCommandOptionType.STRING)
                .setName("rank")
                .setDescription("Select a rank to view its requirements")
                .setRequired(false);

        SlashCommandOptionBuilder items = new SlashCommandOptionBuilder()
                .setType(SlashCommandOptionType.STRING)
                .setName("item")
                .setDescription("Search for the item")
                .setAutocompletable(true)
                .setRequired(true);

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

        // Register the "Give Points" user context menu command
        UserContextMenu.with("Give 1 Point").createForServer(api.getServerById(guildId).get()).join();

        // Register the "Give Points" user context menu command
        UserContextMenu.with("Give 3 Points").createForServer(api.getServerById(guildId).get()).join();

        // Register the "Give Points" user context menu command
        UserContextMenu.with("Give 5 Points").createForServer(api.getServerById(guildId).get()).join();

        // Register the new context menu command for checking points
        UserContextMenu.with("Check Points").createForServer(api.getServerById(guildId).get()).join();

        // Register a context menu command for Mods to link character names
        UserContextMenu.with("Link OSRS Name")
                .setDefaultEnabledForPermissions(PermissionType.MANAGE_SERVER) // Only available to moderators with Manage Server permission
                .createForServer(api.getServerById(guildId).get()).join();

        // Register the "points" command with optional user mention, points, and reason
        SlashCommand.with("points", "Check your points or give points to another user with an optional reason.")
                .addOption(SlashCommandOption.create(SlashCommandOptionType.USER, "user", "The Discord user you want to give points to", false))
                .addOption(SlashCommandOption.create(SlashCommandOptionType.LONG, "points", "The amount of points you want to give", false))
                .addOption(SlashCommandOption.create(SlashCommandOptionType.STRING, "reason", "The reason for giving points", false))
                .createForServer(api.getServerById(guildId).get()).join();

        SlashCommand.with("mod_points", "Check your points or give points to another user with an optional reason.")
                .setDefaultEnabledForPermissions(PermissionType.MANAGE_SERVER)
                .addOption(SlashCommandOption.create(SlashCommandOptionType.USER, "user", "The Discord user you want to give points to", true))
                .addOption(SlashCommandOption.create(SlashCommandOptionType.LONG, "points", "The amount of points you want to give", true))
                .addOption(SlashCommandOption.create(SlashCommandOptionType.STRING, "reason", "The reason for giving points", true))
                .createForServer(api.getServerById(guildId).get()).join();

        SlashCommand.with("owner_points", "Check your points or give points to another user with an optional reason.")
                .setDefaultEnabledForPermissions(PermissionType.ADMINISTRATOR)
                .addOption(SlashCommandOption.create(SlashCommandOptionType.ROLE, "role", "The Discord user you want to give points to", true))
                .addOption(SlashCommandOption.create(SlashCommandOptionType.LONG, "points", "The amount of points you want to give", true))
                .addOption(SlashCommandOption.create(SlashCommandOptionType.STRING, "reason", "The reason for giving points", true))
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

        //Register the "pointsreaction" command
        SlashCommand.with("pointsreaction", "Reaction giveaway for 1 point per user.")
                .setDefaultEnabledForPermissions(PermissionType.MANAGE_SERVER)
                .createForServer(api.getServerById(guildId).get()).join();

        // Register the "item_price" command
        SlashCommand.with("item_price", "Fetch item price from GE Tracker")
                .addOption(items.build())
                .createForServer(api.getServerById(guildId).get())
                .join();

        // Register the "flip" command
        SlashCommand.with("flip", "Flip the whole game.")
                .createForServer(api.getServerById(guildId).get()).join();

        // Register a nested moneymake command
        SlashCommand command =
                SlashCommand.with("moneymake", "Shows money-making methods related to smithing",
                                Arrays.asList(
                                        SlashCommandOption.createWithOptions(SlashCommandOptionType.SUB_COMMAND_GROUP, "smithing", "Smithing-related methods",
                                                Arrays.asList(
                                                        SlashCommandOption.createWithOptions(SlashCommandOptionType.SUB_COMMAND, "blast-furnace", "Show the best blast furnace smithing methods",
                                                                Arrays.asList(
                                                                        SlashCommandOption.create(SlashCommandOptionType.STRING, "bar", "The type of bar to smith (optional)", false)
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                        .createForServer(api.getServerById(guildId).get())
                        .join();


        // 1) Build a subcommand option for "item"
        SlashCommandOptionBuilder giveawayItemOption = new SlashCommandOptionBuilder()
                .setType(SlashCommandOptionType.STRING)
                .setName("item")
                .setDescription("Exact item name if applicable")
                .setRequired(false)
                .setAutocompletable(true);

        // 2) Build the /giveaway start subcommand
        SlashCommandOption giveawayStart = SlashCommandOption.createWithOptions(
                SlashCommandOptionType.SUB_COMMAND,
                "start",
                "Start a new giveaway",
                Arrays.asList(
                        SlashCommandOption.create(SlashCommandOptionType.STRING, "prize", "Name of the prize", true),
                        SlashCommandOption.create(SlashCommandOptionType.LONG, "winners", "Number of winners", true),
                        SlashCommandOption.createWithChoices(
                                SlashCommandOptionType.STRING,
                                "funds_source",
                                "Clan or personal",
                                true,
                                Arrays.asList(
                                        SlashCommandOptionChoice.create("Clan", "clan"),
                                        SlashCommandOptionChoice.create("Personal", "personal")
                                )
                        ),
                        // We'll create 3 separate fields for days, hours, and minutes (all optional)
                        SlashCommandOption.create(SlashCommandOptionType.LONG, "days", "How many days", false),
                        SlashCommandOption.create(SlashCommandOptionType.LONG, "hours", "How many hours", false),
                        SlashCommandOption.create(SlashCommandOptionType.LONG, "minutes", "How many minutes", false),
                        SlashCommandOption.create(SlashCommandOptionType.STRING, "description", "Giveaway description", false),
                        giveawayItemOption.build()
                )
        );
        // 3) Build the /giveaway end subcommand
        SlashCommandOption giveawayEnd = SlashCommandOption.createWithOptions(
                SlashCommandOptionType.SUB_COMMAND,
                "end",
                "End a giveaway early (mark ended)",
                Arrays.asList(
                        SlashCommandOption.create(SlashCommandOptionType.LONG, "giveaway_id", "ID of the giveaway to end", true)
                )
        );
        // 4) Build the /giveaway claim subcommand
        SlashCommandOption giveawayClaim = SlashCommandOption.createWithOptions(
                SlashCommandOptionType.SUB_COMMAND,
                "claim",
                "Mark that a user claimed their prize",
                Arrays.asList(
                        SlashCommandOption.create(SlashCommandOptionType.LONG, "giveaway_id", "ID of the giveaway", true),
                        SlashCommandOption.create(SlashCommandOptionType.USER, "winner", "Winner who claimed", true)
                )
        );
        // 5) Combine everything into the main /giveaway command
        SlashCommand.with("giveaway", "Manage giveaways",
                Arrays.asList(giveawayStart, giveawayEnd, giveawayClaim)
        )
                .setDefaultEnabledForPermissions(PermissionType.MANAGE_SERVER)
                .createForServer(api.getServerById(guildId).get())
                .join();


        logger.info("Commands registered for guild: {}", guildId);
    }
}
