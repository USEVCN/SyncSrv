package com.oadig.syncsrv.platform.storage;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.*;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import java.io.*;
import java.util.*;

/** One signed SMB handle pair per file, with write/delete sharing denied on both ends. */
final class SmbCopySession implements CopySession {
  private final File source, target;
  private final Runnable unsupported;

  static CopySession open(DiskShare share, String from, String to, Runnable unsupported)
      throws Exception {
    var options = EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE,
        SMB2CreateOptions.FILE_OPEN_REPARSE_POINT);
    File source = share.openFile(from, EnumSet.of(AccessMask.GENERIC_READ), null,
        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ), SMB2CreateDisposition.FILE_OPEN, options);
    File target = null;
    try {
      checkRegular(source);
      target = share.openFile(to, EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE),
          null, EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
          SMB2CreateDisposition.FILE_OPEN_IF, options);
      checkRegular(target);
      return new SmbCopySession(source, target, unsupported);
    } catch (Exception e) {
      if (target != null) try { target.close(); } catch (Exception close) { e.addSuppressed(close); }
      try { source.close(); } catch (Exception close) { e.addSuppressed(close); }
      throw e;
    }
  }

  private static void checkRegular(File file) throws IOException {
    var info = file.getFileInformation();
    if (info.getStandardInformation().isDirectory()
        || (info.getBasicInformation().getFileAttributes()
            & FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.getValue()) != 0)
      throw new IOException("存储端复制拒绝目录或重解析点");
  }

  private SmbCopySession(File source, File target, Runnable unsupported) {
    this.source = source; this.target = target; this.unsupported = unsupported;
  }

  public long sourceSize() { return source.getLength(); }
  public long targetSize() { return target.getLength(); }
  public void truncate() { target.setLength(0); target.flush(); }

  public boolean copy(long offset, int length) throws Exception {
    try {
      source.remoteCopyTo(offset, target, offset, length);
      target.flush();
      return true;
    } catch (SMBApiException e) {
      if (!unsupportedCopy(e)) throw e;
      unsupported.run();
      return false;
    }
  }

  static boolean unsupportedCopy(SMBApiException e) {
    // SMBJ maps STATUS_INVALID_DEVICE_REQUEST to STATUS_OTHER; retain the wire status.
    return Set.of(NtStatus.STATUS_NOT_SUPPORTED, NtStatus.STATUS_NOT_IMPLEMENTED).contains(e.getStatus())
        || (e.getFailedCommand() == SMB2MessageCommandCode.SMB2_IOCTL && e.getStatusCode() == 0xc0000010L);
  }

  public InputStream read(long offset, int length) throws IOException {
    return SmbReadStream.open(target, offset, length, false);
  }

  public void close() throws IOException {
    try (source; target) { /* Close both, preserving suppressed close failures. */ }
  }
}
