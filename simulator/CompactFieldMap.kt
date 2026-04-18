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
 * [fieldNames] and [indexByName] are shared across all instances of the same header type (by
 * reference, via [copy] / [deepCopy]). [fieldValues] is per-instance. Lookup is O(1) via the shared
 * [indexByName] HashMap.
 */
class CompactFieldMap
private constructor(
  private val fieldNames: Array<String>,
  private val fieldValues: Array<Value?>,
  /**
   * Shared across all copies of the same type. One HashMap per header/struct TYPE, not per instance
   * — amortized to zero per fork-copy.
   */
  private val indexByName: HashMap<String, Int>,
) : AbstractMutableMap<String, Value>() {

  override val size: Int
    get() = fieldNames.size

  private fun indexOf(key: String): Int = indexByName.getOrDefault(key, -1)

  override fun containsKey(key: String): Boolean = key in indexByName

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

  /** Shallow copy: shared keys + index map, independent values array. */
  fun copy(): CompactFieldMap = CompactFieldMap(fieldNames, fieldValues.copyOf(), indexByName)

  /**
   * Deep copy: shared keys + index map, each value deep-copied. Primitives return `this`, so only
   * nested aggregates (HeaderVal, StructVal, HeaderStackVal) allocate.
   */
  fun deepCopy(): CompactFieldMap {
    val newValues = fieldValues.copyOf()
    for (i in newValues.indices) newValues[i] = newValues[i]?.deepCopy()
    return CompactFieldMap(fieldNames, newValues, indexByName)
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
      val index = HashMap<String, Int>(keys.size * 4 / 3 + 1)
      for (i in keys.indices) index[keys[i]] = i
      return CompactFieldMap(keys, values, index)
    }
  }
}
