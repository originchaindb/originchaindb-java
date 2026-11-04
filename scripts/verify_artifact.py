"""Install a built candidate into a temporary application. No registry uploads."""

import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT / "artifacts"


def run(command, cwd, env=None):
    subprocess.run([str(value) for value in command], cwd=cwd, env=env, check=True)


with tempfile.TemporaryDirectory(prefix="originchaindb-install-") as temporary:
    app = Path(temporary)
    artifact = next(
        (
            path
            for path in ARTIFACTS.glob("*.jar")
            if not path.name.endswith(("-sources.jar", "-javadoc.jar"))
        )
    )
    cp = str(artifact) + os.pathsep + str(ROOT / "target/dependency/*")
    java = Path(os.environ["JAVA_HOME"]) / "bin"
    (app / "InstallCheck.java").write_text(
        'import ai.originchain.*;import java.util.List;public class InstallCheck{public static void main(String[]a){var db=new Client("https://example.invalid","test");try(var batches=db.rows().writeBatches("app.items",List.of(),new BatchOptions())){}try(var pages=db.rows().pages("app.items","id",new PageOptions())){}}}'
    )
    run([java / "javac", "-cp", cp, "InstallCheck.java"], app)
    run([java / "java", "-cp", cp + os.pathsep + str(app), "InstallCheck"], app)
print("Fresh package application passed: " + "java")
