package com.oadig.syncsrv.modules.restore;

import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.platform.storage.*;
import com.oadig.syncsrv.shared.Models.Endpoint;
import java.io.*;
import java.nio.file.*;
import java.util.List;

/** Restores use the same protocol adapters as 备份库与归档库 and stay within the selected root. */
public final class RestoreDestination implements Storage {
  private final Storage delegate;
  private final Endpoint endpoint;
  private final String prefix;
  public final String identity;

  public RestoreDestination(ConfigurationService config, String endpointId, String directory)
      throws Exception {
    String relative;
    if (endpointId == null || endpointId.isBlank()) {
      Path requested = Path.of(directory);
      if (!requested.isAbsolute()) throw new IllegalArgumentException("恢复目录须为绝对路径");
      config.validateRestore(requested);
      Path destination = StoragePaths.canonical(requested), base = destination;
      while (!Files.exists(base)) base = base.getParent();
      endpoint = new Endpoint();
      endpoint.root = base.toString();
      relative = base.relativize(destination).toString().replace('\\', '/');
      identity = destination.toString();
    } else {
      endpoint = config.endpoint(endpointId);
      relative = StoragePaths.relative(directory == null ? "" : directory);
      config.validateRestoreEndpoint(endpoint, relative);
      Endpoint resolved =
          com.oadig.syncsrv.shared.Json.read(
              com.oadig.syncsrv.shared.Json.write(endpoint), Endpoint.class);
      resolved.root = StoragePaths.join(endpoint.root, relative);
      identity = ConfigurationService.location(resolved);
    }
    prefix = relative;
    delegate = new EndpointStorage(endpoint, StorageFactory.open(endpoint));
    try {
      Item root = delegate.stat("");
      if (root == null || !root.directory() || root.symbolic())
        throw new IOException("恢复连接的根目录不可访问");
    } catch (Exception e) {
      delegate.close();
      throw e;
    }
  }

  private RestoreDestination(Endpoint endpoint, String prefix, String identity) throws Exception {
    this.endpoint = endpoint; this.prefix = prefix; this.identity = identity;
    delegate = new EndpointStorage(endpoint, StorageFactory.open(endpoint));
  }

  public RestoreDestination fork() throws Exception {
    return new RestoreDestination(endpoint, prefix, identity);
  }

  private String path(String p) {
    StoragePaths.relative(p);
    return prefix.isEmpty() ? p : StoragePaths.join(prefix, p);
  }

  public Location location(String p) { return delegate.location(path(p)); }
  public Storage unwrap() { return delegate.unwrap(); }
  public String copyPath(String p) { return path(p); }

  public Item stat(String p) throws Exception {
    return delegate.stat(path(p));
  }

  public List<Item> list(String p) throws Exception {
    return delegate.list(path(p));
  }

  public InputStream read(String p, long offset) throws Exception {
    return delegate.read(path(p), offset);
  }

  public InputStream read(String p, long offset, long length) throws Exception {
    return delegate.read(path(p), offset, length);
  }

  public void write(String p, long offset, InputStream in, long size) throws Exception {
    delegate.write(path(p), offset, in, size);
  }

  public void mkdirs(String p) throws Exception {
    delegate.mkdirs(path(p));
  }

  public void move(String from, String to) throws Exception {
    delegate.move(path(from), path(to));
  }

  public void delete(String p, boolean directory) throws Exception {
    delegate.delete(path(p), directory);
  }

  public boolean modified(String p,long millis) throws Exception {return delegate.modified(path(p),millis);}

  public Capabilities capabilities() {
    return delegate.capabilities();
  }

  public void close() throws Exception {
    delegate.close();
  }
}
