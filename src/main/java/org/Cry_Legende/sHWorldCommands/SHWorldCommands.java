package org.Cry_Legende.sHWorldCommands;

import org.Cry_Legende.sHWorldCommands.network.NetBus;
import org.Cry_Legende.sHWorldCommands.network.ShNetworkBus;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
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
    private volatile FileConfiguration messages;
    // Section 'reseau' lue au démarrage : un reload qui la voit changer prévient qu'un redémarrage est nécessaire
    private Map<String, Object> startNetworkSettings = Map.of();
    // Sous-commandes de /shworldcommands (jamais lues via l'alias shwc, qui garde l'ancienne syntaxe <joueur> <commande>)
    private static final List<String> SUBCOMMANDS = List.of("run", "reload", "list", "clear");
    private static final String USE = "shworldcommands.use";
    private static final int LIST_LIMIT = 30;
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
        startNetworkSettings = networkSettings();

        if (getConfig().getBoolean("reseau.actif", true) && getServer().getPluginManager().isPluginEnabled("SHNetwork")) {
            // Sur le réseau, jamais de retour à commands.yml : sur un serveur copié d'un autre, il contient des
            // commandes déjà données ailleurs.
            if (!enableNetwork()) {
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }

        getServer().getPluginManager().registerEvents(this, this);

        PluginCommand pc = getCommand("shworldcommands");
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
        YamlConfiguration loaded;
        try {
            loaded = ReloadException.read(file);
        } catch (ReloadException e) {
            getLogger().warning("messages.yml illisible, textes du jar utilisés : " + e.getMessage());
            loaded = new YamlConfiguration();
        }
        applyMessages(loaded);
    }

    private void applyMessages(YamlConfiguration loaded) {
        try (InputStream bundled = getResource("messages.yml")) {
            if (bundled != null) {
                loaded.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(bundled, StandardCharsets.UTF_8)));
            }
        } catch (IOException ignored) {
            // messages du fichier seuls
        }
        messages = loaded;
    }

    private Map<String, Object> networkSettings() {
        ConfigurationSection section = getConfig().getConfigurationSection("reseau");
        return section == null ? Map.of() : section.getValues(true);
    }

    /**
     * Relit config.yml et messages.yml. Un fichier illisible laisse tout en l'état : l'erreur (fichier, ligne,
     * problème) est donnée à l'expéditeur et à la console. commands.yml (les commandes en attente) n'est jamais relu.
     */
    private void reloadFiles(CommandSender sender) {
        YamlConfiguration loadedMessages;
        try {
            ReloadException.read(new File(getDataFolder(), "config.yml"));
            loadedMessages = ReloadException.read(new File(getDataFolder(), "messages.yml"));
        } catch (ReloadException e) {
            getLogger().warning("Rechargement refusé, ancienne configuration conservée : " + e.getMessage());
            sender.sendMessage(message("reload-failed")
                .replace("%file%", e.file()).replace("%line%", e.line()).replace("%error%", e.problem()));
            return;
        }
        reloadConfig();
        applyMessages(loadedMessages);
        sender.sendMessage(message("reload"));
        if (!networkSettings().equals(startNetworkSettings)) {
            sender.sendMessage(message("reload-network"));
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
        // Via l'alias shwc, le premier mot est toujours un joueur : l'ancienne syntaxe ne change pas
        boolean legacyAlias = label.substring(label.lastIndexOf(':') + 1).equalsIgnoreCase("shwc");
        String first = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (!legacyAlias) {
            if (args.length == 0) {
                sendUsageLine(sender);
                return true;
            }
            if (SUBCOMMANDS.contains(first)) {
                subcommand(sender, first, args);
                return true;
            }
        }
        return run(sender, args);
    }

    private void subcommand(CommandSender sender, String sub, String[] args) {
        switch (sub) {
            case "run" -> run(sender, Arrays.copyOfRange(args, 1, args.length));
            case "reload" -> {
                if (sender.hasPermission("shworldcommands.admin.reload")) {
                    reloadFiles(sender);
                } else {
                    sender.sendMessage(message("pas-de-permission"));
                }
            }
            case "list" -> {
                if (sender.hasPermission("shworldcommands.admin.list")) {
                    listPending(sender, args.length > 1 ? args[1] : null);
                } else {
                    sender.sendMessage(message("pas-de-permission"));
                }
            }
            default -> {
                if (!sender.hasPermission("shworldcommands.admin.clear")) {
                    sender.sendMessage(message("pas-de-permission"));
                } else if (args.length < 2) {
                    sender.sendMessage(message("usage-clear"));
                } else {
                    clearPending(sender, args[1]);
                }
            }
        }
    }

    /** Sous-commandes que l'expéditeur a le droit d'utiliser. */
    private List<String> allowedSubcommands(CommandSender sender) {
        List<String> allowed = new ArrayList<>();
        if (sender.hasPermission(USE)) {
            allowed.add("run");
        }
        for (String sub : List.of("reload", "list", "clear")) {
            if (sender.hasPermission("shworldcommands.admin." + sub)) {
                allowed.add(sub);
            }
        }
        return allowed;
    }

    private void sendUsageLine(CommandSender sender) {
        List<String> allowed = allowedSubcommands(sender);
        if (allowed.isEmpty()) {
            sender.sendMessage(message("pas-de-permission"));
        } else {
            sender.sendMessage(message("usage-line").replace("%commands%", String.join("|", allowed)));
        }
    }

    /** Joueur d'une commande en attente : en ligne, ou connu du cache du serveur (jamais de recherche réseau). */
    private UUID resolveUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached == null ? null : cached.getUniqueId();
    }

    private String nameOf(String uuid) {
        try {
            String name = Bukkit.getOfflinePlayer(UUID.fromString(uuid)).getName();
            return name != null ? name : uuid;
        } catch (IllegalArgumentException e) {
            return uuid;
        }
    }

    /** /shworldcommands list [joueur] : commandes en attente (mode fichier ; en mode réseau elles sont en base). */
    private void listPending(CommandSender sender, String playerName) {
        if (network != null) {
            sender.sendMessage(message("network-unavailable"));
            return;
        }
        synchronized (commandsLock) {
            if (playerName != null) {
                UUID uuid = resolveUuid(playerName);
                List<String> pending = uuid == null ? List.of() : commandsData.getStringList(uuid.toString());
                if (pending.isEmpty()) {
                    sender.sendMessage(message("list-none-player").replace("%player%", playerName));
                    return;
                }
                sender.sendMessage(message("list-player-header")
                    .replace("%player%", playerName).replace("%count%", String.valueOf(pending.size())));
                pending.forEach(command -> sender.sendMessage(message("list-command").replace("%command%", command)));
                return;
            }
            List<String> owners = new ArrayList<>();
            int total = 0;
            for (String key : commandsData.getKeys(false)) {
                int count = commandsData.getStringList(key).size();
                if (count > 0) {
                    owners.add(key);
                    total += count;
                }
            }
            if (owners.isEmpty()) {
                sender.sendMessage(message("list-empty"));
                return;
            }
            sender.sendMessage(message("list-header")
                .replace("%total%", String.valueOf(total)).replace("%players%", String.valueOf(owners.size())));
            int shown = Math.min(owners.size(), LIST_LIMIT);
            for (int i = 0; i < shown; i++) {
                String key = owners.get(i);
                sender.sendMessage(message("list-entry").replace("%player%", nameOf(key))
                    .replace("%count%", String.valueOf(commandsData.getStringList(key).size())));
            }
            if (owners.size() > shown) {
                sender.sendMessage(message("list-more").replace("%more%", String.valueOf(owners.size() - shown)));
            }
        }
    }

    /** /shworldcommands clear <joueur> : supprime les commandes en attente d'un joueur. */
    private void clearPending(CommandSender sender, String playerName) {
        if (network != null) {
            sender.sendMessage(message("network-unavailable"));
            return;
        }
        UUID uuid = resolveUuid(playerName);
        int removed = 0;
        synchronized (commandsLock) {
            if (uuid != null) {
                removed = commandsData.getStringList(uuid.toString()).size();
                if (removed > 0) {
                    commandsData.set(uuid.toString(), null);
                    saveCommandsData();
                }
            }
        }
        if (removed == 0) {
            sender.sendMessage(message("list-none-player").replace("%player%", playerName));
            return;
        }
        getLogger().info(sender.getName() + " a supprimé " + removed + " commande(s) en attente de " + playerName + ".");
        sender.sendMessage(message("clear-done")
            .replace("%count%", String.valueOf(removed)).replace("%player%", playerName));
    }

    /** /shworldcommands run <joueur> <commande>, et l'ancienne forme /shwc <joueur> <commande>. */
    private boolean run(CommandSender sender, String[] args) {
        if (!sender.hasPermission(USE)) {
            sender.sendMessage(message("pas-de-permission"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(message("usage-run"));
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
        boolean legacyAlias = alias.substring(alias.lastIndexOf(':') + 1).equalsIgnoreCase("shwc");
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String typed = args[0].toLowerCase(Locale.ROOT);
            if (!legacyAlias) {
                allowedSubcommands(sender).stream().filter(sub -> sub.startsWith(typed)).forEach(out::add);
            }
            if (sender.hasPermission(USE)) {
                addPlayers(out, typed);
            }
        } else if (args.length == 2 && !legacyAlias && SUBCOMMANDS.contains(args[0].toLowerCase(Locale.ROOT))
            && allowedSubcommands(sender).contains(args[0].toLowerCase(Locale.ROOT)) && !args[0].equalsIgnoreCase("reload")) {
            addPlayers(out, args[1].toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static void addPlayers(List<String> out, String typed) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(typed)) {
                out.add(online.getName());
            }
        }
    }
}
