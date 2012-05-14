package scalanlp.epic

import scalala.tensor.dense.DenseVector
import collection.mutable.ArrayBuffer
import scalanlp.inference.Factor
import scalanlp.util._

/**
 *
 * @author dlwh
 */
class EPModel[Datum, Augment](maxEPIter: Int, initFeatureValue: Feature => Option[Double],
                              models: EPModel.CompatibleModel[Datum, Augment]*)(implicit aIsFactor: Augment <:< Factor[Augment]) extends Model[Datum] {
  type ExpectedCounts = EPExpectedCounts
  type Inference = EPInference[Datum, Augment]

  val featureIndex: Index[Feature] = {
    val index = Index[Feature]()
    for ((m, i) <- models.zipWithIndex; f <- m.featureIndex) index.index(ComponentFeature(i, f))
    index
  }

  override def initialValueForFeature(f: Feature) = initFeatureValue(f) getOrElse {
    f match {
      case ComponentFeature(m, ff) => models(m).initialValueForFeature(ff)
      case _ => 0.0
    }
  }

  private val offsets = models.map(_.numFeatures).unfold(0)(_ + _)

  def emptyCounts = {
    val counts = for ((m: Model[Datum] {type Inference <: ProjectableInference[Datum, Augment]}) <- models.toIndexedSeq) yield m.emptyCounts
    EPExpectedCounts(0.0, counts)
  }

  def expectedCountsToObjective(ecounts: EPModel[Datum, Augment]#ExpectedCounts) = {
    val vectors = for ((m, e) <- models zip ecounts.counts) yield m.expectedCountsToObjective(e.asInstanceOf[m.ExpectedCounts])._2
    ecounts.loss -> DenseVector.vertcat(vectors: _*)
  }

  def inferenceFromWeights(weights: DenseVector[Double]) = {
    val allWeights = partitionWeights(weights)
    val builders = ArrayBuffer.tabulate(models.length) {
      i =>
        models(i).inferenceFromWeights(allWeights(i))
    }
    new EPInference(builders, maxEPIter)
  }

  private def partitionWeights(weights: DenseVector[Double]): Array[DenseVector[Double]] = {
    Array.tabulate(models.length)(m => projectWeights(weights, m))
  }

  private def projectWeights(weights: DenseVector[Double], modelIndex: Int) = {
    val result = DenseVector.zeros[Double](models(modelIndex).numFeatures)
    for (i <- 0 until result.size) {
      result(i) = weights(i + offsets(modelIndex))
    }
    result
  }

  override def cacheFeatureWeights(weights: DenseVector[Double], prefix: String) {
    super.cacheFeatureWeights(weights, prefix)
    for (((w, m), i) <- (partitionWeights(weights) zip models).zipWithIndex) {
      m.cacheFeatureWeights(w, prefix + "-model-" + i)
    }
  }
}

object EPModel {
  type CompatibleModel[Datum, Augment] = Model[Datum] { type Inference <: ProjectableInference[Datum, Augment]}
}

case class EPExpectedCounts(var loss: Double, counts: IndexedSeq[ExpectedCounts[_]]) extends scalanlp.epic.ExpectedCounts[EPExpectedCounts] {
  def +=(other: EPExpectedCounts) = {
    for( (t, u) <- counts zip other.counts) {
      t.asInstanceOf[{ def +=(e: ExpectedCounts[_]):ExpectedCounts[_]}] += u
    }
    this.loss += other.loss
    this
  }

  def -=(other: EPExpectedCounts) = {
    for( (t, u) <- counts zip other.counts) {
      t.asInstanceOf[{ def -=(e: ExpectedCounts[_]):ExpectedCounts[_]}] -= u
    }
    this.loss -= other.loss
    this
  }
}

case class ComponentFeature(index: Int, feature: Feature) extends Feature