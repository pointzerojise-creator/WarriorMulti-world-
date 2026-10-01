package com.warrior.multiworld;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class WarriorMultiWorld extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String PREFIX = ChatColor.GOLD + "[WarriorMulti-world] " + ChatColor.RESET;
    private static final List<String> TYPES = Arrays.asList("NORMAL", "NETHER", "END", "FLAT", "VOID");
    private static final List<String> SUBS = Arrays.asList("create", "delete", "tp", "list", "setmode", "setpvp", "reload");

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("wmw").setExecutor(this);
        getCommand("wmw").setTabCompleter(this);
        // Load worlds after the server has finished starting
        Bukkit.getScheduler().runTask(this, this::loadConfiguredWorlds);
    }

    // ---------------------------------------------------------------- worlds

    private void loadConfiguredWorlds() {
        ConfigurationSection sec = getConfig().getConfigurationSection("worlds");
        if (sec == null) return;
        for (String name : sec.getKeys(false)) {
            String type = sec.getString(name + ".type", "NORMAL");
            World w = Bukkit.getWorld(name);
            if (w == null) w = buildWorld(name, type);
            if (w == null) {
                getLogger().warning("Could not load world: " + name);
                continue;
            }
            w.setPVP(sec.getBoolean(name + ".pvp", true));
            getLogger().info("Loaded world " + name + " (" + type + ", " + sec.getString(name + ".gamemode", "SURVIVAL") + ")");
        }
    }

    private World buildWorld(String name, String typeStr) {
        WorldCreator wc = new WorldCreator(name);
        switch (typeStr.toUpperCase(Locale.ROOT)) {
            case "NETHER":
                wc.environment(World.Environment.NETHER);
                break;
            case "END":
                wc.environment(World.Environment.THE_END);
                break;
            case "FLAT":
                wc.type(WorldType.FLAT);
                break;
            case "VOID":
                wc.generator(new VoidGenerator());
                break;
            default:
                wc.environment(World.Environment.NORMAL);
        }
        return wc.createWorld();
    }

    /** Empty world with a single spawn point. */
    private static class VoidGenerator extends ChunkGenerator {
        @Override
        public Location getFixedSpawnLocation(World world, java.util.Random random) {
            return new Location(world, 0.5, 64, 0.5);
        }
    }

    private GameMode modeOf(World world) {
        String s = getConfig().getString("worlds." + world.getName() + ".gamemode");
        if (s == null) return null;
        try {
            return GameMode.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void applyMode(Player p) {
        if (p.hasPermission("warriormultiworld.bypass")) return;
        GameMode gm = modeOf(p.getWorld());
        if (gm != null && p.getGameMode() != gm) p.setGameMode(gm);
    }

    // ---------------------------------------------------------------- events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        applyMode(e.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        applyMode(e.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> applyMode(p));
    }

    // -------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) {
            s.sendMessage(PREFIX + "/wmw <create|delete|tp|list|setmode|setpvp|reload>");
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        boolean admin = s.hasPermission("warriormultiworld.admin");

        switch (sub) {
            case "list": {
                s.sendMessage(PREFIX + "Worlds:");
                for (World w : Bukkit.getWorlds()) {
                    GameMode gm = modeOf(w);
                    s.sendMessage(ChatColor.YELLOW + " - " + w.getName() + ChatColor.GRAY + " ["
                            + w.getEnvironment() + ", " + (gm == null ? "default" : gm) + ", players: "
                            + w.getPlayers().size() + "]");
                }
                return true;
            }
            case "tp": {
                if (!s.hasPermission("warriormultiworld.tp")) return noPerm(s);
                if (a.length < 2) return usage(s, "/wmw tp <world> [player]");
                World w = Bukkit.getWorld(a[1]);
                if (w == null) return err(s, "World not found.");
                Player target;
                if (a.length >= 3) {
                    if (!admin) return noPerm(s);
                    target = Bukkit.getPlayerExact(a[2]);
                    if (target == null) return err(s, "Player not found.");
                } else if (s instanceof Player) {
                    target = (Player) s;
                } else {
                    return err(s, "Console must specify a player.");
                }
                target.teleport(w.getSpawnLocation());
                s.sendMessage(PREFIX + "Teleported " + target.getName() + " to " + w.getName() + ".");
                return true;
            }
            case "create": {
                if (!admin) return noPerm(s);
                if (a.length < 2) return usage(s, "/wmw create <name> [normal|nether|end|flat|void] [gamemode]");
                String name = a[1];
                if (!name.matches("[A-Za-z0-9_\\-]+")) return err(s, "Use only letters, numbers, _ and -.");
                if (Bukkit.getWorld(name) != null) return err(s, "That world already exists.");
                String type = a.length >= 3 ? a[2].toUpperCase(Locale.ROOT) : "NORMAL";
                if (!TYPES.contains(type)) return err(s, "Type must be one of " + TYPES);
                String gm = "SURVIVAL";
                if (a.length >= 4) {
                    GameMode parsed = parseMode(a[3]);
                    if (parsed == null) return err(s, "Invalid gamemode.");
                    gm = parsed.name();
                }
                s.sendMessage(PREFIX + "Creating world " + name + "...");
                World w = buildWorld(name, type);
                if (w == null) return err(s, "World creation failed.");
                getConfig().set("worlds." + name + ".type", type);
                getConfig().set("worlds." + name + ".gamemode", gm);
                getConfig().set("worlds." + name + ".pvp", true);
                saveConfig();
                s.sendMessage(PREFIX + ChatColor.GREEN + "World " + name + " created (" + type + ", " + gm + ").");
                return true;
            }
            case "delete": {
                if (!admin) return noPerm(s);
                if (a.length < 2) return usage(s, "/wmw delete <world>");
                World w = Bukkit.getWorld(a[1]);
                if (w == null) return err(s, "World not found.");
                if (w.equals(Bukkit.getWorlds().get(0))) return err(s, "You cannot delete the main world.");
                World fallback = Bukkit.getWorlds().get(0);
                for (Player p : new ArrayList<>(w.getPlayers())) p.teleport(fallback.getSpawnLocation());
                File folder = w.getWorldFolder();
                String name = w.getName();
                if (!Bukkit.unloadWorld(w, false)) return err(s, "Could not unload world.");
                deleteFolder(folder);
                getConfig().set("worlds." + name, null);
                saveConfig();
                s.sendMessage(PREFIX + ChatColor.GREEN + "World " + name + " deleted.");
                return true;
            }
            case "setmode": {
                if (!admin) return noPerm(s);
                if (a.length < 3) return usage(s, "/wmw setmode <world> <survival|creative|adventure|spectator>");
                World w = Bukkit.getWorld(a[1]);
                if (w == null) return err(s, "World not found.");
                GameMode gm = parseMode(a[2]);
                if (gm == null) return err(s, "Invalid gamemode.");
                getConfig().set("worlds." + w.getName() + ".gamemode", gm.name());
                saveConfig();
                for (Player p : w.getPlayers()) applyMode(p);
                s.sendMessage(PREFIX + ChatColor.GREEN + w.getName() + " gamemode set to " + gm + ".");
                return true;
            }
            case "setpvp": {
                if (!admin) return noPerm(s);
                if (a.length < 3) return usage(s, "/wmw setpvp <world> <true|false>");
                World w = Bukkit.getWorld(a[1]);
                if (w == null) return err(s, "World not found.");
                boolean pvp = Boolean.parseBoolean(a[2]);
                w.setPVP(pvp);
                getConfig().set("worlds." + w.getName() + ".pvp", pvp);
                saveConfig();
                s.sendMessage(PREFIX + ChatColor.GREEN + "PvP in " + w.getName() + " set to " + pvp + ".");
                return true;
            }
            case "reload": {
                if (!admin) return noPerm(s);
                reloadConfig();
                loadConfiguredWorlds();
                s.sendMessage(PREFIX + ChatColor.GREEN + "Config reloaded.");
                return true;
            }
            default:
                return usage(s, "/wmw <create|delete|tp|list|setmode|setpvp|reload>");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        if (a.length == 1) return filter(SUBS, a[0]);
        String sub = a[0].toLowerCase(Locale.ROOT);
        List<String> worlds = Bukkit.getWorlds().stream().map(World::getName).collect(Collectors.toList());
        if (a.length == 2 && Arrays.asList("delete", "tp", "setmode", "setpvp").contains(sub)) return filter(worlds, a[1]);
        if (a.length == 3 && sub.equals("create")) return filter(TYPES, a[2]);
        if (a.length == 3 && sub.equals("setmode")) return filter(modes(), a[2]);
        if (a.length == 4 && sub.equals("create")) return filter(modes(), a[3]);
        if (a.length == 3 && sub.equals("setpvp")) return filter(Arrays.asList("true", "false"), a[2]);
        return new ArrayList<>();
    }

    // --------------------------------------------------------------- helpers

    private List<String> modes() {
        return Arrays.asList("survival", "creative", "adventure", "spectator");
    }

    private List<String> filter(List<String> list, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return list.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(p)).collect(Collectors.toList());
    }

    private GameMode parseMode(String s) {
        try {
            return GameMode.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void deleteFolder(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteFolder(c);
        f.delete();
    }

    private boolean noPerm(CommandSender s) {
        s.sendMessage(PREFIX + ChatColor.RED + "You don't have permission.");
        return true;
    }

    private boolean err(CommandSender s, String m) {
        s.sendMessage(PREFIX + ChatColor.RED + m);
        return true;
    }

    private boolean usage(CommandSender s, String m) {
        s.sendMessage(PREFIX + ChatColor.YELLOW + "Usage: " + m);
        return true;
    }
}
