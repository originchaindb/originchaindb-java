"""Install the actual JAR and its embedded POM in a fresh Maven application."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT / "artifacts"


def run(command, cwd):
    subprocess.run([str(value) for value in command], cwd=cwd, check=True)


artifact = next(
    path
    for path in ARTIFACTS.glob("*.jar")
    if not path.name.endswith(("-sources.jar", "-javadoc.jar"))
)
with zipfile.ZipFile(artifact) as archive:
    descriptor = archive.read("META-INF/maven/ai.originchain/originchaindb/pom.xml")
pom = ET.fromstring(descriptor)
namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
assert pom.findtext("m:groupId", namespaces=namespace) == "ai.originchain"
assert pom.findtext("m:artifactId", namespaces=namespace) == "originchaindb"
version = pom.findtext("m:version", namespaces=namespace)
assert version and all(
    character.isalnum() or character in ".-" for character in version
)
pom_path = ARTIFACTS / f"originchaindb-{version}.pom"
pom_path.write_bytes(descriptor)
maven = (
    os.environ.get("MAVEN_COMMAND") or shutil.which("mvn.cmd") or shutil.which("mvn")
)
if not maven:
    raise RuntimeError("Maven is required for fresh application verification")
java = Path(os.environ["JAVA_HOME"]) / "bin"

with tempfile.TemporaryDirectory(prefix="originchaindb-install-") as temporary:
    app = Path(temporary)
    repository = app / "repository"
    command = [maven, "-B", "-ntp", f"-Dmaven.repo.local={repository}"]
    run(
        [
            *command,
            "org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file",
            f"-Dfile={artifact}",
            f"-DpomFile={pom_path}",
        ],
        app,
    )
    (app / "pom.xml").write_text(
        '<project xmlns="http://maven.apache.org/POM/4.0.0">'
        "<modelVersion>4.0.0</modelVersion><groupId>example</groupId>"
        "<artifactId>install-check</artifactId><version>1</version>"
        "<properties><maven.compiler.release>17</maven.compiler.release>"
        "<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>"
        "<dependencies><dependency><groupId>ai.originchain</groupId>"
        f"<artifactId>originchaindb</artifactId><version>{version}</version>"
        "</dependency></dependencies><build><plugins><plugin>"
        "<groupId>org.apache.maven.plugins</groupId>"
        "<artifactId>maven-compiler-plugin</artifactId><version>3.14.1</version>"
        "</plugin></plugins></build></project>",
        encoding="utf-8",
    )
    source = app / "src/main/java/InstallCheck.java"
    source.parent.mkdir(parents=True)
    source.write_text(
        "import ai.originchain.*;import java.util.List;"
        "public class InstallCheck{public static void main(String[]a){"
        'var db=new Client("https://example.invalid","test");'
        'try(var batches=db.rows().writeBatches("app.items",List.of(),new BatchOptions())){}'
        'try(var pages=db.rows().pages("app.items","id",new PageOptions())){}}}',
        encoding="utf-8",
    )
    run(
        [
            *command,
            "compile",
            "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath",
            "-Dmdep.outputFile=classpath.txt",
            "-DincludeScope=runtime",
        ],
        app,
    )
    installed = repository / f"ai/originchain/originchaindb/{version}/{artifact.name}"
    assert installed.read_bytes() == artifact.read_bytes()
    classpath = (app / "classpath.txt").read_text(encoding="utf-8").strip()
    assert installed.resolve() in {
        Path(entry).resolve() for entry in classpath.split(os.pathsep)
    }
    run(
        [
            java / "java",
            "-cp",
            str(app / "target/classes") + os.pathsep + classpath,
            "InstallCheck",
        ],
        app,
    )
print("Fresh Maven application passed with isolated dependency resolution")
