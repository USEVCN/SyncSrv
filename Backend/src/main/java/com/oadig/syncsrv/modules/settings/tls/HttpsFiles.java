package com.oadig.syncsrv.modules.settings.tls;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.List;
import java.util.Set;

/** Owner-only identity files on both POSIX hosts and Windows NTFS. */
final class HttpsFiles {
    private HttpsFiles() { }

    static void directory(Path path) throws IOException {
        checkParents(path);
        Files.createDirectories(path);
        restrict(path, true);
    }

    static void checkParents(Path path) throws IOException {
        // Ancestors of the external configuration may be OS aliases (e.g. macOS /var).
        // Never allow symlinks within the managed identity store itself.
        if (Files.isSymbolicLink(path)) throw new IOException("HTTPS 配置路径不能是符号链接");
    }

    static byte[] read(Path path, int limit) throws IOException {
        checkParents(path.getParent());
        checkParents(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit)
            throw new IOException("HTTPS 文件不可用");
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] raw = stream.readNBytes(limit + 1);
            if (raw.length > limit) throw new IOException("HTTPS 文件过大");
            return raw;
        }
    }

    static void writeNew(Path path, String text) throws IOException {
        checkParents(path.getParent());
        Files.createFile(path);
        restrict(path, false);
        Files.writeString(path, text, StandardCharsets.US_ASCII, StandardOpenOption.WRITE);
        try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
    }

    static void atomicWrite(Path target, String text) throws IOException {
        directory(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".https-", ".tmp");
        try {
            restrict(temp, false);
            Files.writeString(temp, text, StandardCharsets.ISO_8859_1);
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temp); }
    }

    private static void restrict(Path path, boolean directory) throws IOException {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        } else {
            var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (acl == null) throw new IOException("此文件系统不支持私钥访问权限控制");
            var entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                    .setPermissions(Set.of(AclEntryPermission.values()));
            if (directory) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
            acl.setAcl(List.of(entry.build()));
        }
    }
}
