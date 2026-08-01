package ru.refontstudio.refontcrafts.libs;

import ru.refontstudio.refontcrafts.RefontCrafts;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * Downloads and isolates JDBC libraries at runtime so the distributable plugin JAR stays small.
 *
 * Bukkit 1.8 does not understand plugin.yml's libraries section, therefore this loader cannot be
 * replaced with the modern server-side dependency loader while keeping one JAR for every version.
 */
public final class LibraryManager implements Closeable {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final long MAX_LIBRARY_SIZE = 64L * 1024L * 1024L;

    private static final Dependency SQLITE = new Dependency(
            "sqlite-jdbc-3.46.1.0.jar",
            "6dc7464e3803648d3ff18a7359bab6adf079fcd8495b18991f6f5edcb8ac6e3b",
            "org/xerial/sqlite-jdbc/3.46.1.0/sqlite-jdbc-3.46.1.0.jar"
    );
    private static final Dependency SLF4J_API = new Dependency(
            "slf4j-api-1.7.36.jar",
            "d3ef575e3e4979678dc01bf1dcce51021493b4d11fb7f1be8ad982877c16a1c0",
            "org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar"
    );
    private static final Dependency MYSQL = new Dependency(
            "mysql-connector-j-8.0.33.jar",
            "e2a3b2fc726a1ac64e998585db86b30fa8bf3f706195b78bb77c5f99bf877bd9",
            "com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar"
    );

    private final RefontCrafts plugin;
    private final File directory;

    private URLClassLoader sqliteLoader;
    private URLClassLoader mysqlLoader;
    private Driver sqliteDriver;
    private Driver mysqlDriver;

    public LibraryManager(RefontCrafts plugin) {
        this.plugin = plugin;
        this.directory = new File(plugin.getDataFolder(), "libraries");
    }

    /** Prepares only the backend selected in config. Other backends remain lazy. */
    public void prepare(String backend) throws SQLException {
        if ("mysql".equalsIgnoreCase(backend)) {
            mysqlDriver();
        } else {
            sqliteDriver();
        }
    }

    public synchronized Driver sqliteDriver() throws SQLException {
        if (sqliteDriver != null) return sqliteDriver;
        try {
            File sqlite = ensure(SQLITE);
            File slf4j = ensure(SLF4J_API);
            sqliteLoader = createLoader(sqlite, slf4j);
            sqliteDriver = instantiateDriver(sqliteLoader, "org.sqlite.JDBC");
            return sqliteDriver;
        } catch (Exception error) {
            closeQuietly(sqliteLoader);
            sqliteLoader = null;
            throw asSqlException("Could not prepare SQLite JDBC library", error);
        }
    }

    public synchronized Driver mysqlDriver() throws SQLException {
        if (mysqlDriver != null) return mysqlDriver;
        try {
            File mysql = ensure(MYSQL);
            mysqlLoader = createLoader(mysql);
            mysqlDriver = instantiateDriver(mysqlLoader, "com.mysql.cj.jdbc.Driver");
            return mysqlDriver;
        } catch (Exception error) {
            closeQuietly(mysqlLoader);
            mysqlLoader = null;
            throw asSqlException("Could not prepare MySQL JDBC library", error);
        }
    }

    public File directory() {
        return directory;
    }

    private File ensure(Dependency dependency) throws IOException {
        ensureDirectory();
        File target = new File(directory, dependency.fileName);

        if (target.isFile()) {
            if (dependency.sha256.equalsIgnoreCase(sha256(target))) return target;
            plugin.getLogger().warning("Library checksum mismatch, downloading a clean copy: " + dependency.fileName);
            if (!target.delete()) {
                throw new IOException("Could not remove invalid library: " + target.getAbsolutePath());
            }
        }

        IOException failure = null;
        for (String base : repositoryBases()) {
            String source = base + dependency.mavenPath;
            try {
                download(source, target, dependency);
                plugin.getLogger().info("Downloaded library: " + dependency.fileName);
                return target;
            } catch (IOException error) {
                failure = error;
                plugin.getLogger().warning("Library download failed from " + host(source) + ": " + safeMessage(error));
            }
        }

        IOException result = new IOException(
                "Could not download " + dependency.fileName + ". Put the verified file into "
                        + directory.getAbsolutePath());
        if (failure != null) result.initCause(failure);
        throw result;
    }

