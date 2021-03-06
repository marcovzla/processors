package edu.arizona.sista.learning

import scala.collection.mutable
import scala.collection.mutable.{ListBuffer, ArrayBuffer}
import edu.arizona.sista.struct.Counter
import edu.arizona.sista.struct.Lexicon
import scala.io.BufferedSource
import java.util.zip.GZIPInputStream
import java.io.{FileWriter, PrintWriter, FileInputStream, BufferedInputStream}
import org.slf4j.LoggerFactory
import RVFDataset._

/**
 * Parent class for classification datasets
 * User: mihais
 * Date: 4/23/13
 */
abstract class Dataset[L, F](
  val labelLexicon:Lexicon[L],
  val featureLexicon:Lexicon[F],
  val labels:ArrayBuffer[Int]) {

  def this() = this(new Lexicon[L], new Lexicon[F], new ArrayBuffer[Int])

  def += (datum:Datum[L, F])

  def numFeatures = featureLexicon.size
  def numLabels = labelLexicon.size

  /** number of training examples */
  def size = labels.size

  def indices = 0 until size

  def featuresCounter(datumOffset:Int):Counter[Int]

  /** Returns the Datum for given row */
  def mkDatum(row:Int): Datum[L, F]

  /** Removes features that appear less than threshold times in this dataset. */
  def removeFeaturesByFrequency(threshold:Int):Dataset[L, F]

  /** Creates a new dataset keeping only the features in the given set */
  def keepOnly(featuresToKeep:Set[Int]):Dataset[L, F]

  /** Convert this dataset to a CounterDataset */
  def toCounterDataset:CounterDataset[L, F]
}

/**
 * Dataset containing only BVFDatums
 * Important note: to encode feature values > 1, simply store the same feature multiple times (equal to feature value)!
 * @tparam L Type of labels
 * @tparam F Type of features
 */
class BVFDataset[L, F] (
  ll:Lexicon[L],
  fl:Lexicon[F],
  ls:ArrayBuffer[Int],
  val features:ArrayBuffer[Array[Int]]) extends Dataset[L, F](ll, fl, ls) {

  def this() = this(
    new Lexicon[L], new Lexicon[F], new ArrayBuffer[Int],
    new ArrayBuffer[Array[Int]])

  def += (datum:Datum[L, F]) {
    datum match {
      case bd:BVFDatum[L, F] =>
        labels += labelLexicon.add(bd.label)
        features += featuresToArray(bd.features)
      case _ => throw new RuntimeException("ERROR: you cannot add a non BVFDatum to a BVFDataset!")
    }
  }

  private def featuresToArray(fs:Iterable[F]):Array[Int] = {
    val fb = new ListBuffer[Int]
    for(f <- fs) fb += featureLexicon.add(f)
    fb.toList.sorted.toArray
  }

  override def mkDatum(row:Int): Datum[L, F] = {
    val feats = for (f <- features(row)) yield featureLexicon.get(f)
    new BVFDatum[L, F](labelLexicon.get(labels(row)), feats)
  }

  override def featuresCounter(datumOffset:Int):Counter[Int] = {
    val c = new Counter[Int]
    features(datumOffset).foreach(f => c.incrementCount(f))
    c
  }

  def countFeatures(fs:ArrayBuffer[Array[Int]]):Counter[Int] = {
    val counts = new Counter[Int]
    for(d <- fs) {
      for(f <- d) {
        counts.incrementCount(f)
      }
    }
    counts
  }

  override def removeFeaturesByFrequency(threshold:Int):Dataset[L, F] = {
    // compute feature frequencies
    val counts = countFeatures(features)
    logger.debug("Total unique features before filtering: " + counts.size)

    // map old feature ids to new ids, over the filtered set
    val featureIndexMap = new mutable.HashMap[Int, Int]()
    var newId = 0
    for(f <- 0 until featureLexicon.size) {
      if(counts.getCount(f) >= threshold) {
        featureIndexMap += f -> newId
        newId += 1
      }
    }

    // construct the new dataset with the filtered features
    val newFeatures = new ArrayBuffer[Array[Int]]
    for(i <- 0 until size) {
      val feats = features(i)
      val filteredFeats = removeByFreq(feats, counts, threshold, featureIndexMap)
      newFeatures += filteredFeats
    }
    logger.debug("Total features after filtering: " + countFeatures(newFeatures).size)

    new BVFDataset[L, F](labelLexicon, featureLexicon.mapIndicesTo(featureIndexMap.toMap), labels, newFeatures)
  }

  private def removeByFreq(fs:Array[Int],
                           counts:Counter[Int],
                           threshold:Int,
                           featureIndexMap:mutable.HashMap[Int, Int]):Array[Int] = {
    val filtered = new ArrayBuffer[Int]()
    for(f <- fs) {
      if(counts.getCount(f) >= threshold) {
        assert(featureIndexMap.contains(f))
        filtered += featureIndexMap.get(f).get
      }
    }
    filtered.toArray
  }

  override def keepOnly(featuresToKeep:Set[Int]):Dataset[L, F] = {
    throw new RuntimeException("Not supported yet!")
  }

  override def toCounterDataset:CounterDataset[L, F] = {
    val cfs = new ArrayBuffer[Counter[Int]]()
    for(d <- features.indices) {
      val cf = new Counter[Int]
      for(i <- features(d).indices) {
        cf.incrementCount(features(d)(i))
      }
      cfs += cf
    }
    new CounterDataset[L, F](labelLexicon, featureLexicon, labels, cfs)
  }
}

