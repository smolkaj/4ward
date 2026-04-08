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

  suspend fun <T> withReadLock(block: suspend () -> T): T =
    withContext(Dispatchers.IO) {
      rwLock.readLock().lock()
      try {
        block()
      } finally {
        rwLock.readLock().unlock()
      }
    }

  /**
   * Non-suspend variant for use from blocking thread pool tasks (e.g.,
   * [ForkJoinPool][java.util.concurrent.ForkJoinPool]).
   */
  fun <T> withReadLockBlocking(block: () -> T): T {
    rwLock.readLock().lock()
    try {
      return block()
    } finally {
      rwLock.readLock().unlock()
    }
  }

  suspend fun <T> withWriteLock(block: suspend () -> T): T =
    withContext(Dispatchers.IO) {
      rwLock.writeLock().lock()
      try {
        block()
      } finally {
        rwLock.writeLock().unlock()
      }
    }

  /**
   * Non-suspend variant of [withWriteLock] for use from blocking contexts (e.g., [PacketBroker]).
   */
  fun <T> withWriteLockBlocking(block: () -> T): T {
    rwLock.writeLock().lock()
    try {
      return block()
    } finally {
      rwLock.writeLock().unlock()
    }
  }
}
