package org.Cry_Legende.sHWorldCommands.network;

import java.util.UUID;

/**
 * Ce dont les commandes de monde en réseau ont besoin de SHNetwork. Seule {@link ShNetworkBus} connaît SHNetwork : sans
 * lui, aucune classe qui touche à son API n'est chargée.
 */
public interface NetBus {

    /** Candidature à une tâche de meneur. */
    interface Leader {

        boolean isLeader();

        void release();
    }

    /** Joueur connecté quelque part sur le réseau. */
    record Online(UUID uuid, String name, String server) {
    }

    /** Nom de ce serveur sur le réseau. */
    String serverName();

    /** Joueur connecté sur le réseau (pseudo sans tenir compte de la casse), ou null. */
    Online find(String name);

    /** Commande console exécutée là où se trouve le joueur (ou à sa prochaine connexion), une seule fois. */
    void runFor(UUID player, String command);

    /** Se porte candidat pour une tâche qui ne tourne que sur un serveur à la fois. */
    Leader leader(String task, Runnable onAcquire, Runnable onLose);
}
