package me.ryanhamshire.GriefPrevention.util;

import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class PlayerName {

    public static OfflinePlayer resolvePlayerByName(String name) {
        //try online players first
        Player targetPlayer = GriefPrevention.instance.getServer().getPlayerExact(name);
        if (targetPlayer != null) return targetPlayer;

        UUID bestMatchID = null;

        //try exact match first
        bestMatchID = GriefPrevention.instance.playerNameToIDMap.get(name);

        //if failed, try ignore case
        if (bestMatchID == null) {
            bestMatchID = GriefPrevention.instance.playerNameToIDMap.get(name.toLowerCase());
        }
        if (bestMatchID == null) {
            return null;
        }

        return GriefPrevention.instance.getServer().getOfflinePlayer(bestMatchID);
    }

    //helper method to resolve a player name from the player's UUID
    public static @NotNull String lookupPlayerName(@Nullable UUID playerID) {
        //parameter validation
        if (playerID == null) return "someone";
        //check the cache
        OfflinePlayer player = GriefPrevention.instance.getServer().getOfflinePlayer(playerID);
        return lookupPlayerName(player);
    }

    public static @NotNull String lookupPlayerName(@NotNull AnimalTamer tamer) {
        // If the tamer is not a player or has played, prefer their name if it exists.
        if (!(tamer instanceof OfflinePlayer player) || player.hasPlayedBefore() || player.isOnline()) {
            String name = tamer.getName();
            if (name != null) return name;
        }

        // Fall back to tamer's UUID.
        return "someone(" + tamer.getUniqueId() + ")";
    }

    //cache for player name lookups, to save searches of all offline players
    public static void cacheUUIDNamePair(UUID playerID, String playerName) {
        //store the reverse mapping
        GriefPrevention.instance.playerNameToIDMap.put(playerName, playerID);
        GriefPrevention.instance.playerNameToIDMap.put(playerName.toLowerCase(), playerID);
    }

    //string overload for above helper
    public static String lookupPlayerName(String playerID) {
        UUID id;
        try {
            id = UUID.fromString(playerID);
        }
        catch (IllegalArgumentException ex) {
            GriefPrevention.AddLogEntry("Error: Tried to look up a local player name for invalid UUID: " + playerID);
            return "someone";
        }
        return lookupPlayerName(id);
    }

}
