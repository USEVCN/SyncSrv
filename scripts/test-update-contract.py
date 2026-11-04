#!/usr/bin/env python3
"""Compile only update-contract code/tests into a disposable directory, using cached dependencies.

No Maven build, dependency installation, package, service, database, or active target/classes writes.
JAVA_HOME selects Java 17; SYNCSRV_TEST_CLASSPATH may replace an existing Surefire classpath.
"""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent


def classpath():
    value = os.environ.get('SYNCSRV_TEST_CLASSPATH')
    if not value:
        for report in (ROOT / 'Backend/target/surefire-reports').glob('TEST-*.xml'):
            value = next((p.get('value') for p in ET.parse(report).findall('.//property') if p.get('name') == 'java.class.path'), None)
            if value:
                break
    if not value:
        raise ValueError('Existing cached test classpath required; set SYNCSRV_TEST_CLASSPATH. No dependencies were downloaded.')
    paths = value.split(os.pathsep)
    # A stale Surefire report must not silently test an older Jackson security patch.
    pom = ROOT / 'Backend/pom.xml'
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    if pom.is_file():
        declared = ET.parse(pom).findtext('m:properties/m:jackson-bom.version', namespaces=ns)
        if declared:
            bom = Path.home() / '.m2/repository/com/fasterxml/jackson/jackson-bom' / declared / ('jackson-bom-' + declared + '.pom')
            if not bom.is_file():
                raise ValueError('Declared Jackson BOM is not cached; dependencies were not downloaded.')
            annotations = ET.parse(bom).findtext('m:properties/m:jackson.version.annotations', namespaces=ns)
            aligned = []
            for item in paths:
                path = Path(item)
                if '/com/fasterxml/jackson/' in item and path.suffix == '.jar':
                    artifact = path.parent.parent.name
                    version = annotations if artifact == 'jackson-annotations' else declared
                    if not version or '${' in version:
                        raise ValueError('Cannot resolve cached Jackson annotation version.')
                    path = path.parent.parent / version / (artifact + '-' + version + '.jar')
                    if not path.is_file():
                        raise ValueError('Declared Jackson dependency is not cached: ' + artifact + '; no download attempted.')
                aligned.append(str(path))
            paths = aligned
    if not any('/junit-platform-launcher/' in p for p in paths):
        engine = next((Path(p) for p in paths if '/junit-platform-engine/' in p), None)
        if engine:
            version = engine.parent.name
            launcher = engine.parent.parent.parent / 'junit-platform-launcher' / version / ('junit-platform-launcher-' + version + '.jar')
            if launcher.is_file():
                paths.append(str(launcher))
    if not any('/junit-platform-launcher/' in p for p in paths):
        raise ValueError('Matching cached JUnit launcher required; dependencies were not installed.')
    return os.pathsep.join(paths)


def main():
    arguments = sys.argv[1:]
    if arguments:
        if len(arguments) != 3 or Path(arguments[0]).stat().st_size > 262144 or Path(arguments[1]).stat().st_size > 131072:
            raise ValueError('Optional offline metadata check: INDEX_FILE SIDECAR_FILE FEED_URL')
    java_home = os.environ.get('JAVA_HOME')
    if not java_home:
        local = Path.home() / 'Library/Java/JavaVirtualMachines/ms-17.0.17/Contents/Home'
        java_home = str(local) if local.is_dir() else None
    javac = str(Path(java_home) / 'bin/javac') if java_home else shutil.which('javac')
    java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
    if not java or not javac or not subprocess.check_output([javac, '-version'], text=True).startswith('javac 17.'):
        raise ValueError('Set JAVA_HOME to an existing Java 17 installation.')
    cp = classpath()
    with tempfile.TemporaryDirectory(prefix='update-contract-test-') as temporary:
        output = Path(temporary)
        runner = output / 'RunUpdateTests.java'
        runner.write_text('''import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.*;
public class RunUpdateTests { public static void main(String[] args) {
 if (args.length == 3) { try {
  var index = com.oadig.syncsrv.shared.Json.MAPPER.readTree(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[0])));
  var release = com.oadig.syncsrv.modules.operations.ReleaseContract.resolve(java.net.URI.create(args[2]), index,
   java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[1])), com.oadig.syncsrv.modules.operations.ReleaseTrust.keys(), java.time.Instant.now());
  System.out.println("Publisher contract verified: " + release.path("version").asText()); return;
 } catch (Exception error) { System.err.println(error.getMessage()); System.exit(1); } }
 var listener = new SummaryGeneratingListener();
 var request = LauncherDiscoveryRequestBuilder.request().selectors(
  selectClass("com.oadig.syncsrv.modules.operations.ReleaseContractTest"),
  selectClass("com.oadig.syncsrv.modules.operations.CenterUpdateTest"),
  selectClass("com.oadig.syncsrv.modules.operations.UpdateServerSourceTest"),
  selectClass("com.oadig.syncsrv.AgentDistributionTest")).build();
 var launcher = LauncherFactory.create(); launcher.registerTestExecutionListeners(listener); launcher.execute(request);
 var summary = listener.getSummary(); var out = new java.io.PrintWriter(System.out, true);
 summary.printTo(out); summary.printFailuresTo(out);
 if (summary.getTestsFoundCount() == 0 || summary.getTestsFailedCount() > 0) System.exit(1);
}}''')
        main_root = ROOT / 'Backend/src/main/java/com/oadig/syncsrv'
        tests = ROOT / 'Backend/src/test/java/com/oadig/syncsrv/modules/operations'
        files = [main_root / 'modules/operations' / name for name in
                 ['ReleaseTrust.java', 'ReleaseContract.java', 'CenterUpdateService.java', 'CenterUpdateController.java', 'UpdateSourceConfiguration.java']]
        files += [main_root / 'app/SyncSrvApplication.java', tests / 'ReleaseContractTest.java', tests / 'CenterUpdateTest.java', tests / 'UpdateServerSourceTest.java',
                  ROOT / 'Backend/src/test/java/com/oadig/syncsrv/AgentDistributionTest.java', runner]
        subprocess.run([javac, '--release', '17', '-cp', cp, '-d', str(output), *map(str, files)], check=True)
        agent = next(p for p in cp.split(os.pathsep) if '/mockito-core/' in p)
        subprocess.run([java, '-javaagent:' + agent, '-cp', str(output) + os.pathsep + cp, 'RunUpdateTests', *arguments], check=True)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        raise SystemExit(str(error))
