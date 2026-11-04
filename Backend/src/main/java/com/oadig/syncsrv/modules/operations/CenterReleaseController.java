package com.oadig.syncsrv.modules.operations;
import com.oadig.syncsrv.shared.Json;
import com.oadig.syncsrv.shared.web.ApiResponse;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
public final class CenterReleaseController {
 private final Path root;
 public CenterReleaseController(@Value("${syncsrv.center-releases:artifacts/center-releases}")String directory){root=Path.of(directory).toAbsolutePath().normalize();}
 private Path file(String relative)throws Exception{
  if(!Files.isDirectory(root))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"中心发布仓库尚未配置");
  Path real=root.toRealPath(),candidate=real.resolve(relative);
  if(!Files.isRegularFile(candidate,LinkOption.NOFOLLOW_LINKS)||!candidate.toRealPath().startsWith(real))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  return candidate.toRealPath();
 }
 private static void version(String version){if(!version.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))throw new ResponseStatusException(HttpStatus.NOT_FOUND);}
 @GetMapping({"/center/releases/latest","/api/center-release"})
 public ResponseEntity<String> latest()throws Exception{
  Path pointer=file("latest.txt");if(Files.size(pointer)>80)throw new SecurityException("Invalid release pointer");
  return manifest(Files.readString(pointer).strip());
 }
 @GetMapping("/center/releases/{version}/manifest")
 public ResponseEntity<String> manifest(@PathVariable String version)throws Exception{
  version(version);Path manifest=file(version+"/release.json");if(Files.size(manifest)>131072)throw new SecurityException("Manifest too large");
  String text=Files.readString(manifest);var release=ReleaseTrust.verify(Json.MAPPER.readTree(text),"SyncSrv");
  if(!version.equals(release.path("version").asText()))throw new SecurityException("Release identity mismatch");
  // Feed is raw signed bytes, consumed directly by the offline privileged updater.
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(text);
 }
 @GetMapping("/center/releases/{version}/{filename}")
 public ResponseEntity<Resource> download(@PathVariable String version,@PathVariable String filename)throws Exception{
  version(version);if(!filename.equals("syncsrv-"+version+"-linux-x64.tar.gz"))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  manifest(version);Path asset=file(version+"/"+filename);
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("application/gzip"))
   .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(filename).build().toString()).contentLength(Files.size(asset)).body(new FileSystemResource(asset));
 }
}
