package epic.dense

import breeze.linalg._
import breeze.linalg.operators.OpMulMatrix
import epic.features.SegmentedIndex
import epic.framework.Feature
import breeze.util.Index

import scala.runtime.ScalaRunTime
import scala.util.Random

case class LowRankQuadraticTransform[FV](numOutputs: Int, numRanks: Int, numLeftInputs: Int, numRightInputs: Int, innerTransform: Transform[FV, DenseVector[Double]]) extends Transform[FV, DenseVector[Double]] {

  val neurons = (0 until numOutputs).map(i => new LowRankQuadraticTransformNeuron(numRanks, numLeftInputs, numRightInputs))
  val neuronIndex = SegmentedIndex(neurons.map(_.index):_*)
  val index = SegmentedIndex(neuronIndex, innerTransform.index)

  def extractLayer(weights: DenseVector[Double]) = {
    val subTransforms = (0 until neurons.size).map(i => neurons(i).extractLayer(weights(neuronIndex.componentOffset(i) until neuronIndex.componentOffset(i) + neuronIndex.indices(i).size)))
    new Layer(subTransforms, innerTransform.extractLayer(weights(index.componentOffset(1) to -1)))
  }
  
  def initialWeightVector(initWeightsScale: Double, rng: Random, outputLayer: Boolean, spec: String) = {
    val subVects = DenseVector.vertcat(neurons.map(_.initialWeightVector(initWeightsScale, rng, outputLayer, spec)):_*) 
    DenseVector.vertcat(subVects, innerTransform.initialWeightVector(initWeightsScale, rng, outputLayer, spec))
  }
  
  def clipHiddenWeightVectors(weights: DenseVector[Double], norm: Double, outputLayer: Boolean) {
    innerTransform.clipHiddenWeightVectors(weights(index.componentOffset(1) to -1), norm, outputLayer);
  }

  case class Layer(sublayers: Seq[LRQTNLayer], innerLayer: innerTransform.Layer) extends Transform.Layer[FV,DenseVector[Double]] {
    
    override val index = LowRankQuadraticTransform.this.index
    val neuronIndex = LowRankQuadraticTransform.this.neuronIndex

    def activations(fv: FV) = {
      val innerActivations = innerLayer.activations(fv)
      DenseVector(Array.tabulate(sublayers.size)(i => sublayers(i).activations(innerActivations)(0)))
    }

    def tallyDerivative(deriv: DenseVector[Double], _scale: =>Vector[Double], fv: FV) = {
      val innerActivations = innerLayer.activations(fv)
      for (i <- 0 until sublayers.size) {
        sublayers(i).tallyDerivative(deriv(neuronIndex.componentOffset(i) until neuronIndex.componentOffset(i) + neuronIndex.indices(i).size), _scale(i), innerActivations)
      }
    }
  }

 
}

/**
 * Separate because I was having some issues...
 */