/**
 * Dataset containing only RVFDatums
 * @tparam L Type of labels
 * @tparam F Type of features
 */
class RVFDataset[L, F] (
  ll:Lexicon[L],
  fl:Lexicon[F],
  ls:ArrayBuffer[Int],
  fs:ArrayBuffer[Array[Int]],
  val values:ArrayBuffer[Array[Double]]) extends BVFDataset[L, F](ll, fl, ls, fs) with FeatureTraversable[F, Double]{

  def this() = this(
    new Lexicon[L], new Lexicon[F], new ArrayBuffer[Int],
    new ArrayBuffer[Array[Int]],
    new ArrayBuffer[Array[Double]])

  override def += (datum:Datum[L, F]) {
    datum match {
      case d:RVFDatum[L, F] =>
        labels += labelLexicon.add(d.label)
        val fvs = featuresCounterToArray(d.featuresCounter)
        features += fvs.map(fv => fv._1)
        values += fvs.map(fv => fv._2)
      case _ => throw new RuntimeException("ERROR: you cannot add a non RVFDatum to a RVFDataset!")
    }
  }

  private def featuresCounterToArray(fs:Counter[F]):Array[(Int, Double)] = {
    val fb = new ListBuffer[(Int, Double)]
    for(f <- fs.keySet) {
      fb += new Tuple2[Int, Double](featureLexicon.add(f), fs.getCount(f))
    }
    fb.sortBy(_._1).toArray
  }

  override def featuresCounter(datumOffset:Int):Counter[Int] = {
    val c = new Counter[Int]
    val fs = features(datumOffset)
    val vs = values(datumOffset)
    for(i <- fs.indices) {
      c.incrementCount(fs(i), vs(i))
    }
    c
  }

  override def removeFeaturesByFrequency(threshold:Int):Dataset[L, F] = {

    // compute feature frequencies
    val counts = countFeatures(features)
    logger.debug("Total unique features before filtering: " + counts.size)

    // map old feature ids to new ids, over the filtered set
    val featureIndexMap = new mutable.HashMap[Int, Int]()
    var newId = 0
    for(f <- 0 until featureLexicon.size) {
      if(counts.getCount(f) >= threshold) {
        featureIndexMap += f -> newId
        newId += 1
      }
    }

    // construct the new dataset with the filtered features
    val newFeatures = new ArrayBuffer[Array[Int]]
    val newValues = new ArrayBuffer[Array[Double]]()
    for(i <- 0 until size) {
      val feats = features(i)
      val vals = values(i)
      val (filteredFeats, filteredVals) = removeByFreq(feats, vals, counts, threshold, featureIndexMap)
      newFeatures += filteredFeats
      newValues += filteredVals
    }
    logger.debug("Total features after filtering: " + countFeatures(newFeatures).size)

    new RVFDataset[L, F](labelLexicon, featureLexicon.mapIndicesTo(featureIndexMap.toMap), labels, newFeatures, newValues)
  }

  private def removeByFreq(fs:Array[Int],
                           vs:Array[Double],
                           counts:Counter[Int],
                           threshold:Int,
                           featureIndexMap:mutable.HashMap[Int, Int]):(Array[Int], Array[Double]) = {
    val filteredFeats = new ArrayBuffer[Int]()
    val filteredVals = new ArrayBuffer[Double]()
    for(i <- fs.indices) {
      val f = fs(i)
      val v = vs(i)
      if(counts.getCount(f) >= threshold) {
        assert(featureIndexMap.contains(f))
        filteredFeats += featureIndexMap.get(f).get
        filteredVals += v
      }
    }
    (filteredFeats.toArray, filteredVals.toArray)
  }

  override def mkDatum(row:Int): Datum[L, F] = {
    val intFeats = featuresCounter(row)
    val feats = new Counter[F]
    for (f <- intFeats.keySet) {
      feats.setCount(featureLexicon.get(f), intFeats.getCount(f))
    }
    new RVFDatum[L, F](labelLexicon.get(labels(row)), feats)
  }

  def featureUpdater: FeatureUpdater[F, Double] = new FeatureUpdater[F, Double] {
    def foreach[U](fn: ((F, Double)) => U): Unit = {
      for(i <- 0 until RVFDataset.this.size) {
        for(j <- features(i).indices) {
          val fi = features(i)(j)
          val v = values(i)(j)
          val f = featureLexicon.get(fi)
          fn((f, v))
        }
      }
    }

    def updateAll(fn: ((F, Double)) => Double): Unit = {
      for(i <- 0 until RVFDataset.this.size) {
        for(j <- features(i).indices) {
          val fi = features(i)(j)
          val v = values(i)(j)
          val f = featureLexicon.get(fi)
          values(i)(j) = fn((f, v))
        }
      }
    }
  }

  override def keepOnly(featuresToKeep:Set[Int]):Dataset[L, F] = {
    // map old feature ids to new ids, over the filtered set
    val featureIndexMap = new mutable.HashMap[Int, Int]()
    var newId = 0
    for(f <- 0 until featureLexicon.size) {
      if(featuresToKeep.contains(f)) {
        featureIndexMap += f -> newId
        newId += 1
      }
    }

    // construct the new dataset with the filtered features
    val newFeatures = new ArrayBuffer[Array[Int]]
    val newValues = new ArrayBuffer[Array[Double]]
    for(row <- features.indices) {
      val (nfs, nvs) = keepOnlyRow(features(row), values(row), featureIndexMap)
      newFeatures += nfs
      newValues += nvs
    }

    new RVFDataset[L, F](labelLexicon, featureLexicon.mapIndicesTo(featureIndexMap.toMap), labels, newFeatures, newValues)
  }

  def keepOnlyRow(feats:Array[Int], vals:Array[Double], featureIndexMap:mutable.HashMap[Int, Int]):(Array[Int], Array[Double]) = {
    val newFeats = new ArrayBuffer[Int]()
    val newVals = new ArrayBuffer[Double]()

    for(i <- feats.indices) {
      val f = feats(i)
      val v = vals(i)
      if(featureIndexMap.contains(f)) {
        newFeats += featureIndexMap.get(f).get
        newVals += v
      }
    }

    (newFeats.toArray, newVals.toArray)
  }

  override def toCounterDataset:CounterDataset[L, F] = {
    val cfs = new ArrayBuffer[Counter[Int]]()
    for(d <- features.indices) {
      val cf = new Counter[Int]
      for(i <- features(d).indices) {
        cf.incrementCount(features(d)(i), values(d)(i))
      }
      cfs += cf
    }
    new CounterDataset[L, F](labelLexicon, featureLexicon, labels, cfs)
  }
}

