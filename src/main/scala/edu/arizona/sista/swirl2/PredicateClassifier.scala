package edu.arizona.sista.swirl2

import java.io._

import edu.arizona.sista.learning._
import edu.arizona.sista.processors.{Sentence, Document}
import edu.arizona.sista.struct.Counter
import edu.arizona.sista.utils.Files
import org.slf4j.LoggerFactory

import edu.arizona.sista.utils.StringUtils._
import PredicateClassifier._

import scala.collection.mutable.ListBuffer

/**
 * Identifies the predicates in SR frames
 * User: mihais
 * Date: 5/28/15
 */
class PredicateClassifier {
  lazy val featureExtractor = new PredicateFeatureExtractor
  var classifier:Classifier[String, String] = null
  val lemmaCounts = new Counter[String]

  def train(trainPath:String): Unit = {
    val reader = new Reader
    val doc = reader.load(trainPath)

    computePredStats(doc)

    countLemmas(doc)
    featureExtractor.lemmaCounts = Some(lemmaCounts)

    var dataset = createDataset(doc)
    dataset = dataset.removeFeaturesByFrequency(2)
    classifier = new LogisticRegressionClassifier[String, String]()
    //classifier = new RFClassifier[String, String](numTrees = 10, maxTreeDepth = 0, trainBagPct = 0.8, howManyFeaturesPerNode = featuresPerNode)
    //classifier = new RandomForestClassifier[String, String](numTrees = 100)
    //classifier = new LinearSVMClassifier[String, String]()
    classifier.train(dataset)
  }

  def featuresPerNode(total:Int):Int = total / 2 // (10 * math.sqrt(total)).toInt

  def countLemmas(doc:Document): Unit = {
    for(s <- doc.sentences) {
      for(l <- s.lemmas.get) {
        lemmaCounts.incrementCount(l)
      }
    }
    logger.debug(s"Found ${lemmaCounts.size} unique lemmas in the training dataset.")
    var count = 0
    for(l <- lemmaCounts.keySet) {
      if(lemmaCounts.getCount(l) > ArgumentFeatureExtractor.UNKNOWN_THRESHOLD)
        count += 1
    }
    logger.debug(s"$count of these lemmas will be kept as such. The rest will mapped to Unknown.")
  }

  def test(testPath:String): Unit = {
    val reader = new Reader
    val doc = reader.load(testPath)

    val output = new ListBuffer[(String, String)]
    for(s <- doc.sentences;
        i <- s.words.indices) {
      val g = goldLabel(s, i)
      val scores = classify(s, i)
      val p = (scores.getCount(POS_LABEL) >= POS_THRESHOLD) match {
        case true => POS_LABEL
        case false => NEG_LABEL
      }
      output += new Tuple2(g, p)
    }

    BinaryScorer.score(output.toList, POS_LABEL)
  }

  def classify(sent:Sentence, position:Int):Counter[String] = {
    if(filter(sent, position)) {
      val datum = mkDatum(sent, position, NEG_LABEL)
      val s = classifier.scoresOf(datum)
      //println(s"Scores for datum: $s")
      s
    } else {
      val s = new Counter[String]
      s.setCount(NEG_LABEL, 1.0)
      s
    }
  }

  def filter(s:Sentence, i:Int):Boolean = {
    val tag = s.tags.get(i)
    if(tag.startsWith("NN") || tag.startsWith("VB")) true
    else false
  }

  def createDataset(doc:Document): Dataset[String, String] = {
    val dataset = new BVFDataset[String, String]()
    val labelStats = new Counter[String]()

    for(s <- doc.sentences;
        i <- s.words.indices) {
      if(filter(s, i)) {
        val label = goldLabel(s, i)
        labelStats.incrementCount(label)
        dataset += mkDatum(s, i, label)
      }
    }
    logger.info("Label statistics for training examples: " + labelStats)
    dataset
  }

  def goldLabel(sentence:Sentence, position:Int):String = {
    val outgoing = sentence.semanticRoles.get.outgoingEdges

    if (position >= outgoing.length)
      return NEG_LABEL

    outgoing(position).nonEmpty match {
      case true => POS_LABEL
      case _ => NEG_LABEL
    }
  }

  def mkDatum(sent:Sentence, position:Int, label:String): BVFDatum[String, String] = {
    new BVFDatum[String, String](label, featureExtractor.mkFeatures(sent, position))
  }

  def computePredStats(doc:Document): Unit = {
    val posStats = new Counter[String]()
    var tokenCount = 0
    for(s <- doc.sentences) {
      tokenCount += s.words.length
      val g = s.semanticRoles.get
      for(i <- g.outgoingEdges.indices) {
        if(g.outgoingEdges(i).nonEmpty) {
          val pos = s.tags.get(i)
          posStats.incrementCount(pos.substring(0, 2))
        }
      }
    }
    logger.info(s"Found ${doc.sentences.length} sentences with $tokenCount tokens.")
    logger.info("Predicates by POS tag: " + posStats)
  }

  def saveTo(w:Writer): Unit = {
    classifier.saveTo(w)
  }
}

object PredicateClassifier {
  val logger = LoggerFactory.getLogger(classOf[PredicateClassifier])

  val POS_LABEL = "+"
  val NEG_LABEL = "-"
  val POS_THRESHOLD = 0.50 // lower this to boost recall

  def main(args:Array[String]): Unit = {
    val props = argsToProperties(args)
    var pc = new PredicateClassifier

    if(props.containsKey("train")) {
      pc.train(props.getProperty("train"))
      if(props.containsKey("model")) {
        val os = new PrintWriter(new BufferedWriter(new FileWriter(props.getProperty("model"))))
        pc.saveTo(os)
        os.close()
      }
    }

    if(props.containsKey("test")) {
      if(props.containsKey("model")) {
        val is = new BufferedReader(new FileReader(props.getProperty("model")))
        pc = loadFrom(is)
        is.close()
      }
      pc.test(props.getProperty("test"))
    }
  }

  def loadFrom(r:java.io.Reader):PredicateClassifier = {
    val pc = new PredicateClassifier
    val reader = Files.toBufferedReader(r)

    val c = LiblinearClassifier.loadFrom[String, String](reader)
    pc.classifier = c

    pc
  }
}
