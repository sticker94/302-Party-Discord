package org.javacord.Discord302Party.command;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.Discord302Party.service.RankRequirementUpdater;
import org.javacord.Discord302Party.service.UserVerificationService;
import org.javacord.Discord302Party.service.WOMGroupUpdater;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;
import org.javacord.api.util.logging.ExceptionLogger;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class RunUpdatersCommand implements SlashCommandCreateListener {

    private static final Logger logger = LogManager.getLogger(RunUpdatersCommand.class);
    private final WOMGroupUpdater womGroupUpdater;
    private final RankRequirementUpdater rankRequirementUpdater;
    private final UserVerificationService userVerificationService;

    public RunUpdatersCommand(WOMGroupUpdater womGroupUpdater, RankRequirementUpdater rankRequirementUpdater, UserVerificationService userVerificationService) {
        this.womGroupUpdater = womGroupUpdater;
        this.rankRequirementUpdater = rankRequirementUpdater;
        this.userVerificationService = userVerificationService;
    }

    @Override
    public void onSlashCommandCreate(SlashCommandCreateEvent event) {
        if (event.getSlashCommandInteraction().getCommandName().equalsIgnoreCase("run_updaters")) {
            logger.info("RunUpdaters command received.");

            // Send an initial "Processing..." response to avoid timing out
            event.getSlashCommandInteraction().createImmediateResponder()
                    .setContent("Running WOMGroupUpdater, RankRequirementUpdater, and UserVerificationService, please wait...")
                    .respond().join();

            // Run all updaters asynchronously
            CompletableFuture.runAsync(() -> {
                // Run WOMGroupUpdater
                womGroupUpdater.updateGroupMembers();

                // Run RankRequirementUpdater
                rankRequirementUpdater.validateAllRankRequirements();

                // Run UserVerificationService
                userVerificationService.verifyAllUsers(Objects.requireNonNull(event.getSlashCommandInteraction().getServer().orElse(null)));
            }).exceptionally(e -> {
                // Handle any exceptions that occur during the updater runs
                logger.error("An error occurred while running the updaters: ", e);
                event.getSlashCommandInteraction().createFollowupMessageBuilder()
                        .setContent("Error: An error occurred while running the updaters.")
                        .send().exceptionally(ExceptionLogger.get());
                return null;
            }).thenRun(() -> {
                // Respond with a success message once all updaters have completed
                event.getSlashCommandInteraction().createFollowupMessageBuilder()
                        .setContent("WOMGroupUpdater, RankRequirementUpdater, and UserVerificationService have successfully run.")
                        .send().exceptionally(ExceptionLogger.get());
            });
        }
    }
}
