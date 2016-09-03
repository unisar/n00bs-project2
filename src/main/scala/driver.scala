import scala.collection.immutable.Vector
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.rdd.RDD

import scala.collection.mutable

//import scala.Vector
import scala.collection.Map

object driver {
  var appName = "MalwareClassifier"
//  var master = "spark://54.201.210.226:7077"
  var master = "local[8]"
  var executor = "10g"

  lazy val conf = new SparkConf()
    .setAppName(appName)
    .setMaster(master)
    .set("spark.executor.memory", executor)
    .set("spark.sql.warehouse.dir", "spark-warehouse")
    .set("spark.network.timeout", "6000")
    .set("spark.executor.heartbeatInterval", "600")

  val sc = new SparkContext(conf)
  sc.hadoopConfiguration.set("fs.s3n.awsAccessKeyId", "AKIAISHYBNDYMKIBCDUQ")
  sc.hadoopConfiguration.set("fs.s3n.awsSecretAccessKey", "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" )
}
/**
  * Created by UNisar on 9/2/2016.
  */
class driver (xTrain: String, yTrain: String, xTest: String, binariesPath: String, resultsPath: String) extends Serializable {

  import driver._

  val corpusIterator: RDD[(String, Map[Vector[String], Long])] = sc.textFile(xTrain).zipWithIndex().map(c => (c._2, c._1)).cogroup(sc.textFile(yTrain).zipWithIndex().map(c => (c._2, c._1))).
    map(x => (x._2._2.head, readFile(x._2._1.head)))

  def readFile(inputPath: String) = sc.textFile(binariesPath + inputPath + ".bytes").
    map ( x => x.split(" ").drop(1).
    sliding(4).map ( _.toVector)).flatMap ( c => c).countByValue()

  def createCombiner(input: Map[Vector[String], Long]) = {
    var map = new mutable.HashMap[Vector[String], Long]
    for ( (k,v) <- input)
      map.put(k, v)
    map
  }

  def mergeValue( input: mutable.Map[Vector[String], Long], output: Map[Vector[String], Long]) = {
    for (( k,v) <- output )
        input.put(k, input.getOrElse(k, 0L) + 1L)
    input
  }

  def mergeCombiner ( input: mutable.Map[Vector[String], Long], output: mutable.Map[Vector[String], Long]) =
  {
    for ((k,v) <- output)
      input.put(k, input.getOrElse(k, 0L) + 1L)
    input
  }

  def run: Unit = {

    val combined = corpusIterator.combineByKey(createCombiner, mergeValue, mergeCombiner)

    combined.coalesce(1).saveAsTextFile("outputFolder")

  }
}
