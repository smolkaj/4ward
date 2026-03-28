package fourward.p4runtime

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Coroutine-friendly read-write lock. Multiple readers can proceed concurrently; writers get
 * exclusive access. Uses [Dispatchers.IO] to avoid blocking coroutine threads while holding locks.
 */
class ReadWriteMutex {
  private val rwLock = ReentrantReadWriteLock()

  /** Acquires the read lock, executes [block], and releases. Multiple readers run concurrently. */
  suspend fun <T> withReadLock(block: suspend () -> T): T =
    withContext(Dispatchers.IO) {
      rwLock.readLock().lock()
      try {
        block()
      } finally {
        rwLock.readLock().unlock()
      }
    }

  /** Acquires the write lock, executes [block], and releases. Exclusive — no concurrent readers. */
  suspend fun <T> withWriteLock(block: suspend () -> T): T =
    withContext(Dispatchers.IO) {
      rwLock.writeLock().lock()
      try {
        block()
      } finally {
        rwLock.writeLock().unlock()
      }
    }
}
