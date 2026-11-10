<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHWorldCommands

*Garde des commandes en attente jusqu'à ce que le joueur soit dans le bon monde, puis les exécute.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Garde des commandes en attente jusqu'à ce que le joueur soit dans le bon monde, puis les exécute.

Utilisé sur : **Opale**.

## Mode réseau (plusieurs Opale)

Avec **SHNetwork** chargé et `reseau.actif: true` (par défaut), les commandes en attente sont communes à tous les serveurs. Sans SHNetwork, rien ne change (`commands.yml`).

- **Joueur connecté sur un autre serveur** : `/shwc` y est relancée (`runFor` de SHNetwork) ; ce serveur exécute ou met en attente selon le monde du joueur.
- **Commande en attente** : dans MariaDB (table `shwc_pending`). Quand le joueur entre dans un monde autorisé (connexion ou changement de monde), sur n'importe quel serveur, ce serveur réclame ses commandes par un UPDATE conditionnel (un seul serveur les obtient), les exécute puis les retire. Un joueur reparti ou sorti du monde entre-temps les retrouve à la prochaine occasion. Un monde qui n'existe que sur un serveur (spawn, arène, events sur Opale-1 dans la variante C) ne déclenche donc que là ; `SuperiorWorld` existe sur tous.
- **Réclamations sans suite** (serveur arrêté pendant l'exécution) : signalées une fois dans la console du meneur (`leader` SHNetwork, tâche `shworldcommands`) pour une vérification à la main, jamais relancées d'office.
- **Reprise** : au premier démarrage en mode réseau, `commands.yml` est repris une seule fois pour tout le réseau, puis chaque serveur renomme le sien en `commands.yml.avant-reseau`. Base injoignable : le plugin se désactive au lieu de retomber sur le fichier local.

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/shwc` | — | Stocke ou exécute immédiatement une commande pour un joueur selon son monde | `shworldcommands.use` |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shworldcommands.use` | Permet d’utiliser la commande /shwc | `op` |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHWorldCommands/
├── <a href="src/main/resources/commands.yml">commands.yml</a>
├── <a href="src/main/resources/config.yml">config.yml</a>
└── <a href="src/main/resources/messages.yml">messages.yml</a>
</pre>

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
