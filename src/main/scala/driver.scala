import java.io.PrintWriter

import scala.collection.immutable.Vector
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.rdd.RDD
import org.apache.log4j.Logger
import org.apache.spark.ml.{Pipeline, PipelineModel}
import org.apache.spark.ml.classification.{NaiveBayes, RandomForestClassificationModel, RandomForestClassifier}
import org.apache.spark.ml.evaluation.MulticlassClassificationEvaluator
import org.apache.spark.ml.feature.{IndexToString, LabeledPoint, StringIndexer, VectorIndexer}
import org.apache.spark.ml.linalg.Vectors
import org.apache.spark.mllib.feature.{HashingTF, IDF}
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Row}
import scala.collection.mutable

object driver {
  var appName = "MalwareClassifier"
  //  var master = "spark://54.149.171.83:7077"
  var master = "local[8]"
  var executor = "10g"
  var conf: SparkConf = null
  var sc: SparkContext = null
  val log = Logger.getLogger(getClass.getName)
  def initialize()
  {
    conf = new SparkConf()
      .setAppName(appName)
      .setMaster(master)
      .set("spark.executor.memory", executor)
      .set("spark.sql.warehouse.dir", "spark-warehouse")
      .set("spark.network.timeout", "9000")
      .set("spark.executor.heartbeatInterval", "800")

    sc = new SparkContext(conf)
    sc.hadoopConfiguration.set("fs.s3n.awsAccessKeyId", "AKIAISHYBNDYMKIBCDUQ")
    sc.hadoopConfiguration.set("fs.s3n.awsSecretAccessKey", "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" )
  }
}
/**
  * Created by UNisar on 9/2/2016.
  */
class driver (xTrain: String, yTrain: String, xTest: String, binariesPath: String, metadataPath: String, resultsPath: String, numberOfGrams: Int) extends Serializable {
  import driver._
  val sqlContext = new org.apache.spark.sql.SQLContext(sc)
  import sqlContext.implicits._
  val opCodes = sc.textFile("Opcodes.txt").map(_.toLowerCase()).collect().toSet
  val opCodesMap = new mutable.HashMap[String, Int]()
  val segmentsMap = new mutable.HashMap[String, Int]()
  var index = 1
  for ( code <- opCodes)
  {
    opCodesMap.put(code, index )
    index = index + 1
  }
  index = 200
  sc.textFile("Segments.txt").foreach ( line => {
    segmentsMap.put(line, index)
    index = index + 1
  })

  val corpusIterator = sc.textFile(xTrain).zipWithIndex().map(c => (c._2, c._1)).cogroup(sc.textFile(yTrain).zipWithIndex().map(c => (c._2, c._1))).map ( x => (x._2._2.head, x._2._1.head))

  def appendSegments(): Unit = {
    sc.textFile("Large1GramOpcodeRaw").take(10).foreach(println)
  }

  /*
  This method returns all the opcodes in the given assembly file in the sequential order
   */
  def readASM(inputPath: String) = {
    val fullPath = metadataPath + inputPath + ".asm"
    sc.textFile(fullPath).flatMap ( line => {
      var index = line.indexOf(';')
      if (index == -1)
        index = line.length
      val tokens = line.dropRight ( line.length - index).split("[\\s]+").toSet
      val result = tokens.intersect(opCodes)
      if (result.isEmpty)
        None
      else
        result
    }).collect().mkString(" ")
  }

  def convertToSVMLIB() : Unit = {

    sc.textFile("Large1GramOpcodeRaw").map ( line => {
      val tokens = line.split(" ")
      val instance = tokens(0)
      val label = tokens(1)
      val features = sc.parallelize(line.split(" ").drop(2).map(opCodesMap(_))).countByValue().toSeq.sortWith((a,b) => a._1 < b._1)
      var output = ""
      for ( f <- features)
        output = output.concat(" " + f._1 + ":" + f._2)

      instance + " " + label +  " " + output + " " + readSegmentInformation(instance)
    }).saveAsTextFile("Large1GramOpcodeVector")

    //    sc.textFile("Large1GramOpcodeTestingRaw").map ( line => {
    //      val features = sc.parallelize(line.split(" ").map(opCodesMap(_))).countByValue().toSeq.sortWith((a,b) => a._1 < b._1)
    //      var output = ""
    //      for ( f <- features)
    //        output = output.concat(" " + f._1 + ":" + f._2)
    //
    //      "0" + " " + output
    //    }).coalesce(1).saveAsTextFile("Large1GramOpcodeVectorTesting")
  }

  def readSegmentInformation(path: String) = {
    val fullPath = metadataPath + path + ".asm"
    val map = sc.textFile(fullPath).map (_.split(" ")(0).split(":")(0)).countByValue()
    var output = ""
    for (m <- map)
      output = output + segmentsMap(m._1) + ":" + m._2
    output
  }

  /***
    * This method generates the feature vectors over all the assembly files where the feature vectors consist of all opcodes
    */
  def runASM: Unit = {

    val finalOutput = corpusIterator.map ( item =>
    {
      item._1 + " " + readASM(item._2)
    })
    finalOutput.saveAsTextFile(resultsPath)
  }

  /***
    * This method generates the feature vectors over all the assembly files with a hard-coded label (why?)
    */
  def runASMTest: Unit = {
    val finalOutput = sc.textFile(xTest).map ( item =>
    {
      0 + " " + readASM(item)
    })
    finalOutput.saveAsTextFile(resultsPath + "Testing")
  }

