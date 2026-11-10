package org.Cry_Legende.sHWorldCommands;

import org.Cry_Legende.sHWorldCommands.network.NetBus;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Commandes de monde en mode réseau (SHNetwork présent) : une seule liste d'attente pour tous les serveurs, en MariaDB.
 *
 * <ul>
 *   <li>Joueur connecté sur un autre serveur : /shwc y est relancé ({@code runFor}), c'est ce serveur qui exécute ou
 *   met en attente selon le monde où se trouve le joueur.</li>
 *   <li>Joueur ici hors des mondes autorisés : la commande attend dans la base commune.</li>
 *   <li>Joueur qui arrive dans un monde autorisé (connexion ou changement de monde), sur n'importe quel serveur : ce
 *   serveur réclame ses commandes (une seule fois pour tout le réseau), les exécute puis les retire. Un monde qui
 *   n'existe que sur un serveur (variante C : spawn, arène, events sur Opale-1) n'y déclenche donc que là.</li>
 * </ul>
 */
final class NetworkPending {

    private static final String LEADER_TASK = "shworldcommands";
    private static final long CHECK_INTERVAL_TICKS = 20L * 60L * 60L;
    private static final long STALE_MILLIS = TimeUnit.MINUTES.toMillis(10);

    private final SHWorldCommands plugin;
    private final NetBus bus;
    private final PendingStore store;
    private final Set<Long> staleWarned = ConcurrentHashMap.newKeySet();
    private NetBus.Leader leader;
    private BukkitTask checkTask;

    NetworkPending(SHWorldCommands plugin, NetBus bus, PendingStore store) {
        this.plugin = plugin;
        this.bus = bus;
        this.store = store;
    }

    void enable() {
        leader = bus.leader(LEADER_TASK, this::checkStale, () -> {
        });
        checkTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (leader != null && leader.isLeader()) {
                checkStale();
            }
        }, CHECK_INTERVAL_TICKS, CHECK_INTERVAL_TICKS);
    }

    void disable() {
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
        if (leader != null) {
            leader.release();
            leader = null;
        }
        store.close();
    }

    private void async(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    private void sync(Runnable task) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * /shwc pour un joueur qui n'est pas sur ce serveur : relancée sur le sien.
     *
     * @return faux s'il n'est connecté nulle part
     */
    boolean forward(String name, String command) {
        NetBus.Online online = bus.find(name);
        if (online == null || online.server().equals(bus.serverName())) {
            return false;
        }
        bus.runFor(online.uuid(), "shwc " + online.name() + " " + command);
        return true;
    }

    /** Joueur ici, hors des mondes autorisés : la commande attend dans la base commune. Thread principal. */
    void store(Player target, String command) {
        UUID uuid = target.getUniqueId();
        String name = target.getName();
        String origin = bus.serverName();
        async(() -> {
            try {
                store.insert(uuid, name, command, origin);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Commande perdue pour " + name + " (base commune injoignable) : " + command, e);
                return;
            }
            // Le joueur a pu entrer dans un monde autorisé pendant l'écriture
            sync(() -> {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && plugin.isInWhitelistedWorld(player.getWorld())) {
                    claimAndRun(player);
                }
            });
        });
    }

    /** Joueur ici dans un monde autorisé : ses commandes en attente sont réclamées puis exécutées. Thread principal. */
    void claimAndRun(Player player) {
        UUID uuid = player.getUniqueId();
        String token = UUID.randomUUID().toString();
        String server = bus.serverName();
        async(() -> {
            List<PendingStore.Claimed> claimed;
            try {
                claimed = store.claim(uuid, server, token);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Commandes en attente de " + uuid + " illisibles, réessai au prochain changement de monde", e);
                return;
            }
            if (!claimed.isEmpty()) {
                sync(() -> run(uuid, token, claimed));
            }
        });
    }

    private void run(UUID uuid, String token, List<PendingStore.Claimed> claimed) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline() || !plugin.isInWhitelistedWorld(player.getWorld())) {
            // Reparti ou sorti du monde entre-temps : les commandes redeviennent libres
            async(() -> {
                try {
                    store.release(token);
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "Commandes de " + uuid + " restées réclamées (jeton " + token + ") : à libérer à la main", e);
                }
            });
            return;
        }
        for (PendingStore.Claimed pending : claimed) {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), pending.command().replace("%player%", player.getName()));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Commande en attente en erreur pour " + player.getName() + " (" + pending.command() + ") : " + e.getMessage());
            }
        }
        async(() -> {
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    store.delete(token);
                    return;
                } catch (SQLException e) {
                    if (attempt == 2) {
                        plugin.getLogger().log(Level.SEVERE, "Commandes exécutées pour " + uuid + " mais pas retirées (jeton " + token
                            + ") : à supprimer à la main, elles ne seront pas relancées", e);
                    }
                }
            }
        });
    }

    /** Réclamations restées sans suite (serveur arrêté pendant l'exécution) : signalées une fois, jamais relancées d'office. */
    private void checkStale() {
        async(() -> {
            try {
                for (PendingStore.Stale stale : store.stale(System.currentTimeMillis() - STALE_MILLIS)) {
                    if (staleWarned.add(stale.id())) {
                        plugin.getLogger().severe("Commande réclamée par " + stale.server() + " sans suite pour " + (stale.name() != null ? stale.name() : stale.uuid())
                            + " (ligne " + stale.id() + " de " + PendingStore.TABLE + ") : " + stale.command()
                            + ". Serveur arrêté pendant l'exécution : vérifier si elle a été donnée, puis supprimer la ligne ou vider son claim_token.");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Contrôle des commandes en attente impossible", e);
            }
        });
    }
}
