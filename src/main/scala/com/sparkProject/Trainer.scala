package com.sparkProject

import org.apache.spark.SparkConf
import org.apache.spark.ml.classification.LogisticRegression
import org.apache.spark.ml.evaluation.{BinaryClassificationEvaluator, RegressionEvaluator,MulticlassClassificationEvaluator}
import org.apache.spark.sql.SparkSession
import org.apache.spark.ml.feature.{CountVectorizer, CountVectorizerModel, IDF, IDFModel, OneHotEncoder, RegexTokenizer, StopWordsRemover, StringIndexer, VectorAssembler}
import org.apache.spark.ml.{Pipeline, PipelineModel}
import org.apache.spark.ml.tuning.{CrossValidator, ParamGridBuilder, TrainValidationSplit}
import org.apache.spark.mllib.evaluation.BinaryClassificationMetrics

object Trainer {

  def main(args: Array[String]): Unit = {

    val conf = new SparkConf().setAll(Map(
      "spark.scheduler.mode" -> "FIFO",
      "spark.speculation" -> "false",
      "spark.reducer.maxSizeInFlight" -> "48m",
      "spark.serializer" -> "org.apache.spark.serializer.KryoSerializer",
      "spark.kryoserializer.buffer.max" -> "1g",
      "spark.shuffle.file.buffer" -> "32k",
      "spark.default.parallelism" -> "12",
      "spark.sql.shuffle.partitions" -> "12",
      "spark.driver.maxResultSize" -> "2g"
    ))

    val spark = SparkSession.builder.config(conf).appName("TP_spark").getOrCreate()


    /** *****************************************************************************
      *
      * TP 3
      *
      *       - lire le fichier sauvegarder précédemment
      *       - construire les Stages du pipeline, puis les assembler
      *       - trouver les meilleurs hyperparamètres pour l'entraînement du pipeline avec une grid-search
      *       - Sauvegarder le pipeline entraîné
      *
      * if problems with unimported modules => sbt plugins update
      *
      * *******************************************************************************/

    println("hello world ! from Trainer")
    // lire le fichier parquet
    val data = spark.read.parquet("/home/margaux/Documents/Cours/Intro_Hadoop/guided_project/TP_ParisTech_2017_2018_starter/prepared_trainingset")

    // Stage 1 : tokenizer
    val tokenizer = new RegexTokenizer()
      .setPattern("\\W+")
      .setGaps(true)
      .setInputCol("text")
      .setOutputCol("tokens")

    // Stage 2 : retirer les stop words
    val remover = new StopWordsRemover()
      .setInputCol("tokens")
      .setOutputCol("filtered")

    // Stage 3 : TF
    val cvModel = new CountVectorizer()
      .setInputCol("filtered")
      .setOutputCol("tf")
      .setVocabSize(3)
      .setMinDF(2)

    // Stage 4 : IDF
    val idf = new IDF()
      .setInputCol("tf")
      .setOutputCol("tfidf")

    // Stage 5 : convertir country en index
    val country_indexer = new StringIndexer()
      .setInputCol("country2")
      .setOutputCol("country_indexed")

    // Stage 6 : convertir currency en index
    val currency_indexer = new StringIndexer()
      .setInputCol("currency2")
      .setOutputCol("currency_indexed")

    // Stage 7 et 8 : onehotencoder
    val country_encoder = new OneHotEncoder()
      .setInputCol("country_indexed")
      .setOutputCol("country_onehot")

    val currency_encoder = new OneHotEncoder()
      .setInputCol("currency_indexed")
      .setOutputCol("currency_onehot")

    // Stage 9 : assembler les colonnes
    val colSelected = List("tfidf", "days_campaign", "hours_prepa", "goal", "country_onehot", "currency_onehot")
    val assembler = new VectorAssembler()
      .setInputCols(colSelected.toArray)
      .setOutputCol("features")

    // Stage 10 : logistic regression
    val lr = new LogisticRegression()
      .setElasticNetParam(0.0)
      .setFitIntercept(true)
      .setFeaturesCol("features")
      .setLabelCol("final_status")
      .setStandardization(true)
      .setPredictionCol("predictions")
      .setRawPredictionCol("raw_predictions")
      .setThresholds(Array(0.7, 0.3))
      .setTol(1.0e-6)
      .setMaxIter(300)

    val pipeline = new Pipeline()
      .setStages(Array(tokenizer, remover, cvModel, idf, country_indexer,
        currency_indexer, country_encoder, currency_encoder, assembler, lr))

    val model = pipeline.fit(data)
    val new_df = model.transform(data)
    new_df.select("raw_predictions").show(10)

    // Split the data into training and test sets (10% held out for testing)
    val Array(training, test) = data.randomSplit(Array(0.9, 0.1))

    // Préparer la grid search

    val paramGrid = new ParamGridBuilder()
      .addGrid(cvModel.minDF, (55 to 95 by 20).toArray)
      .addGrid(lr.regParam, Array(10e-8, 10e-6, 10e-4, 10ce-2))
      .build()

    val trainValidationSplit = new TrainValidationSplit()
      .setEstimator(lr)
      .setEvaluator(new MulticlassClassificationEvaluator().setMetricName("f1"))
      .setEstimatorParamMaps(paramGrid)
      // 70% of the data will be used for training and the remaining 20% for validation.
      .setTrainRatio(0.7)

  }
}

