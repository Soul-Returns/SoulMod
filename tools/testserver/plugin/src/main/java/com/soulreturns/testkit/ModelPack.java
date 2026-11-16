package com.soulreturns.testkit;

import com.sun.net.httpserver.HttpServer;
import org.bukkit.entity.Player;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * {@code /hx forge pack on|off}: a tiny server resource pack in namespace {@code hypixel_skyblock} that
 * defines item models the way Hypixel's pack does ({@code items/item/uncategorized/<name>.json}), with our
 * own flat placeholder textures, to test the mod's model lookup for unseen forge items. Built in memory and
 * served from 127.0.0.1 on an ephemeral port; the test client accepts server packs (servers.dat).
 */
final class ModelPack {
    /** Model name → placeholder colour (a framed square, nothing like Hypixel's art). */
    private static final Map<String, Color> MODELS = Map.of(
        "refined_diamond", new Color(0x2EC4F0),
        "refined_titanium", new Color(0xE0E0E8),
        "gemstone_mixture", new Color(0xE040A0),
        "titanium", new Color(0x9090A0));

    private HttpServer server;
    private byte[] zip;
    private byte[] sha1;

    String send(Player player) {
        try {
            if (server == null) start();
        } catch (IOException e) {
            throw new IllegalArgumentException("could not serve the pack: " + e.getMessage());
        }
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/pack.zip";
        player.setResourcePack(url, sha1);
        return "sent " + MODELS.size() + " hypixel_skyblock item models (" + zip.length + " bytes) from " + url;
    }

    String remove(Player player) {
        player.removeResourcePacks();
        return "server resource packs removed";
    }

    void stop() {
        if (server != null) server.stop(0);
        server = null;
    }

    private void start() throws IOException {
        zip = build();
        try {
            sha1 = MessageDigest.getInstance("SHA-1").digest(zip);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/pack.zip", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/zip");
            ex.sendResponseHeaders(200, zip.length);
            try (var out = ex.getResponseBody()) {
                out.write(zip);
            }
        });
        server.start();
    }

    private static byte[] build() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var z = new ZipOutputStream(bytes)) {
            put(z, "pack.mcmeta", "{\"pack\":{\"description\":\"SoulMod test: synthetic hypixel_skyblock item models\","
                + "\"pack_format\":88,\"min_format\":88,\"max_format\":88}}");
            for (var e : MODELS.entrySet()) {
                String name = e.getKey();
                String ref = "hypixel_skyblock:item/uncategorized/" + name;
                put(z, "assets/hypixel_skyblock/items/item/uncategorized/" + name + ".json",
                    "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + ref + "\"}}");
                put(z, "assets/hypixel_skyblock/models/item/uncategorized/" + name + ".json",
                    "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"" + ref + "\"}}");
                z.putNextEntry(new ZipEntry("assets/hypixel_skyblock/textures/item/uncategorized/" + name + ".png"));
                z.write(texture(e.getValue()));
                z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] texture(Color fill) throws IOException {
        var img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 2; y < 14; y++) {
            for (int x = 2; x < 14; x++) {
                boolean edge = x == 2 || y == 2 || x == 13 || y == 13;
                img.setRGB(x, y, (edge ? Color.BLACK : fill).getRGB());
            }
        }
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static void put(ZipOutputStream z, String path, String text) throws IOException {
        z.putNextEntry(new ZipEntry(path));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }
}
