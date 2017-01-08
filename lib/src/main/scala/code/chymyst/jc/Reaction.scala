package code.chymyst.jc

import Core._
import scala.{Symbol => ScalaSymbol}

sealed trait InputPatternType

case object Wildcard extends InputPatternType

final case class SimpleVar(v: ScalaSymbol) extends InputPatternType

final case class SimpleConst(v: Any) extends InputPatternType

final case class OtherInputPattern(matcher: PartialFunction[Any, Unit], vars: List[ScalaSymbol]) extends InputPatternType

case object UnknownInputPattern extends InputPatternType

sealed trait OutputPatternType

final case class ConstOutputValue(v: Any) extends OutputPatternType

case object OtherOutputPattern extends OutputPatternType

/** Indicates whether a reaction has a guard condition.
  *
  */
sealed trait GuardPresenceType {
  def knownFalse: Boolean = this match {
    case GuardAbsent => true
    case _ => false
  }
}

/** Indicates the presence of a guard condition.
  * The guard is parsed into a flat conjunction of guard clauses, which are then analyzed for cross-dependencies between molecules.
  *
  * For example, consider the reaction {{{go { case a(x) + b(y) + c(z) if x > n && y > 0 && y > z && n > 1 => ...} }}}. Here {{{n}}} is an integer constant defined outside the reaction.
  * The conditions for starting this reaction is that a(x) has value x > n; that b(y) has value y > 0; that c(z) has value such that y > z; and finally that n > 1, independently of any molecule values.
  * The condition n > 1 is a static guard. The condition x > n pertains only to the molecule a(x) and therefore can be moved out of the guard into the InputMoleculeInfo for that molecule. Similarly, the condition y > 0 can be moved out of the guard.
  * However, the condition y > z relates two different molecule values; it is a cross guard.
  *
  * @param vars The list of all pattern variables used by the guard condition. Each element of this list is a list of variables used by one guard clause. In the example shown above, this will be {{{List(List('y, 'z))}}} because all other conditions are moved out of the guard.
  * @param staticGuard The conjunction of all the clauses of the guard that are independent of pattern variables. This closure can be called in order to determine whether the reaction should even be considered to start, regardless of the presence of molecules. In this example, the value of {{{staticGuard}}} will be {{{Some(() => n > 1)}}}.
  * @param crossGuards A list of functions that represent the clauses of the guard that relate values of different molecules. The closure `Any => Unit` should be called with the arguments representing the tuples of pattern variables from each molecule used by the cross guard.
  *                    In the present example, {{{crossGuards}}} will be {{{List((List('y, 'z), { case List(y, z) if y > z => () }))}}}.
  */
final case class GuardPresent(vars: List[List[ScalaSymbol]], staticGuard: Option[() => Boolean], crossGuards: List[(List[ScalaSymbol], PartialFunction[List[Any], Unit])]) extends GuardPresenceType

case object GuardAbsent extends GuardPresenceType

/** Indicates that there is no information about the presence of the guard.
  * This happens only with reactions that
  */
case object GuardPresenceUnknown extends GuardPresenceType

/** Compile-time information about an input molecule pattern in a reaction.
  * This class is immutable.
  *
  * @param molecule The molecule emitter value that represents the input molecule.
  * @param flag     Type of the input pattern: wildcard, constant match, etc.
  * @param sha1     Hash sum of the source code (AST tree) of the input pattern.
  */
final case class InputMoleculeInfo(molecule: Molecule, flag: InputPatternType, sha1: String) {
  /** Determine whether this input molecule pattern is weaker than another pattern.
    * Pattern a(xxx) is weaker than b(yyy) if a==b and if anything matched by yyy will also be matched by xxx.
    *
    * @param info The input molecule info for another input molecule.
    * @return Some(true) if we can surely determine that this matcher is weaker than another;
    *         Some(false) if we can surely determine that this matcher is not weaker than another;
    *         None if we cannot determine anything because information is insufficient.
    */
  private[jc] def matcherIsWeakerThan(info: InputMoleculeInfo): Option[Boolean] = {
    if (molecule =!= info.molecule) Some(false)
    else flag match {
      case Wildcard | SimpleVar(_) => Some(true)
      case OtherInputPattern(matcher1,_) => info.flag match {
        case SimpleConst(c) => Some(matcher1.isDefinedAt(c))
        case OtherInputPattern(_,_) => if (sha1 === info.sha1) Some(true) else None // We can reliably determine identical matchers.
        case _ => Some(false) // Here we can reliably determine that this matcher is not weaker.
      }
      case SimpleConst(c) => Some(info.flag match {
        case SimpleConst(`c`) => true
        case _ => false
      })
      case _ => Some(false)
    }
  }

  private[jc] def matcherIsWeakerThanOutput(info: OutputMoleculeInfo): Option[Boolean] = {
    if (molecule =!= info.molecule) Some(false)
    else flag match {
      case Wildcard | SimpleVar(_) => Some(true)
      case OtherInputPattern(matcher1,_) => info.flag match {
        case ConstOutputValue(c) => Some(matcher1.isDefinedAt(c))
        case _ => None // Here we can't reliably determine whether this matcher is weaker.
      }
      case SimpleConst(c) => info.flag match {
        case ConstOutputValue(`c`) => Some(true)
        case ConstOutputValue(_) => Some(false) // definitely not the same constant
        case _ => None // Otherwise, it could be this constant but we can't determine.
      }
      case _ => Some(false)
    }
  }

  // Here "similar" means either it's definitely weaker or it could be weaker (but it is definitely not stronger).
  private[jc] def matcherIsSimilarToOutput(info: OutputMoleculeInfo): Option[Boolean] = {
    if (molecule =!= info.molecule) Some(false)
    else flag match {
      case Wildcard | SimpleVar(_) => Some(true)
      case OtherInputPattern(matcher1, _) => info.flag match {
        case ConstOutputValue(c) => Some(matcher1.isDefinedAt(c))
        case _ => Some(true) // Here we can't reliably determine whether this matcher is weaker, but it's similar (i.e. could be weaker).
      }
      case SimpleConst(c) => Some(info.flag match {
        case ConstOutputValue(`c`) => true
        case ConstOutputValue(_) => false // definitely not the same constant
        case _ => true // Otherwise, it could be this constant.
      })
      case UnknownInputPattern => Some(true) // pattern unknown - could be weaker.
    }
  }

  override def toString: String = {
    val printedPattern = flag match {
      case Wildcard => "_"
      case SimpleVar(v) => v.name
      case SimpleConst(c) => c.toString
      case OtherInputPattern(_, _) => s"<${sha1.substring(0, 4)}...>"
      case UnknownInputPattern => s"?"
    }

    s"$molecule($printedPattern)"
  }

}

