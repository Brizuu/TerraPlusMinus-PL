package de.btegermany.terraplusminus.commands;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import de.btegermany.terraplusminus.Terraplusminus;
import de.btegermany.terraplusminus.data.TerraConnector;
import de.btegermany.terraplusminus.gen.RealWorldGenerator;
import de.btegermany.terraplusminus.utils.ConfigurationHelper;
import de.btegermany.terraplusminus.utils.LinkedWorld;
import io.papermc.lib.PaperLib;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.buildtheearth.terraminusminus.generator.EarthGeneratorSettings;
import net.buildtheearth.terraminusminus.projection.GeographicProjection;
import net.buildtheearth.terraminusminus.projection.OutOfProjectionBoundsException;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static org.bukkit.ChatColor.RED;

public class TpllCommand implements BasicCommand {

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String[] args) {

        if (!(stack.getSender() instanceof Player)) {
            stack.getSender().sendMessage("This command can only be used by players!");
            return;
        }

        Player player = (Player) stack.getSender();
        if (!player.hasPermission("t+-.tpll")) {
            player.sendMessage(Terraplusminus.config.getString("prefix") + "§7No permission for /tpll");
            return;
        }

        // --- OBSŁUGA SELEKTORÓW ---
        if (args.length > 0 && (args[0].startsWith("@") || !isDouble(args[0].replace(",", "").replace("°", ""))) && player.hasPermission("t+-.forcetpll")) {
            handleEntitySelectors(player, args);
            return;
        }

        // --- PASSTHROUGH ---
        String passthroughTpll = Terraplusminus.config.getString("passthrough_tpll");
        if (passthroughTpll != null && !passthroughTpll.isEmpty()) {
            if (args.length == 0) {
                player.chat("/" + passthroughTpll + ":tpll");
            } else {
                player.chat("/" + passthroughTpll + ":tpll " + String.join(" ", args));
            }
            return;
        }

        if (args.length < 2) {
            player.sendMessage(RED + "Proper usage: /tpll <latitude> <longitude> [height (optional)]");
            return;
        }

        // --- PRZETWARZANIE WSPÓŁRZĘDNYCH ---
        World tpWorld = player.getWorld();
        double[] coordinates = new double[2];
        try {
            coordinates[1] = Double.parseDouble(args[0].replace(",", "").replace("°", ""));
            coordinates[0] = Double.parseDouble(args[1].replace("°", ""));
        } catch (NumberFormatException e) {
            player.sendMessage(RED + "Invalid coordinates!");
            return;
        }

        ChunkGenerator generator = tpWorld.getGenerator();
        if (!(generator instanceof RealWorldGenerator)) {
            player.sendMessage(Terraplusminus.config.getString("prefix") + RED + "The world generator must be set to Terraplusminus");
            return;
        }

        RealWorldGenerator terraGenerator = (RealWorldGenerator) generator;
        EarthGeneratorSettings generatorSettings = terraGenerator.getSettings();
        GeographicProjection projection = generatorSettings.projection();
        int yOffset = terraGenerator.getYOffset();

        double[] mcCoordinates;
        try {
            mcCoordinates = projection.fromGeo(coordinates[0], coordinates[1]);
        } catch (OutOfProjectionBoundsException e) {
            player.sendMessage(RED + "Location is not within projection bounds");
            return;
        }

        // Sprawdzanie uprawnień admina (granice pracy)
        if (!player.hasPermission("t+-.admin")) {
            double minLat = Terraplusminus.config.getDouble("min_latitude");
            double maxLat = Terraplusminus.config.getDouble("max_latitude");
            double minLon = Terraplusminus.config.getDouble("min_longitude");
            double maxLon = Terraplusminus.config.getDouble("max_longitude");
            if (minLat != 0 && maxLat != 0 && minLon != 0 && maxLon != 0) {
                if (coordinates[1] < minLat || coordinates[0] < minLon || coordinates[1] > maxLat || coordinates[0] > maxLon) {
                    player.sendMessage(Terraplusminus.config.getString("prefix") + RED + "Area restricted!");
                    return;
                }
            }
        }

        int xOffset = Terraplusminus.config.getInt("terrain_offset.x");
        int zOffset = Terraplusminus.config.getInt("terrain_offset.z");
        double targetX = mcCoordinates[0] + xOffset;
        double targetZ = mcCoordinates[1] + zOffset;

        if (args.length >= 3) {
            // Podano wysokość ręcznie
            double height = Double.parseDouble(args[2]) + yOffset;
            finalizeTeleport(player, tpWorld, mcCoordinates, height, xOffset, zOffset, coordinates, true);
        } else {
            // Automatyczne wykrywanie wysokości - asynchronicznie
            player.sendMessage(Terraplusminus.config.getString("prefix") + "§7Locating position...");

            tpWorld.getChunkAtAsync((int) targetX >> 4, (int) targetZ >> 4).thenAccept(chunk -> {
                int internalHeight = tpWorld.getHighestBlockYAt((int) targetX, (int) targetZ);

                if (internalHeight > tpWorld.getMinHeight() + 1) {
                    // Chunk już ma teren w grze
                    finalizeTeleport(player, tpWorld, mcCoordinates, internalHeight + 1.0, xOffset, zOffset, coordinates, true);
                } else {
                    // Pusty chunk - pytamy API
                    player.sendMessage(Terraplusminus.config.getString("prefix") + "§7Fetching elevation from API...");
                    TerraConnector terraConnector = new TerraConnector();
                    terraConnector.getHeight((int) mcCoordinates[0], (int) mcCoordinates[1])
                            .thenAcceptAsync(rawHeight -> {
                                double finalHeight = rawHeight + yOffset;
                                Bukkit.getScheduler().runTask(Terraplusminus.instance, () -> {
                                    finalizeTeleport(player, tpWorld, mcCoordinates, finalHeight, xOffset, zOffset, coordinates, false);
                                });
                            }).exceptionally(ex -> {
                                player.sendMessage(RED + "Error while fetching elevation from API!");
                                return null;
                            });
                }
            });
        }
    }

    private void finalizeTeleport(Player player, World tpWorld, double[] mcCoordinates, double height, int xOffset, int zOffset, double[] geoCoordinates, boolean hasCustomHeight) {

        if (height > tpWorld.getMaxHeight()) {
            handleLinkedWorlds(player, true, geoCoordinates, height, mcCoordinates, xOffset, zOffset);
            return;
        } else if (height <= tpWorld.getMinHeight()) {
            handleLinkedWorlds(player, false, geoCoordinates, height, mcCoordinates, xOffset, zOffset);
            return;
        }

        Location location = new Location(tpWorld, mcCoordinates[0] + xOffset, height, mcCoordinates[1] + zOffset, player.getLocation().getYaw(), player.getLocation().getPitch());

        // Klucz: Używamy tylko asynchronicznej metody bez blokujących sprawdzeń
        PaperLib.teleportAsync(player, location).thenAccept(success -> {
            if (success) {
                player.sendMessage(Terraplusminus.config.getString("prefix") + "§7Teleported to " + geoCoordinates[1] + ", " + geoCoordinates[0]);
            }
        });
    }

    private void handleLinkedWorlds(Player player, boolean isNext, double[] geo, double height, double[] mc, int xOff, int zOff) {
        if (!Terraplusminus.config.getBoolean("linked_worlds.enabled")) {
            player.sendMessage(Terraplusminus.config.getString("prefix") + RED + "World height limit reached!");
            return;
        }

        String method = Terraplusminus.config.getString("linked_worlds.method");
        if (method.equalsIgnoreCase("SERVER")) {
            sendPluginMessageToBungeeBridge(isNext, player, geo);
        } else if (method.equalsIgnoreCase("MULTIVERSE")) {
            LinkedWorld linked = isNext ? ConfigurationHelper.getNextServerName(player.getWorld().getName()) : ConfigurationHelper.getPreviousServerName(player.getWorld().getName());
            if (linked == null) {
                player.sendMessage(Terraplusminus.config.getString("prefix") + RED + "No linked world found!");
                return;
            }
            World linkedWorld = Bukkit.getWorld(linked.getWorldName());
            double newHeight = height + linked.getOffset();
            player.sendMessage(Terraplusminus.config.getString("prefix") + "§7Teleporting to linked world...");
            PaperLib.teleportAsync(player, new Location(linkedWorld, mc[0] + xOff, newHeight, mc[1] + zOff, player.getLocation().getYaw(), player.getLocation().getPitch()));
        }
    }

    private void handleEntitySelectors(Player player, String[] args) {
        if (args[0].equals("@a")) {
            Terraplusminus.instance.getServer().getOnlinePlayers().forEach(p -> p.chat("/tpll " + String.join(" ", args).substring(3)));
            player.sendMessage(Terraplusminus.config.getString("prefix") + "§7Teleporting all players...");
        } else if (args[0].equals("@p")) {
            Player nearest = null;
            double dist = Double.MAX_VALUE;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.equals(player) && p.getLocation().distanceSquared(player.getLocation()) < dist) {
                    nearest = p;
                    dist = p.getLocation().distanceSquared(player.getLocation());
                }
            }
            if (nearest != null) nearest.chat("/tpll " + String.join(" ", args).substring(3));
        } else {
            Player target = Bukkit.getPlayer(args[0]);
            if (target != null) {
                target.chat("/tpll " + String.join(" ", args).replace(target.getName(), ""));
            }
        }
    }

    private static void sendPluginMessageToBungeeBridge(boolean isNextServer, Player player, double[] coordinates) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(player.getUniqueId().toString());
        LinkedWorld server = isNextServer ? ConfigurationHelper.getNextServerName(Bukkit.getServer().getName()) : ConfigurationHelper.getPreviousServerName(Bukkit.getServer().getName());
        if (server != null) {
            out.writeUTF(server.getWorldName() + ", " + server.getOffset());
            out.writeUTF(coordinates[1] + ", " + coordinates[0]);
            player.sendPluginMessage(Terraplusminus.instance, "bungeecord:terraplusminus", out.toByteArray());
            player.sendMessage(Terraplusminus.config.getString("prefix") + "§cSending to another server...");
        }
    }

    public boolean isDouble(String str) {
        try {
            Double.parseDouble(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}