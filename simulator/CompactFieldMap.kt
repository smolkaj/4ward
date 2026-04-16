package fourward.simulator

/**
 * A [MutableMap] backed by parallel arrays of keys and values, optimised for the small, static-key
 * P4 header field maps that dominate the fork-copy hot path.
 *
 * P4 headers have 3–15 fields whose names are fixed at pipeline-load time. The legacy
 * [LinkedHashMap] allocates a `Node` per entry on every `deepCopy` (128 forks × 25 headers × ~10
 * entries = ~32k Node allocations per packet). This map replaces that with a single
 * `fieldValues.copyOf()` call per header — no per-entry allocation, no rehashing, no bucket
 * management.
 *
 * [keys] is shared across all instances of the same header type (by reference). [values] is
 * per-instance. Lookup is linear scan on [keys], which is competitive with [HashMap] for N ≤ ~20
 * entries due to cache-line locality.
 */
class CompactFieldMap(
  private val fieldNames: Array<String>,
  private val fieldValues: Array<Value?>,
) : AbstractMutableMap<String, Value>() {

  init {
    require(fieldNames.size == fieldValues.size) {
      "keys/values size mismatch: ${fieldNames.size} vs ${fieldValues.size}"
    }
  }

  override val size: Int
    get() = fieldNames.size

  /** O(N) scan — fast for N ≤ 20 (1–2 cache lines). */
  private fun indexOf(key: String): Int {
    for (i in fieldNames.indices) if (fieldNames[i] == key) return i
    return -1
  }

  override fun containsKey(key: String): Boolean = indexOf(key) >= 0

  override fun get(key: String): Value? {
    val i = indexOf(key)
    return if (i >= 0) fieldValues[i] else null
  }

  override fun put(key: String, value: Value): Value? {
    val i = indexOf(key)
    require(i >= 0) { "no such field: '$key' (available: ${fieldNames.toList()})" }
    val old = fieldValues[i]
    fieldValues[i] = value
    return old
  }

  /** Zeros all values (used by [HeaderVal.setInvalid]'s `clear(); putAll(...)` pattern). */
  override fun clear() {
    fieldValues.fill(null)
  }

  /** Shallow copy: shared keys, independent values array (values themselves shared by ref). */
  fun copy(): CompactFieldMap = CompactFieldMap(fieldNames, fieldValues.copyOf())

  /**
   * Deep copy: shared keys, each value deep-copied. Primitives return `this`, so only nested
   * aggregates (HeaderVal, StructVal, HeaderStackVal) allocate — same semantics as the
   * LinkedHashMap deepCopy path but without per-entry Node allocation.
   */
  fun deepCopy(): CompactFieldMap {
    val newValues = fieldValues.copyOf()
    for (i in newValues.indices) newValues[i] = newValues[i]?.deepCopy()
    return CompactFieldMap(fieldNames, newValues)
  }

  // --- AbstractMutableMap plumbing ---

  override val entries: MutableSet<MutableMap.MutableEntry<String, Value>>
    get() =
      object : AbstractMutableSet<MutableMap.MutableEntry<String, Value>>() {
        override val size: Int
          get() = fieldNames.size

        override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, Value>> {
          var idx = 0
          return object : MutableIterator<MutableMap.MutableEntry<String, Value>> {
            override fun hasNext(): Boolean = idx < fieldNames.size

            override fun next(): MutableMap.MutableEntry<String, Value> {
              val i = idx++
              return object : MutableMap.MutableEntry<String, Value> {
                override val key: String
                  get() = fieldNames[i]

                override val value: Value
                  get() = fieldValues[i]!!

                override fun setValue(newValue: Value): Value {
                  val old = fieldValues[i]!!
                  fieldValues[i] = newValue
                  return old
                }
              }
            }

            override fun remove() {
              throw UnsupportedOperationException("cannot remove keys from a compact field map")
            }
          }
        }

        override fun add(element: MutableMap.MutableEntry<String, Value>): Boolean {
          put(element.key, element.value)
          return true
        }
      }

  companion object {
    /** Creates from a list of (name, value) pairs. Key order is preserved. */
    fun of(entries: List<Pair<String, Value>>): CompactFieldMap {
      val keys = Array(entries.size) { i -> entries[i].first }
      val values = Array<Value?>(entries.size) { i -> entries[i].second }
      return CompactFieldMap(keys, values)
    }
  }
}
