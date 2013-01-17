package epic.everything

import epic.trees._
import epic.coref.{CorefInstanceFeaturizer, FeaturizedCorefInstance, CorefInstance}
import epic.sequences.{SemiCRF, Segmentation}
import epic.trees.StandardTreeProcessor
import epic.parser.ParseChart.SparsityPattern
import breeze.collection.mutable.TriangularArray

//import models.PropertyPropagation.IndexedLink
import epic.parser.projections.ConstraintCoreGrammar

/**
 * 
 * @author dlwh
 */
case class ProcessedDocument(sentences: IndexedSeq[ProcessedSentence],
//                             coref: FeaturizedCorefInstance,
                             id: String="") {
  def treeInstances = sentences.map(s => TreeInstance(s.id +"-tree", s.tree, s.words))


}

case class ProcessedSentence(words: IndexedSeq[String],
                             tree: BinarizedTree[AnnotatedLabel],
                             sparsity: SparsityPattern,
                             ner: Segmentation[NERType.Value, String],
                             nerConstraints: SemiCRF.SpanConstraints,
                             speaker: Option[String],
                             index: Int,
                             id: String="") {

  def length= words.length

  def isPossibleSpan(begin: Int, end: Int) = (
//    true
    sparsity.activeTriangularIndices.contains(TriangularArray.index(begin,end))
      || (nerConstraints.allowedLabels(begin,end).ne(null) && nerConstraints.allowedLabels(begin,end).nonEmpty)
  )
}


object ProcessedDocument {
  case class Factory(treeProcessor: StandardTreeProcessor,
                     constraints: ConstraintCoreGrammar[AnnotatedLabel, String],
//                     graphFeaturizer: PropertyPropagation.GraphBuilder,
                     nerConstraints: SemiCRF.ConstraintGrammar[NERType.Value, String],
                     corefFeaturizer: CorefInstanceFeaturizer) extends (Document=>ProcessedDocument) {

    def apply(d: Document):ProcessedDocument = {
      val newSentences = for(s <- d.sentences) yield {
        val seg = s.nerSegmentation
        var tree = treeProcessor(s.tree.map(_.treebankString))
        tree = UnaryChainRemover.removeUnaryChains(tree)

//        val graph = TriangularArray.tabulate(s.length)((b,e) => graphFeaturizer.linksFor(d, DSpan(d.id, s.sentId, b, e)))

        ProcessedSentence(s.words,
          tree,
          constraints.rawConstraints(s.words).sparsity,
          seg,
          nerConstraints.constraints(s.words),
          s.speaker,
          s.sentId, /*graph,*/ s.id)
      }

      ProcessedDocument(newSentences, /*corefFeaturizer.featurizeDocument(d),*/ d.id)
    }

  }
}