package com.datastax.spark.connector


import java.net.InetAddress

import com.datastax.spark.connector.cql._
import com.datastax.spark.connector.mapper.ColumnMapper
import com.datastax.spark.connector.rdd.partitioner.{CassandraPartitionedRDD, ReplicaPartitioner}
import com.datastax.spark.connector.rdd.reader._
import com.datastax.spark.connector.rdd.{CassandraJoinRDD, SpannedRDD, ValidRDDType}
import com.datastax.spark.connector.writer.{ReplicaMapper, _}
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD

import scala.reflect.ClassTag


/** Provides Cassandra-specific methods on `RDD` */
class RDDFunctions[T](rdd: RDD[T]) extends WritableToCassandra[T] with Serializable {

  override val sparkContext: SparkContext = rdd.sparkContext

  /**
   * Saves the data from `RDD` to a Cassandra table. Uses the specified column names.
   * @see [[com.datastax.spark.connector.writer.WritableToCassandra]]
   */
  def saveToCassandra(keyspaceName: String,
                      tableName: String,
                      columns: ColumnSelector = AllColumns,
                      writeConf: WriteConf = WriteConf.fromSparkConf(sparkContext.getConf))
                     (implicit connector: CassandraConnector = CassandraConnector(sparkContext.getConf),
                      rwf: RowWriterFactory[T]): Unit = {

    val writer = TableWriter(connector, keyspaceName, tableName, columns, writeConf)
    rdd.sparkContext.runJob(rdd, writer.write _)
  }

  /**
   * Saves the data from `RDD` to a new table defined by the given `TableDef`.
   *
   * First it creates a new table with all columns from the `TableDef`
   * and then it saves RDD content in the same way as [[saveToCassandra]].
   * The table must not exist prior to this call.
   *
   * @param table table definition used to create a new table
   * @param columns Selects the columns to save data to.
   *                Uses only the unique column names, and you must select at least all primary key
   *                 columns. All other fields are discarded. Non-selected property/column names are left unchanged.
   *                 This parameter does not affect table creation.
   * @param writeConf additional configuration object allowing to set consistency level, batch size, etc.
   * @param connector optional, implicit connector to Cassandra
   * @param rwf factory for obtaining the row writer to be used to extract column values
   *            from items of the `RDD`
   */
  def saveAsCassandraTableEx(table: TableDef,
                             columns: ColumnSelector = AllColumns,
                             writeConf: WriteConf = WriteConf.fromSparkConf(sparkContext.getConf))
                            (implicit connector: CassandraConnector = CassandraConnector(sparkContext.getConf),
                             rwf: RowWriterFactory[T]): Unit = {

    connector.withSessionDo(session => session.execute(table.cql))
    saveToCassandra(table.keyspaceName, table.tableName, columns, writeConf)
  }

  /**
   * Saves the data from `RDD` to a new table with definition taken from the
   * `ColumnMapper` for this class.
   *
   * @param keyspaceName keyspace where to create a new table
   * @param tableName name of the table to create; the table must not exist
   * @param columns Selects the columns to save data to.
   *                Uses only the unique column names, and you must select at least all primary key
   *                 columns. All other fields are discarded. Non-selected property/column names are left unchanged.
   *                 This parameter does not affect table creation.
   * @param writeConf additional configuration object allowing to set consistency level, batch size, etc.
   * @param connector optional, implicit connector to Cassandra
   * @param rwf factory for obtaining the row writer to be used to extract column values
   *            from items of the `RDD`
   * @param columnMapper a column mapper determining the definition of the table
   */
  def saveAsCassandraTable(keyspaceName: String,
                           tableName: String,
                           columns: ColumnSelector = AllColumns,
                           writeConf: WriteConf = WriteConf.fromSparkConf(sparkContext.getConf))
                          (implicit connector: CassandraConnector = CassandraConnector(sparkContext.getConf),
                           rwf: RowWriterFactory[T],
                           columnMapper: ColumnMapper[T]): Unit = {

    val table = TableDef.fromType[T](keyspaceName, tableName)
    saveAsCassandraTableEx(table, columns, writeConf)
  }

