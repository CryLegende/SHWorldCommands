package org.Cry_Legende.sHWorldCommands;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class SHWorldCommands extends JavaPlugin
    implements Listener, CommandExecutor, TabCompleter {

    // On utilise la config directement, pas besoin de la stocker en variable de classe si on n'y accède que depuis la classe principale
    private File commandsFile;
    private FileConfiguration commandsData;
    private final Map<UUID, Long> lastMessageTime = new HashMap<>();
    private final Object commandsLock = new Object();

    @Override
    public void onEnable() {
        saveDefaultConfig(); // Crée et charge config.yml

        commandsFile = new File(getDataFolder(), "commands.yml");
        if (!commandsFile.exists()) {
            try {
                commandsFile.getParentFile().mkdirs();
                commandsFile.createNewFile();
                YamlConfiguration.loadConfiguration(commandsFile).save(commandsFile);
            } catch (IOException e) {
                getLogger().severe("Impossible de créer commands.yml : " + e.getMessage());
            }
        }
        commandsData = YamlConfiguration.loadConfiguration(commandsFile);

        getServer().getPluginManager().registerEvents(this, this);

        PluginCommand pc = getCommand("shwc");
        if (pc != null) {
            pc.setExecutor(this);
            pc.setTabCompleter(this);
        }

        getLogger().info("SHWorldCommands activé.");
    }

    @Override
    public void onDisable() {
        saveCommandsData();
        getLogger().info("SHWorldCommands désactivé.");
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        checkAndExecute(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> checkAndExecute(event.getPlayer()), 20L);
    }

    private void checkAndExecute(Player player) {
        UUID uuid = player.getUniqueId();
        List<String> cmds;
        synchronized (commandsLock) {
            cmds = commandsData.getStringList(uuid.toString());
            if (cmds.isEmpty() || !isInWhitelistedWorld(player.getWorld())) {
                return;
            }
            for (String cmd : cmds) {
                Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(),
                    cmd.replace("%player%", player.getName())
                );
            }
            commandsData.set(uuid.toString(), null);
            saveCommandsData();
        }
    }

    private boolean isInWhitelistedWorld(World world) {
        return getConfig().getStringList("whitelisted-worlds")
            .contains(world.getName());
    }

    private void saveCommandsData() {
        synchronized (commandsLock) {
            try {
                commandsData.save(commandsFile);
            } catch (IOException e) {
                getLogger().severe("Erreur lors de la sauvegarde de commands.yml : " + e.getMessage());
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("shworldcommands.use")) {
            sender.sendMessage(ChatColor.RED + "Vous n'avez pas la permission.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /shwc <player> <command>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Joueur introuvable ou déconnecté.");
            return true;
        }

        UUID uuid = target.getUniqueId();
        String commandToStore = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        if (isInWhitelistedWorld(target.getWorld())) {
            Bukkit.dispatchCommand(
                Bukkit.getConsoleSender(),
                commandToStore.replace("%player%", target.getName())
            );
        } else {
            synchronized (commandsLock) {
                List<String> list = commandsData.getStringList(uuid.toString());
                list.add(commandToStore);
                commandsData.set(uuid.toString(), list);
                saveCommandsData();
            }

            long last = lastMessageTime.getOrDefault(uuid, 0L);
            if (System.currentTimeMillis() - last >= 10_000L) { // Cooldown de 10s
                sendBlacklistNotification(target); // Appel de la nouvelle méthode de notification
                lastMessageTime.put(uuid, System.currentTimeMillis());
            }
        }
        return true;
    }

    /**
     * Nouvelle méthode dédiée à l'envoi des notifications
     * Gère l'affichage du message, titre, sous-titre et actionbar en se basant sur la config.
     * N'envoie rien si la chaîne de configuration est vide.
     *
     * @param player Le joueur à qui envoyer la notification.
     */
    private void sendBlacklistNotification(Player player) {
        FileConfiguration config = getConfig();

        // Traduction des couleurs pour tous les messages
        String message = config.getString("notification.message", "");
        String title = config.getString("notification.title", "");
        String subtitle = config.getString("notification.subtitle", "");
        String actionbar = config.getString("notification.actionbar", "");

        // Envoi du message dans le chat s'il n'est pas vide
        if (!message.isEmpty()) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        }

        // Envoi de l'actionbar s'il n'est pas vide
        if (!actionbar.isEmpty()) {
            String coloredActionbar = ChatColor.translateAlternateColorCodes('&', actionbar);
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(coloredActionbar));
        }

        // Envoi du titre et du sous-titre s'ils ne sont pas tous les deux vides
        if (!title.isEmpty() || !subtitle.isEmpty()) {
            String coloredTitle = ChatColor.translateAlternateColorCodes('&', title);
            String coloredSubtitle = ChatColor.translateAlternateColorCodes('&', subtitle);

            // On récupère les durées depuis la config, avec des valeurs par défaut au cas où
            // Multiplié par 20 pour convertir les secondes en ticks (unité de Minecraft)
            int fadeIn = config.getInt("notification.title-timing.fade-in", 1) * 20;
            int stay = config.getInt("notification.title-timing.stay", 5) * 20;
            int fadeOut = config.getInt("notification.title-timing.fade-out", 1) * 20;

            player.sendTitle(coloredTitle, coloredSubtitle, fadeIn, stay, fadeOut);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        return Collections.emptyList();
    }
}
