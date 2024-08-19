package org.javacord.Discord302Party.command;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.Discord302Party.service.RankRequirementUpdater;
import org.javacord.Discord302Party.service.WOMGroupUpdater;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;

public class RunUpdatersCommand implements SlashCommandCreateListener {

    private static final Logger logger = LogManager.getLogger(RunUpdatersCommand.class);
    private final WOMGroupUpdater womGroupUpdater;
    private final RankRequirementUpdater rankRequirementUpdater;

    public RunUpdatersCommand(WOMGroupUpdater womGroupUpdater, RankRequirementUpdater rankRequirementUpdater) {
        this.womGroupUpdater = womGroupUpdater;
        this.rankRequirementUpdater = rankRequirementUpdater;
    }

    @Override
    public void onSlashCommandCreate(SlashCommandCreateEvent event) {
        if (event.getSlashCommandInteraction().getCommandName().equalsIgnoreCase("run_updaters")) {
            logger.info("RunUpdaters command received.");

            // Send an initial "Processing..." response to avoid timing out
            event.getSlashCommandInteraction().createImmediateResponder()
                    .setContent("Running WOMGroupUpdater and RankRequirementUpdater, please wait...")
                    .respond().join();

            // Run WOMGroupUpdater
            womGroupUpdater.updateGroupMembers();

            // Run RankRequirementUpdater
            rankRequirementUpdater.validateAllRankRequirements();

            // Respond with a success message
            event.getSlashCommandInteraction().createFollowupMessageBuilder()
                    .setContent("WOMGroupUpdater and RankRequirementUpdater have successfully run.")
                    .send();
        }
    }
}
