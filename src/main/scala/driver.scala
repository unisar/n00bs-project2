import org.apache.spark.{SparkConf, SparkContext, ml}
import org.apache.spark.ml.feature.{LabeledPoint, NGram}
import org.apache.spark.ml.linalg.Vector
import org.apache.spark.mllib.feature.HashingTF
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.sql.{DataFrame, SQLContext}

object driver {
  var appName = "MalwareClassifier"
  var master = "local[4]"
  var executor = "8g"

  lazy val conf = new SparkConf()
    .setAppName(appName)
    .setMaster(master)
    .set("spark.executor.memory", executor)
    .set("spark.sql.warehouse.dir", "D:/PhD/Fall 2016/DataScience/Malware/Classification/spark-warehouse")

  val sc = new SparkContext(conf)
  sc.hadoopConfiguration.set("fs.s3n.awsAccessKeyId", "AKIAISHYBNDYMKIBCDUQ")
  sc.hadoopConfiguration.set("fs.s3n.awsSecretAccessKey", "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" )
}
/**
  * Created by UNisar on 9/2/2016.
  */
class driver (xTrain: String, yTrain: String, xTest: String, binariesPath: String, resultsPath: String) extends Serializable {

  import driver._
val sqlContext = new SQLContext(sc)

  val corpusIterator = sc.textFile(xTrain).zipWithIndex().map(c => (c._2, c._1)).cogroup(sc.textFile(yTrain).zipWithIndex().map(c => (c._2, c._1))).
    map(x => ((x._2._1.head, readFile(x._2._1.head)), x._2._2.head))

  def readFile(inputPath: String): scala.collection.Map[scala.Vector[Int], Long] = sc.textFile(binariesPath + inputPath + ".bytes").
    map ( x => x.split(" ").drop(1).
    map(Integer.parseInt(_, 16)).sliding(4).map ( _.toVector)).flatMap ( c => c).countByValue()

  def run: Unit = {
    corpusIterator.foreach(x => {
      x._1._2.foreach (z => println(readFile(x._1._1)))
    }
    )
  }
}
