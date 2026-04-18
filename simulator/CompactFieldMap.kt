package fourward.simulator

/**
 * A [MutableMap] backed by parallel arrays of field names and values, for the small, static-key P4
 * header field maps.
 *
 * [fieldNames] is shared across all copies of the same header type; [fieldValues] is per-instance.
 * [copy] is a single `fieldValues.copyOf()` — no per-entry allocation, unlike [LinkedHashMap] which
 * allocates a `Node` per entry. Lookup is linear scan on [fieldNames], competitive with [HashMap]
 * for N ≤ ~20 entries.
 */
class CompactFieldMap
private constructor(private val fieldNames: Array<String>, private val fieldValues: Array<Value?>) :
  AbstractMutableMap<String, Value>() {

  init {
    require(fieldNames.size == fieldValues.size) {
      "fieldNames/fieldValues size mismatch: ${fieldNames.size} vs ${fieldValues.size}"
    }
  }

  override val size: Int
    get() = fieldNames.size

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

  override fun clear() {
    fieldValues.fill(null)
  }

  /** Shallow copy: shared field names, independent values array. */
  fun copy(): CompactFieldMap = CompactFieldMap(fieldNames, fieldValues.copyOf())

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
    /** Creates from a list of (name, value) pairs. Field order is preserved. */
    fun of(entries: List<Pair<String, Value>>): CompactFieldMap {
      val keys = Array(entries.size) { i -> entries[i].first }
      val values = Array<Value?>(entries.size) { i -> entries[i].second }
      return CompactFieldMap(keys, values)
    }
  }
}
