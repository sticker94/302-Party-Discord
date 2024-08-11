package org.javacord.Discord302Party.command;

import io.github.cdimascio.dotenv.Dotenv;
import org.javacord.api.entity.channel.Channel;
import org.javacord.api.entity.channel.ServerTextChannel;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.interaction.SlashCommandCreateEvent;
import org.javacord.api.listener.interaction.SlashCommandCreateListener;
import org.javacord.api.entity.permission.Role;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class ConfigCommand implements SlashCommandCreateListener {

    // Load environment variables
    private static final Dotenv dotenv = Dotenv.load();
    private static final String DB_URL = "jdbc:mysql://" + dotenv.get("DB_HOST") + ":3306/" + dotenv.get("DB_NAME");
    private static final String USER = dotenv.get("DB_USER");
    private static final String PASS = dotenv.get("DB_PASS");

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL, USER, PASS);
    }

    private void updatePointsChannel(String channelId) {
        String query = "INSERT INTO disc_config (key_name, value) VALUES ('points_channel_id', ?) ON DUPLICATE KEY UPDATE value = VALUES(value)";
        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            preparedStatement.setString(1, channelId);
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onSlashCommandCreate(SlashCommandCreateEvent event) {
        if (event.getSlashCommandInteraction().getCommandName().equalsIgnoreCase("config")) {
            Server server = event.getSlashCommandInteraction().getServer().orElse(null);
            User user = event.getSlashCommandInteraction().getUser();

            if (server != null) {
                // Get the "Party Leader" role
                Role partyLeaderRole = server.getRolesByName("Party Leader").stream().findFirst().orElse(null);

                // Check if the user has the "Party Leader" role
                if (partyLeaderRole != null && user.getRoles(server).contains(partyLeaderRole)) {
                    ServerTextChannel channel = event.getSlashCommandInteraction().getOptionChannelValueByName("channel")
                            .flatMap(Channel::asServerTextChannel).orElse(null);

                    if (channel != null) {
                        // Update the config with the channel ID
                        updatePointsChannel(channel.getIdAsString());
                        event.getSlashCommandInteraction().createImmediateResponder()
                                .setContent("Points transactions will now be posted in " + channel.getMentionTag())
                                .respond();
                    } else {
                        event.getSlashCommandInteraction().createImmediateResponder()
                                .setContent("Please mention a valid text channel.")
                                .respond();
                    }
                } else {
                    event.getSlashCommandInteraction().createImmediateResponder()
                            .setContent("You must have the 'Party Leader' role to perform this action.")
                            .respond();
                }
            }
        }
    }
}