  /** Applies a function to each item, and groups consecutive items having the same value together.
    * Contrary to `groupBy`, items from the same group must be already next to each other in the
    * original collection. Works locally on each partition, so items from different
    * partitions will never be placed in the same group. */
  def spanBy[U](f: (T) => U): RDD[(U, Iterable[T])] =
    new SpannedRDD[U, T](rdd, f)

  /**
   * Uses the data from `RDD` to join with a Cassandra table without retrieving the entire table.
   * Any RDD which can be used to saveToCassandra can be used to joinWithCassandra as well as any
   * RDD which only specifies the partition Key of a Cassandra Table. This method executes single
   * partition requests against the Cassandra Table and accepts the functional modifiers that a
   * normal [[com.datastax.spark.connector.rdd.CassandraTableScanRDD]] takes.
   *
   * By default this method only uses the Partition Key for joining but any combination of columns
   * which are acceptable to C* can be used in the join. Specify columns using joinColumns as a parameter
   * or the on() method.
   *
   * Example With Prior Repartitioning: {{{
   * val source = sc.parallelize(keys).map(x => new KVRow(x))
   * val repart = source.repartitionByCassandraReplica(keyspace, tableName, 10)
   * val someCass = repart.joinWithCassandraTable(keyspace, tableName)
   * }}}
   *
   * Example Joining on Clustering Columns: {{{
   * val source = sc.parallelize(keys).map(x => (x, x * 100))
   * val someCass = source.joinWithCassandraTable(keyspace, wideTable).on(SomeColumns("key", "group"))
   * }}}
   **/
  def joinWithCassandraTable[R](keyspaceName: String, tableName: String,
                                selectedColumns: ColumnSelector = AllColumns,
                                joinColumns: ColumnSelector = PartitionKeyColumns)
                               (implicit connector: CassandraConnector = CassandraConnector(sparkContext.getConf),
                                newType: ClassTag[R], rrf: RowReaderFactory[R], ev: ValidRDDType[R],
                                currentType: ClassTag[T], rwf: RowWriterFactory[T]): CassandraJoinRDD[T, R] = {
    new CassandraJoinRDD[T, R](rdd, keyspaceName, tableName, connector, columnNames = selectedColumns, joinColumns = joinColumns)
  }


  /**
   * Repartitions the data (via a shuffle) based upon the replication of the given `keyspaceName` and `tableName`. 
   * Calling this method before using joinWithCassandraTable will ensure that requests will be coordinator
   * local. `partitionsPerHost` Controls the number of Spark Partitions that will be created in this repartitioning
   * event.
   * The calling RDD must have rows that can be converted into the partition key of the given Cassandra Table.
   **/
  def repartitionByCassandraReplica(keyspaceName: String, tableName: String, partitionsPerHost: Int = 10)
                                   (implicit connector: CassandraConnector = CassandraConnector(sparkContext.getConf),
                                    currentType: ClassTag[T], rwf: RowWriterFactory[T]) = {
    val part = new ReplicaPartitioner(partitionsPerHost, connector)
    val repart = rdd.keyByCassandraReplica(keyspaceName, tableName).partitionBy(part)
    val output = repart.mapPartitions(_.map(_._2), preservesPartitioning = true)
    new CassandraPartitionedRDD[T](output)
  }

  /**
   * Key every row in the RDD by with the IP Adresses of all of the Cassandra nodes which a contain a replica
   * of the data specified by that row.
   * The calling RDD must have rows that can be converted into the partition key of the given Cassandra Table.
   */
  def keyByCassandraReplica(keyspaceName: String, tableName: String)
                           (implicit connector: CassandraConnector = CassandraConnector(sparkContext.getConf),
                            currentType: ClassTag[T], rwf: RowWriterFactory[T]): RDD[(Set[InetAddress], T)] = {
    val converter = ReplicaMapper[T](connector, keyspaceName, tableName)
    rdd.mapPartitions(primaryKey =>
      converter.keyByReplicas(primaryKey)
    )
  }


}
