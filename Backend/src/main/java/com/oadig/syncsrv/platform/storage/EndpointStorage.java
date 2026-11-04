package com.oadig.syncsrv.platform.storage;

import com.oadig.syncsrv.shared.Models.Endpoint;
import java.io.*;
import java.util.List;

/** Keeps the device binding through storage adapters; metadata still uses the configured protocol. */
public final class EndpointStorage implements Storage {
  private final Endpoint endpoint;
  private final Storage delegate;
  public EndpointStorage(Endpoint endpoint, Storage delegate) {
    this.endpoint = endpoint;
    this.delegate = delegate;
  }
  public Location location(String path) { return new Location(endpoint, path); }
  public Storage unwrap() { return delegate; }
  public Item stat(String path) throws Exception { return delegate.stat(path); }
  public List<Item> list(String path) throws Exception { return delegate.list(path); }
  public InputStream read(String path, long offset) throws Exception { return delegate.read(path, offset); }
  public InputStream read(String path, long offset, long length) throws Exception { return delegate.read(path, offset, length); }
  public void write(String path, long offset, InputStream in, long length) throws Exception { delegate.write(path, offset, in, length); }
  public void mkdirs(String path) throws Exception { delegate.mkdirs(path); }
  public void move(String from, String to) throws Exception { delegate.move(from, to); }
  public void delete(String path, boolean directory) throws Exception { delegate.delete(path, directory); }
  public boolean canCopyTo(Storage target) { return delegate.canCopyTo(target.unwrap()); }
  public boolean copyRangeTo(String from, Storage target, String to, long offset, int length) throws Exception {
    return delegate.copyRangeTo(from, target.unwrap(), target.copyPath(to), offset, length);
  }
  public CopySession openCopySession(String from, Storage target, String to) throws Exception {
    return delegate.openCopySession(from, target.unwrap(), target.copyPath(to));
  }
  public boolean modified(String path,long millis) throws Exception {return delegate.modified(path,millis);}
  public Capabilities capabilities() { return delegate.capabilities(); }
  public void close() throws Exception { delegate.close(); }
}