object RVFDataset {
  val logger = LoggerFactory.getLogger(classOf[RVFDataset[String, String]])

  def mkDatasetFromSvmLightFormat(fn:String):RVFDataset[Int, String] = {
    val dataset = new RVFDataset[Int, String]
    var datumCount = 0

    var source:BufferedSource = null
    if(fn.endsWith(".gz"))
      source = io.Source.fromInputStream(new GZIPInputStream(new BufferedInputStream(new FileInputStream(fn))))
    else
      source = io.Source.fromFile(fn)

    for(line <- source.getLines()) {
      // strip comments following #
      val pound = line.indexOf("#")
      var content = line
      if(pound >= 0) {
        content = line.substring(0, pound)
      }
      content = content.trim
      // logger.debug("Parsing line: [" + content + "]")

      if(content.length > 0) {
        val bits = content.split("\\s+")

        var label = bits(0)
        if(label.startsWith("+")) label = label.substring(1)
        val features = new Counter[String]
        for(i <- 1 until bits.length) {
          val fbits = bits(i).split(":")
          if(fbits.length != 2) {
            throw new RuntimeException("ERROR: invalid feature format: " + bits(i))
          }
          val f = fbits(0)
          val v = fbits(1).toDouble
          features.incrementCount(f, v)
        }
        val datum = new RVFDatum[Int, String](label.toInt, features)
        dataset += datum
        datumCount += 1
      }
    }
    dataset
  }


