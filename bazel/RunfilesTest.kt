package fourward.bazel

import java.nio.file.Files
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RunfilesTest {

  @Test
  fun `resolves main-repo file`() {
    val path = resolveRunfile("_main/bazel/Runfiles.kt")
    assertTrue("resolved path should exist: $path", Files.isRegularFile(path))
  }

  @Test
  fun `resolves p4c-4ward binary`() {
    val path = resolveRunfile("_main/p4c_backend/p4c-4ward")
    assertTrue(Files.isExecutable(path))
  }

  @Test
  fun `resolves external-repo file via rlocationpath property`() {
    // External repos use canonical names that differ across environments.
    // Production code gets the path via $(rlocationpath ...) in BUILD jvm_flags.
    // This test verifies the same mechanism works.
    val path = resolveRunfile(requireP4IncludeProperty())
    assertTrue("resolved path should exist: $path", Files.isRegularFile(path))
  }

  @Test
  fun `main-repo path without _main prefix fails`() {
    assertThrows(IllegalStateException::class.java) { resolveRunfile("bazel/RunfilesTest.kt") }
  }

  @Test
  fun `throws on missing file`() {
    val e =
      assertThrows(IllegalStateException::class.java) {
        resolveRunfile("_main/nonexistent/path.txt")
      }
    assertTrue("error should name the path", e.message!!.contains("nonexistent"))
  }
}
