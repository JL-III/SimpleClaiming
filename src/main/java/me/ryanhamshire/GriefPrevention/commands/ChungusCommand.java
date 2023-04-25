package me.ryanhamshire.GriefPrevention.commands;

import me.ryanhamshire.GriefPrevention.GriefPrevention;
import me.ryanhamshire.GriefPrevention.claim.Claim;
import me.ryanhamshire.GriefPrevention.claim.ClaimPermission;
import me.ryanhamshire.GriefPrevention.claim.CreateClaimResult;
import me.ryanhamshire.GriefPrevention.enums.CustomLogEntryTypes;
import me.ryanhamshire.GriefPrevention.enums.MessageType;
import me.ryanhamshire.GriefPrevention.enums.ShovelMode;
import me.ryanhamshire.GriefPrevention.events.SaveTrappedPlayerEvent;
import me.ryanhamshire.GriefPrevention.events.TrustChangedEvent;
import me.ryanhamshire.GriefPrevention.listeners.EconomyHandler;
import me.ryanhamshire.GriefPrevention.tasks.AutoExtendClaimTask;
import me.ryanhamshire.GriefPrevention.tasks.PlayerRescueTask;
import me.ryanhamshire.GriefPrevention.tasks.WelcomeTask;
import me.ryanhamshire.GriefPrevention.util.DataStore;
import me.ryanhamshire.GriefPrevention.util.Messages;
import me.ryanhamshire.GriefPrevention.util.PlayerData;
import me.ryanhamshire.GriefPrevention.enums.TextMode;
import me.ryanhamshire.GriefPrevention.visualization.BoundaryVisualization;
import me.ryanhamshire.GriefPrevention.visualization.VisualizationType;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.function.Supplier;

public class ChungusCommand implements CommandExecutor {