    private void download(String source, File target, Dependency dependency) throws IOException {
        URL url = new URL(source);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Refusing non-HTTPS library URL");
        }

        File temporary = new File(directory, dependency.fileName + ".part-" + Long.toHexString(System.nanoTime()));
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "RefontCrafts/" + plugin.getDescription().getVersion());
            connection.setRequestProperty("Accept", "application/java-archive, application/octet-stream;q=0.9, */*;q=0.1");

            if (connection instanceof HttpsURLConnection) {
                // Uses the JVM trust store and hostname verification. Do not install permissive TLS handlers.
                ((HttpsURLConnection) connection).setUseCaches(false);
            }

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + status + " " + connection.getResponseMessage());
            }

            long declaredLength = connection.getContentLengthLong();
            if (declaredLength > MAX_LIBRARY_SIZE) {
                throw new IOException("Remote file is unexpectedly large: " + declaredLength + " bytes");
            }

            MessageDigest digest = digest();
            long written = 0L;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(temporary))) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    written += read;
                    if (written > MAX_LIBRARY_SIZE) {
                        throw new IOException("Downloaded file exceeded the size limit");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }

            if (written <= 0L) throw new IOException("Downloaded file is empty");
            String actual = hex(digest.digest());
            if (!dependency.sha256.equalsIgnoreCase(actual)) {
                throw new IOException("SHA-256 mismatch: expected " + dependency.sha256 + ", got " + actual);
            }

            moveAtomically(temporary, target);
        } finally {
            if (connection != null) connection.disconnect();
            if (temporary.exists() && !temporary.equals(target) && !temporary.delete()) {
                temporary.deleteOnExit();
            }
        }
    }

    private URLClassLoader createLoader(File... files) throws IOException {
        URL[] urls = new URL[files.length];
        for (int i = 0; i < files.length; i++) urls[i] = files[i].toURI().toURL();
        return new URLClassLoader(urls, plugin.getClass().getClassLoader());
    }

    private Driver instantiateDriver(ClassLoader loader, String className) throws Exception {
        Class<?> type = Class.forName(className, true, loader);
        Object instance = type.newInstance();
        if (!(instance instanceof Driver)) {
            throw new SQLException(className + " does not implement java.sql.Driver");
        }
        return (Driver) instance;
    }

    private void ensureDirectory() throws IOException {
        if (directory.isDirectory()) return;
        if (directory.exists()) throw new IOException("Library path is not a directory: " + directory.getAbsolutePath());
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Could not create library directory: " + directory.getAbsolutePath());
        }
    }

    private static void moveAtomically(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private static MessageDigest digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static List<String> repositoryBases() {
        List<String> bases = new ArrayList<String>(2);
        bases.add("https://repo.maven.apache.org/maven2/");
        bases.add("https://repo1.maven.org/maven2/");
        return bases;
    }

    private static String host(String source) {
        try {
            return new URL(source).getHost();
        } catch (Exception ignored) {
            return source;
        }
    }

    private static SQLException asSqlException(String message, Throwable cause) {
        if (cause instanceof SQLException) return (SQLException) cause;
        return new SQLException(message + ": " + safeMessage(cause), cause);
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty()
                ? (error == null ? "unknown error" : error.getClass().getSimpleName())
                : message;
    }

    @Override
    public synchronized void close() {
        shutdownMySqlCleanupThread();
        sqliteDriver = null;
        mysqlDriver = null;
        closeQuietly(sqliteLoader);
        closeQuietly(mysqlLoader);
        sqliteLoader = null;
        mysqlLoader = null;
    }

    private void shutdownMySqlCleanupThread() {
        if (mysqlLoader == null) return;
        try {
            Class<?> cleanup = Class.forName(
                    "com.mysql.cj.jdbc.AbandonedConnectionCleanupThread", false, mysqlLoader);
            Method method = cleanup.getMethod("checkedShutdown");
            method.invoke(null);
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable error) {
            plugin.getLogger().log(Level.FINE, "Could not stop MySQL cleanup thread", error);
        }
    }

    private static void closeQuietly(URLClassLoader loader) {
        if (loader == null) return;
        try {
            loader.close();
        } catch (IOException ignored) {
        }
    }

    private static final class Dependency {
        final String fileName;
        final String sha256;
        final String mavenPath;

        Dependency(String fileName, String sha256, String mavenPath) {
            this.fileName = fileName;
            this.sha256 = sha256;
            this.mavenPath = mavenPath;
        }
    }
}
