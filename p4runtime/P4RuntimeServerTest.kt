package fourward.p4runtime

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import p4.v1.P4RuntimeGrpcKt.P4RuntimeCoroutineStub
import p4.v1.P4RuntimeOuterClass.MasterArbitrationUpdate
import p4.v1.P4RuntimeOuterClass.ReadRequest
import p4.v1.P4RuntimeOuterClass.StreamMessageRequest
import p4.v1.P4RuntimeOuterClass.StreamMessageResponse
import p4.v1.P4RuntimeOuterClass.TableEntry
import p4.v1.P4RuntimeOuterClass.Uint128

/**
 * Regression test for the Netty event loop deadlock (#459).
 *
 * Uses the real [P4RuntimeServer] (Netty transport, TCP) to verify that a Read RPC completes while a
 * StreamChannel is open on the same HTTP/2 connection. Before the fix, gRPC-Java's Netty backend
 * assigned a single event loop thread per connection, causing the suspended StreamChannel coroutine
 * and the Read coroutine to deadlock.
 */
class P4RuntimeServerTest {

  private lateinit var server: P4RuntimeServer
  private lateinit var channel: ManagedChannel
  private lateinit var stub: P4RuntimeCoroutineStub

  @Before
  fun setUp() {
    server = P4RuntimeServer(port = 0).start()
    channel = ManagedChannelBuilder.forAddress("localhost", server.port())
      .usePlaintext()
      .build()
    stub = P4RuntimeCoroutineStub(channel)
  }

  @After
  fun tearDown() {
    channel.shutdownNow()
    server.stop()
  }

  @Test
  fun `Read completes while StreamChannel is open on the same connection`() = runBlocking {
    // Open a bidirectional StreamChannel and become master.
    val requests = Channel<StreamMessageRequest>(Channel.UNLIMITED)
    val responses = Channel<StreamMessageResponse>(Channel.UNLIMITED)

    val streamJob = launch {
      stub.streamChannel(requests.consumeAsFlow()).collect { responses.send(it) }
    }

    requests.send(
      StreamMessageRequest.newBuilder()
        .setArbitration(
          MasterArbitrationUpdate.newBuilder()
            .setDeviceId(1)
            .setElectionId(Uint128.newBuilder().setHigh(0).setLow(1))
        )
        .build()
    )

    // Wait for arbitration response.
    withTimeout(5_000) { responses.receive() }

    // Issue a Read RPC on the same channel (same HTTP/2 connection) while the
    // StreamChannel is open. Before #459, this would deadlock because Netty's
    // single event loop thread was blocked by the suspended StreamChannel.
    val readCompleted = withTimeout(5_000) {
      try {
        stub.read(
          ReadRequest.newBuilder()
            .setDeviceId(1)
            .addEntities(
              p4.v1.P4RuntimeOuterClass.Entity.newBuilder()
                .setTableEntry(TableEntry.newBuilder().setTableId(0))
            )
            .build()
        ).collect { /* drain */ }
        true
      } catch (e: io.grpc.StatusException) {
        // FAILED_PRECONDITION (no pipeline loaded) is fine — the point is the
        // RPC completed rather than deadlocking.
        true
      }
    }

    assertTrue("Read RPC should complete without deadlock", readCompleted)

    // Clean up the stream.
    requests.close()
    withTimeout(5_000) { streamJob.join() }
  }
}