    //handles slash commands
    public boolean onCommand(CommandSender sender, Command cmd, String commandLabel, String[] args)
    {

        Player player = null;
        if (sender instanceof Player)
        {
            player = (Player) sender;
        }

        //claim
        if (cmd.getName().equalsIgnoreCase("claim") && player != null)
        {
            if (!GriefPrevention.instance.claimsEnabledForWorld(player.getWorld()))
            {
                Messages.sendMessage(player, TextMode.Err, MessageType.ClaimsDisabledWorld);
                return true;
            }

            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());

            //if he's at the claim count per player limit already and doesn't have permission to bypass, display an error message
            if (GriefPrevention.instance.config_claims_maxClaimsPerPlayer > 0 &&
                    !player.hasPermission("griefprevention.overrideclaimcountlimit") &&
                    playerData.getClaims().size() >= GriefPrevention.instance.config_claims_maxClaimsPerPlayer)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.ClaimCreationFailedOverClaimCountLimit);
                return true;
            }

            //default is chest claim radius, unless -1
            int radius = GriefPrevention.instance.config_claims_automaticClaimsForNewPlayersRadius;
            if (radius < 0) radius = (int) Math.ceil(Math.sqrt(GriefPrevention.instance.config_claims_minArea) / 2);

            //if player has any claims, respect claim minimum size setting
            if (playerData.getClaims().size() > 0)
            {
                //if player has exactly one land claim, this requires the claim modification tool to be in hand (or creative mode player)
                if (playerData.getClaims().size() == 1 && player.getGameMode() != GameMode.CREATIVE && player.getItemInHand().getType() != GriefPrevention.instance.config_claims_modificationTool)
                {
                    GriefPrevention.sendMessage(player, TextMode.Err, MessageType.MustHoldModificationToolForThat);
                    return true;
                }

                radius = (int) Math.ceil(Math.sqrt(GriefPrevention.instance.config_claims_minArea) / 2);
            }

            //allow for specifying the radius
            if (args.length > 0)
            {
                if (playerData.getClaims().size() < 2 && player.getGameMode() != GameMode.CREATIVE && player.getItemInHand().getType() != GriefPrevention.instance.config_claims_modificationTool)
                {
                    GriefPrevention.sendMessage(player, TextMode.Err, MessageType.RadiusRequiresGoldenShovel);
                    return true;
                }

                int specifiedRadius;
                try
                {
                    specifiedRadius = Integer.parseInt(args[0]);
                }
                catch (NumberFormatException e)
                {
                    return false;
                }

                if (specifiedRadius < radius)
                {
                    GriefPrevention.sendMessage(player, TextMode.Err, MessageType.MinimumRadius, String.valueOf(radius));
                    return true;
                }
                else
                {
                    radius = specifiedRadius;
                }
            }

            if (radius < 0) radius = 0;

            Location lc = player.getLocation().add(-radius, 0, -radius);
            Location gc = player.getLocation().add(radius, 0, radius);

            //player must have sufficient unused claim blocks
            int area = Math.abs((gc.getBlockX() - lc.getBlockX() + 1) * (gc.getBlockZ() - lc.getBlockZ() + 1));
            int remaining = playerData.getRemainingClaimBlocks();
            if (remaining < area)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.CreateClaimInsufficientBlocks, String.valueOf(area - remaining));
                GriefPrevention.instance.dataStore.tryAdvertiseAdminAlternatives(player);
                return true;
            }

            CreateClaimResult result = this.dataStore.createClaim(lc.getWorld(),
                    lc.getBlockX(), gc.getBlockX(),
                    lc.getBlockY() - GriefPrevention.instance.config_claims_claimsExtendIntoGroundDistance - 1,
                    gc.getWorld().getHighestBlockYAt(gc) - GriefPrevention.instance.config_claims_claimsExtendIntoGroundDistance - 1,
                    lc.getBlockZ(), gc.getBlockZ(),
                    player.getUniqueId(), null, null, player);
            if (!result.succeeded || result.claim == null)
            {
                if (result.claim != null)
                {
                    GriefPrevention.sendMessage(player, TextMode.Err, MessageType.CreateClaimFailOverlapShort);

                    BoundaryVisualization.visualizeClaim(player, result.claim, VisualizationType.CONFLICT_ZONE);
                }
                else
                {
                    GriefPrevention.sendMessage(player, TextMode.Err, MessageType.CreateClaimFailOverlapRegion);
                }
            }
            else
            {
                GriefPrevention.sendMessage(player, TextMode.Success, MessageType.CreateClaimSuccess);

                //link to a video demo of land claiming, based on world type
                if (GriefPrevention.instance.creativeRulesApply(player.getLocation())) {
                    GriefPrevention.sendMessage(player, TextMode.Instr, MessageType.CreativeBasicsVideo2, DataStore.CREATIVE_VIDEO_URL);
                }
                else if (GriefPrevention.instance.claimsEnabledForWorld(player.getWorld()))
                {
                    GriefPrevention.sendMessage(player, TextMode.Instr, MessageType.SurvivalBasicsVideo2, DataStore.SURVIVAL_VIDEO_URL);
                }
                BoundaryVisualization.visualizeClaim(player, result.claim, VisualizationType.CLAIM);
                playerData.claimResizing = null;
                playerData.lastShovelLocation = null;

                AutoExtendClaimTask.scheduleAsync(result.claim);
            }

            return true;
        }

        //extendclaim
        if (cmd.getName().equalsIgnoreCase("extendclaim") && player != null)
        {
            if (args.length < 1)
            {
                //link to a video demo of land claiming, based on world type
                if (GriefPrevention.instance.creativeRulesApply(player.getLocation()))
                {
                    GriefPrevention.sendMessage(player, TextMode.Instr, MessageType.CreativeBasicsVideo2, DataStore.CREATIVE_VIDEO_URL);
                }
                else if (GriefPrevention.instance.claimsEnabledForWorld(player.getLocation().getWorld()))
                {
                    GriefPrevention.sendMessage(player, TextMode.Instr, MessageType.SurvivalBasicsVideo2, DataStore.SURVIVAL_VIDEO_URL);
                }
                return false;
            }

            int amount;
            try
            {
                amount = Integer.parseInt(args[0]);
            }
            catch (NumberFormatException e)
            {
                //link to a video demo of land claiming, based on world type
                if (GriefPrevention.instance.creativeRulesApply(player.getLocation()))
                {
                    GriefPrevention.sendMessage(player, TextMode.Instr, MessageType.CreativeBasicsVideo2, DataStore.CREATIVE_VIDEO_URL);
                }
                else if (GriefPrevention.instance.claimsEnabledForWorld(player.getLocation().getWorld()))
                {
                    GriefPrevention.sendMessage(player, TextMode.Instr, MessageType.SurvivalBasicsVideo2, DataStore.SURVIVAL_VIDEO_URL);
                }
                return false;
            }

            //requires claim modification tool in hand
            if (player.getGameMode() != GameMode.CREATIVE && player.getItemInHand().getType() != GriefPrevention.instance.config_claims_modificationTool)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.MustHoldModificationToolForThat);
                return true;
            }

            //must be standing in a land claim
            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            Claim claim = this.dataStore.getClaimAt(player.getLocation(), true, playerData.lastClaim);
            if (claim == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.StandInClaimToResize);
                return true;
            }

            //must have permission to edit the land claim you're in
            Supplier<String> errorMessage = claim.checkPermission(player, ClaimPermission.Edit, null);
            if (errorMessage != null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.NotYourClaim);
                return true;
            }

            //determine new corner coordinates
            org.bukkit.util.Vector direction = player.getLocation().getDirection();
            if (direction.getY() > .75)
            {
                GriefPrevention.sendMessage(player, TextMode.Info, MessageType.ClaimsExtendToSky);
                return true;
            }

            if (direction.getY() < -.75)
            {
                GriefPrevention.sendMessage(player, TextMode.Info, MessageType.ClaimsAutoExtendDownward);
                return true;
            }

            Location lc = claim.getLesserBoundaryCorner();
            Location gc = claim.getGreaterBoundaryCorner();
            int newx1 = lc.getBlockX();
            int newx2 = gc.getBlockX();
            int newy1 = lc.getBlockY();
            int newy2 = gc.getBlockY();
            int newz1 = lc.getBlockZ();
            int newz2 = gc.getBlockZ();

            //if changing Z only
            if (Math.abs(direction.getX()) < .3)
            {
                if (direction.getZ() > 0)
                {
                    newz2 += amount;  //north
                }
                else
                {
                    newz1 -= amount;  //south
                }
            }

            //if changing X only
            else if (Math.abs(direction.getZ()) < .3)
            {
                if (direction.getX() > 0)
                {
                    newx2 += amount;  //east
                }
                else
                {
                    newx1 -= amount;  //west
                }
            }

            //diagonals
            else
            {
                if (direction.getX() > 0)
                {
                    newx2 += amount;
                }
                else
                {
                    newx1 -= amount;
                }

                if (direction.getZ() > 0)
                {
                    newz2 += amount;
                }
                else
                {
                    newz1 -= amount;
                }
            }

            //attempt resize
            playerData.claimResizing = claim;
            this.dataStore.resizeClaimWithChecks(player, playerData, newx1, newx2, newy1, newy2, newz1, newz2);
            playerData.claimResizing = null;

            return true;
        }

        //abandonclaim
        if (cmd.getName().equalsIgnoreCase("abandonclaim") && player != null)
        {
            return this.abandonClaimHandler(player, false);
        }

        //abandontoplevelclaim
        if (cmd.getName().equalsIgnoreCase("abandontoplevelclaim") && player != null)
        {
            return this.abandonClaimHandler(player, true);
        }

        //ignoreclaims
        if (cmd.getName().equalsIgnoreCase("ignoreclaims") && player != null)
        {
            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());

            playerData.ignoreClaims = !playerData.ignoreClaims;

            //toggle ignore claims mode on or off
            if (!playerData.ignoreClaims)
            {
                GriefPrevention.sendMessage(player, TextMode.Success, MessageType.RespectingClaims);
            }
            else
            {
                GriefPrevention.sendMessage(player, TextMode.Success, MessageType.IgnoringClaims);
            }

            return true;
        }

        //abandonallclaims
        else if (cmd.getName().equalsIgnoreCase("abandonallclaims") && player != null)
        {
            if (args.length > 1) return false;

            if (args.length != 1 || !"confirm".equalsIgnoreCase(args[0]))
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.ConfirmAbandonAllClaims);
                return true;
            }

            //count claims
            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            int originalClaimCount = playerData.getClaims().size();

            //check count
            if (originalClaimCount == 0)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.YouHaveNoClaims);
                return true;
            }

            if (this.config_claims_abandonReturnRatio != 1.0D)
            {
                //adjust claim blocks
                for (Claim claim : playerData.getClaims())
                {
                    playerData.setAccruedClaimBlocks(playerData.getAccruedClaimBlocks() - (int) Math.ceil((claim.getArea() * (1 - this.config_claims_abandonReturnRatio))));
                }
            }


            //delete them
            this.dataStore.deleteClaimsForPlayer(player.getUniqueId(), false);

            //inform the player
            int remainingBlocks = playerData.getRemainingClaimBlocks();
            GriefPrevention.sendMessage(player, TextMode.Success, MessageType.SuccessfulAbandon, String.valueOf(remainingBlocks));

            //revert any current visualization
            playerData.setVisibleBoundaries(null);

            return true;
        }

        //restore nature
        else if (cmd.getName().equalsIgnoreCase("restorenature") && player != null)
        {
            //change shovel mode
            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            playerData.shovelMode = ShovelMode.RestoreNature;
            GriefPrevention.sendMessage(player, TextMode.Instr, MessageType.RestoreNatureActivate);
            return true;
        }

        //restore nature aggressive mode
        else if (cmd.getName().equalsIgnoreCase("restorenatureaggressive") && player != null)
        {
            //change shovel mode
            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            playerData.shovelMode = ShovelMode.RestoreNatureAggressive;
            GriefPrevention.sendMessage(player, TextMode.Warn, MessageType.RestoreNatureAggressiveActivate);
            return true;
        }

        //restore nature fill mode
        else if (cmd.getName().equalsIgnoreCase("restorenaturefill") && player != null)
        {
            //change shovel mode
            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            playerData.shovelMode = ShovelMode.RestoreNatureFill;

            //set radius based on arguments
            playerData.fillRadius = 2;
            if (args.length > 0)
            {
                try
                {
                    playerData.fillRadius = Integer.parseInt(args[0]);
                }
                catch (Exception exception) { }
            }

            if (playerData.fillRadius < 0) playerData.fillRadius = 2;

            GriefPrevention.sendMessage(player, TextMode.Success, MessageType.FillModeActive, String.valueOf(playerData.fillRadius));
            return true;
        }

        //trust <player>
        else if (cmd.getName().equalsIgnoreCase("trust") && player != null)
        {
            //requires exactly one parameter, the other player's name
            if (args.length != 1) return false;

            //most trust commands use this helper method, it keeps them consistent
            this.handleTrustCommand(player, ClaimPermission.Build, args[0]);

            return true;
        }

        //transferclaim <player>
        else if (cmd.getName().equalsIgnoreCase("transferclaim") && player != null)
        {
            //which claim is the user in?
            Claim claim = this.dataStore.getClaimAt(player.getLocation(), true, null);
            if (claim == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Instr, MessageType.TransferClaimMissing);
                return true;
            }

            //check additional permission for admin claims
            if (claim.isAdminClaim() && !player.hasPermission("griefprevention.adminclaims"))
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.TransferClaimPermission);
                return true;
            }

            UUID newOwnerID = null;  //no argument = make an admin claim
            String ownerName = "admin";

            if (args.length > 0)
            {
                OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
                if (targetPlayer == null)
                {
                    GriefPrevention.sendMessage(player, TextMode.Err, MessageType.PlayerNotFound2);
                    return true;
                }
                newOwnerID = targetPlayer.getUniqueId();
                ownerName = targetPlayer.getName();
            }

            //change ownerhsip
            try
            {
                this.dataStore.changeClaimOwner(claim, newOwnerID);
            }
            catch (DataStore.NoTransferException e)
            {
                GriefPrevention.sendMessage(player, TextMode.Instr, MessageType.TransferTopLevel);
                return true;
            }

            //confirm
            GriefPrevention.sendMessage(player, TextMode.Success, MessageType.TransferSuccess);
            GriefPrevention.AddLogEntry(player.getName() + " transferred a claim at " + GriefPrevention.getfriendlyLocationString(claim.getLesserBoundaryCorner()) + " to " + ownerName + ".", CustomLogEntryTypes.AdminActivity);

            return true;
        }

        //trustlist
        else if (cmd.getName().equalsIgnoreCase("trustlist") && player != null)
        {
            Claim claim = this.dataStore.getClaimAt(player.getLocation(), true, null);

            //if no claim here, error message
            if (claim == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.TrustListNoClaim);
                return true;
            }

            //if no permission to manage permissions, error message
            Supplier<String> errorMessage = claim.checkPermission(player, ClaimPermission.Manage, null);
            if (errorMessage != null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, errorMessage.get());
                return true;
            }

            //otherwise build a list of explicit permissions by permission level
            //and send that to the player
            ArrayList<String> builders = new ArrayList<>();
            ArrayList<String> containers = new ArrayList<>();
            ArrayList<String> accessors = new ArrayList<>();
            ArrayList<String> managers = new ArrayList<>();
            claim.getPermissions(builders, containers, accessors, managers);

            GriefPrevention.sendMessage(player, TextMode.Info, MessageType.TrustListHeader);

            StringBuilder permissions = new StringBuilder();
            permissions.append(ChatColor.GOLD).append('>');

            if (managers.size() > 0)
            {
                for (String manager : managers)
                    permissions.append(this.trustEntryToPlayerName(manager)).append(' ');
            }

            player.sendMessage(permissions.toString());
            permissions = new StringBuilder();
            permissions.append(ChatColor.YELLOW).append('>');

            if (builders.size() > 0)
            {
                for (String builder : builders)
                    permissions.append(this.trustEntryToPlayerName(builder)).append(' ');
            }

            player.sendMessage(permissions.toString());
            permissions = new StringBuilder();
            permissions.append(ChatColor.GREEN).append('>');

            if (containers.size() > 0)
            {
                for (String container : containers)
                    permissions.append(this.trustEntryToPlayerName(container)).append(' ');
            }

            player.sendMessage(permissions.toString());
            permissions = new StringBuilder();
            permissions.append(ChatColor.BLUE).append('>');

            if (accessors.size() > 0)
            {
                for (String accessor : accessors)
                    permissions.append(this.trustEntryToPlayerName(accessor)).append(' ');
            }

            player.sendMessage(permissions.toString());

            player.sendMessage(
                    ChatColor.GOLD + this.dataStore.getMessage(MessageType.Manage) + " " +
                            ChatColor.YELLOW + this.dataStore.getMessage(MessageType.Build) + " " +
                            ChatColor.GREEN + this.dataStore.getMessage(MessageType.Containers) + " " +
                            ChatColor.BLUE + this.dataStore.getMessage(MessageType.Access));

            if (claim.getSubclaimRestrictions())
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.HasSubclaimRestriction);
            }

            return true;
        }

        //untrust <player> or untrust [<group>]
        else if (cmd.getName().equalsIgnoreCase("untrust") && player != null)
        {
            //requires exactly one parameter, the other player's name
            if (args.length != 1) return false;

            //determine which claim the player is standing in
            Claim claim = this.dataStore.getClaimAt(player.getLocation(), true /*ignore height*/, null);

            //determine whether a single player or clearing permissions entirely
            boolean clearPermissions = false;
            OfflinePlayer otherPlayer = null;
            if (args[0].equals("all"))
            {
                if (claim == null || claim.checkPermission(player, ClaimPermission.Edit, null) == null)
                {
                    clearPermissions = true;
                }
                else
                {
                    GriefPrevention.sendMessage(player, TextMode.Err, MessageType.ClearPermsOwnerOnly);
                    return true;
                }
            }
            else
            {
                //validate player argument or group argument
                if (!args[0].startsWith("[") || !args[0].endsWith("]"))
                {
                    otherPlayer = this.resolvePlayerByName(args[0]);
                    if (!clearPermissions && otherPlayer == null && !args[0].equals("public"))
                    {
                        //bracket any permissions - at this point it must be a permission without brackets
                        if (args[0].contains("."))
                        {
                            args[0] = "[" + args[0] + "]";
                        }
                        else
                        {
                            GriefPrevention.sendMessage(player, TextMode.Err, MessageType.PlayerNotFound2);
                            return true;
                        }
                    }

                    //correct to proper casing
                    if (otherPlayer != null)
                        args[0] = otherPlayer.getName();
                }
            }

            //if no claim here, apply changes to all his claims
            if (claim == null)
            {
                PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());

                String idToDrop = args[0];
                if (otherPlayer != null)
                {
                    idToDrop = otherPlayer.getUniqueId().toString();
                }

                //calling event
                TrustChangedEvent event = new TrustChangedEvent(player, playerData.getClaims(), null, false, idToDrop);
                Bukkit.getPluginManager().callEvent(event);

                if (event.isCancelled())
                {
                    return true;
                }

                //dropping permissions
                for (Claim targetClaim : event.getClaims()) {
                    claim = targetClaim;

                    //if untrusting "all" drop all permissions
                    if (clearPermissions)
                    {
                        claim.clearPermissions();
                    }

                    //otherwise drop individual permissions
                    else
                    {
                        claim.dropPermission(idToDrop);
                        claim.managers.remove(idToDrop);
                    }

                    //save changes
                    this.dataStore.saveClaim(claim);
                }

                //beautify for output
                if (args[0].equals("public"))
                {
                    args[0] = "the public";
                }

                //confirmation message
                if (!clearPermissions)
                {
                    GriefPrevention.sendMessage(player, TextMode.Success, MessageType.UntrustIndividualAllClaims, args[0]);
                }
                else
                {
                    GriefPrevention.sendMessage(player, TextMode.Success, MessageType.UntrustEveryoneAllClaims);
                }
            }

            //otherwise, apply changes to only this claim
            else if (claim.checkPermission(player, ClaimPermission.Manage, null) != null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.NoPermissionTrust, claim.getOwnerName());
                return true;
            }
            else
            {
                //if clearing all
                if (clearPermissions)
                {
                    //requires owner
                    if (claim.checkPermission(player, ClaimPermission.Edit, null) != null)
                    {
                        GriefPrevention.sendMessage(player, TextMode.Err, MessageType.UntrustAllOwnerOnly);
                        return true;
                    }

                    //calling the event
                    TrustChangedEvent event = new TrustChangedEvent(player, claim, null, false, args[0]);
                    Bukkit.getPluginManager().callEvent(event);

                    if (event.isCancelled())
                    {
                        return true;
                    }

                    event.getClaims().forEach(Claim::clearPermissions);
                    GriefPrevention.sendMessage(player, TextMode.Success, MessageType.ClearPermissionsOneClaim);
                }

                //otherwise individual permission drop
                else
                {
                    String idToDrop = args[0];
                    if (otherPlayer != null)
                    {
                        idToDrop = otherPlayer.getUniqueId().toString();
                    }
                    boolean targetIsManager = claim.managers.contains(idToDrop);
                    if (targetIsManager && claim.checkPermission(player, ClaimPermission.Edit, null) != null)  //only claim owners can untrust managers
                    {
                        GriefPrevention.sendMessage(player, TextMode.Err, MessageType.ManagersDontUntrustManagers, claim.getOwnerName());
                        return true;
                    }
                    else
                    {
                        //calling the event
                        TrustChangedEvent event = new TrustChangedEvent(player, claim, null, false, idToDrop);
                        Bukkit.getPluginManager().callEvent(event);

                        if (event.isCancelled())
                        {
                            return true;
                        }

                        event.getClaims().forEach(targetClaim -> targetClaim.dropPermission(event.getIdentifier()));

                        //beautify for output
                        if (args[0].equals("public"))
                        {
                            args[0] = "the public";
                        }

                        GriefPrevention.sendMessage(player, TextMode.Success, MessageType.UntrustIndividualSingleClaim, args[0]);
                    }
                }

                //save changes
                this.dataStore.saveClaim(claim);
            }

            return true;
        }

        //accesstrust <player>
        else if (cmd.getName().equalsIgnoreCase("accesstrust") && player != null)
        {
            //requires exactly one parameter, the other player's name
            if (args.length != 1) return false;

            this.handleTrustCommand(player, ClaimPermission.Access, args[0]);

            return true;
        }

        //containertrust <player>
        else if (cmd.getName().equalsIgnoreCase("containertrust") && player != null)
        {
            //requires exactly one parameter, the other player's name
            if (args.length != 1) return false;

            this.handleTrustCommand(player, ClaimPermission.Inventory, args[0]);

            return true;
        }

        //permissiontrust <player>
        else if (cmd.getName().equalsIgnoreCase("permissiontrust") && player != null)
        {
            //requires exactly one parameter, the other player's name
            if (args.length != 1) return false;

            this.handleTrustCommand(player, null, args[0]);  //null indicates permissiontrust to the helper method

            return true;
        }

        //restrictsubclaim
        else if (cmd.getName().equalsIgnoreCase("restrictsubclaim") && player != null)
        {
            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            Claim claim = this.dataStore.getClaimAt(player.getLocation(), true, playerData.lastClaim);
            if (claim == null || claim.parent == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.StandInSubclaim);
                return true;
            }

            // If player has /ignoreclaims on, continue
            // If admin claim, fail if this user is not an admin
            // If not an admin claim, fail if this user is not the owner
            if (!playerData.ignoreClaims && (claim.isAdminClaim() ? !player.hasPermission("griefprevention.adminclaims") : !player.getUniqueId().equals(claim.parent.ownerID)))
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.OnlyOwnersModifyClaims, claim.getOwnerName());
                return true;
            }

            if (claim.getSubclaimRestrictions())
            {
                claim.setSubclaimRestrictions(false);
                GriefPrevention.sendMessage(player, TextMode.Success, MessageType.SubclaimUnrestricted);
            }
            else
            {
                claim.setSubclaimRestrictions(true);
                GriefPrevention.sendMessage(player, TextMode.Success, MessageType.SubclaimRestricted);
            }
            this.dataStore.saveClaim(claim);
            return true;
        }

        //buyclaimblocks
        else if (cmd.getName().equalsIgnoreCase("buyclaimblocks") && player != null)
        {
            //if economy is disabled, don't do anything
            EconomyHandler.EconomyWrapper economyWrapper = economyHandler.getWrapper();
            if (economyWrapper == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.BuySellNotConfigured);
                return true;
            }

            if (!player.hasPermission("griefprevention.buysellclaimblocks"))
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.NoPermissionForCommand);
                return true;
            }

            //if purchase disabled, send error message
            if (GriefPrevention.instance.config_economy_claimBlocksPurchaseCost == 0)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.OnlySellBlocks);
                return true;
            }

            Economy economy = economyWrapper.getEconomy();

            //if no parameter, just tell player cost per block and balance
            if (args.length != 1)
            {
                GriefPrevention.sendMessage(player, TextMode.Info, MessageType.BlockPurchaseCost, String.valueOf(GriefPrevention.instance.config_economy_claimBlocksPurchaseCost), String.valueOf(economy.getBalance(player)));
                return false;
            }
            else
            {
                PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());

                //try to parse number of blocks
                int blockCount;
                try
                {
                    blockCount = Integer.parseInt(args[0]);
                }
                catch (NumberFormatException numberFormatException)
                {
                    return false;  //causes usage to be displayed
                }

                if (blockCount <= 0)
                {
                    return false;
                }

                //if the player can't afford his purchase, send error message
                double balance = economy.getBalance(player);
                double totalCost = blockCount * GriefPrevention.instance.config_economy_claimBlocksPurchaseCost;
                if (totalCost > balance)
                {
                    GriefPrevention.sendMessage(player, TextMode.Err, MessageType.InsufficientFunds, String.valueOf(totalCost), String.valueOf(balance));
                }

                //otherwise carry out transaction
                else
                {
                    int newBonusClaimBlocks = playerData.getBonusClaimBlocks() + blockCount;

                    //if the player is going to reach max bonus limit, send error message
                    int bonusBlocksLimit = GriefPrevention.instance.config_economy_claimBlocksMaxBonus;
                    if (bonusBlocksLimit != 0 && newBonusClaimBlocks > bonusBlocksLimit)
                    {
                        GriefPrevention.sendMessage(player, TextMode.Err, MessageType.MaxBonusReached, String.valueOf(blockCount), String.valueOf(bonusBlocksLimit));
                        return true;
                    }

                    //withdraw cost
                    economy.withdrawPlayer(player, totalCost);

                    //add blocks
                    playerData.setBonusClaimBlocks(playerData.getBonusClaimBlocks() + blockCount);
                    this.dataStore.savePlayerData(player.getUniqueId(), playerData);

                    //inform player
                    GriefPrevention.sendMessage(player, TextMode.Success, MessageType.PurchaseConfirmation, String.valueOf(totalCost), String.valueOf(playerData.getRemainingClaimBlocks()));
                }

                return true;
            }
        }

        //sellclaimblocks <amount>
        else if (cmd.getName().equalsIgnoreCase("sellclaimblocks") && player != null)
        {
            //if economy is disabled, don't do anything
            EconomyHandler.EconomyWrapper economyWrapper = economyHandler.getWrapper();
            if (economyWrapper == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.BuySellNotConfigured);
                return true;
            }

            if (!player.hasPermission("griefprevention.buysellclaimblocks"))
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.NoPermissionForCommand);
                return true;
            }

            //if disabled, error message
            if (GriefPrevention.instance.config_economy_claimBlocksSellValue == 0)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.OnlyPurchaseBlocks);
                return true;
            }

            //load player data
            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            int availableBlocks = playerData.getRemainingClaimBlocks();

            //if no amount provided, just tell player value per block sold, and how many he can sell
            if (args.length != 1)
            {
                GriefPrevention.sendMessage(player, TextMode.Info, MessageType.BlockSaleValue, String.valueOf(GriefPrevention.instance.config_economy_claimBlocksSellValue), String.valueOf(availableBlocks));
                return false;
            }

            //parse number of blocks
            int blockCount;
            try
            {
                blockCount = Integer.parseInt(args[0]);
            }
            catch (NumberFormatException numberFormatException)
            {
                return false;  //causes usage to be displayed
            }

            if (blockCount <= 0)
            {
                return false;
            }

            //if he doesn't have enough blocks, tell him so
            if (blockCount > availableBlocks)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.NotEnoughBlocksForSale);
            }

            //otherwise carry out the transaction
            else
            {
                //compute value and deposit it
                double totalValue = blockCount * GriefPrevention.instance.config_economy_claimBlocksSellValue;
                economyWrapper.getEconomy().depositPlayer(player, totalValue);

                //subtract blocks
                playerData.setBonusClaimBlocks(playerData.getBonusClaimBlocks() - blockCount);
                this.dataStore.savePlayerData(player.getUniqueId(), playerData);

                //inform player
                GriefPrevention.sendMessage(player, TextMode.Success, MessageType.BlockSaleConfirmation, String.valueOf(totalValue), String.valueOf(playerData.getRemainingClaimBlocks()));
            }

            return true;
        }

        //adminclaims
        else if (cmd.getName().equalsIgnoreCase("adminclaims") && player != null)
        {
            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            playerData.shovelMode = ShovelMode.Admin;
            GriefPrevention.sendMessage(player, TextMode.Success, MessageType.AdminClaimsMode);

            return true;
        }

        //basicclaims
        else if (cmd.getName().equalsIgnoreCase("basicclaims") && player != null)
        {
            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            playerData.shovelMode = ShovelMode.Basic;
            playerData.claimSubdividing = null;
            GriefPrevention.sendMessage(player, TextMode.Success, MessageType.BasicClaimsMode);

            return true;
        }

        //subdivideclaims
        else if (cmd.getName().equalsIgnoreCase("subdivideclaims") && player != null)
        {
            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            playerData.shovelMode = ShovelMode.Subdivide;
            playerData.claimSubdividing = null;
            GriefPrevention.sendMessage(player, TextMode.Instr, MessageType.SubdivisionMode);
            GriefPrevention.sendMessage(player, TextMode.Instr, MessageType.SubdivisionVideo2, DataStore.SUBDIVISION_VIDEO_URL);

            return true;
        }

        //deleteclaim
        else if (cmd.getName().equalsIgnoreCase("deleteclaim") && player != null)
        {
            //determine which claim the player is standing in
            Claim claim = this.dataStore.getClaimAt(player.getLocation(), true /*ignore height*/, null);

            if (claim == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.DeleteClaimMissing);
            }
            else
            {
                //deleting an admin claim additionally requires the adminclaims permission
                if (!claim.isAdminClaim() || player.hasPermission("griefprevention.adminclaims"))
                {
                    PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
                    if (claim.children.size() > 0 && !playerData.warnedAboutMajorDeletion)
                    {
                        GriefPrevention.sendMessage(player, TextMode.Warn, MessageType.DeletionSubdivisionWarning);
                        playerData.warnedAboutMajorDeletion = true;
                    }
                    else
                    {
                        claim.removeSurfaceFluids(null);
                        this.dataStore.deleteClaim(claim, true, true);

                        //if in a creative mode world, /restorenature the claim
                        if (GriefPrevention.instance.creativeRulesApply(claim.getLesserBoundaryCorner()) || GriefPrevention.instance.config_claims_survivalAutoNatureRestoration)
                        {
                            GriefPrevention.instance.restoreClaim(claim, 0);
                        }

                        GriefPrevention.sendMessage(player, TextMode.Success, MessageType.DeleteSuccess);
                        GriefPrevention.AddLogEntry(player.getName() + " deleted " + claim.getOwnerName() + "'s claim at " + GriefPrevention.getfriendlyLocationString(claim.getLesserBoundaryCorner()), CustomLogEntryTypes.AdminActivity);

                        //revert any current visualization
                        playerData.setVisibleBoundaries(null);

                        playerData.warnedAboutMajorDeletion = false;
                    }
                }
                else
                {
                    GriefPrevention.sendMessage(player, TextMode.Err, MessageType.CantDeleteAdminClaim);
                }
            }

            return true;
        }
        else if (cmd.getName().equalsIgnoreCase("claimexplosions") && player != null)
        {
            //determine which claim the player is standing in
            Claim claim = this.dataStore.getClaimAt(player.getLocation(), true /*ignore height*/, null);

            if (claim == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.DeleteClaimMissing);
            }
            else
            {
                Supplier<String> noBuildReason = claim.checkPermission(player, ClaimPermission.Build, null);
                if (noBuildReason != null)
                {
                    GriefPrevention.sendMessage(player, TextMode.Err, noBuildReason.get());
                    return true;
                }

                if (claim.areExplosivesAllowed)
                {
                    claim.areExplosivesAllowed = false;
                    GriefPrevention.sendMessage(player, TextMode.Success, MessageType.ExplosivesDisabled);
                }
                else
                {
                    claim.areExplosivesAllowed = true;
                    GriefPrevention.sendMessage(player, TextMode.Success, MessageType.ExplosivesEnabled);
                }
            }

            return true;
        }

        //deleteallclaims <player>
        else if (cmd.getName().equalsIgnoreCase("deleteallclaims"))
        {
            //requires exactly one parameter, the other player's name
            if (args.length != 1) return false;

            //try to find that player
            OfflinePlayer otherPlayer = this.resolvePlayerByName(args[0]);
            if (otherPlayer == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.PlayerNotFound2);
                return true;
            }

            //delete all that player's claims
            this.dataStore.deleteClaimsForPlayer(otherPlayer.getUniqueId(), true);

            GriefPrevention.sendMessage(player, TextMode.Success, MessageType.DeleteAllSuccess, otherPlayer.getName());
            if (player != null)
            {
                GriefPrevention.AddLogEntry(player.getName() + " deleted all claims belonging to " + otherPlayer.getName() + ".", CustomLogEntryTypes.AdminActivity);

                //revert any current visualization
                if (player.isOnline())
                {
                    this.dataStore.getPlayerData(player.getUniqueId()).setVisibleBoundaries(null);
                }
            }

            return true;
        }
        else if (cmd.getName().equalsIgnoreCase("deleteclaimsinworld"))
        {
            //must be executed at the console
            if (player != null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.ConsoleOnlyCommand);
                return true;
            }

            //requires exactly one parameter, the world name
            if (args.length != 1) return false;

            //try to find the specified world
            World world = Bukkit.getServer().getWorld(args[0]);
            if (world == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.WorldNotFound);
                return true;
            }

            //delete all claims in that world
            this.dataStore.deleteClaimsInWorld(world, true);
            GriefPrevention.AddLogEntry("Deleted all claims in world: " + world.getName() + ".", CustomLogEntryTypes.AdminActivity);
            return true;
        }
        else if (cmd.getName().equalsIgnoreCase("deleteuserclaimsinworld"))
        {
            //must be executed at the console
            if (player != null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.ConsoleOnlyCommand);
                return true;
            }

            //requires exactly one parameter, the world name
            if (args.length != 1) return false;

            //try to find the specified world
            World world = Bukkit.getServer().getWorld(args[0]);
            if (world == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.WorldNotFound);
                return true;
            }

            //delete all USER claims in that world
            this.dataStore.deleteClaimsInWorld(world, false);
            GriefPrevention.AddLogEntry("Deleted all user claims in world: " + world.getName() + ".", CustomLogEntryTypes.AdminActivity);
            return true;
        }

        //claimbook
        else if (cmd.getName().equalsIgnoreCase("claimbook"))
        {
            //requires one parameter
            if (args.length != 1) return false;

            //try to find the specified player
            Player otherPlayer = this.getServer().getPlayer(args[0]);
            if (otherPlayer == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.PlayerNotFound2);
                return true;
            }
            else
            {
                WelcomeTask task = new WelcomeTask(otherPlayer);
                task.run();
                return true;
            }
        }

        //claimslist or claimslist <player>
        else if (cmd.getName().equalsIgnoreCase("claimslist"))
        {
            //at most one parameter
            if (args.length > 1) return false;

            //player whose claims will be listed
            OfflinePlayer otherPlayer;

            //if another player isn't specified, assume current player
            if (args.length < 1)
            {
                if (player != null)
                    otherPlayer = player;
                else
                    return false;
            }

            //otherwise if no permission to delve into another player's claims data
            else if (player != null && !player.hasPermission("griefprevention.claimslistother"))
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.ClaimsListNoPermission);
                return true;
            }

            //otherwise try to find the specified player
            else
            {
                otherPlayer = this.resolvePlayerByName(args[0]);
                if (otherPlayer == null)
                {
                    GriefPrevention.sendMessage(player, TextMode.Err, MessageType.PlayerNotFound2);
                    return true;
                }
            }

            //load the target player's data
            PlayerData playerData = this.dataStore.getPlayerData(otherPlayer.getUniqueId());
            Vector<Claim> claims = playerData.getClaims();
            GriefPrevention.sendMessage(player, TextMode.Instr, MessageType.StartBlockMath,
                    String.valueOf(playerData.getAccruedClaimBlocks()),
                    String.valueOf((playerData.getBonusClaimBlocks() + this.dataStore.getGroupBonusBlocks(otherPlayer.getUniqueId()))),
                    String.valueOf((playerData.getAccruedClaimBlocks() + playerData.getBonusClaimBlocks() + this.dataStore.getGroupBonusBlocks(otherPlayer.getUniqueId()))));
            if (claims.size() > 0)
            {
                GriefPrevention.sendMessage(player, TextMode.Instr, MessageType.ClaimsListHeader);
                for (int i = 0; i < playerData.getClaims().size(); i++)
                {
                    Claim claim = playerData.getClaims().get(i);
                    GriefPrevention.sendMessage(player, TextMode.Instr, getfriendlyLocationString(claim.getLesserBoundaryCorner()) + this.dataStore.getMessage(MessageType.ContinueBlockMath, String.valueOf(claim.getArea())));
                }

                GriefPrevention.sendMessage(player, TextMode.Instr, MessageType.EndBlockMath, String.valueOf(playerData.getRemainingClaimBlocks()));
            }

            //drop the data we just loaded, if the player isn't online
            if (!otherPlayer.isOnline())
                this.dataStore.clearCachedPlayerData(otherPlayer.getUniqueId());

            return true;
        }

        //adminclaimslist
        else if (cmd.getName().equalsIgnoreCase("adminclaimslist"))
        {
            //find admin claims
            Vector<Claim> claims = new Vector<>();
            for (Claim claim : this.dataStore.claims)
            {
                if (claim.ownerID == null)  //admin claim
                {
                    claims.add(claim);
                }
            }
            if (claims.size() > 0)
            {
                GriefPrevention.sendMessage(player, TextMode.Instr, MessageType.ClaimsListHeader);
                for (Claim claim : claims)
                {
                    GriefPrevention.sendMessage(player, TextMode.Instr, getfriendlyLocationString(claim.getLesserBoundaryCorner()));
                }
            }

            return true;
        }

        //unlockItems
        else if (cmd.getName().equalsIgnoreCase("unlockdrops") && player != null)
        {
            PlayerData playerData;

            if (player.hasPermission("griefprevention.unlockothersdrops") && args.length == 1)
            {
                Player otherPlayer = Bukkit.getPlayer(args[0]);
                if (otherPlayer == null)
                {
                    GriefPrevention.sendMessage(player, TextMode.Err, MessageType.PlayerNotFound2);
                    return true;
                }

                playerData = this.dataStore.getPlayerData(otherPlayer.getUniqueId());
                GriefPrevention.sendMessage(player, TextMode.Success, MessageType.DropUnlockOthersConfirmation, otherPlayer.getName());
            }
            else
            {
                playerData = this.dataStore.getPlayerData(player.getUniqueId());
                GriefPrevention.sendMessage(player, TextMode.Success, MessageType.DropUnlockConfirmation);
            }

            playerData.dropsAreUnlocked = true;

            return true;
        }

        //deletealladminclaims
        else if (player != null && cmd.getName().equalsIgnoreCase("deletealladminclaims"))
        {
            if (!player.hasPermission("griefprevention.deleteclaims"))
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.NoDeletePermission);
                return true;
            }

            //delete all admin claims
            this.dataStore.deleteClaimsForPlayer(null, true);  //null for owner id indicates an administrative claim

            GriefPrevention.sendMessage(player, TextMode.Success, MessageType.AllAdminDeleted);
            if (player != null)
            {
                GriefPrevention.AddLogEntry(player.getName() + " deleted all administrative claims.", CustomLogEntryTypes.AdminActivity);

                //revert any current visualization
                this.dataStore.getPlayerData(player.getUniqueId()).setVisibleBoundaries(null);
            }

            return true;
        }

        //adjustbonusclaimblocks <player> <amount> or [<permission>] amount
        else if (cmd.getName().equalsIgnoreCase("adjustbonusclaimblocks"))
        {
            //requires exactly two parameters, the other player or group's name and the adjustment
            if (args.length != 2) return false;

            //parse the adjustment amount
            int adjustment;
            try
            {
                adjustment = Integer.parseInt(args[1]);
            }
            catch (NumberFormatException numberFormatException)
            {
                return false;  //causes usage to be displayed
            }

            //if granting blocks to all players with a specific permission
            if (args[0].startsWith("[") && args[0].endsWith("]"))
            {
                String permissionIdentifier = args[0].substring(1, args[0].length() - 1);
                int newTotal = this.dataStore.adjustGroupBonusBlocks(permissionIdentifier, adjustment);

                GriefPrevention.sendMessage(player, TextMode.Success, MessageType.AdjustGroupBlocksSuccess, permissionIdentifier, String.valueOf(adjustment), String.valueOf(newTotal));
                if (player != null)
                    GriefPrevention.AddLogEntry(player.getName() + " adjusted " + permissionIdentifier + "'s bonus claim blocks by " + adjustment + ".");

                return true;
            }

            //otherwise, find the specified player
            OfflinePlayer targetPlayer;
            try
            {
                UUID playerID = UUID.fromString(args[0]);
                targetPlayer = this.getServer().getOfflinePlayer(playerID);

            }
            catch (IllegalArgumentException e)
            {
                targetPlayer = this.resolvePlayerByName(args[0]);
            }

            if (targetPlayer == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.PlayerNotFound2);
                return true;
            }

            //give blocks to player
            PlayerData playerData = this.dataStore.getPlayerData(targetPlayer.getUniqueId());
            playerData.setBonusClaimBlocks(playerData.getBonusClaimBlocks() + adjustment);
            this.dataStore.savePlayerData(targetPlayer.getUniqueId(), playerData);

            GriefPrevention.sendMessage(player, TextMode.Success, MessageType.AdjustBlocksSuccess, targetPlayer.getName(), String.valueOf(adjustment), String.valueOf(playerData.getBonusClaimBlocks()));
            if (player != null)
                GriefPrevention.AddLogEntry(player.getName() + " adjusted " + targetPlayer.getName() + "'s bonus claim blocks by " + adjustment + ".", CustomLogEntryTypes.AdminActivity);

            return true;
        }

        //adjustbonusclaimblocksall <amount>
        else if (cmd.getName().equalsIgnoreCase("adjustbonusclaimblocksall"))
        {
            //requires exactly one parameter, the amount of adjustment
            if (args.length != 1) return false;

            //parse the adjustment amount
            int adjustment;
            try
            {
                adjustment = Integer.parseInt(args[0]);
            }
            catch (NumberFormatException numberFormatException)
            {
                return false;  //causes usage to be displayed
            }

            //for each online player
            @SuppressWarnings("unchecked")
            Collection<Player> players = (Collection<Player>) this.getServer().getOnlinePlayers();
            StringBuilder builder = new StringBuilder();
            for (Player onlinePlayer : players)
            {
                UUID playerID = onlinePlayer.getUniqueId();
                PlayerData playerData = this.dataStore.getPlayerData(playerID);
                playerData.setBonusClaimBlocks(playerData.getBonusClaimBlocks() + adjustment);
                this.dataStore.savePlayerData(playerID, playerData);
                builder.append(onlinePlayer.getName()).append(' ');
            }

            GriefPrevention.sendMessage(player, TextMode.Success, MessageType.AdjustBlocksAllSuccess, String.valueOf(adjustment));
            GriefPrevention.AddLogEntry("Adjusted all " + players.size() + "players' bonus claim blocks by " + adjustment + ".  " + builder.toString(), CustomLogEntryTypes.AdminActivity);

            return true;
        }

        //setaccruedclaimblocks <player> <amount>
        else if (cmd.getName().equalsIgnoreCase("setaccruedclaimblocks"))
        {
            //requires exactly two parameters, the other player's name and the new amount
            if (args.length != 2) return false;

            //parse the adjustment amount
            int newAmount;
            try
            {
                newAmount = Integer.parseInt(args[1]);
            }
            catch (NumberFormatException numberFormatException)
            {
                return false;  //causes usage to be displayed
            }

            //find the specified player
            OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
            if (targetPlayer == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.PlayerNotFound2);
                return true;
            }

            //set player's blocks
            PlayerData playerData = this.dataStore.getPlayerData(targetPlayer.getUniqueId());
            playerData.setAccruedClaimBlocks(newAmount);
            this.dataStore.savePlayerData(targetPlayer.getUniqueId(), playerData);

            GriefPrevention.sendMessage(player, TextMode.Success, MessageType.SetClaimBlocksSuccess);
            if (player != null)
                GriefPrevention.AddLogEntry(player.getName() + " set " + targetPlayer.getName() + "'s accrued claim blocks to " + newAmount + ".", CustomLogEntryTypes.AdminActivity);

            return true;
        }

        //trapped
        else if (cmd.getName().equalsIgnoreCase("trapped") && player != null)
        {
            //FEATURE: empower players who get "stuck" in an area where they don't have permission to build to save themselves

            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            Claim claim = this.dataStore.getClaimAt(player.getLocation(), false, playerData.lastClaim);

            //if another /trapped is pending, ignore this slash command
            if (playerData.pendingTrapped)
            {
                return true;
            }

            //if the player isn't in a claim or has permission to build, tell him to man up
            if (claim == null || claim.checkPermission(player, ClaimPermission.Build, null) == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.NotTrappedHere);
                return true;
            }

            //rescue destination may be set by GPFlags or other plugin, ask to find out
            SaveTrappedPlayerEvent event = new SaveTrappedPlayerEvent(claim);
            Bukkit.getPluginManager().callEvent(event);

            //if the player is in the nether or end, he's screwed (there's no way to programmatically find a safe place for him)
            if (player.getWorld().getEnvironment() != World.Environment.NORMAL && event.getDestination() == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.TrappedWontWorkHere);
                return true;
            }

            //if the player is in an administrative claim and AllowTrappedInAdminClaims is false, he should contact an admin
            if (!GriefPrevention.instance.config_claims_allowTrappedInAdminClaims && claim.isAdminClaim() && event.getDestination() == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.TrappedWontWorkHere);
                return true;
            }
            //send instructions
            GriefPrevention.sendMessage(player, TextMode.Instr, MessageType.RescuePending);

            //create a task to rescue this player in a little while
            PlayerRescueTask task = new PlayerRescueTask(player, player.getLocation(), event.getDestination());
            this.getServer().getScheduler().scheduleSyncDelayedTask(this, task, 200L);  //20L ~ 1 second

            return true;
        }

        //siege
        else if (cmd.getName().equalsIgnoreCase("siege") && player != null)
        {
            //error message for when siege mode is disabled
            if (!this.siegeEnabledForWorld(player.getWorld()))
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.NonSiegeWorld);
                return true;
            }

            //requires one argument
            if (args.length > 1)
            {
                return false;
            }

            //can't start a siege when you're already involved in one
            Player attacker = player;
            PlayerData attackerData = this.dataStore.getPlayerData(attacker.getUniqueId());
