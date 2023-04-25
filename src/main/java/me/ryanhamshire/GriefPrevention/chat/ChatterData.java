package me.ryanhamshire.GriefPrevention.chat;

import java.util.concurrent.ConcurrentLinkedQueue;

class ChatterData
{
    public String lastMessage = "";                 //the player's last chat message, or slash command complete with parameters
    public long lastMessageTimestamp;               //last time the player sent a chat message or used a monitored slash command
    public int spamLevel = 0;                       //number of consecutive "spams"
    public boolean spamWarned = false;              //whether the player has received a warning recently

    //all recent message lengths and their total
    private final ConcurrentLinkedQueue<LengthTimestampPair> recentMessageLengths = new ConcurrentLinkedQueue<>();
    private int recentTotalLength = 0;

    public void AddMessage(String message, long timestamp) {
        int length = message.length();
        this.recentMessageLengths.add(new LengthTimestampPair(length, timestamp));
        this.recentTotalLength += length;
        this.lastMessage = message;
        this.lastMessageTimestamp = timestamp;
    }

    public int getTotalRecentLength(long timestamp) {
        LengthTimestampPair oldestPair = this.recentMessageLengths.peek();
        while (oldestPair != null && timestamp - oldestPair.timestamp > 10000) {
            this.recentMessageLengths.poll();
            this.recentTotalLength -= oldestPair.length;
            oldestPair = this.recentMessageLengths.peek();
        }
        return this.recentTotalLength;
    }
}
