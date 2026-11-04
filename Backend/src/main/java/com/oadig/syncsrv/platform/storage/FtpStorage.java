package com.oadig.syncsrv.platform.storage;

import com.oadig.syncsrv.shared.Models.Endpoint;
import java.io.*;
import java.time.Duration;
import java.util.*;
import org.apache.commons.net.ftp.*;

public final class FtpStorage implements Storage {
  private final FTPClient ftp;
  private final String root;
  private final Endpoint endpoint;

  public FtpStorage(Endpoint e) throws Exception {
    endpoint = e;
    root = e.root.replaceAll("/+$", "");
    ftp = new FTPClient();
    ftp.setConnectTimeout(15000);
    ftp.setDefaultTimeout(30000);
    ftp.setDataTimeout(Duration.ofSeconds(30));
    ftp.setControlEncoding("UTF-8");
    try {
      ftp.connect(e.host, e.port > 0 ? e.port : 21);
      if (!FTPReply.isPositiveCompletion(ftp.getReplyCode()) || !ftp.login(e.username, e.password))
        throw new IOException("FTP 登录失败");
      FTPClientConfig listing = new FTPClientConfig(ftp.getSystemType());
      listing.setUnparseableEntries(true);
      ftp.configure(listing);
      ftp.setListHiddenFiles(true);
      ftp.enterLocalPassiveMode();
      if (!ftp.setFileType(FTP.BINARY_FILE_TYPE)) throw new IOException("FTP 二进制模式失败");
    } catch (Exception ex) {
      closeAfterFailure(ex);
      throw ex;
    }
  }

  private String path(String p) {
    return StoragePaths.join(root, p).isEmpty() ? "/" : StoragePaths.join(root, p);
  }

  private Item item(FTPFile f) {
    return new Item(
        f.getName(),
        f.isDirectory(),
        f.getSize(),
        f.getTimestamp() == null ? 0 : f.getTimestamp().getTimeInMillis(),
        f.isSymbolicLink());
  }

  @Override
  public Item stat(String p) throws Exception {
    if (p.isEmpty()) {
      String old = ftp.printWorkingDirectory();
      if (!ftp.changeWorkingDirectory(path("")))
        throw new IOException("FTP 根目录不可访问: " + ftp.getReplyString());
      if (old != null && !ftp.changeWorkingDirectory(old)) throw new IOException("FTP 工作目录恢复失败");
      return new Item("", true, 0, 0, false);
    }
    String parent = StoragePaths.parent(p);
    if (!parent.isEmpty()) {
      Item directory = stat(parent);
      if (directory == null) return null;
      if (!directory.directory() || directory.symbolic()) throw new IOException("FTP 父目录类型异常");
    }
    for (Item i : list(parent)) if (i.name().equals(StoragePaths.name(p))) return i;
    return null;
  }

  @Override
  public List<Item> list(String p) throws Exception {
    FTPFile[] files = ftp.listFiles(path(p));
    if (!FTPReply.isPositiveCompletion(ftp.getReplyCode()))
      throw new IOException("FTP 列举失败: " + ftp.getReplyString());
    List<Item> out = new ArrayList<>();
    for (FTPFile f : files)
      if (!f.getName().equals(".") && !f.getName().equals("..")) {
        if (!f.isDirectory() && !f.isFile() && !f.isSymbolicLink())
          throw new IOException("FTP 无法识别条目类型");
        out.add(item(f));
      }
    return out;
  }

  @Override
  public InputStream read(String p, long offset) throws Exception {
    // A partial verification read must not leave an ABOR/426 reply on the main connection.
    FtpStorage reader = new FtpStorage(endpoint);
    InputStream stream;
    try {
      reader.ftp.setRestartOffset(offset);
      stream = reader.ftp.retrieveFileStream(path(p));
      if (stream == null) throw new IOException("FTP 读取失败: " + reader.ftp.getReplyString());
    } catch (Exception ex) {
      reader.closeAfterFailure(ex);
      throw ex;
    }
    return new FilterInputStream(stream) {
      boolean eof, closed;

      @Override
      public int read() throws IOException {
        int n = in.read();
        if (n < 0) eof = true;
        return n;
      }

      @Override
      public int read(byte[] b, int o, int n) throws IOException {
        int count = in.read(b, o, n);
        if (count < 0) eof = true;
        return count;
      }

      @Override
      public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        try {
          super.close();
          if (eof && !reader.ftp.completePendingCommand()) throw new IOException("FTP 读取未完成");
        } catch (IOException e) { failure = e; }
        try { reader.close(); }
        catch (IOException e) {
          if (failure == null) failure = e;
          else failure.addSuppressed(e);
        }
        if (failure != null) throw failure;
      }
    };
  }

  @Override
  public void write(String p, long offset, InputStream data, long length) throws Exception {
    mkdirs(StoragePaths.parent(p));
    ftp.setRestartOffset(offset);
    if (!ftp.storeFile(path(p), data))
      throw new IOException("FTP 写入/偏移续传失败: " + ftp.getReplyString());
  }

  @Override
  public void mkdirs(String p) throws Exception {
    if (p.isEmpty()) return;
    String current = "";
    for (String part : p.split("/")) {
      current = current.isEmpty() ? part : current + "/" + part;
      Item i = stat(current);
      if (i == null) {
        if (!ftp.makeDirectory(path(current))) throw new IOException("FTP 创建目录失败");
      } else if (!i.directory()) throw new IOException("FTP 目录类型冲突");
    }
  }

  @Override
  public void move(String a, String b) throws Exception {
    if (stat(b) != null) throw new IOException("归档名称已存在");
    if (!ftp.rename(path(a), path(b))) throw new IOException("FTP 改名失败");
  }

  @Override
  public void delete(String p, boolean directory) throws Exception {
    if (!(directory ? ftp.removeDirectory(path(p)) : ftp.deleteFile(path(p))))
      throw new IOException("FTP 删除失败");
  }

  @Override
  public Capabilities capabilities() {
    return new Capabilities(true, true, false, "REST 偏移写入取决于服务器；断点始终回读验证");
  }

  private void closeAfterFailure(Exception failure) {
    try { close(); }
    catch (IOException cleanup) { failure.addSuppressed(cleanup); }
  }

  @Override
  public void close() throws IOException {
    if (ftp.isConnected()) ftp.disconnect();
  }
}
