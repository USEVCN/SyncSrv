package com.oadig.syncsrv.platform.storage;

import java.io.*;
import java.util.List;

public interface Storage extends AutoCloseable {
  record Location(com.oadig.syncsrv.shared.Models.Endpoint endpoint, String path) {}

  default Location location(String path) { return null; }
  default Storage unwrap() { return this; }
  default String copyPath(String path) { return path; }
  record Item(String name, boolean directory, long size, long modified, boolean symbolic) {}

  record Capabilities(
      boolean offsetRead, boolean offsetWrite, boolean atomicReplace, String note) {}

  /** null means a confirmed missing path; permission and transport failures must throw. */
  Item stat(String path) throws Exception;

  List<Item> list(String directory) throws Exception;

  InputStream read(String path, long offset) throws Exception;

  /** Length is a read-ahead bound; -1 reads to EOF. */
  default InputStream read(String path, long offset, long length) throws Exception {
    return read(path, offset);
  }

  void write(String path, long offset, InputStream data, long length) throws Exception;

  default boolean canCopyTo(Storage target) {
    return false;
  }

  /** false means unsupported; other failures throw and retain verified checkpoints. */
  default boolean copyRangeTo(String from, Storage target, String to, long offset, int length)
      throws Exception {
    return false;
  }

  /** Optional protected file session; null uses the ordinary verified transfer path. */
  default CopySession openCopySession(String from, Storage target, String to) throws Exception {
    return null;
  }

  default boolean modified(String path,long millis) throws Exception { return false; }

  void mkdirs(String directory) throws Exception;

  void move(String from, String to) throws Exception;

  void delete(String path, boolean directory) throws Exception;

  Capabilities capabilities();

  @Override
  void close() throws Exception;
}
