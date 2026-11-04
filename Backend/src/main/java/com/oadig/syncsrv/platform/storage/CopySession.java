package com.oadig.syncsrv.platform.storage;

import java.io.InputStream;

/** Both file identities and contents are protected from external writes/deletes until close. */
public interface CopySession extends AutoCloseable {
  long sourceSize() throws Exception;
  long targetSize() throws Exception;
  void truncate() throws Exception;
  /** false is allowed only for an unsupported storage-side copy command. */
  boolean copy(long offset, int length) throws Exception;
  /** Reads the protected destination handle; closing the stream must not close the session. */
  InputStream read(long offset, int length) throws Exception;
  @Override void close() throws Exception;
}
