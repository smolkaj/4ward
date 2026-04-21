package fourward.bazel

import java.nio.file.Files
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RunfilesTest {

  @Test
  fun `repoRoot resolves to an existing directory`() {
    assertTrue("repoRoot should exist: $repoRoot", Files.isDirectory(repoRoot))
  }

  @Test
  fun `repoRoot-relative main-repo file resolves`() {
    val path = repoRoot.resolve("bazel/Runfiles.kt")
    assertTrue("resolved path should exist: $path", Files.isRegularFile(path))
  }

  @Test
  fun `repoRoot-relative main-repo binary resolves`() {
    val path = repoRoot.resolve("p4c_backend/p4c-4ward")
    assertTrue("binary should exist and be executable: $path", Files.isExecutable(path))
  }

  @Test
  fun `resolveRunfileProperty reads a BUILD-injected rlocationpath`() {
    val path = resolveRunfileProperty("fourward.p4include")
    assertTrue("resolved path should exist: $path", Files.isRegularFile(path))
  }

  @Test
  fun `resolveRunfileProperty throws when the property is unset`() {
    val e =
      assertThrows(IllegalStateException::class.java) {
        resolveRunfileProperty("fourward.definitely_unset_property")
      }
    assertTrue("error should name the missing property", e.message!!.contains("definitely_unset"))
  }
}
