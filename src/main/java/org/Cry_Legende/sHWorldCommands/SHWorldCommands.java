package org.Cry_Legende.sHWorldCommands;

import org.Cry_Legende.sHWorldCommands.network.NetBus;
import org.Cry_Legende.sHWorldCommands.network.ShNetworkBus;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;

public class SHWorldCommands extends JavaPlugin
    implements Listener, CommandExecutor, TabCompleter {

    // On utilise la config directement, pas besoin de la stocker en variable de classe si on n'y accède que depuis la classe principale
    private File commandsFile;
    private FileConfiguration commandsData;
    private final Map<UUID, Long> lastMessageTime = new HashMap<>();
    private final Object commandsLock = new Object();
    private FileConfiguration messages;
    // Mode réseau (SHNetwork présent) : commandes en attente dans une base commune ; null sur un serveur seul
    private NetworkPending network;

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
        loadMessages();

        if (getConfig().getBoolean("reseau.actif", true) && getServer().getPluginManager().isPluginEnabled("SHNetwork")) {
            // Sur le réseau, jamais de retour à commands.yml : sur un serveur copié d'un autre, il contient des
            // commandes déjà données ailleurs.
            if (!enableNetwork()) {
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }

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
        if (network != null) {
            network.disable();
            network = null;
            getLogger().info("SHWorldCommands désactivé.");
            return;
        }
        saveCommandsData();
        getLogger().info("SHWorldCommands désactivé.");
    }

    /** messages.yml, avec ceux du jar par défaut pour les clés absentes du fichier du serveur. */
    private void loadMessages() {
        File file = new File(getDataFolder(), "messages.yml");
        if (!file.exists()) {
            saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(file);
        try (InputStream bundled = getResource("messages.yml")) {
            if (bundled != null) {
                messages.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(bundled, StandardCharsets.UTF_8)));
            }
        } catch (IOException ignored) {
            // messages du fichier seuls
        }
    }

    private String message(String key) {
        return ChatColor.translateAlternateColorCodes('&', messages.getString(key, ""));
    }

    /**
     * Mode réseau : commandes en attente dans MariaDB (table shwc_pending), communes à tous les serveurs. Les commandes
     * de commands.yml y sont reprises une seule fois pour tout le réseau (premier serveur démarré), puis chaque serveur
     * met son fichier de côté.
     */
    private boolean enableNetwork() {
        PendingStore store = null;
        try {
            NetBus bus = ShNetworkBus.open();
            store = new PendingStore(getConfig().getString("reseau.base-de-donnees.url", ""),
                getConfig().getString("reseau.base-de-donnees.utilisateur", ""),
                getConfig().getString("reseau.base-de-donnees.mot-de-passe", ""),
                Math.max(500, getConfig().getInt("reseau.base-de-donnees.delai-ms", 3000)));
            store.createTables();
            Map<UUID, List<String>> pending = new LinkedHashMap<>();
            for (String key : commandsData.getKeys(false)) {
                try {
                    List<String> commands = commandsData.getStringList(key);
                    if (!commands.isEmpty()) {
                        pending.put(UUID.fromString(key), commands);
                    }
                } catch (IllegalArgumentException e) {
                    getLogger().warning("UUID invalide dans commands.yml : " + key);
                }
            }
            int imported = store.importOnce(pending, bus.serverName());
            if (imported >= 0) {
                getLogger().info("commands.yml repris dans la base commune : " + imported + " commande(s).");
            }
            retireYaml();
            commandsData = new YamlConfiguration();
            NetworkPending pendingNetwork = new NetworkPending(this, bus, store);
            pendingNetwork.enable();
            this.network = pendingNetwork;
            getLogger().info("SHNetwork détecté : commandes en attente communes à tout le réseau (MariaDB).");
            return true;
        } catch (Exception | LinkageError e) {
            if (store != null) {
                store.close();
            }
            getLogger().log(Level.SEVERE, "Mode réseau impossible (SHNetwork ou base injoignable) : SHWorldCommands désactivé sur ce serveur.", e);
            return false;
        }
    }

    /** commands.yml mis de côté une fois la base commune en place : il ne sera plus jamais relu. */
    private void retireYaml() {
        if (!commandsFile.isFile()) {
            return;
        }
        File target = new File(commandsFile.getParentFile(), commandsFile.getName() + ".avant-reseau");
        if (target.exists()) {
            target = new File(commandsFile.getParentFile(), commandsFile.getName() + ".avant-reseau-" + System.currentTimeMillis());
        }
        if (commandsFile.renameTo(target)) {
            getLogger().info("commands.yml mis de côté : " + target.getName());
        } else {
            getLogger().warning("commands.yml non renommé : à mettre de côté à la main avant tout retour sans SHNetwork.");
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        checkAndExecute(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> checkAndExecute(event.getPlayer()), 20L);
    }

    // Vide le cooldown de notification : sans ça la map garde une entrée par joueur pour toujours
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastMessageTime.remove(event.getPlayer().getUniqueId());
    }

    private void checkAndExecute(Player player) {
        if (network != null) {
            if (player.isOnline() && isInWhitelistedWorld(player.getWorld())) {
                network.claimAndRun(player);
            }
            return;
        }
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

    boolean isInWhitelistedWorld(World world) {
        return getConfig().getStringList("whitelisted-worlds")
            .contains(world.getName());
    }

    private void saveCommandsData() {
        if (network != null) {
            return; // mode réseau : les commandes sont en base, commands.yml n'est plus écrit
        }
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
            sender.sendMessage(message("pas-de-permission"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(message("usage"));
            return true;
        }

        String commandToStore = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            // Mode réseau : joueur connecté sur un autre serveur, /shwc y est relancée
            if (network != null && network.forward(args[0], commandToStore)) {
                return true;
            }
            sender.sendMessage(message("joueur-introuvable"));
            return true;
        }

        UUID uuid = target.getUniqueId();

        if (isInWhitelistedWorld(target.getWorld())) {
            Bukkit.dispatchCommand(
                Bukkit.getConsoleSender(),
                commandToStore.replace("%player%", target.getName())
            );
            return true;
        }

        if (network != null) {
            // Mode réseau : la commande attend dans la base commune, quel que soit le serveur où le joueur ira
            network.store(target, commandToStore);
        } else {
            synchronized (commandsLock) {
                List<String> list = commandsData.getStringList(uuid.toString());
                list.add(commandToStore);
                commandsData.set(uuid.toString(), list);
                saveCommandsData();
            }
        }

        long last = lastMessageTime.getOrDefault(uuid, 0L);
        if (System.currentTimeMillis() - last >= 10_000L) { // Cooldown de 10s
            sendBlacklistNotification(target); // Appel de la nouvelle méthode de notification
            lastMessageTime.put(uuid, System.currentTimeMillis());
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
