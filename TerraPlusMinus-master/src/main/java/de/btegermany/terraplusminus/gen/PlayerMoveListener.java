package de.btegermany.terraplusminus.gen;

import de.btegermany.terraplusminus.Terraplusminus;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class PlayerMoveListener implements Listener {

    private final Set<UUID> msgCooldown = new HashSet<>();
    // Zapobiega zbyt częstym próbom regeneracji tego samego chunka
    private final Set<Long> retryCooldown = new HashSet<>();

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX() &&
                event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        Location to = event.getTo();
        if (to == null) return;

        int cx = to.getBlockX() >> 4;
        int cz = to.getBlockZ() >> 4;
        long chunkKey = ((long) cx << 32) | (cz & 0xFFFFFFFFL);

        if (ChunkStatusCache.isFailed(cx, cz)) {
            // 1. Zablokuj gracza
            event.setTo(event.getFrom());

            UUID uuid = event.getPlayer().getUniqueId();
            if (!msgCooldown.contains(uuid)) {
                event.getPlayer().sendMessage("§c§l[!] §7Teren przed Tobą nie załadował się. Próbuję naprawić...");
                msgCooldown.add(uuid);
                org.bukkit.Bukkit.getScheduler().runTaskLater(Terraplusminus.instance, () -> msgCooldown.remove(uuid), 80L);
            }

            // 2. Spróbuj naprawić chunk (jeśli nie trwa już próba naprawy)
            if (!retryCooldown.contains(chunkKey)) {
                retryCooldown.add(chunkKey);

                World world = to.getWorld();

                // Uruchamiamy naprawę z małym opóźnieniem (1 sekunda), by nie lagować natychmiastowo
                // Uruchamiamy naprawę z małym opóźnieniem (1 sekunda)
                org.bukkit.Bukkit.getScheduler().runTaskLater(Terraplusminus.instance, () -> {
                    // 1. Usuwamy błąd z cache, aby generator dostał drugą szansę
                    ChunkStatusCache.removeFailure(cx, cz);

                    if (world != null) {
                        // 2. Wyładowujemy chunk BEZ zapisywania (false, false)
                        // To sprawi, że serwer "zapomni" o pustym/zepsutym chunku
                        world.unloadChunk(cx, cz, false);

                        // 3. Prosimy serwer o ponowne wczytanie chunka.
                        // Ponieważ nie ma go w pamięci, serwer odpali Twój RealWorldGenerator.
                        world.getChunkAtAsync(cx, cz, chunk -> {
                            // Opcjonalnie: wymuszamy wysłanie pakietów do graczy w pobliżu
                            // (W nowszych wersjach dzieje się to automatycznie)
                        });
                    }

                    // 4. Po 5 sekundach pozwalamy na kolejną próbę naprawy
                    org.bukkit.Bukkit.getScheduler().runTaskLater(Terraplusminus.instance, () -> retryCooldown.remove(chunkKey), 100L);
                }, 200L);
            }
        }
    }
}