case class LowRankQuadraticTransformNeuron(numRanks: Int, numLeftInputs: Int, numRightInputs: Int) {

  val index = SegmentedIndex(new AffineTransform.Index(numRanks, numLeftInputs, false), new AffineTransform.Index(numRanks, numRightInputs, false))

  def extractLayer(weights: DenseVector[Double]) = {
    val lhsSize = numRanks * numLeftInputs
    val rhsSize = numRanks * numRightInputs
    val lhsMat = weights(0 until lhsSize).asDenseMatrix.reshape(numRanks, numLeftInputs, view = View.Require)
    val rhsMat = weights(lhsSize until (lhsSize + rhsSize)).asDenseMatrix.reshape(numRanks, numRightInputs, view = View.Require)
    new LRQTNLayer(lhsMat, rhsMat, index, numRanks, numLeftInputs, numRightInputs)
  }
  
  def initialWeightVector(initWeightsScale: Double, rng: Random, outputLayer: Boolean, spec: String) = {
    if (spec == "magic") {
      DenseVector.vertcat(AffineTransform.getMagicAffineWeights(index.indices(0).size, numLeftInputs, numRanks, initWeightsScale, rng),
                          AffineTransform.getMagicAffineWeights(index.indices(1).size, numRightInputs, numRanks, initWeightsScale, rng))
    } else {
      DenseVector.vertcat(AffineTransform.getGaussianAffineWeights(index.indices(0).size, initWeightsScale, rng),
                          AffineTransform.getGaussianAffineWeights(index.indices(1).size, initWeightsScale, rng))
    } 
  }
  
  def clipHiddenWeightVectors(weights: DenseVector[Double], norm: Double, outputLayer: Boolean) {
  }
  
//  case class Layer(lhsWeights: DenseMatrix[Double], rhsWeights: DenseMatrix[Double]) extends Transform.Layer[Den
//    override val index = LowRankQuadraticTransformNeuron.this.index
//
//    val lhsWeightst = lhsWeights.t
//    val rhsWeightst = rhsWeights.t
//
//    def activations(fv: DenseVector[Double]) = {
//      val lhsProj = lhsWeights * fv
//      val rhsProj = rhsWeights * fv
//      DenseVector(Array(lhsProj.dot(rhsProj)))
//    }
//
//    def tallyDerivative(deriv: DenseVector[Double], _scale: =>Vector[Double], fv: DenseVector[Double]) = {
////      println("SCALE: " + _scale)
//      val scale = _scale(0)
//      val lhsSize = numRanks * numLeftInputs
//      val rhsSize = numRanks * numRightInputs
//      val lhsDeriv = deriv(0 until lhsSize).asDenseMatrix.reshape(numRanks, numLeftInputs, view = View.Require)
//      val rhsDeriv = deriv(lhsSize until rhsSize).asDenseMatrix.reshape(numRanks, numRightInputs, view = View.Require)
//      
//      val innerActs = fv
//      val lhsProj = lhsWeights * innerActs
//      val rhsProj = rhsWeights * innerActs
//      
//      for (r <- 0 until lhsWeights.rows) {
//        for (i <- 0 until lhsWeights.cols) {
//          lhsDeriv(r)(i) += innerActs(i) * rhsProj(r)
//        }
//        for (i <- 0 until rhsWeights.cols) {
//          rhsDeriv(r)(i) += innerActs(i) * lhsProj(r)
//        }
//      }
//      require(deriv.size == lhsSize + rhsSize, "Backpropagating through LowRankQuadraticTransform is not currently supported")
//    }
//  }

}


case class LRQTNLayer(lhsWeights: DenseMatrix[Double], rhsWeights: DenseMatrix[Double], index: Index[Feature], numRanks: Int, numLeftInputs: Int, numRightInputs: Int) {
  val lhsWeightst = lhsWeights.t
  val rhsWeightst = rhsWeights.t

  def activations(fv: DenseVector[Double]) = {
    val lhsProj = lhsWeights * fv
    val rhsProj = rhsWeights * fv
    val dotProd = lhsProj.dot(rhsProj)
//    println(dotProd + "            " + lhsProj.data.toSeq + "         " + rhsProj.data.toSeq)
    DenseVector(Array(dotProd))
  }

  def tallyDerivative(deriv: DenseVector[Double], _scale: =>Vector[Double], fv: DenseVector[Double]) = {
//      println("SCALE: " + _scale)
    val scale = _scale(0)
    if (Math.abs(scale) > 1e-6) {
      val lhsSize = numRanks * numLeftInputs
      val rhsSize = numRanks * numRightInputs
//      println(deriv.size + " " + lhsSize + " " + numRanks + " " + numLeftInputs + " " + rhsSize)
      val lhsDeriv = deriv(0 until lhsSize).asDenseMatrix.reshape(numRanks, numLeftInputs, view = View.Require)
      val rhsDeriv = deriv(lhsSize until lhsSize + rhsSize).asDenseMatrix.reshape(numRanks, numRightInputs, view = View.Require)

      val innerActs = fv
      val lhsProj = lhsWeights * innerActs
      val rhsProj = rhsWeights * innerActs

      for (r <- 0 until lhsWeights.rows) {
//        lhsDeriv += rhsProj * innerActs.t * scale
//      lhsDeriv(r) += innerActs * scale * rhsProj(r)
        for (i <- 0 until lhsWeights.cols) {
          lhsDeriv(r, i) += scale * innerActs(i) * rhsProj(r)
        }
        for (i <- 0 until rhsWeights.cols) {
          rhsDeriv(r, i) += scale * innerActs(i) * lhsProj(r)
        }
      }
      require(deriv.size == lhsSize + rhsSize, "Backpropagating through LowRankQuadraticTransform is not currently supported")
    }
  }
}

//}
