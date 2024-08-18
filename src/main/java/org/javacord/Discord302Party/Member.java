package org.javacord.Discord302Party;

import java.sql.Timestamp;

public class Member {

    private int WOMId;
    private String username;
    private String rank;
    private Timestamp rankObtainedTimestamp;

    public Member(int WOMId, String username, String rank, Timestamp rankObtainedTimestamp) {
        this.WOMId = WOMId;
        this.username = username;
        this.rank = rank;
        this.rankObtainedTimestamp = rankObtainedTimestamp;
    }

    public int getWOMId() {
        return WOMId;
    }

    public String getUsername() {
        return username;
    }

    public String getRank() {
        return rank;
    }

    public Timestamp getRankObtainedTimestamp() {
        return rankObtainedTimestamp;
    }

    public void setWOMId(int WOMId) {
        this.WOMId = WOMId;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setRank(String rank) {
        this.rank = rank;
    }

    public void setRankObtainedTimestamp(Timestamp rankObtainedTimestamp) {
        this.rankObtainedTimestamp = rankObtainedTimestamp;
    }
}
