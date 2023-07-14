package com.ber2.spark.sketches.theta

import org.apache.spark.sql.DataFrame
import com.ber2.spark.sketches.common.SparkBaseSpec
import com.ber2.spark.sketches.theta.SparkUdfs.{
  stringsToSketch,
  aggSketches,
  getEstimate,
  intersection,
  setDifference
}

class SparkUdfsSpec extends SparkBaseSpec {
  behavior of "Spark Theta UDFs and UDAFs"

  lazy val data: DataFrame = {
    import spark.implicits._
    Seq(
      ("key_1_1", "key_2_1", "document_1"),
      ("key_1_1", "key_2_1", "document_2"),
      ("key_1_1", "key_2_1", "document_3"),
      ("key_1_1", "key_2_1", "document_4"),
      ("key_1_1", "key_2_2", "document_1"),
      ("key_1_1", "key_2_2", "document_6"),
      ("key_1_1", "key_2_2", "document_7"),
      ("key_1_1", "key_2_2", "document_8"),
      ("key_1_2", "key_2_1", "document_1"),
      ("key_1_2", "key_2_1", "document_2"),
      ("key_1_2", "key_2_1", "document_3"),
      ("key_1_2", "key_2_1", "document_4"),
      ("key_1_2", "key_2_2", "document_5"),
      ("key_1_2", "key_2_2", "document_9"),
      ("key_1_2", "key_2_2", "document_10"),
      ("key_1_2", "key_2_2", "document_11")
    ).toDF("key_1", "key_2", "document")
  }

  lazy val aggData = {
    import spark.implicits._

    data
      .groupBy($"key_1")
      .agg(stringsToSketch($"document").as("theta_sketch"))
      .withColumn("cnt", getEstimate($"theta_sketch"))
  }

  it should "aggregate and merge" in {
    import spark.implicits._

    val preAgg = data
      .groupBy($"key_1", $"key_2")
      .agg(stringsToSketch($"document").as("theta_sketch"))

    val indirectTransform = preAgg
      .groupBy($"key_1")
      .agg(aggSketches($"theta_sketch").as("theta_sketch"))
      .withColumn("cnt", getEstimate($"theta_sketch"))

    assertDataFrameNoOrderEquals(aggData, indirectTransform)
  }

  it should "count uniques" in {
    import spark.implicits._

    val expectedCounts = Seq(
      ("key_1_1", 7L),
      ("key_1_2", 8L)
    ).toDF("key_1", "cnt")

    val actualCounts = aggData
      .drop($"theta_sketch")

    assertDataFrameNoOrderEquals(expectedCounts, actualCounts)
  }

  it should "compute overlaps" in {
    import spark.implicits._

    val left = aggData.filter($"key_1" === "key_1_1").drop($"cnt")
    val right = aggData.filter($"key_1" === "key_1_2").drop($"cnt")

    val expectedOverlap = Seq(4L).toDF("overlap")

    val actualOverlap =
      left
        .as("l")
        .crossJoin(right.as("r"))
        .withColumn(
          "overlap",
          getEstimate(
            intersection($"l.theta_sketch", $"r.theta_sketch")
          )
        )
        .select($"overlap")

    assertDataFrameEquals(expectedOverlap, actualOverlap)
  }

  it should "compute set differences" in {
    import spark.implicits._

    val left = aggData.filter($"key_1" === "key_1_1").drop($"cnt")
    val right = aggData.filter($"key_1" === "key_1_2").drop($"cnt")

    val expectedDifference = Seq(3L).toDF("difference")

    val actualDifference =
      left
        .as("l")
        .crossJoin(right.as("r"))
        .withColumn(
          "difference",
          getEstimate(
            setDifference($"l.theta_sketch", $"r.theta_sketch")
          )
        )
        .select($"difference")

    assertDataFrameEquals(expectedDifference, actualDifference)
  }
}
