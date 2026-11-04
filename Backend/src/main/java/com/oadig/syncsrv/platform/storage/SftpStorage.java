package com.oadig.syncsrv.platform.storage;

import com.oadig.syncsrv.shared.Ids;
import com.oadig.syncsrv.shared.Models.Endpoint;
import java.io.*;
import java.security.*;
import java.util.*;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.sftp.*;

public final class SftpStorage implements Storage {
  private final SSHClient ssh = new SSHClient();
  private SFTPClient sftp;
  private final String root;

  public SftpStorage(Endpoint e) throws Exception {
    root = e.root;
    if (e.hostKey == null || !e.hostKey.startsWith("SHA256:"))
      throw new IllegalArgumentException("SFTP 必须配置 SHA256 主机密钥指纹");
    ssh.setConnectTimeout(15000);
    ssh.setTimeout(30000);
    ssh.addHostKeyVerifier(
        new net.schmizz.sshj.transport.verification.HostKeyVerifier() {
          public List<String> findExistingAlgorithms(String host, int port) {
            return List.of();
          }

          public boolean verify(String host, int port, PublicKey key) {
            byte[] wire = new Buffer.PlainBuffer().putPublicKey(key).getCompactData();
            String fingerprint =
                "SHA256:"
                    + Base64.getEncoder()
                        .withoutPadding()
                        .encodeToString(Ids.digest().digest(wire));
            return MessageDigest.isEqual(
                fingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                e.hostKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
          }
        });
    try {
      ssh.connect(e.host, e.port > 0 ? e.port : 22);
      ssh.authPassword(e.username, e.password);
      sftp = ssh.newSFTPClient();
    } catch (Exception ex) {
      ssh.close();
      throw ex;
    }
  }

  private String path(String p) {
    return StoragePaths.join(root, p);
  }

  private Item item(String name, FileAttributes a) throws IOException {
    if (!List.of(FileMode.Type.REGULAR, FileMode.Type.DIRECTORY, FileMode.Type.SYMLINK)
        .contains(a.getType())) throw new IOException("不支持特殊文件: " + name);
    return new Item(
        name,
        a.getType() == FileMode.Type.DIRECTORY,
        a.getSize(),
        a.getMtime() * 1000,
        a.getType() == FileMode.Type.SYMLINK);
  }

  @Override
  public Item stat(String p) throws Exception {
    try {
      return item(StoragePaths.name(p), sftp.lstat(path(p)));
    } catch (SFTPException e) {
      if (e.getStatusCode() == Response.StatusCode.NO_SUCH_FILE) return null;
      throw e;
    }
  }

  @Override
  public List<Item> list(String p) throws Exception {
    List<Item> out = new ArrayList<>();
    for (RemoteResourceInfo i : sftp.ls(path(p)))
      if (!i.getName().equals(".") && !i.getName().equals(".."))
        out.add(item(i.getName(), i.getAttributes()));
    return out;
  }

  @Override
  public InputStream read(String p, long offset) throws Exception { return read(p, offset, -1); }

  @Override
  public InputStream read(String p, long offset, long length) throws Exception {
    if (offset < 0 || length < -1 || (length >= 0 && offset > Long.MAX_VALUE - length))
      throw new IllegalArgumentException("SFTP 读取范围不正确");
    RemoteFile file = sftp.open(path(p), EnumSet.of(OpenMode.READ));
    return SftpStreams.read(file, offset, length);
  }

  @Override
  public void write(String p, long offset, InputStream data, long length) throws Exception {
    if (offset < 0 || length < 0 || offset > Long.MAX_VALUE - length)
      throw new IllegalArgumentException("SFTP 写入范围不正确");
    mkdirs(StoragePaths.parent(p));
    EnumSet<OpenMode> modes = EnumSet.of(OpenMode.WRITE, OpenMode.CREAT);
    if (offset == 0) modes.add(OpenMode.TRUNC);
    try (RemoteFile file = sftp.open(path(p), modes)) {
      SftpStreams.write(file, offset, data, length);
    }
  }

  @Override
  public void mkdirs(String p) throws Exception {
    if (!p.isEmpty()) sftp.mkdirs(path(p));
  }

  @Override
  public void move(String a, String b) throws Exception {
    if (stat(b) != null) throw new IOException("目标已存在");
    sftp.rename(path(a), path(b));
  }

  @Override
  public void delete(String p, boolean directory) throws Exception {
    if (directory) sftp.rmdir(path(p));
    else sftp.rm(path(p));
  }

  @Override
  public Capabilities capabilities() {
    return new Capabilities(true, true, false, "按偏移读写并验证主机密钥；服务端持久化语义需实机验收");
  }

  @Override
  public void close() throws IOException {
    try {
      if (sftp != null) sftp.close();
    } finally {
      ssh.close();
    }
  }
}
