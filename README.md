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
| `/shworldcommands run <joueur> <commande>` | `/shwc <joueur> <commande>` | Exécute la commande en console tout de suite si le joueur est dans un monde autorisé, sinon la met en attente et le prévient. `/shworldcommands <joueur> <commande>` marche aussi ; via `/shwc` le premier mot est toujours un joueur. | `shworldcommands.use` |
| `/shworldcommands reload` | — | Recharge `config.yml` et `messages.yml` (jamais `commands.yml`). Un fichier YAML invalide laisse l'ancienne configuration en place et affiche le fichier et la ligne de l'erreur. | `shworldcommands.admin.reload` |
| `/shworldcommands list [joueur]` | — | Commandes en attente, au total ou pour un joueur. Mode fichier seulement. | `shworldcommands.admin.list` |
| `/shworldcommands clear <joueur>` | — | Supprime les commandes en attente d'un joueur. Mode fichier seulement. | `shworldcommands.admin.clear` |

Toutes les commandes marchent en console. Avec SHNetwork, les commandes en attente sont en base : `list` et `clear` répondent qu'elles sont indisponibles en mode réseau.

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shworldcommands.use` | Utiliser `/shworldcommands run` et `/shwc` | `op` |
| `shworldcommands.admin` | Toutes les sous-commandes d'administration (`reload`, `list`, `clear`) | `op` |
| `shworldcommands.admin.reload` | `/shworldcommands reload` | `op` |
| `shworldcommands.admin.list` | `/shworldcommands list` | `op` |
| `shworldcommands.admin.clear` | `/shworldcommands clear` | `op` |

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
