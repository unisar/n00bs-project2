import org.apache.spark.{SparkConf, SparkContext}
import com.typesafe.config.ConfigFactory
/**
  * Created by UNisar on 9/2/2016.
  */
object main {
  def main (args: Array[String]): Unit =
  {
    var defaultAppName = "Test"
    var master = "local[4]"
    var memory = "14g"

    if (args.length < 5)
      {
        println ("Incorrect number of arguments. ")
        println ("Run as: run X_Train Y_Train X_Test Path_To_Binaries_Folder Output_Folder")
        sys.exit(1)
      }
    if (args.length>5)
      {
        defaultAppName = args(5)
        master = args(6)
        memory = args(7)
      }
    driver.appName = defaultAppName
    driver.master = master
    driver.executor = memory
//    val conf = new SparkConf()
//        .setAppName(defaultAppName)
//        .setMaster(master)
//        .set("spark.executor.memory", memory)
//
//    val sc = new SparkContext(conf)
//    sc.hadoopConfiguration.set("fs.s3n.awsAccessKeyId", "AKIAISHYBNDYMKIBCDUQ")
//    sc.hadoopConfiguration.set("fs.s3n.awsSecretAccessKey", "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" )
    new driver(args{0}, args{1}, args{2}, args{3}, args{4}).run

  }
}
