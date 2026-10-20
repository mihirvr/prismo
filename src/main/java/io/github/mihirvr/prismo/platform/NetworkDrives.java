package io.github.mihirvr.prismo.platform;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tells a folder on another computer (a NAS, a shared folder, a mapped network drive) from a local one (P7, row 11).
 * Prismo doesn't watch network folders live (PROJECT.md): change notifications over the network are unreliable (SMB
 * drops them when its buffer fills, NFS has none), and a watch that hangs on a dead connection would hold the indexing
 * thread. They are rescanned on a timer instead.
 *
 * <p>Windows: a {@code \\server\share} path is a network folder; for a drive letter, Windows says
 * ({@code GetDriveTypeW} = {@code DRIVE_REMOTE}, through the Foreign Function and Memory API, JEP 454). Linux: the type of
 * the file system it's mounted from ({@code /proc/mounts}: nfs, cifs, smb3, sshfs …). Elsewhere, or if asking fails:
 * local (watched as before; the watcher falls back to rescans on its own if watching fails).
 */
public final class NetworkDrives {

    private static final Logger log = LoggerFactory.getLogger(NetworkDrives.class);

    /** Network library folders are looked through for changes this often (local ones are watched live). */
    public static final long RESCAN_MINUTES = 10;

    /** {@code GetDriveTypeW}'s answer for a network drive. */
    static final int DRIVE_REMOTE = 4;
    /** File system types (the third column of /proc/mounts) that live on another computer. */
    static final Set<String> NETWORK_TYPES = Set.of("nfs", "nfs4", "cifs", "smb3", "smbfs", "fuse.sshfs", "sshfs",
            "9p", "afs", "fuse.rclone", "davfs", "fuse.davfs2", "ncpfs", "glusterfs", "ceph");

    private static final MethodHandle GET_DRIVE_TYPE = OperatingSystem.current() == OperatingSystem.WINDOWS
            ? lookUpDriveType() : null;

    private NetworkDrives() {
    }

    /** True if {@code folder} is on another computer. Quick: no file is read on Windows (Linux reads /proc/mounts). */
    public static boolean isNetwork(Path folder) {
        return switch (OperatingSystem.current()) {
            case WINDOWS -> isUnc(folder) || driveType(folder) == DRIVE_REMOTE;
            case LINUX -> {
                try {
                    yield isNetworkMount(folder, Files.readAllLines(Path.of("/proc/mounts")));
                } catch (IOException | RuntimeException e) {
                    log.debug("Can't read /proc/mounts: {}", e.toString());
                    yield false;
                }
            }
            default -> false;
        };
    }

    /** {@code \\server\share\…}: Windows' way of naming a folder on another computer. */
    static boolean isUnc(Path folder) {
        String text = folder.toString();
        return text.startsWith("\\\\") && !text.startsWith("\\\\?\\") && !text.startsWith("\\\\.\\");
    }

    /**
     * True if the longest mount point holding {@code folder} in {@code mounts} (lines of /proc/mounts: device, mount
     * point, type, …) has a network file system type.
     */
    static boolean isNetworkMount(Path folder, List<String> mounts) {
        String best = null;
        String bestType = null;
        // Library folders are stored as real (absolute) paths; the text is compared, so this tests the same on Windows.
        String path = folder.normalize().toString().replace('\\', '/');
        for (String line : mounts) {
            String[] fields = line.split(" ");
            if (fields.length < 3) {
                continue;
            }
            String point = fields[1].replace("\\040", " "); // /proc/mounts writes a space as \040
            boolean holds = path.equals(point) || path.startsWith(point.endsWith("/") ? point : point + "/");
            if (holds && (best == null || point.length() > best.length())) {
                best = point;
                bestType = fields[2];
            }
        }
        return bestType != null && NETWORK_TYPES.contains(bestType.toLowerCase(Locale.ROOT));
    }

    /** {@code GetDriveTypeW} for the folder's drive ("D:\"), or -1 if it can't be asked. */
    private static int driveType(Path folder) {
        Path root = folder.getRoot();
        if (GET_DRIVE_TYPE == null || root == null) {
            return -1;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name = arena.allocateFrom(root.toString(), StandardCharsets.UTF_16LE);
            return (int) GET_DRIVE_TYPE.invokeExact(name);
        } catch (Throwable e) {
            log.debug("GetDriveTypeW failed for {}: {}", root, e.toString());
            return -1;
        }
    }

    @SuppressWarnings("restricted") // FFM downcalls are "restricted": allowed by --enable-native-access=ALL-UNNAMED
    private static MethodHandle lookUpDriveType() {
        try {
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
            // UINT GetDriveTypeW(LPCWSTR rootPathName)
            return Linker.nativeLinker().downcallHandle(kernel32.findOrThrow("GetDriveTypeW"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
        } catch (RuntimeException | Error e) {
            log.warn("Network drives can't be told apart: {}", e.toString());
            return null;
        }
    }
}
