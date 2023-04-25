package me.ryanhamshire.GriefPrevention.managers;

import org.bukkit.configuration.file.FileConfiguration;

public class ConfigManager {

    FileConfiguration config;

    public ConfigManager(FileConfiguration config) {
        this.config = config;
    }

    public FileConfiguration getConfig() {
        return config;
    }

}
