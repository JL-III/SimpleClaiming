package me.ryanhamshire.GriefPrevention.dynmap;

import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;

import java.util.*;

public class DynmapIntegration {

    private static final long TWO_SECONDS_IN_TICKS = 20L * 2L;
    private static final String DEF_INFOWINDOW = "div class=\"infowindow\">Claim Owner: <span style=\"font-weight:bold;\">%owner%</span><br/>Permission Trust: <span style=\"font-weight:bold;\">%managers%</span><br/>Trust: <span style=\"font-weight:bold;\">%builders%</span><br/>Container Trust: <span style=\"font-weight:bold;\">%containers%</span><br/>Access Trust: <span style=\"font-weight:bold;\">%accessors%</span></div>";
    private static final String DEF_ADMININFOWINDOW = "<div class=\"infowindow\"><span style=\"font-weight:bold;\">Administrator Claim</span><br/>Permission Trust: <span style=\"font-weight:bold;\">%managers%</span><br/>Trust: <span style=\"font-weight:bold;\">%builders%</span><br/>Container Trust: <span style=\"font-weight:bold;\">%containers%</span><br/>Access Trust: <span style=\"font-weight:bold;\">%accessors%</span></div>";
    static final String ADMIN_ID = "administrator";
    Plugin dynmap;
    DynmapAPI api;
    MarkerAPI markerapi;
    GriefPrevention griefPrevention;

    MarkerSet set;
    boolean use3d;
    String infowindow;
    String admininfowindow;
    AreaStyle defstyle;
    Map<String, AreaStyle> ownerstyle;
    Set<String> visible;
    Set<String> hidden;
    int maxdepth;

    private UpdateProcessing updateProcessing;

    private boolean reload = false;

    Map<String, AreaMarker> resareas;

    public DynmapIntegration(GriefPrevention plugin) {
        this.griefPrevention = plugin;
        resareas = new HashMap<>();
        updateProcessing = new UpdateProcessing(plugin, this);
        api = (DynmapAPI) dynmap;
        activate();
    }

    public void onDisable() {
        Bukkit.getLogger().info("Cancelling tasks...");
        Bukkit.getServer().getScheduler().cancelTasks(griefPrevention);

        if(set != null) {
            Bukkit.getLogger().info("Deleting marker set...");
            set.deleteMarkerSet();
            set = null;
        }

        Bukkit.getLogger().info("Clearing areas...");
        resareas.clear();

        Bukkit.getLogger().info("Disabled successfully.");
    }

    private void activate() {
        /* Get markers API */
        markerapi = api.getMarkerAPI();
        if(markerapi == null) {
            Bukkit.getLogger().severe("Unable to load dynmap marker API!");

            return;
        }

//        /* Load configuration */
//        if(reload) {
//            reloadConfig();
//            if(set != null) {
//                set.deleteMarkerSet();
//                set = null;
//            }
//            resareas.clear();
//        } else {
//            reload = true;
//        }
//
//        getConfig().options().copyDefaults(true);   /* Load defaults, if needed */
//        saveConfig();  /* Save updates, if needed */

        /* Add marker set for mobs (make it transient) */
        set = markerapi.getMarkerSet("griefprevention.markerset");
        if(set == null) {
            set = markerapi.createMarkerSet(
                    "griefprevention.markerset",
                    getConfig().getString("layer.name", "GriefPrevention"),
                    null,
                    false);
        } else {
            set.setMarkerSetLabel(getConfig().getString("layer.name", "GriefPrevention"));
        }

        if(set == null) {
            getLogger().severe("Unable to create marker set!");
            disablePlugin();
            return;
        }

        int minzoom = getConfig().getInt("layer.minzoom", 0);
        if(minzoom > 0) {
            set.setMinZoom(minzoom);
        }
        set.setLayerPriority(getConfig().getInt("layer.layerprio", 10));
        set.setHideByDefault(getConfig().getBoolean("layer.hidebydefault", false));
        use3d = getConfig().getBoolean("use3dregions", false);
        infowindow = getConfig().getString("infowindow", DEF_INFOWINDOW);
        admininfowindow = getConfig().getString("adminclaiminfowindow", DEF_ADMININFOWINDOW);
        maxdepth = getConfig().getInt("maxdepth", 16);

        /* Get style information */
        defstyle = new AreaStyle(getConfig(), "regionstyle");
        ownerstyle = new HashMap<>();
        ConfigurationSection sect = getConfig().getConfigurationSection("ownerstyle");
        if(sect != null) {
            Set<String> ids = sect.getKeys(false);

            for(String id : ids) {
                ownerstyle.put(id.toLowerCase(),
                        new AreaStyle(getConfig(), "ownerstyle." + id, defstyle));
            }
        }
        List<String> vis = getConfig().getStringList("visibleregions");
        visible = new HashSet<>(vis);
        List<String> hid = getConfig().getStringList("hiddenregions");
        hidden = new HashSet<>(hid);

        startUpdateTask();

        getLogger().info("Activated successfully.");
    }

    /*
    Repeatedly calls updateClaims (with a delay of course).
    This task cancels when onDisable() is called.
     */
    private void startUpdateTask() {
        final var updatePeriod = 20L * Math.max(15L,
                getConfig().getLong("update.period", 300L));

        new BukkitRunnable() {
            @Override
            public void run() {
                updateProcessing.updateClaims();
            }
        }.runTaskTimerAsynchronously(this, TWO_SECONDS_IN_TICKS, updatePeriod);
    }
}
