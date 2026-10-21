package io.github.mihirvr.prismo.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class NetworkDrivesTest {

    @TempDir
    Path tmp;

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aServerShareIsANetworkFolderAndALocalDriveIsNot() {
        assertTrue(NetworkDrives.isUnc(Path.of("\\\\nas\\photos\\2024")));
        assertTrue(NetworkDrives.isNetwork(Path.of("\\\\nas\\photos")));
        assertFalse(NetworkDrives.isUnc(Path.of("D:\\Photos")));
        assertFalse(NetworkDrives.isUnc(Path.of("\\\\?\\C:\\Photos")), "a long-path prefix is local");
        assertFalse(NetworkDrives.isNetwork(tmp), "the temp folder is on a local disk");
    }

    @Test
    void linuxMountsAreReadByTheirLongestMountPoint() {
        List<String> mounts = List.of(
                "/dev/sda2 / ext4 rw,relatime 0 0",
                "//nas/photos /mnt/nas cifs rw,vers=3.0 0 0",
                "nas:/export /mnt/nfs\\040share nfs4 rw 0 0",
                "/dev/sdb1 /mnt/nas/usb ext4 rw 0 0",
                "broken line");

        assertTrue(NetworkDrives.isNetworkMount(Path.of("/mnt/nas/2024"), mounts));
        assertTrue(NetworkDrives.isNetworkMount(Path.of("/mnt/nas"), mounts), "the mount point itself");
        assertTrue(NetworkDrives.isNetworkMount(Path.of("/mnt/nfs share/a"), mounts), "\\040 is a space");
        assertFalse(NetworkDrives.isNetworkMount(Path.of("/mnt/nas/usb/DCIM"), mounts), "a disk mounted inside it");
        assertFalse(NetworkDrives.isNetworkMount(Path.of("/mnt/nasty"), mounts), "a name that only starts the same");
        assertFalse(NetworkDrives.isNetworkMount(Path.of("/home/me/Pictures"), mounts));
        assertFalse(NetworkDrives.isNetworkMount(Path.of("/home"), List.of()), "nothing known: local");
        assertTrue(NetworkDrives.isNetworkMount(Path.of("/mnt/nas/2024"), List.of("//nas/photos /mnt/nas cifs rw 0 0",
                "/dev/sda2 / ext4 rw 0 0")), "the longest mount point wins, whatever the order");
    }
}
