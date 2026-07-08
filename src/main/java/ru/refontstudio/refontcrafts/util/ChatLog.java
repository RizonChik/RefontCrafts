package ru.refontstudio.refontcrafts.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class ChatLog {
    public static void send(String msg) {
        String m = Text.color(msg);
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("refontcrafts.notify") || p.hasPermission("refontcrafts.admin")) p.sendMessage(m);
    }
}