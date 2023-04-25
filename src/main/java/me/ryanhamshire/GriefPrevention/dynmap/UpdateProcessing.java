package me.ryanhamshire.GriefPrevention.dynmap;

import me.ryanhamshire.GriefPrevention.GriefPrevention;
import me.ryanhamshire.GriefPrevention.claim.Claim;
import me.ryanhamshire.GriefPrevention.util.DataStore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.dynmap.markers.AreaMarker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import static me.ryanhamshire.GriefPrevention.dynmap.DynmapIntegration.ADMIN_ID;

public class UpdateProcessing {

    private final GriefPrevention plugin;
    private final DynmapIntegration dynmapIntegration;
    private final Map<UUID, String> playerNameCache;
    private final Pattern idPattern;
    private boolean showDebug;

    //TODO this is referencing the GriefPrevention class but still has ties to the old set up where this was a standalone plugin.
    // The DynmapGriefPreventionPlugin class needs to be refactored to be an object in this plugin.

    public UpdateProcessing(@NotNull GriefPrevention plugin, DynmapIntegration dynmapIntegration) {
        this.plugin = plugin;
        this.dynmapIntegration = dynmapIntegration;
        showDebug = plugin.getConfig().getBoolean("debug", false);
        this.playerNameCache = new TreeMap<>();
        this.idPattern = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-5][0-9a-f]{3}-[089ab][0-9a-f]{3}-[0-9a-f]{12}$");
    }


    @Nullable ArrayList<Claim> getClaims(){
        ArrayList<Claim> claims;
        try {
            Field fld = DataStore.class.getDeclaredField("claims");
            fld.setAccessible(true);
            Object o = fld.get(plugin.dataStore);
            claims = (ArrayList<Claim>) o;
        } catch(NoSuchFieldException | IllegalArgumentException | IllegalAccessException e) {
            plugin.getLogger().warning("Error getting claims from reflection: " + e.getMessage());
            return null;
        }

        return claims;
    }

    private @Nullable ArrayList<Claim> getClaimsNonAsync(){
        final CompletableFuture<ArrayList<Claim>> completableFuture = new CompletableFuture<>();

        final BukkitRunnable runnable = new BukkitRunnable() {
            @Override
            public void run() {
                final ArrayList<Claim> claims = getClaims();
                completableFuture.complete(claims);
            }
        };

        long startTime = System.currentTimeMillis();
        runnable.runTask(plugin);

        try {
            ArrayList<Claim> result = completableFuture.get(500L, TimeUnit.MILLISECONDS);
            if (showDebug) {
                long timeTaken = System.currentTimeMillis() - startTime;
                plugin.getLogger().info("getClaims() time taken: " + timeTaken);
            }
            return result;
        }
        catch (InterruptedException | ConcurrentModificationException | ExecutionException | TimeoutException e){
            e.printStackTrace();
        }

        return null;
    }

    void updateClaims() {
        final Map<String, AreaMarker> newmap = new HashMap<>(); /* Build new map */
        final ArrayList<Claim> claims = getClaimsNonAsync();

        int parentClaims = 0;
        int childClaims = 0;
        int deletions = 0;

        /* If claims, process them */
        if(claims != null) {
            for(final Claim claim : claims) {
                handleClaim(claim, newmap);
                parentClaims++;

                if (claim.children != null) {
                    for (Claim childClaim : claim.children) {
                        handleClaim(childClaim, newmap);
                        childClaims++;
                    }
                }
            }
        }
        /* Now, review old map - anything left is gone */
        for(final AreaMarker oldm : dynmapIntegration.resareas.values()) {
            oldm.deleteMarker();
            deletions++;
        }

        /* And replace with new map */
        dynmapIntegration.resareas = newmap;

        if (showDebug)
            plugin.getLogger().info(String.format("claims: %s, child claims: %s, deletions: %s", parentClaims, childClaims, deletions));
    }

    private void handleClaim(@NotNull Claim claim, Map<String, AreaMarker> newmap) {
        double[] x;
        double[] z;
        Location l0 = claim.getLesserBoundaryCorner();
        Location l1 = claim.getGreaterBoundaryCorner();
        if(l0 == null) {
            return;
        }
        String wname = l0.getWorld() != null ?
                l0.getWorld().getName() : "";
        String owner = claim.isAdminClaim() ? ADMIN_ID : claim.getOwnerName();
        if (owner == null) owner = "unknown";

        /* Handle areas */
        if(!isVisible(owner, wname)) return;

        /* Make outline */
        x = new double[4];
        z = new double[4];
        x[0] = l0.getX();
        z[0] = l0.getZ();
        x[1] = l0.getX();
        z[1] = l1.getZ() + 1.0;
        x[2] = l1.getX() + 1.0;
        z[2] = l1.getZ() + 1.0;
        x[3] = l1.getX() + 1.0;
        z[3] = l0.getZ();
        Long id = claim.getID();
        String markerid = "GP_" + Long.toHexString(id);
        AreaMarker m = dynmapIntegration.resareas.remove(markerid); /* Existing area? */
        if(m == null) {
            m = dynmapIntegration.set.createAreaMarker(markerid, owner, false, wname, x, z, false);
            if(m == null) {
                return;
            }
        } else {
            m.setCornerLocations(x, z); /* Replace corner locations */
            m.setLabel(owner);   /* Update label */
        }
        if(dynmapIntegration.use3d) { /* If 3D? */
            m.setRangeY(l1.getY() + 1.0, l0.getY());
        }
        /* Set line and fill properties */
        addStyle(owner, m);

        /* Build popup */
        String desc = formatInfoWindow(claim);

        m.setDescription(desc); /* Set popup */

        /* Add to map */
        newmap.put(markerid, m);
    }

    private void addStyle(String owner, AreaMarker m) {
        if (owner == null) return;
        AreaStyle as = null;

        if(!dynmapIntegration.ownerstyle.isEmpty()) {
            as = dynmapIntegration.ownerstyle.get(owner.toLowerCase());
        }
        if(as == null) {
            as = dynmapIntegration.defstyle;
        }

        int sc = 0xFF0000;
        int fc = 0xFF0000;
        try {
            sc = Integer.parseInt(as.strokecolor.substring(1), 16);
            fc = Integer.parseInt(as.fillcolor.substring(1), 16);
        } catch(NumberFormatException ignored) { }

        m.setLineStyle(as.strokeweight, as.strokeopacity, sc);
        m.setFillStyle(as.fillopacity, fc);
        if(as.label != null) {
            m.setLabel(as.label);
        }
    }
    private boolean isVisible(String owner, String worldname) {
        if((dynmapIntegration.visible != null) && (dynmapIntegration.visible.size() > 0)) {
            if((!dynmapIntegration.visible.contains(owner)) && (!dynmapIntegration.visible.contains("world:" + worldname)) &&
                    (!dynmapIntegration.visible.contains(worldname + "/" + owner))) {
                return false;
            }
        }

        if((dynmapIntegration.hidden != null) && (dynmapIntegration.hidden.size() > 0)) {
            return !dynmapIntegration.hidden.contains(owner) && !dynmapIntegration.hidden.contains("world:" + worldname)
                    && !dynmapIntegration.hidden.contains(worldname + "/" + owner);
        }

        return true;
    }

    @NotNull
    private String formatInfoWindow(@NotNull Claim claim) {
        String v;
        if(claim.isAdminClaim()) {
            v = "<div class=\"regioninfo\">" + dynmapIntegration.admininfowindow + "</div>";
        } else {
            v = "<div class=\"regioninfo\">" + dynmapIntegration.infowindow + "</div>";
        }
        String ownerName = claim.getOwnerName();
        if (ownerName == null) ownerName = "";

        v = v.replace("%owner%", claim.isAdminClaim() ? ADMIN_ID : ownerName);
        v = v.replace("%area%", Integer.toString(claim.getArea()));
        ArrayList<String> builders = new ArrayList<>();
        ArrayList<String> containers = new ArrayList<>();
        ArrayList<String> accessors = new ArrayList<>();
        ArrayList<String> managers = new ArrayList<>();
        claim.getPermissions(builders, containers, accessors, managers);
        /* Build builders list */
        final StringBuilder accum = new StringBuilder();
        for(int i = 0; i < builders.size(); i++) {
            if(i > 0) {
                accum.append(", ");
            }
            String claimName = resolveClaimName(builders.get(i));

            accum.append(claimName);
        }
        v = v.replace("%builders%", accum.toString());
        /* Build containers list */
        accum.setLength(0);
        for(int i = 0; i < containers.size(); i++) {
            if(i > 0) {
                accum.append(", ");
            }
            accum.append(resolveClaimName(containers.get(i)));
        }
        v = v.replace("%containers%", accum.toString());
        /* Build accessors list */
        accum.setLength(0);
        for(int i = 0; i < accessors.size(); i++) {
            if(i > 0) {
                accum.append(", ");
            }
            accum.append(resolveClaimName(accessors.get(i)));
        }
        v = v.replace("%accessors%", accum.toString());
        /* Build managers list */
        accum.setLength(0);
        for(int i = 0; i < managers.size(); i++) {
            if(i > 0) {
                accum.append(", ");
            }
            accum.append(resolveClaimName(managers.get(i)));
        }
        v = v.replace("%managers%", accum.toString());

        return v;
    }

    private String resolveClaimName(final String claimName){
        return isStringUUID(claimName) ?
                resolvePlayernameFromId(claimName) :
                claimName;
    }

    private boolean isStringUUID(final String input){
        return this.idPattern.matcher(input).matches();
    }

    @NotNull
    private String resolvePlayernameFromId(final @NotNull String playerId){
        final UUID id = UUID.fromString(playerId);
        if (playerNameCache.containsKey(id)){
            return playerNameCache.get(id);
        }
        else {
            final Player player = Bukkit.getPlayer(playerId);
            String playerName;
            if (player != null) {
                playerName = player.getName();
                playerNameCache.put(id, playerName);
            }
            else {
                final OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(id);
                playerName = offlinePlayer.getName();
                playerNameCache.put(id, playerName);
            }

            return playerName != null ?
                    playerName : playerId;
        }
    }
}

