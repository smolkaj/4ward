package fourward.simulator

/**
 * Static layout of a P4 header stack: a fixed-size array of headers plus a `nextIndex` counter (P4
 * spec §8.18).
 *
 * Memory layout within the buffer (relative to the stack's [base]):
 * ```
 * [nextIndex: nextIndexWidth bits | element[0] | element[1] | ... | element[size-1]]
 * ```
 *
 * Static `stack[i]` indexing compiles to a constant offset; dynamic `i` becomes one
 * multiply-and-add at access time.
 */
data class HeaderStackLayout(
  val typeName: String,
  val elementLayout: HeaderLayout,
  val size: Int,
  val nextIndexWidth: Int = NEXT_INDEX_WIDTH,
) {
  init {
    require(size > 0) { "header stack size must be positive, got $size" }
  }

  /** Bit offset of the `nextIndex` counter within the stack. */
  val nextIndexOffset: Int = 0

  /** Bit offset of the first element (just past the counter). */
  val firstElementOffset: Int = nextIndexWidth

  /** Bit offset of element `[index]` within the stack. */
  fun elementOffset(index: Int): Int = firstElementOffset + index * elementLayout.totalBits

  /** Total bit width of this stack: counter + size × element. */
  val totalBits: Int = nextIndexWidth + size * elementLayout.totalBits

  companion object {
    /** Width of the `nextIndex` counter. 8 bits supports stacks up to 255 deep, plenty for P4. */
    const val NEXT_INDEX_WIDTH: Int = 8
  }
}

/**
 * A view onto a [HeaderStackLayout]'s elements in a [PacketBuffer], anchored at a [base] bit
 * offset.
 *
 * Replaces the legacy `HeaderStackVal` (a heap-allocated `MutableList<Value>` plus an `Int`
 * `nextIndex`). All state — counter and elements — lives in the buffer; `pushFront`/`popFront`
 * become block memcpys within the buffer.
 */
class HeaderStackView(val buffer: PacketBuffer, val layout: HeaderStackLayout, val base: Int = 0) :
  Value() {

  /** Returns a view over an independent copy of [buffer]. */
  override fun deepCopy(): HeaderStackView = HeaderStackView(buffer.copyOf(), layout, base)

  /** Current `nextIndex` value, stored in the first [layout.nextIndexWidth] bits of the stack. */
  var nextIndex: Int
    get() = buffer.readBits(base + layout.nextIndexOffset, layout.nextIndexWidth).toInt()
    set(value) {
      require(value in 0..layout.size) { "nextIndex must be in 0..${layout.size}, got $value" }
      buffer.writeBits(base + layout.nextIndexOffset, layout.nextIndexWidth, value.toLong())
    }

  /** Number of slots in the stack. Fixed at layout time. */
  val size: Int
    get() = layout.size

  /** Returns a [HeaderView] for element `[index]`. Throws on out-of-range. */
  operator fun get(index: Int): HeaderView {
    require(index in 0 until layout.size) {
      "header-stack index $index out of range 0..${layout.size}"
    }
    return HeaderView(buffer, layout.elementLayout, base + layout.elementOffset(index))
  }

  /**
   * Shifts elements towards higher indices by [count] (P4 spec §8.18). Elements that fall off the
   * end are lost; the first [count] slots become invalid (zeroed).
   */
  fun pushFront(count: Int) {
    require(count >= 0) { "pushFront count must be non-negative, got $count" }
    if (count == 0) return
    val effectiveCount = minOf(count, layout.size)
    val elementBits = layout.elementLayout.totalBits
    // Move elements [0..size-effectiveCount-1] to [effectiveCount..size-1].
    // Iterate from the high end downwards so we don't overwrite source data.
    for (i in (layout.size - effectiveCount - 1) downTo 0) {
      copyElement(srcIndex = i, dstIndex = i + effectiveCount, elementBits = elementBits)
    }
    // Zero the new low slots.
    for (i in 0 until effectiveCount) {
      buffer.zeroRange(base + layout.elementOffset(i), elementBits)
    }
    nextIndex = minOf(nextIndex + count, layout.size)
  }

  /**
   * Shifts elements towards lower indices by [count] (P4 spec §8.18). The first [count] elements
   * are dropped; the last [count] slots become invalid (zeroed).
   */
  fun popFront(count: Int) {
    require(count >= 0) { "popFront count must be non-negative, got $count" }
    if (count == 0) return
    val effectiveCount = minOf(count, layout.size)
    val elementBits = layout.elementLayout.totalBits
    for (i in 0 until (layout.size - effectiveCount)) {
      copyElement(srcIndex = i + effectiveCount, dstIndex = i, elementBits = elementBits)
    }
    for (i in (layout.size - effectiveCount) until layout.size) {
      buffer.zeroRange(base + layout.elementOffset(i), elementBits)
    }
    nextIndex = maxOf(nextIndex - count, 0)
  }

  /** Block-copies element [srcIndex] over element [dstIndex]. */
  private fun copyElement(srcIndex: Int, dstIndex: Int, elementBits: Int) {
    var remaining = elementBits
    var srcBit = base + layout.elementOffset(srcIndex)
    var dstBit = base + layout.elementOffset(dstIndex)
    while (remaining > 0) {
      val chunk = minOf(Long.SIZE_BITS, remaining)
      buffer.writeBits(dstBit, chunk, buffer.readBits(srcBit, chunk))
      srcBit += chunk
      dstBit += chunk
      remaining -= chunk
    }
  }
}