  def generateAllGrams(): Unit = {
    val tf = new HashingTF()
    sc.textFile("Large1GramOpcodeTesting/part-00000").map ( line => {
      val tokens = line.split(" ")
      //    val label = tokens(0)
      val features = tokens

      "0 " + features.mkString(" ") + " " + features.sliding(2).map(_.mkString("-")).collect( { case a: String => a }).mkString(" ")
    }).coalesce(1).saveAsTextFile("Large2GramOpcodeTesting")
  }

  def naiveBayes(): Unit = {

    val data = sqlContext.read.format("libsvm").load("Large1GramOpcodeVector/part-00000")
    val testingData = sqlContext.read.format("libsvm").load(resultsPath+"Testing/part-00000")

    val model = new NaiveBayes().fit(data)

    val predictions = model.transform(testingData)
    predictions.select("prediction").toDF("predictedLabel").select($"predictedLabel"+1).rdd.coalesce(1).saveAsTextFile("LargeResults.txt")
  }

  def classifyRandomForest(): Unit = {

    val data = sqlContext.read.format("libsvm").load("Large1GramOpcodeVector/part-00000")

    // Index labels, adding metadata to the label column.
    // Fit on whole dataset to include all labels in index.
    val labelIndexer = new StringIndexer()
      .setInputCol("label")
      .setOutputCol("indexedLabel")
      .fit(data)

    // Automatically identify categorical features, and index them.
    // Set maxCategories so features with > 4 distinct values are treated as continuous.
    val featureIndexer = new VectorIndexer()
      .setInputCol("features")
      .setOutputCol("indexedFeatures")
      .setMaxCategories(40)
      .fit(data)

    // Split the data into training and test sets (30% held out for testing).
    val Array(trainingData, testData) = data.randomSplit(Array(0.8, 0.2))

    // Convert indexed labels back to original labels.
    val labelConverter = new IndexToString()
      .setInputCol("prediction")
      .setOutputCol("predictedLabel")
      .setLabels(labelIndexer.labels)

    // Train a RandomForest model.
    val rf = new RandomForestClassifier()
      .setMaxBins(80)
      .setLabelCol("indexedLabel")
      .setFeaturesCol("indexedFeatures")
      .setNumTrees(100)

    // Chain indexers and forest in a Pipeline.
    val pipeline = new Pipeline()
      .setStages(Array(labelIndexer, featureIndexer, rf, labelConverter))

    // Train model. This also runs the indexers.
    val model = pipeline.fit(trainingData)

    val predictions = model.transform(testData)

    // Select (prediction, true label) and compute test error.
    val evaluator = new MulticlassClassificationEvaluator()
      .setLabelCol("indexedLabel")
      .setPredictionCol("prediction")
      .setMetricName("accuracy")

    val accuracy = evaluator.evaluate(predictions)
    println("Test Error = " + (1.0 - accuracy))
    println("Accuracy = " + accuracy)
    //
    //    val rfModel = model.stages(2).asInstanceOf[RandomForestClassificationModel]
    //    println("Learned classification forest model:\n" + rfModel.toDebugString)

    //    val splits = data.randomSplit(Array(0.8, 0.2))
    //    val (trainingData, testData) = (splits(0), splits(1))

    //    val numClasses = 9
    //    val categoricalFeaturesInfo = scala.Predef.Map[Int, Int]()
    //    val numTrees = 3
    //    val featureSubsetStrategy = "auto"
    //
    //    val impurity = "gini"
    //    val maxDepth = 4
    //    val maxBins = 4
    //
    //    val model = RandomForest.trainClassifier(data, numClasses, categoricalFeaturesInfo, numTrees, featureSubsetStrategy, impurity, maxDepth, maxBins)
    //
    //    val testingData = MLUtils.loadLibSVMFile(sc, resultsPath + "Testing")
    //
    //    testingData.map ( point => model.predict(point.features)).saveAsTextFile("ClassificationResult")

    //    val labelAndPreds = testData.map { point =>
    //      val prediction = model.predict(point.features)
    //      (point.label, prediction)
    //    }

    //    val testErr = labelAndPreds.filter ( r => r._1 != r._2).count.toDouble / testData.count()
    //    println("Test Error = " + testErr)
    //println("Learned classification forest model: \n" + model.toDebugString)

    //    model.save(sc, "model")

  }
  @deprecated
  def runBytes: Unit = {

    val finalOutput = corpusIterator.map ( item =>
    {
      var output = (item._1.toInt).toString
      val map: Seq[(String, Long)] = readFile(item._2).toSeq.sortWith((u,v) => u._2 > v._2).map ( x => (x._1, x._2))
        .sortBy(_._1)
      for ( m <- map)
        output = output.concat(" " + (Integer.parseInt(m._1, 16)+1) + ":" + m._2)
      output
    })
    finalOutput.saveAsTextFile(resultsPath)
  }

  @deprecated
  def runBytesTest: Unit = {
    val finalOutput = sc.textFile(xTest).map ( item =>
    {
      var output="0"
      val map: Seq[(String, Long)] = readFile(item).toSeq.sortWith((u,v) => u._2 > v._2).map ( x => (x._1, x._2))
        .sortBy(_._1)
      for ( m <- map)
        output = output.concat(" " + (Integer.parseInt(m._1, 16)+1) + ":" + m._2)
      output
    })
    finalOutput.saveAsTextFile(resultsPath + "Testing")
  }

  @deprecated
  def readFile(inputPath: String) = {
    val fullPath = metadataPath + inputPath + ".bytes"

    sc.textFile(fullPath).
      map(x => x.split(" ").drop(1).
        sliding(numberOfGrams).map(_.mkString(""))).flatMap(c => c).countByValue().filter ( !_._1.contains('?')).filter ( x => x._2 > 3)
  }
}
