package com.oadig.syncsrv.platform.storage;

import com.oadig.syncsrv.shared.Models.Endpoint;
import java.nio.file.Path;

public class StorageFactory {
  public static Storage open(Endpoint endpoint, com.oadig.syncsrv.platform.persistence.Store store)
      throws Exception {
    return new EndpointStorage(endpoint, open(endpoint));
  }
  public static Storage open(Endpoint endpoint) throws Exception {
    return switch (endpoint.protocol) {
      case "LOCAL" -> new LocalStorage(Path.of(endpoint.root));
      case "SMB" -> new SmbStorage(endpoint);
      case "SFTP" -> new SftpStorage(endpoint);
      case "FTP" -> new FtpStorage(endpoint);
      case "WEBDAV" -> new WebDavStorage(endpoint);
      default -> throw new IllegalArgumentException("不支持的存储协议");
    };
  }
}
