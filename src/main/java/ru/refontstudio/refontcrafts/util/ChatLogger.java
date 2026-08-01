package ru.refontstudio.refontcrafts.util;

import java.util.Locale;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import ru.refontstudio.refontcrafts.RefontCrafts;

public class ChatLogger extends Handler {
    @Override
    public void publish(LogRecord r) {
        if (r == null) return;
        String m = r.getMessage();
        if (m == null || m.isEmpty()) return;

        String ml = m.toLowerCase(Locale.ROOT);
        if (ml.startsWith("enabling ") || ml.startsWith("disabling ")
                || ml.contains("has been enabled") || ml.contains("has been disabled")) return;

        ChatLog.send(RefontCrafts.getInstance().prefix() + "&7" + m);
        if (r.getThrown() != null) {
            Throwable t = r.getThrown();
            int lines = 0;
            ChatLog.send(RefontCrafts.getInstance().prefix() + "&c" + t.toString());
            for (StackTraceElement el : t.getStackTrace()) {
                if (lines >= 8) break;
                ChatLog.send(RefontCrafts.getInstance().prefix() + "&7  at " + el.toString());
                lines++;
            }
        }
    }
    @Override public void flush() {}
    @Override public void close() throws SecurityException {}
}
