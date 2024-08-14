package org.javacord.Discord302Party;

import io.github.cdimascio.dotenv.Dotenv;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class RankService {

    Dotenv dotenv = Dotenv.load();

    private final String DB_URL = "jdbc:mysql://" + dotenv.get("DB_HOST") + ":3306/" + dotenv.get("DB_NAME");
    private final String USER = dotenv.get("DB_USER");
    private final String PASS = dotenv.get("DB_PASS");

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL, USER, PASS);
    }

    public List<String> getAllRanks() {
        List<String> ranks = new ArrayList<>();
        String query = "SELECT rank FROM config ORDER BY rank_order ASC";

        try (Connection connection = connect();
             PreparedStatement preparedStatement = connection.prepareStatement(query);
             ResultSet resultSet = preparedStatement.executeQuery()) {

            while (resultSet.next()) {
                ranks.add(resultSet.getString("rank"));
            }

        } catch (SQLException e) {
            e.printStackTrace(); // Handle exception properly
        }

        return ranks;
    }
}