package fourward.simulator

import java.util.concurrent.ConcurrentHashMap

/**
 * Bidirectional name ↔ integer-code mapping for P4 error members.
 *
 * P4 errors are nominally a distinct type ([fourward.ir.Type.hasError]), but p4c lowers them to a
 * fixed-width bit field — V1Model's `standard_metadata_t.parser_error` is emitted as `bit<32>`
 * after the midend. To round-trip [ErrorVal]s through a buffer-backed field map, we need a stable
 * name↔bit-pattern mapping.
 *
 * The seven spec errors (§7.1.5) get fixed low codes. Program-declared errors (e.g.
 * `IPv4IncorrectVersion`) get codes lazily assigned on first encode, keyed off the name so the
 * mapping is stable for decode. Lookups are thread-safe.
 */
internal object ErrorCodes {

  @Suppress("MagicNumber")
  private val STANDARD: Map<String, Int> =
    linkedMapOf(
      "NoError" to 0,
      "PacketTooShort" to 1,
      "NoMatch" to 2,
      "StackOutOfBounds" to 3,
      "HeaderTooShort" to 4,
      "ParserTimeout" to 5,
      "ParserInvalidArgument" to 6,
    )

  private const val DYNAMIC_BASE = 1024

  /** Live mapping (includes [STANDARD] plus any dynamically registered names). */
  private val nameToCode: ConcurrentHashMap<String, Int> = ConcurrentHashMap(STANDARD)
  private val codeToName: ConcurrentHashMap<Int, String> =
    ConcurrentHashMap<Int, String>().apply { STANDARD.forEach { (n, c) -> put(c, n) } }

  /** Next code to hand out for a previously unseen error name. */
  private var nextDynamicCode: Int = DYNAMIC_BASE

  @Synchronized
  private fun registerDynamic(name: String): Int {
    nameToCode[name]?.let {
      return it
    }
    val code = nextDynamicCode++
    nameToCode[name] = code
    codeToName[code] = name
    return code
  }

  fun encode(name: String): Long = (nameToCode[name] ?: registerDynamic(name)).toLong()

  fun decode(code: Long): String =
    codeToName[code.toInt()]
      ?: throw IllegalArgumentException("no P4 error registered for code $code")
}
