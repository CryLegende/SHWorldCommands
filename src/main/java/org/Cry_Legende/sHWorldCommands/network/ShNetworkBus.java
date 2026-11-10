package org.Cry_Legende.sHWorldCommands.network;

import org.Cry_Legende.sHNetwork.api.LeaderHandle;
import org.Cry_Legende.sHNetwork.api.NetPlayer;
import org.Cry_Legende.sHNetwork.api.SHNetworkAPI;

import java.util.Optional;
import java.util.UUID;

/** Pont vers SHNetwork. Cette classe n'est chargée que si SHNetwork est installé. */
public final class ShNetworkBus implements NetBus {

    private final SHNetworkAPI api;

    private ShNetworkBus() {
        this.api = SHNetworkAPI.get();
    }

    /** Point d'entrée : le plugin ne résout cette classe qu'à cet appel, donc jamais sans SHNetwork. */
    public static NetBus open() {
        return new ShNetworkBus();
    }

    @Override
    public String serverName() {
        return api.serverName();
    }

    @Override
    public Online find(String name) {
        Optional<NetPlayer> found = api.find(name);
        return found.map(p -> new Online(p.uuid(), p.name(), p.server())).orElse(null);
    }

    @Override
    public void runFor(UUID player, String command) {
        api.runFor(player.toString(), command);
    }

    @Override
    public Leader leader(String task, Runnable onAcquire, Runnable onLose) {
        LeaderHandle handle = api.leader(task, onAcquire, onLose);
        return new Leader() {
            @Override
            public boolean isLeader() {
                return handle.isLeader();
            }

            @Override
            public void release() {
                handle.release();
            }
        };
    }
}
