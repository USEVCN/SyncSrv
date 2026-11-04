package com.oadig.syncsrv.platform.storage;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mserref.NtStatus;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.*;
import com.hierynomus.mssmb2.*;
import com.hierynomus.smbj.*;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.*;
import com.oadig.syncsrv.shared.Models.Endpoint;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class SmbStorage implements Storage {
  private final SMBClient client =
      new SMBClient(
          SmbConfig.builder()
              .withTimeout(30, TimeUnit.SECONDS)
              // 源目录、备份库与归档库 connections can be idle while another tree is hashed. Bound requests,
              // not the background socket reader: an idle timeout closes the entire share.
              .withSoTimeout(0, TimeUnit.SECONDS)
              .withSigningRequired(true)
              .withReadBufferSize(SmbStreams.BUFFER)
              .withWriteBufferSize(SmbStreams.BUFFER)
              .build());
  private Connection connection;
  private Session session;
  private DiskShare share;
  private final String root;
  private final String copyIdentity;
  private boolean copyUnsupported;

  public SmbStorage(Endpoint e) throws Exception {
    String[] parts = e.root.replace('\\', '/').replaceAll("^/+|/+$", "").split("/", 2);
    if (parts[0].isBlank()) throw new IllegalArgumentException("SMB 根目录须包含共享名");
    root = parts.length > 1 ? parts[1] : "";
    copyIdentity =
        com.oadig.syncsrv.shared.Ids.textHash(
            String.join(
                "\n",
                e.host.toLowerCase(Locale.ROOT),
                Integer.toString(e.port > 0 ? e.port : 445),
                parts[0].toLowerCase(Locale.ROOT),
                e.username,
                e.domain,
                e.password));
    SmbFailure.Stage stage = SmbFailure.Stage.NEGOTIATE;
    try {
      connection = client.connect(e.host, e.port > 0 ? e.port : 445);
      stage = SmbFailure.Stage.AUTHENTICATE;
      session =
          connection.authenticate(
              new AuthenticationContext(e.username, e.password.toCharArray(), e.domain));
      stage = SmbFailure.Stage.SHARE;
      share = (DiskShare) session.connectShare(parts[0]);
    } catch (Exception ex) {
      SmbFailure failure = SmbFailure.from(e, stage, ex);
      try {
        close();
      } catch (Exception cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  private String path(String p) {
    StoragePaths.relative(p);
    return (root.isEmpty() ? p : root + (p.isEmpty() ? "" : "/" + p)).replace('/', '\\');
  }

  private boolean reparse(long attrs) {
    return (attrs & FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.getValue()) != 0;
  }

  @Override
  public Item stat(String p) throws Exception {
    try {
      FileAllInformation i = share.getFileInformation(path(p));
      return new Item(
          StoragePaths.name(p),
          i.getStandardInformation().isDirectory(),
          i.getStandardInformation().getEndOfFile(),
          i.getBasicInformation().getLastWriteTime().toEpochMillis(),
          reparse(i.getBasicInformation().getFileAttributes()));
    } catch (SMBApiException e) {
      if (e.getStatus() == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND
          || e.getStatus() == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND
          || e.getStatus() == NtStatus.STATUS_NO_SUCH_FILE) return null;
      throw e;
    }
  }

  @Override
  public List<Item> list(String p) throws Exception {
    List<Item> out = new ArrayList<>();
    for (FileIdBothDirectoryInformation i : share.list(path(p)))
      if (!i.getFileName().equals(".") && !i.getFileName().equals(".."))
        out.add(
            new Item(
                i.getFileName(),
                (i.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0,
                i.getEndOfFile(),
                i.getLastWriteTime().toEpochMillis(),
                reparse(i.getFileAttributes())));
    return out;
  }

  @Override
  public InputStream read(String p, long offset) throws Exception {
    return read(p, offset, -1);
  }

  @Override
  public InputStream read(String p, long offset, long length) throws Exception {
    if (offset < 0 || length < -1 || (length >= 0 && offset > Long.MAX_VALUE - length))
      throw new IllegalArgumentException("读取偏移不能为负数");
    com.hierynomus.smbj.share.File f =
        share.openFile(
            path(p),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
    return SmbReadStream.open(f, offset, length);
  }

  @Override
  public void write(String p, long offset, InputStream data, long length) throws Exception {
    if (offset < 0 || length < 0) throw new IllegalArgumentException("写入范围不正确");
    mkdirs(StoragePaths.parent(p));
    try (com.hierynomus.smbj.share.File f =
        share.openFile(
            path(p),
            EnumSet.of(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            offset == 0
                ? SMB2CreateDisposition.FILE_OVERWRITE_IF
                : SMB2CreateDisposition.FILE_OPEN_IF,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))) {
      SmbStreams.write(f, offset, data, length);
    }
  }

  @Override
  public boolean canCopyTo(Storage target) {
    return !copyUnsupported
        && target instanceof SmbStorage other
        && copyIdentity.equals(other.copyIdentity);
  }

  @Override
  public boolean copyRangeTo(String from, Storage target, String to, long offset, int length)
      throws Exception {
    if (!canCopyTo(target)) return false;
    if (offset < 0 || length <= 0) throw new IllegalArgumentException("复制范围不正确");
    SmbStorage other = (SmbStorage) target;
    String source = path(from), destination = other.path(to);
    if (source.equalsIgnoreCase(destination)) throw new IOException("不能复制到源文件自身");
    other.mkdirs(StoragePaths.parent(to));
    try (com.hierynomus.smbj.share.File input =
            share.openFile(
                source,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
        com.hierynomus.smbj.share.File output =
            share.openFile(
                destination,
                EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                offset == 0
                    ? SMB2CreateDisposition.FILE_OVERWRITE_IF
                    : SMB2CreateDisposition.FILE_OPEN_IF,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))) {
      input.remoteCopyTo(offset, output, offset, length);
      output.flush();
      return true;
    } catch (SMBApiException e) {
      if (SmbCopySession.unsupportedCopy(e)) {
        copyUnsupported = true;
        return false;
      }
      throw e;
    }
  }

  @Override
  public CopySession openCopySession(String from, Storage target, String to) throws Exception {
    if (!canCopyTo(target)) return null;
    SmbStorage other = (SmbStorage) target;
    String source = path(from), destination = other.path(to);
    if (source.equalsIgnoreCase(destination)) throw new IOException("不能复制到源文件自身");
    other.mkdirs(StoragePaths.parent(to));
    return SmbCopySession.open(share, source, destination, () -> copyUnsupported = true);
  }

  @Override
  public void mkdirs(String p) throws Exception {
    String current = "";
    if (p.isEmpty()) return;
    for (String part : p.split("/")) {
      current = current.isEmpty() ? part : current + "/" + part;
      Item i = stat(current);
      if (i == null) share.mkdir(path(current));
      else if (!i.directory() || i.symbolic()) throw new IOException("SMB 目录冲突");
    }
  }

  @Override
  public void move(String a, String b) throws Exception {
    if (stat(b) != null) throw new IOException("SMB 目标已存在");
    try (DiskEntry f =
        share.open(
            path(a),
            EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null)) {
      f.rename(path(b), false);
    }
  }

  @Override
  public void delete(String p, boolean directory) throws Exception {
    if (directory) share.rmdir(path(p), false);
    else share.rm(path(p));
  }

  @Override
  public Capabilities capabilities() {
    return new Capabilities(true, true, false, "SMB2/3、签名与偏移读写；拒绝 reparse point");
  }

  @Override
  public void close() throws IOException {
    try {
      if (share != null) share.close();
    } finally {
      try {
        if (session != null) session.close();
      } finally {
        try {
          if (connection != null) connection.close();
        } finally {
          client.close();
        }
      }
    }
  }
}
