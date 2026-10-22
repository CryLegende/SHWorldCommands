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
└── <a href="src/main/resources/config.yml">config.yml</a>
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
