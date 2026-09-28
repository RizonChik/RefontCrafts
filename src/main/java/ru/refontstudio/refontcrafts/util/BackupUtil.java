package ru.refontstudio.refontcrafts.util;

import ru.refontstudio.refontcrafts.RefontCrafts;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class BackupUtil {
    public static void writeSnapshot(RefontCrafts plugin, List<String> shapelessLines, List<String> anvilLines, String sourceTag) {
        try {
            File dir = new File(plugin.getDataFolder(), "backups");
            if (!dir.exists()) dir.mkdirs();
            String name = "snapshot-" + sourceTag + "-" + System.currentTimeMillis() + ".txt";
            File out = new File(dir, name);
            try (FileWriter fw = new FileWriter(out, false)) {
                fw.write("# shapeless\n");
                for (String s : shapelessLines) fw.write(s + "\n");
                fw.write("# anvil\n");
                for (String s : anvilLines) fw.write(s + "\n");
            }
        } catch (Throwable error) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not write recipe snapshot", error);
        }
    }

    public static void appendPending(RefontCrafts plugin, String line) {
        try {
            File dir = new File(plugin.getDataFolder(), "backups");
            if (!dir.exists()) dir.mkdirs();
            String day = new SimpleDateFormat("yyyyMMdd").format(new Date());
            File out = new File(dir, "pending-" + day + ".txt");
            try (FileWriter fw = new FileWriter(out, true)) {
                fw.write(line + "\n");
            }
        } catch (Throwable error) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not write pending recipe backup", error);
        }
    }
}
