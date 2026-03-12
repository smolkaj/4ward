package fourward.simulator

import p4.v1.P4RuntimeOuterClass

/**
 * Read-only view of table data-plane state.
 *
 * Used by the P4Runtime layer's [fourward.p4runtime.EntityReader] to assemble table entry Entity
 * protos without depending on the full [Simulator].
 */
interface TableDataReader {
  fun getTableEntries(tableName: String): List<P4RuntimeOuterClass.TableEntry>

  fun getDefaultAction(tableName: String): DefaultAction?

  fun getDirectCounterData(entry: P4RuntimeOuterClass.TableEntry): P4RuntimeOuterClass.CounterData?

  fun getDirectMeterData(entry: P4RuntimeOuterClass.TableEntry): P4RuntimeOuterClass.MeterConfig?

  fun hasDirectCounter(tableName: String): Boolean

  fun hasDirectMeter(tableName: String): Boolean
}
