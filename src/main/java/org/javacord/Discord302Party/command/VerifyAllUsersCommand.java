package org.javacord.Discord302Party.command;

import org.javacord.Discord302Party.service.UserVerificationService;
import org.javacord.api.entity.server.Server;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;
import org.javacord.api.util.logging.ExceptionLogger;
import org.javacord.api.interaction.callback.InteractionOriginalResponseUpdater;

import java.util.concurrent.CompletableFuture;

public class VerifyAllUsersCommand implements SlashCommandCreateListener {

    private final UserVerificationService userVerificationService;

    public VerifyAllUsersCommand(UserVerificationService userVerificationService) {
        this.userVerificationService = userVerificationService;
    }

    @Override
    public void onSlashCommandCreate(SlashCommandCreateEvent event) {
        if (event.getSlashCommandInteraction().getCommandName().equalsIgnoreCase("verify_all_users")) {
            Server server = event.getSlashCommandInteraction().getServer().orElse(null);

            if (server == null) {
                event.getSlashCommandInteraction().createImmediateResponder()
                        .setContent("Error: Couldn't retrieve server information.")
                        .respond();
                return;
            }

            // Initial response to acknowledge the command
            InteractionOriginalResponseUpdater updater = event.getSlashCommandInteraction()
                    .createImmediateResponder()
                    .setContent("Starting the verification process...")
                    .respond().join();

            // Run the verification process asynchronously
            CompletableFuture.runAsync(() -> {
                userVerificationService.verifyAllUsers(server);
                updater.setContent("Verification process completed.").update().exceptionally(ExceptionLogger.get());
            }).exceptionally(e -> {
                updater.setContent("Error: An error occurred during the verification process.").update().exceptionally(ExceptionLogger.get());
                e.printStackTrace();
                return null;
            });
        }
    }
}
