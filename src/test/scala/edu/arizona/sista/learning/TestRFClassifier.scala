package edu.arizona.sista.learning

import org.scalatest.{Matchers, FlatSpec}

/**
  *
  * User: mihais
  * Date: 11/29/15
  */
class TestRFClassifier extends FlatSpec with Matchers {
  "RFClassifier" should "work with BVFDataset" in {
    val classifier = new RFClassifier[String, String](numTrees = 1, trainBagPct = 1.0, howManyFeaturesPerNode = RFClassifier.featuresPerNodeAll)
    val dataset = new BVFDataset[String, String]()

    val d1 = new BVFDatum[String, String]("+", List("good", "good", "great"))
    val d2 = new BVFDatum[String, String]("-", List("good", "good", "bad", "awful"))
    val d3 = new BVFDatum[String, String]("-", List("good", "horrible"))
    dataset += d1
    dataset += d2
    dataset += d3

    classifier.verbose = true
    classifier.train(dataset)

    classOf(classifier, List("good", "horrible")) should be ("-")
    classOf(classifier, List("bad", "horrible")) should be ("-")
    classOf(classifier, List("good", "great")) should be ("+")
  }

  def classOf(c:RFClassifier[String, String], feats:List[String]):String = {
    val d = new BVFDatum[String, String]("?", feats)
    val l = c.classOf(d)
    println(s"Class of $feats: $l")
    l
  }
}
