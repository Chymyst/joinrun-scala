package code.winitzki

import code.winitzki.jc.Macros._

import scala.language.experimental.macros

package object jc {

  private[jc] type ReactionBody = PartialFunction[UnapplyArg, Any]

  def site(reactions: Reaction*): WarningsAndErrors = Core.site(Core.defaultReactionPool, Core.defaultSitePool)(reactions: _*)
  def site(reactionPool: Pool)(reactions: Reaction*): WarningsAndErrors = site(reactionPool, reactionPool)(reactions: _*)

  /** Create a reaction site with one or more reactions.
    * All input and output molecules in reactions used in this site should have been
    * already defined, and input molecules should not be already bound to another site.
    *
    * @param reactions One or more reactions of type [[Reaction]]
    * @param reactionPool Thread pool for running new reactions.
    * @param sitePool Thread pool for use when making decisions to schedule reactions.
    * @return List of warning messages.
    */
  def site(reactionPool: Pool, sitePool: Pool)(reactions: Reaction*): WarningsAndErrors = Core.site(reactionPool, sitePool)(reactions: _*)

  /**
    * Users will define reactions using this function.
    * Examples: {{{ go { a(_) => ... } }}}
    * {{{ go { a (_) => ...}.withRetry onThreads threadPool }}}
    *
    * The macro also obtains statically checkable information about input and output molecules in the reaction.
    *
    * @param reactionBody The body of the reaction. This must be a partial function with pattern-matching on molecules.
    * @return A reaction value, to be used later in [[site]].
    */
  def go(reactionBody: Core.ReactionBody): Reaction = macro buildReactionImpl

  /**
    * Convenience syntax: users can write a(x)+b(y) to emit several molecules at once.
    * (However, the molecules are emitted one by one in the present implementation.)
    *
    * @param x the first emitted molecule
    * @return a class with a + operator
    */
  implicit final class EmitMultiple(x: Unit) {
    def +(n: Unit): Unit = ()
  }

  def m[T]: M[T] = macro mImpl[T]

  def b[T, R]: B[T,R] = macro bImpl[T, R]

  val defaultSitePool: Pool = Core.defaultSitePool
  val defaultReactionPool: Pool = Core.defaultReactionPool

}
