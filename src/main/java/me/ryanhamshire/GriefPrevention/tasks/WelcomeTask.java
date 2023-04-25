package me.ryanhamshire.GriefPrevention.tasks;

import me.ryanhamshire.GriefPrevention.util.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import me.ryanhamshire.GriefPrevention.enums.MessageType;
import me.ryanhamshire.GriefPrevention.enums.TextMode;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

public class WelcomeTask implements Runnable {
    private final Player player;

    public WelcomeTask(Player player)
    {
        this.player = player;
    }

    @Override
    public void run() {
        //abort if player has logged out since this task was scheduled
        if (!this.player.isOnline()) return;

        //offer advice and a helpful link
        GriefPrevention.sendMessage(player, TextMode.Instr.getColor(), MessageType.AvoidGriefClaimLand);
        GriefPrevention.sendMessage(player, TextMode.Instr.getColor(), MessageType.SurvivalBasicsVideo2, DataStore.SURVIVAL_VIDEO_URL);

        //give the player a reference book for later
        if (GriefPrevention.instance.config_claims_supplyPlayerManual) {
            ItemFactory factory = Bukkit.getItemFactory();
            BookMeta meta = (BookMeta) factory.getItemMeta(Material.WRITTEN_BOOK);

            DataStore datastore = GriefPrevention.instance.dataStore;
            meta.setAuthor(datastore.getMessage(MessageType.BookAuthor));
            meta.setTitle(datastore.getMessage(MessageType.BookTitle));

            StringBuilder page1 = new StringBuilder();
            String URL = datastore.getMessage(MessageType.BookLink, DataStore.SURVIVAL_VIDEO_URL);
            String intro = datastore.getMessage(MessageType.BookIntro);

            page1.append(URL).append("\n\n");
            page1.append(intro).append("\n\n");
            String editToolName = GriefPrevention.instance.config_claims_modificationTool.name().replace('_', ' ').toLowerCase();
            String infoToolName = GriefPrevention.instance.config_claims_investigationTool.name().replace('_', ' ').toLowerCase();
            String configClaimTools = datastore.getMessage(MessageType.BookTools, editToolName, infoToolName);
            page1.append(configClaimTools);
            if (GriefPrevention.instance.config_claims_automaticClaimsForNewPlayersRadius < 0) {
                page1.append(datastore.getMessage(MessageType.BookDisabledChestClaims));
            }

            StringBuilder page2 = new StringBuilder(datastore.getMessage(MessageType.BookUsefulCommands)).append("\n\n");
            page2.append("/Trust /UnTrust /TrustList\n");
            page2.append("/ClaimsList\n");
            page2.append("/AbandonClaim\n\n");
            page2.append("/Claim /ExtendClaim\n");

            page2.append("/IgnorePlayer\n\n");

            page2.append("/SubdivideClaims\n");
            page2.append("/AccessTrust\n");
            page2.append("/ContainerTrust\n");
            page2.append("/PermissionTrust");

            meta.setPages(page1.toString(), page2.toString());

            ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
            item.setItemMeta(meta);
            player.getInventory().addItem(item);
        }
    }
}