/** Compile-time information about an output molecule pattern in a reaction.
  * This class is immutable.
  *
  * @param molecule The molecule emitter value that represents the output molecule.
  * @param flag     Type of the output pattern: either a constant value or other value.
  */
final case class OutputMoleculeInfo(molecule: Molecule, flag: OutputPatternType) {
  override def toString: String = {
    val printedPattern = flag match {
      case ConstOutputValue(()) => ""
      case ConstOutputValue(c) => c.toString
      case OtherOutputPattern => "?"
    }

    s"$molecule($printedPattern)"
  }
}

// This class is immutable.
final case class ReactionInfo(inputs: List[InputMoleculeInfo], outputs: Option[List[OutputMoleculeInfo]], hasGuard: GuardPresenceType, sha1: String) {

  // The input pattern sequence is pre-sorted for further use.
  private[jc] val inputsSorted: List[InputMoleculeInfo] = inputs.sortBy { case InputMoleculeInfo(mol, flag, sha) =>
    // wildcard and simplevars are sorted together; more specific matchers must precede less specific matchers
    val patternPrecedence = flag match {
      case Wildcard | SimpleVar(_) => 3
      case OtherInputPattern(_, _) => 2
      case SimpleConst(_) => 1
      case _ => 0
    }
    (mol.toString, patternPrecedence, sha)
  }

  override val toString: String = s"${inputsSorted.map(_.toString).mkString(" + ")}${hasGuard match {
    case GuardAbsent => ""
    case GuardPresent(_, _, _) => " if(?)"
    case GuardPresenceUnknown => " ?"
  }} => ${outputs match {
    case Some(outputMoleculeInfos) => outputMoleculeInfos.map(_.toString).mkString(" + ")
    case None => "?"
  }}"
}


/** Represents a reaction body. This class is immutable.
  *
  * @param body Partial function of type {{{ UnapplyArg => Unit }}}
  * @param threadPool Thread pool on which this reaction will be scheduled. (By default, the common pool is used.)
  * @param retry Whether the reaction should be run again when an exception occurs in its body. Default is false.
  */
final case class Reaction(info: ReactionInfo, body: ReactionBody, threadPool: Option[Pool] = None, retry: Boolean) {

  /** Convenience method to specify thread pools per reaction.
    *
    * Example: go { case a(x) => ... } onThreads threadPool24
    *
    * @param newThreadPool A custom thread pool on which this reaction will be scheduled.
    * @return New reaction value with the thread pool set.
    */
  def onThreads(newThreadPool: Pool): Reaction = Reaction(info, body, Some(newThreadPool), retry)

  /** Convenience method to specify the "retry" option for a reaction.
    *
    * @return New reaction value with the "retry" flag set.
    */
  def withRetry: Reaction = Reaction(info, body, threadPool, retry = true)

  /** Convenience method to specify the "no retry" option for a reaction.
    * (This option is the default.)
    *
    * @return New reaction value with the "retry" flag unset.
    */
  def noRetry: Reaction = Reaction(info, body, threadPool, retry = false)

  // Optimization: this is used often.
  val inputMolecules: Seq[Molecule] = info.inputs.map(_.molecule).sortBy(_.toString)

  /** Convenience method for debugging.
    *
    * @return String representation of input molecules of the reaction.
    */
  override val toString: String = s"${inputMolecules.map(_.toString).mkString(" + ")} => ...${if (retry)
    "/R" else ""}"
}