//            if (attackerData.siegeData != null)
//            {
//                GriefPrevention.sendMessage(player, TextMode.Err, Messages.AlreadySieging);
//                return true;
//            }

            //can't start a siege when you're protected from pvp combat
            if (attackerData.pvpImmune)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.CantFightWhileImmune);
                return true;
            }

            //if a player name was specified, use that
            Player defender = null;
            if (args.length >= 1)
            {
                defender = this.getServer().getPlayer(args[0]);
                if (defender == null)
                {
                    GriefPrevention.sendMessage(player, TextMode.Err, MessageType.PlayerNotFound2);
                    return true;
                }
            }

            //otherwise use the last player this player was in pvp combat with
            else if (attackerData.lastPvpPlayer.length() > 0)
            {
                defender = this.getServer().getPlayer(attackerData.lastPvpPlayer);
                if (defender == null)
                {
                    return false;
                }
            }
            else
            {
                return false;
            }

            // First off, you cannot siege yourself, that's just
            // silly:
            if (attacker.getName().equals(defender.getName()))
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.NoSiegeYourself);
                return true;
            }

            //victim must not have the permission which makes him immune to siege
            if (defender.hasPermission("griefprevention.siegeimmune"))
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.SiegeImmune);
                return true;
            }

            //victim must not be under siege already
            PlayerData defenderData = this.dataStore.getPlayerData(defender.getUniqueId());
