package fourward.simulator

import fourward.sim.v1.SimRequest
import fourward.sim.v1.SimResponse
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Entry point for the 4ward simulator subprocess.
 *
 * Reads [SimRequest] messages from stdin and writes [SimResponse] messages to stdout, using
 * length-delimited framing: each message is preceded by a 4-byte big-endian integer giving the
 * number of bytes in the serialised message body.
 *
 * This is the same framing used by Bazel's persistent worker protocol and the Language Server
 * Protocol. It is simple, reliable, and well-supported in both Kotlin and Go.
 *
 * Stderr is reserved for diagnostic output (startup messages, unexpected errors). Never write
 * SimResponse bytes to stderr or diagnostic text to stdout.
 */
fun main() {
  System.err.println("4ward simulator starting")

  val input = DataInputStream(System.`in`.buffered())
  val output = DataOutputStream(System.out.buffered())
  val simulator = Simulator()

  while (true) {
    val request = readRequest(input) ?: break
    // Catch-all at the subprocess boundary: any uncaught exception becomes an
    // ErrorResponse so the controller can report it gracefully rather than
    // receiving a truncated or missing response.
    @Suppress("TooGenericExceptionCaught")
    val response =
      try {
        simulator.handle(request)
      } catch (e: Exception) {
        System.err.println("error handling request: ${e.message}")
        e.printStackTrace(System.err)
        SimResponse.newBuilder()
          .setError(
            fourward.sim.v1.ErrorResponse.newBuilder()
              .setCode(fourward.sim.v1.ErrorCode.INTERNAL_ERROR)
              .setMessage(e.message ?: "unknown error")
          )
          .build()
      }
    writeResponse(output, response)
  }

  System.err.println("4ward simulator exiting")
}

/**
 * Reads one [SimRequest] from [input].
 *
 * Returns null on EOF (the controller closed the pipe — normal shutdown). Throws on IO errors or
 * malformed messages.
 */
private fun readRequest(input: DataInputStream): SimRequest? {
  val length =
    try {
      input.readInt() // 4-byte big-endian length prefix
    } catch (e: java.io.EOFException) {
      return null // clean EOF: controller exited
    }
  val bytes = ByteArray(length)
  input.readFully(bytes)
  return SimRequest.parseFrom(bytes)
}

/**
 * Writes one [SimResponse] to [output] with a 4-byte big-endian length prefix.
 *
 * Flushes immediately so the controller receives the response without waiting for the output buffer
 * to fill.
 */
private fun writeResponse(output: DataOutputStream, response: SimResponse) {
  val bytes = response.toByteArray()
  output.writeInt(bytes.size)
  output.write(bytes)
  output.flush()
}
