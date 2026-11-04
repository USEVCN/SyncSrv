package com.oadig.syncsrv.platform.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.*;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SmbCopySessionTest {
  @Test
  void unsupportedIoctlFallsBackButAccessAndTransportErrorsDoNot() throws Exception {
    DiskShare share = mock(DiskShare.class);
    File source = mock(File.class, RETURNS_DEEP_STUBS), target = mock(File.class, RETURNS_DEEP_STUBS);
    when(share.openFile(eq("source"), any(), isNull(), any(), any(), any())).thenReturn(source);
    when(share.openFile(eq("target"), any(), isNull(), any(), any(), any())).thenReturn(target);
    Runnable unavailable=mock(Runnable.class);
    try(CopySession session=SmbCopySession.open(share,"source","target",unavailable)) {
      doThrow(new SMBApiException(0xc0000010L,SMB2MessageCommandCode.SMB2_IOCTL,"unsupported",null))
          .when(source).remoteCopyTo(0,target,0,4096);
      assertFalse(session.copy(0,4096));verify(unavailable).run();
      var denied=new SMBApiException(0xc0000022L,SMB2MessageCommandCode.SMB2_IOCTL,"denied",null);
      doThrow(denied).when(source).remoteCopyTo(0,target,0,4096);
      assertSame(denied,assertThrows(SMBApiException.class,()->session.copy(0,4096)));
      assertFalse(SmbCopySession.unsupportedCopy(new SMBApiException(0xc0000010L,SMB2MessageCommandCode.SMB2_CREATE,"create failed",null)));
    }
  }

  @Test
  void fileHandlesStayOpenAcrossChunksAndRejectWriteDeleteSharing() throws Exception {
    DiskShare share = mock(DiskShare.class);
    File source = mock(File.class, RETURNS_DEEP_STUBS), target = mock(File.class, RETURNS_DEEP_STUBS);
    when(share.openFile(eq("source"), any(), isNull(), any(), any(), any())).thenReturn(source);
    when(share.openFile(eq("target"), any(), isNull(), any(), any(), any())).thenReturn(target);
    try (CopySession session = SmbCopySession.open(share, "source", "target", () -> {})) {
      session.truncate();
      assertTrue(session.copy(0, 8388608));
      assertTrue(session.copy(8388608, 17));
      verify(source, never()).close(); verify(target, never()).close();
      verify(source).remoteCopyTo(0, target, 0, 8388608);
      verify(source).remoteCopyTo(8388608, target, 8388608, 17);
      verify(target, times(3)).flush();
      when(target.getDiskShare().getTreeConnect().getNegotiatedProtocol().getMaxReadSize())
          .thenReturn(1048576);
      try (InputStream readBack = session.read(0, 17)) { /* A borrowed stream never closes the file. */ }
      verify(target, never()).close();
    }
    var options = EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE, SMB2CreateOptions.FILE_OPEN_REPARSE_POINT);
    verify(share).openFile("source", EnumSet.of(AccessMask.GENERIC_READ), null,
        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ), SMB2CreateDisposition.FILE_OPEN, options);
    verify(share).openFile("target", EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE), null,
        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ), SMB2CreateDisposition.FILE_OPEN_IF, options);
    verify(source).close(); verify(target).close();
  }

  @Test
  void failedTargetValidationClosesBothHandlesWithoutTruncating() throws Exception {
    DiskShare share = mock(DiskShare.class);
    File source = mock(File.class, RETURNS_DEEP_STUBS), target = mock(File.class, RETURNS_DEEP_STUBS);
    when(share.openFile(eq("source"), any(), isNull(), any(), any(), any())).thenReturn(source);
    when(share.openFile(eq("target"), any(), isNull(), any(), any(), any())).thenReturn(target);
    when(target.getFileInformation().getStandardInformation().isDirectory()).thenReturn(true);
    assertThrows(IOException.class, () -> SmbCopySession.open(share, "source", "target", () -> {}));
    verify(source).close(); verify(target).close();
    verify(target, never()).setLength(anyLong());
  }

  @Test
  void invalidReadStreamSetupDoesNotCloseBorrowedHandle() {
    File target = mock(File.class);
    assertThrows(RuntimeException.class, () -> SmbReadStream.open(target, 0, 1, false));
    assertDoesNotThrow(() -> verify(target, never()).close());
  }
}