//            if (defenderData.siegeData != null)
//            {
//                GriefPrevention.sendMessage(player, TextMode.Err, Messages.AlreadyUnderSiegePlayer);
//                return true;
//            }

            //victim must not be pvp immune
            if (defenderData.pvpImmune)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.NoSiegeDefenseless);
                return true;
            }

            Claim defenderClaim = this.dataStore.getClaimAt(defender.getLocation(), false, null);

            //defender must have some level of permission there to be protected
            if (defenderClaim == null || defenderClaim.checkPermission(defender, ClaimPermission.Access, null) != null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.NotSiegableThere);
                return true;
            }

            //attacker must be close to the claim he wants to siege
            if (!defenderClaim.isNear(attacker.getLocation(), 25))
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.SiegeTooFarAway);
                return true;
            }

            //claim can't be under siege already
//            if (defenderClaim.siegeData != null)
//            {
//                GriefPrevention.sendMessage(player, TextMode.Err, Messages.AlreadyUnderSiegeArea);
//                return true;
//            }

            //can't siege admin claims
            if (defenderClaim.isAdminClaim())
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.NoSiegeAdminClaim);
                return true;
            }

//            //can't be on cooldown
//            if (dataStore.onCooldown(attacker, defender, defenderClaim))
//            {
//                GriefPrevention.sendMessage(player, TextMode.Err, Messages.SiegeOnCooldown);
//                return true;
//            }
//
//            //start the siege
//            dataStore.startSiege(attacker, defender, defenderClaim);

            //confirmation message for attacker, warning message for defender
            GriefPrevention.sendMessage(defender, TextMode.Warn, MessageType.SiegeAlert, attacker.getName());
            GriefPrevention.sendMessage(player, TextMode.Success, MessageType.SiegeConfirmed, defender.getName());

            return true;
        }
        else if (cmd.getName().equalsIgnoreCase("softmute"))
        {
            //requires one parameter
            if (args.length != 1) return false;

            //find the specified player
            OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
            if (targetPlayer == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.PlayerNotFound2);
                return true;
            }

            //toggle mute for player
            boolean isMuted = this.dataStore.toggleSoftMute(targetPlayer.getUniqueId());
            if (isMuted)
            {
                GriefPrevention.sendMessage(player, TextMode.Success, MessageType.SoftMuted, targetPlayer.getName());
                String executorName = "console";
                if (player != null)
                {
                    executorName = player.getName();
                }

                GriefPrevention.AddLogEntry(executorName + " muted " + targetPlayer.getName() + ".", CustomLogEntryTypes.AdminActivity, true);
            }
            else
            {
                GriefPrevention.sendMessage(player, TextMode.Success, MessageType.UnSoftMuted, targetPlayer.getName());
            }

            return true;
        }
        else if (cmd.getName().equalsIgnoreCase("gpreload"))
        {
            this.loadConfig();
            this.dataStore.loadMessages();
            playerEventHandler.resetPattern();
            if (player != null)
            {
                GriefPrevention.sendMessage(player, TextMode.Success, "Configuration updated.  If you have updated your Grief Prevention JAR, you still need to /reload or reboot your server.");
            }
            else
            {
                GriefPrevention.AddLogEntry("Configuration updated.  If you have updated your Grief Prevention JAR, you still need to /reload or reboot your server.");
            }

            return true;
        }

        //givepet
        else if (cmd.getName().equalsIgnoreCase("givepet") && player != null)
        {
            //requires one parameter
            if (args.length < 1) return false;

            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());

            //special case: cancellation
            if (args[0].equalsIgnoreCase("cancel"))
            {
                playerData.petGiveawayRecipient = null;
                GriefPrevention.sendMessage(player, TextMode.Success, MessageType.PetTransferCancellation);
                return true;
            }

            //find the specified player
            OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
            if (targetPlayer == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.PlayerNotFound2);
                return true;
            }

            //remember the player's ID for later pet transfer
            playerData.petGiveawayRecipient = targetPlayer;

            //send instructions
            GriefPrevention.sendMessage(player, TextMode.Instr, MessageType.ReadyToTransferPet);

            return true;
        }

        //gpblockinfo
        else if (cmd.getName().equalsIgnoreCase("gpblockinfo") && player != null)
        {
            ItemStack inHand = player.getInventory().getItemInMainHand();
            player.sendMessage("In Hand: " + inHand.getType().name());

            Block inWorld = player.getTargetBlockExact(300, FluidCollisionMode.ALWAYS);
            if (inWorld == null) inWorld = player.getEyeLocation().getBlock();
            player.sendMessage("In World: " + inWorld.getType().name());

            return true;
        }

        //ignoreplayer
        else if (cmd.getName().equalsIgnoreCase("ignoreplayer") && player != null)
        {
            //requires target player name
            if (args.length < 1) return false;

            //validate target player
            OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
            if (targetPlayer == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.PlayerNotFound2);
                return true;
            }

            this.setIgnoreStatus(player, targetPlayer, GriefPrevention.IgnoreMode.StandardIgnore);

            GriefPrevention.sendMessage(player, TextMode.Success, MessageType.IgnoreConfirmation);

            return true;
        }

        //unignoreplayer
        else if (cmd.getName().equalsIgnoreCase("unignoreplayer") && player != null)
        {
            //requires target player name
            if (args.length < 1) return false;

            //validate target player
            OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
            if (targetPlayer == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.PlayerNotFound2);
                return true;
            }

            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            Boolean ignoreStatus = playerData.ignoredPlayers.get(targetPlayer.getUniqueId());
            if (ignoreStatus == null || ignoreStatus == true)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.NotIgnoringPlayer);
                return true;
            }

            this.setIgnoreStatus(player, targetPlayer, GriefPrevention.IgnoreMode.None);

            GriefPrevention.sendMessage(player, TextMode.Success, MessageType.UnIgnoreConfirmation);

            return true;
        }

        //ignoredplayerlist
        else if (cmd.getName().equalsIgnoreCase("ignoredplayerlist") && player != null)
        {
            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<UUID, Boolean> entry : playerData.ignoredPlayers.entrySet())
            {
                if (entry.getValue() != null)
                {
                    //if not an admin ignore, add it to the list
                    if (!entry.getValue())
                    {
                        builder.append(GriefPrevention.lookupPlayerName(entry.getKey()));
                        builder.append(" ");
                    }
                }
            }

            String list = builder.toString().trim();
            if (list.isEmpty())
            {
                GriefPrevention.sendMessage(player, TextMode.Info, MessageType.NotIgnoringAnyone);
            }
            else
            {
                GriefPrevention.sendMessage(player, TextMode.Info, list);
            }

            return true;
        }

        //separateplayers
        else if (cmd.getName().equalsIgnoreCase("separate"))
        {
            //requires two player names
            if (args.length < 2) return false;

            //validate target players
            OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
            if (targetPlayer == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.PlayerNotFound2);
                return true;
            }

            OfflinePlayer targetPlayer2 = this.resolvePlayerByName(args[1]);
            if (targetPlayer2 == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.PlayerNotFound2);
                return true;
            }

            this.setIgnoreStatus(targetPlayer, targetPlayer2, GriefPrevention.IgnoreMode.AdminIgnore);

            GriefPrevention.sendMessage(player, TextMode.Success, MessageType.SeparateConfirmation);

            return true;
        }

        //unseparateplayers
        else if (cmd.getName().equalsIgnoreCase("unseparate"))
        {
            //requires two player names
            if (args.length < 2) return false;

            //validate target players
            OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
            if (targetPlayer == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.PlayerNotFound2);
                return true;
            }

            OfflinePlayer targetPlayer2 = this.resolvePlayerByName(args[1]);
            if (targetPlayer2 == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, MessageType.PlayerNotFound2);
                return true;
            }

            this.setIgnoreStatus(targetPlayer, targetPlayer2, GriefPrevention.IgnoreMode.None);
            this.setIgnoreStatus(targetPlayer2, targetPlayer, GriefPrevention.IgnoreMode.None);

            GriefPrevention.sendMessage(player, TextMode.Success, MessageType.UnSeparateConfirmation);

            return true;
        }
        return false;
    }
}
