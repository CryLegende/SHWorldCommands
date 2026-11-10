package org.Cry_Legende.sHWorldCommands;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Commandes en attente en MariaDB (table {@code shwc_pending}), communes à tous les serveurs. Chargée seulement en mode
 * réseau : HikariCP et le pilote MariaDB viennent de {@code libraries:} dans plugin.yml.
 *
 * <p>Les commandes d'un joueur sont réclamées par un seul serveur (UPDATE conditionnel qui pose un jeton), exécutées
 * puis supprimées. Toutes les méthodes bloquent : jamais sur le fil principal.</p>
 */
final class PendingStore {

    static final String TABLE = "shwc_pending";
    private static final String STATE = "shwc_state";

    /** Commande réclamée, prête à être exécutée. */
    record Claimed(long id, String command) {
    }

    /** Réclamation restée en place (serveur arrêté entre la réclamation et la suppression). */
    record Stale(long id, String uuid, String name, String command, String server, long claimedAt) {
    }

    private final HikariDataSource pool;

    PendingStore(String url, String user, String password, int timeoutMs) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("SHWorldCommands");
        hikari.setJdbcUrl(url);
        hikari.setUsername(user);
        hikari.setPassword(password);
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.addDataSourceProperty("connectTimeout", String.valueOf(timeoutMs));
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(timeoutMs));
        hikari.setMaximumPoolSize(3);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(timeoutMs);
        hikari.setKeepaliveTime(60_000);
        this.pool = new HikariDataSource(hikari);
    }

    Connection connection() throws SQLException {
        return pool.getConnection();
    }

    void close() {
        pool.close();
    }

    void createTables() throws SQLException {
        try (Connection c = connection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,"
                + "uuid CHAR(36) NOT NULL,"
                + "name VARCHAR(16) NULL,"
                + "command TEXT NOT NULL,"
                + "created_at BIGINT NOT NULL,"
                + "origin VARCHAR(32) NOT NULL,"
                + "claim_token CHAR(36) NULL,"
                + "claimed_by VARCHAR(32) NULL,"
                + "claimed_at BIGINT NULL,"
                + "PRIMARY KEY (id),"
                + "KEY idx_uuid (uuid, claim_token),"
                + "KEY idx_token (claim_token)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + STATE + " ("
                + "k VARCHAR(64) NOT NULL,"
                + "v VARCHAR(255) NULL,"
                + "PRIMARY KEY (k)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        }
    }

    /**
     * Reprend une seule fois, pour tout le réseau, les commandes en attente de commands.yml (premier serveur démarré en
     * mode réseau ; drapeau {@code import_yaml} posé dans la même transaction que la copie).
     *
     * @return nombre de commandes reprises, ou -1 si la reprise avait déjà été faite
     */
    int importOnce(Map<UUID, List<String>> pending, String server) throws SQLException {
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + STATE + " (k, v) VALUES ('import_yaml', ?)")) {
                    ps.setString(1, server + " " + System.currentTimeMillis());
                    if (ps.executeUpdate() == 0) {
                        c.rollback();
                        return -1;
                    }
                }
                int copied = 0;
                long now = System.currentTimeMillis();
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + TABLE + " (uuid, name, command, created_at, origin) VALUES (?, NULL, ?, ?, ?)")) {
                    for (Map.Entry<UUID, List<String>> entry : pending.entrySet()) {
                        for (String command : entry.getValue()) {
                            ps.setString(1, entry.getKey().toString());
                            ps.setString(2, command);
                            ps.setLong(3, now);
                            ps.setString(4, server);
                            ps.addBatch();
                            copied++;
                        }
                    }
                    ps.executeBatch();
                }
                c.commit();
                return copied;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    void insert(UUID uuid, String name, String command, String origin) throws SQLException {
        try (Connection c = connection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO " + TABLE + " (uuid, name, command, created_at, origin) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, command);
            ps.setLong(4, System.currentTimeMillis());
            ps.setString(5, origin);
            ps.executeUpdate();
        }
    }

    /** Réclame les commandes libres du joueur pour ce serveur et les rend dans l'ordre d'arrivée. */
    List<Claimed> claim(UUID uuid, String server, String token) throws SQLException {
        List<Claimed> out = new ArrayList<>();
        try (Connection c = connection()) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + TABLE + " SET claim_token = ?, claimed_by = ?, claimed_at = ? "
                + "WHERE uuid = ? AND claim_token IS NULL")) {
                ps.setString(1, token);
                ps.setString(2, server);
                ps.setLong(3, System.currentTimeMillis());
                ps.setString(4, uuid.toString());
                if (ps.executeUpdate() == 0) {
                    return out;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT id, command FROM " + TABLE + " WHERE claim_token = ? ORDER BY id")) {
                ps.setString(1, token);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Claimed(rs.getLong(1), rs.getString(2)));
                    }
                }
            }
        }
        return out;
    }

    int delete(String token) throws SQLException {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement("DELETE FROM " + TABLE + " WHERE claim_token = ?")) {
            ps.setString(1, token);
            return ps.executeUpdate();
        }
    }

    int release(String token) throws SQLException {
        try (Connection c = connection();
             PreparedStatement ps = c.prepareStatement("UPDATE " + TABLE + " SET claim_token = NULL, claimed_by = NULL, claimed_at = NULL WHERE claim_token = ?")) {
            ps.setString(1, token);
            return ps.executeUpdate();
        }
    }

    List<Stale> stale(long olderThan) throws SQLException {
        List<Stale> out = new ArrayList<>();
        try (Connection c = connection();
             PreparedStatement ps = c.prepareStatement("SELECT id, uuid, name, command, claimed_by, claimed_at FROM " + TABLE
                 + " WHERE claim_token IS NOT NULL AND claimed_at < ?")) {
            ps.setLong(1, olderThan);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Stale(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6)));
                }
            }
        }
        return out;
    }
}
