package scalanlp.parser
/*
 Copyright 2010 David Hall

 Licensed under the Apache License, Version 2.0 (the "License")
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/

import projections.GrammarRefinements
import scalanlp.trees._

import annotations.{FilterAnnotations, TreeAnnotator}
import scalala.tensor.Counter2
import scalanlp.parser.ParserParams.{BaseParser, NoParams}
import scalanlp.text.tokenize.EnglishWordClassGenerator

/**
 * Contains codes to read off parsers and grammars from
 * a treebank.
 */
object GenerativeParser {
  /**
   * Extracts a [[scalanlp.parser.BaseGrammar]] and [[scalanlp.parser.Lexicon]]
   * from a treebank
   * @param data the treebank
   * @return
   */
  def extractLexiconAndGrammar(data: Traversable[TreeInstance[AnnotatedLabel, String]]): (SignatureLexicon[AnnotatedLabel, String], BaseGrammar[AnnotatedLabel]) = {
    val root = data.head.tree.label
    val (words, binary, unary) = extractCounts(data)
    val grammar = BaseGrammar(root,
      binary.keysIterator.map(_._2) ++ unary.keysIterator.map(_._2)
    )

    val lexicon = new SignatureLexicon(words, EnglishWordClassGenerator)
    (lexicon, grammar)
  }

  /**
   * Makes a basic
   * @param data
   * @tparam L
   * @tparam W
   * @return
   */
  def fromTrees[L, W](data: Traversable[TreeInstance[L, W]]):SimpleChartParser[L, W] = {
    val grammar = extractGrammar(data.head.tree.label, data)
    SimpleChartParser(AugmentedGrammar.fromRefined(grammar))
  }


  /**
   * Extracts a RefinedGrammar from a raw treebank. The refined grammar could be a core grammar,
   * maybe I should do that.
   *
   * @param root
   * @param data
   * @tparam L
   * @tparam W
   * @return
   */
  def extractGrammar[L, W](root: L, data: TraversableOnce[TreeInstance[L, W]]): RefinedGrammar[L, W] = {
    val (wordCounts, binaryProductions, unaryProductions) = extractCounts(data)
    RefinedGrammar.generative(root, binaryProductions, unaryProductions, wordCounts)
  }

  def extractCounts[L, W](data: TraversableOnce[TreeInstance[L, W]]) = {
    val lexicon = Counter2[L, W, Double]()
    val binaryProductions = Counter2[L, BinaryRule[L], Double]()
    val unaryProductions = Counter2[L, UnaryRule[L], Double]()

    for( ti <- data) {
      val TreeInstance(_, tree, words) = ti
      val leaves = tree.leaves map (l => (l, words(l.span.start)))
      tree.allChildren foreach { 
        case BinaryTree(a, bc, cc, span) =>
          binaryProductions(a, BinaryRule(a, bc.label, cc.label)) += 1.0
        case UnaryTree(a, bc, chain, span) =>
          unaryProductions(a, UnaryRule(a, bc.label, chain)) += 1.0
        case t => 
      }
      for( (l, w) <- leaves) {
        lexicon(l.label, w) += 1
      }
      
    }
    (lexicon, binaryProductions, unaryProductions)
  }
}

object GenerativePipeline extends ParserPipeline {
  case class Params(baseParser: BaseParser,
                    annotator: TreeAnnotator[AnnotatedLabel, String, AnnotatedLabel] = new FilterAnnotations(Set.empty[Annotation]))
  protected val paramManifest = manifest[Params]

  def trainParser(trainTrees: IndexedSeq[TreeInstance[AnnotatedLabel, String]],
                  validate: Parser[AnnotatedLabel, String]=>ParseEval.Statistics,
                  params: Params) = {
    val (xbar,xbarLexicon) = params.baseParser.xbarGrammar(trainTrees)

    val transformed = trainTrees.par.map { ti => params.annotator(ti) }.seq.toIndexedSeq

    val (initLexicon, initBinaries, initUnaries) = GenerativeParser.extractCounts(transformed)

    val (grammar, lexicon) = params.baseParser.xbarGrammar(trainTrees)
    val refGrammar = BaseGrammar(AnnotatedLabel.TOP, initBinaries, initUnaries)
    val indexedRefinements = GrammarRefinements(grammar, refGrammar, {
      (_: AnnotatedLabel).baseAnnotatedLabel
    })

    val (words, binary, unary) = GenerativeParser.extractCounts(transformed)
    val refinedGrammar = RefinedGrammar.generative(xbar, xbarLexicon, indexedRefinements, binary, unary, words)
    val parser = SimpleChartParser(AugmentedGrammar.fromRefined(refinedGrammar))
    Iterator.single(("Gen", parser))
  }
}
