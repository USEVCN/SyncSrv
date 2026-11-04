package com.oadig.syncsrv.modules.discovery;

import com.oadig.syncsrv.shared.Json;
import com.oadig.syncsrv.shared.web.ApiResponse;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public final class AgentDistributionController {
  private final Path releases;
  private com.fasterxml.jackson.databind.JsonNode testTrust;
  public record Asset(String platform, String arch, String filename, String sha256, long size,
                      String packageName, String packageSha256, long packageSize) {}
  public record Release(String version, List<Asset> assets, String payload, String signature, String keyId) {
    public Release(String version, List<Asset> assets) { this(version, assets, null, null, null); }
  }

  @org.springframework.beans.factory.annotation.Autowired
  public AgentDistributionController(@Value("${syncsrv.agent-releases:}") String directory) {
    if (directory.equals("auto")) {
      releases = java.util.stream.Stream.of(Path.of("artifacts/agent-releases"), Path.of("../artifacts/agent-releases"))
          .map(p -> p.toAbsolutePath().normalize()).filter(Files::isDirectory).findFirst().orElse(null);
    } else releases = directory.isBlank() ? null : Path.of(directory).toAbsolutePath().normalize();
  }

  public AgentDistributionController(String directory, com.fasterxml.jackson.databind.JsonNode trust) { this(directory); testTrust=trust; }

  @GetMapping("/install")
  public ResponseEntity<Resource> install(@RequestHeader(value = "User-Agent", defaultValue = "") String userAgent) {
    boolean powershell = userAgent.toLowerCase(Locale.ROOT).contains("powershell");
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
        .body(new ClassPathResource("agent/install." + (powershell ? "ps1" : "sh")));
  }

  @GetMapping({"/agent/releases/latest", "/api/agent-release"})
  public ResponseEntity<ApiResponse<Release>> latest() throws Exception {
    Path pointer = safeFile("latest.txt");
    if (Files.size(pointer) > 80) throw unavailable();
    return manifest(Files.readString(pointer).strip());
  }

  @GetMapping("/agent/releases/{version}/manifest")
  public ResponseEntity<ApiResponse<Release>> manifest(@PathVariable String version) throws Exception {
    validateVersion(version);
    Path file = safeFile(version + "/release.json");
    if (Files.size(file) > 65536) throw unavailable();
    var envelope = Json.MAPPER.readTree(Files.readString(file));
    var verified = testTrust == null ? com.oadig.syncsrv.modules.operations.ReleaseTrust.verify(envelope, "SyncSrvAgent")
        : com.oadig.syncsrv.modules.operations.ReleaseTrust.verify(envelope,"SyncSrvAgent",testTrust,java.time.Instant.now());
    Release display = new Release(verified.path("version").asText(),
        Json.MAPPER.readerFor(new com.fasterxml.jackson.core.type.TypeReference<List<Asset>>() {}).readValue(verified.path("assets")));
    Release release = new Release(display.version(), display.assets(), envelope.path("payload").asText(),
        envelope.path("signature").asText(), envelope.path("keyId").asText());
    if (!version.equals(release.version()) || release.assets() == null || release.assets().isEmpty() || release.assets().size() > 8) throw unavailable();
    Set<String> seen = new HashSet<>();
    for (Asset a : release.assets()) {
      if (a == null || !List.of("windows", "linux", "darwin", "synology").contains(a.platform())
          || !List.of("amd64", "arm64").contains(a.arch()) || !seen.add(a.platform()+"/"+a.arch())) throw unavailable();
      String base = "syncsrv-agent-" + a.platform() + "-" + a.arch();
      if (!(base + (a.platform().equals("windows") ? ".exe" : "")).equals(a.filename())
          || !(base + (a.platform().equals("windows") ? ".zip" : a.platform().equals("synology") ? ".spk" : ".tar.gz")).equals(a.packageName())) throw unavailable();
      validateAsset(version, a.filename(), a.sha256(), a.size());
      validateAsset(version, a.packageName(), a.packageSha256(), a.packageSize());
    }
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(release));
  }

  private void validateAsset(String version, String name, String hash, long size) throws Exception {
    if (hash == null || !hash.matches("[a-f0-9]{64}") || size <= 0 || size > 64L*1024*1024
        || Files.size(safeFile(version + "/" + name)) != size) throw unavailable();
  }
  private void validateVersion(String version) {
    if (!version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  }
  private Path safeFile(String relative) throws Exception {
    if (releases == null) throw unavailable();
    Path root = releases.toRealPath();
    Path asset = root.resolve(relative);
    if (!Files.isRegularFile(asset, LinkOption.NOFOLLOW_LINKS)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    Path real = asset.toRealPath();
    if (!real.startsWith(root)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return real;
  }
  private ResponseStatusException unavailable() {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "源端发布包尚未就绪");
  }

  @GetMapping("/agent/releases/{version}/{filename}")
  public ResponseEntity<Resource> release(@PathVariable String version, @PathVariable String filename) throws Exception {
    validateVersion(version);
    if (!filename.matches("syncsrv-agent-((linux|darwin)-(amd64|arm64)(\\.tar\\.gz)?|windows-(amd64|arm64)\\.(exe|zip)|synology-(amd64|arm64)(\\.spk)?)(\\.sha256)?"))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    Path real = safeFile(version + "/" + filename);
    MediaType type = filename.endsWith(".zip") ? MediaType.parseMediaType("application/zip")
        : filename.endsWith(".tar.gz") ? MediaType.parseMediaType("application/gzip")
        : filename.endsWith(".sha256") ? MediaType.TEXT_PLAIN : MediaType.APPLICATION_OCTET_STREAM;
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(type)
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
        .contentLength(Files.size(real)).body(new FileSystemResource(real));
  }
}
