package com.oadig.syncsrv;
import static org.junit.jupiter.api.Assertions.*;
import com.oadig.syncsrv.app.SyncSrvApplication;
import com.oadig.syncsrv.platform.persistence.MetadataTools;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
class MetadataCommandTest {
  @Test void unsupportedCommandDoesNotStartTheServer() {
    assertThrows(IllegalArgumentException.class, () -> SyncSrvApplication.main(new String[]{"unsupported-command", "source", "target"}));
  }
  @Test void directoryCannotInjectDatabaseOptions() {
    assertThrows(IllegalArgumentException.class, () -> MetadataTools.url(Path.of("data;AUTO_SERVER=TRUE")));
  }
}