  def saveToSvmLightFormat(
    datums:Iterable[Datum[Int, String]],
    featureLexicon:Lexicon[String],
    fn:String) {

    val os = new PrintWriter(new FileWriter(fn))
    for(datum <- datums) {
      os.print(datum.label)
      val fs = new ListBuffer[(Int, Double)]
      val c = datum.featuresCounter
      for(k <- c.keySet) {
        val fi = featureLexicon.get(k)
        if(fi.isDefined) {
          // logger.debug(s"Feature [$k] converted to index ${fi.get + 1}")
          fs += new Tuple2(fi.get + 1, c.getCount(k))
        }
      }
      val fss = fs.toList.sortBy(_._1)
      for(t <- fss) {
        os.print(s" ${t._1}:${t._2}")
      }
      os.println()
    }
    os.close()
  }

  def mkDatumsFromSvmLightFormat(fn:String):Iterable[Datum[Int, String]] = {
    val datums = new ArrayBuffer[Datum[Int, String]]()
    var datumCount = 0

    var source:BufferedSource = null
    if(fn.endsWith(".gz"))
      source = io.Source.fromInputStream(new GZIPInputStream(new BufferedInputStream(new FileInputStream(fn))))
    else
      source = io.Source.fromFile(fn)

    for(line <- source.getLines()) {
      // strip comments following #
      val pound = line.indexOf("#")
      var content = line
      if(pound >= 0) {
        content = line.substring(0, pound)
      }
      content = content.trim
      //logger.debug("Parsing line: " + content)

      if(content.length > 0) {
        val bits = content.split("\\s+")

        var label = bits(0)
        if(label.startsWith("+")) label = label.substring(1)
        val features = new Counter[String]
        for(i <- 1 until bits.length) {
          val fbits = bits(i).split(":")
          if(fbits.length != 2) {
            throw new RuntimeException("ERROR: invalid feature format: " + bits(i))
          }
          val f = fbits(0)
          val v = fbits(1).toDouble
          features.incrementCount(f, v)
        }
        val datum = new RVFDatum[Int, String](label.toInt, features)
        datums += datum
        datumCount += 1
      }
    }
    datums
  }
}

/**
  * Dataset that represents datums as explicit counters
  * This is more efficient for the training of various algorithms such as random forests
  */
class CounterDataset[L, F](ll:Lexicon[L],
                           fl:Lexicon[F],
                           ls:ArrayBuffer[Int],
                           val features:ArrayBuffer[Counter[Int]]) extends Dataset[L, F](ll, fl, ls) {
  override def +=(datum: Datum[L, F]): Unit = {
    labels += labelLexicon.add(datum.label)
    val fvs = toIndexCounter(datum.featuresCounter)
    features += fvs
  }

  private def toIndexCounter(feats:Counter[F]):Counter[Int] = {
    val c = new Counter[Int]
    for(f <- feats.keySet) {
      assert(featureLexicon.contains(f))
      c.incrementCount(featureLexicon.get(f).get, feats.getCount(f))
    }
    c
  }

  private def toFeatureCounter(c:Counter[Int]):Counter[F] = {
    val feats = new Counter[F]
    for(f <- c.keySet) {
      assert(f < featureLexicon.size)
      feats.incrementCount(featureLexicon.get(f), c.getCount(f))
    }
    feats
  }

  /**
    * Returns the Datum for given row
    * These datums are always represented as RVFDatums
    */
  override def mkDatum(row: Int): Datum[L, F] = {
    new RVFDatum[L, F](labelLexicon.get(labels(row)), toFeatureCounter(features(row)))
  }

  /** Creates a new dataset keeping only the features in the given set */
  override def keepOnly(featuresToKeep: Set[Int]): Dataset[L, F] = {
    throw new RuntimeException("ERROR: keepOnly not supported yet!")
  }

  /** Removes features that appear less than threshold times in this dataset. */
  override def removeFeaturesByFrequency(threshold: Int): Dataset[L, F] = {
    throw new RuntimeException("ERROR: removeFeaturesByFrequency not supported yet!")
  }

  override def featuresCounter(datumOffset: Int): Counter[Int] = features(datumOffset)

  def toCounterDataset:CounterDataset[L, F] = this
}